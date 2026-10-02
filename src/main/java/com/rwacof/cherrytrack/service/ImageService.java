package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.dto.UserDtos.UserDto;
import com.rwacof.cherrytrack.exception.BusinessRuleException;
import com.rwacof.cherrytrack.exception.NotFoundException;
import com.rwacof.cherrytrack.model.AuditAction;
import com.rwacof.cherrytrack.model.StoredImage;
import com.rwacof.cherrytrack.model.StoredImage.OwnerType;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.repository.StoredImageRepository;
import com.rwacof.cherrytrack.repository.UserRepository;
import com.rwacof.cherrytrack.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The company logo and users' profile photos. Uploads are checked by their actual bytes (never by the
 * filename or the client's claimed type), limited in size, and only PNG, JPEG and WebP are accepted:
 * SVG is refused because it can carry scripts.
 */
@Service
public class ImageService {

    static final int LOGO_MAX_BYTES = 2 * 1024 * 1024;
    static final int AVATAR_MAX_BYTES = 1024 * 1024;
    private static final int MAX_PIXELS_PER_SIDE = 8000;
    /** The logo belongs to the organisation, not to anyone, so it uses a fixed owner id. */
    private static final long LOGO_OWNER = 0L;

    /** What the API serves: the bytes, their type, and a version that changes with every upload. */
    public record Image(byte[] data, String contentType, long version) {}

    public record Branding(Long logoVersion, String organizationName) {}

    private final StoredImageRepository images;
    private final UserRepository users;
    private final SettingsService settings;
    private final AuditService audit;
    private final Clock clock;

    public ImageService(StoredImageRepository images, UserRepository users, SettingsService settings, AuditService audit, Clock clock) {
        this.images = images;
        this.users = users;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- company logo

    @Transactional(readOnly = true)
    public Branding branding() {
        Long version = images.findUpdatedAt(OwnerType.LOGO, LOGO_OWNER).map(Instant::toEpochMilli).orElse(null);
        return new Branding(version, settings.get(SettingKey.ORGANIZATION_NAME));
    }

    @Transactional(readOnly = true)
    public Optional<Image> logo() {
        return images.findByOwnerTypeAndOwnerId(OwnerType.LOGO, LOGO_OWNER).map(ImageService::toImage);
    }

    @Transactional
    public Branding saveLogo(MultipartFile file) {
        store(OwnerType.LOGO, LOGO_OWNER, file, LOGO_MAX_BYTES, "2 MB");
        audit.record(AuditAction.LOGO_UPDATED, "SETTINGS", 0L, "Company logo was changed", Map.of());
        return branding();
    }

    @Transactional
    public Branding removeLogo() {
        if (images.deleteByOwner(OwnerType.LOGO, LOGO_OWNER) > 0) {
            audit.record(AuditAction.LOGO_REMOVED, "SETTINGS", 0L, "Company logo was removed", Map.of());
        }
        return branding();
    }

    // ---------------------------------------------------------------- profile photos

    @Transactional(readOnly = true)
    public Optional<Image> avatar(Long userId) {
        return images.findByOwnerTypeAndOwnerId(OwnerType.AVATAR, userId).map(ImageService::toImage);
    }

    /** Only ever the caller's own photo: nobody can change someone else's picture. */
    @Transactional
    public UserDto saveOwnAvatar(MultipartFile file) {
        User user = currentUser();
        Instant now = store(OwnerType.AVATAR, user.getId(), file, AVATAR_MAX_BYTES, "1 MB");
        user.setAvatarUpdatedAt(now);
        user.touch(now);
        users.saveAndFlush(user);
        audit.record(AuditAction.PROFILE_UPDATED, "USER", user.getId(), user.getUsername() + " changed their profile photo", Map.of());
        return UserDto.of(user);
    }

    @Transactional
    public UserDto removeOwnAvatar() {
        User user = currentUser();
        if (images.deleteByOwner(OwnerType.AVATAR, user.getId()) > 0) {
            user.setAvatarUpdatedAt(null);
            user.touch(clock.instant());
            users.saveAndFlush(user);
            audit.record(AuditAction.PROFILE_UPDATED, "USER", user.getId(), user.getUsername() + " removed their profile photo", Map.of());
        }
        return UserDto.of(user);
    }

    // ---------------------------------------------------------------- internals

    private User currentUser() {
        return users.findWithRoleById(CurrentUser.require().id()).orElseThrow(() -> new NotFoundException("User", CurrentUser.require().id()));
    }

    private Instant store(OwnerType type, long ownerId, MultipartFile file, int maxBytes, String maxLabel) {
        byte[] data = read(file, maxBytes, maxLabel);
        String contentType = detectType(data);
        if (contentType == null) {
            throw new BusinessRuleException("INVALID_IMAGE", "Use a PNG, JPEG or WebP image.");
        }
        if ("image/png".equals(contentType) && exceedsPixelLimit(data)) {
            throw new BusinessRuleException("INVALID_IMAGE", "That image is too large in pixels. Use one up to " + MAX_PIXELS_PER_SIDE + " x " + MAX_PIXELS_PER_SIDE + ".");
        }
        Instant now = clock.instant();
        StoredImage image = images.findByOwnerTypeAndOwnerId(type, ownerId).orElseGet(() -> new StoredImage(type, ownerId));
        image.replace(contentType, data, now);
        images.saveAndFlush(image);
        return now;
    }

    private static byte[] read(MultipartFile file, int maxBytes, String maxLabel) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("INVALID_IMAGE", "Choose an image file to upload.");
        }
        if (file.getSize() > maxBytes) {
            throw new BusinessRuleException("IMAGE_TOO_LARGE", "That image is larger than " + maxLabel + ". Choose a smaller one.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("INVALID_IMAGE", "That file could not be read.");
        }
    }

    /** Identifies the format from the file's own signature, or null when it is not an accepted image. */
    static String detectType(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /** A tiny file can still declare a gigantic canvas; the PNG header says how big it really is. */
    private static boolean exceedsPixelLimit(byte[] png) {
        if (png.length < 24) return true;
        long width = ((png[16] & 0xFFL) << 24) | ((png[17] & 0xFFL) << 16) | ((png[18] & 0xFFL) << 8) | (png[19] & 0xFFL);
        long height = ((png[20] & 0xFFL) << 24) | ((png[21] & 0xFFL) << 16) | ((png[22] & 0xFFL) << 8) | (png[23] & 0xFFL);
        return width == 0 || height == 0 || width > MAX_PIXELS_PER_SIDE || height > MAX_PIXELS_PER_SIDE;
    }

    private static Image toImage(StoredImage i) {
        return new Image(i.getData(), i.getContentType(), i.getUpdatedAt().toEpochMilli());
    }
}
