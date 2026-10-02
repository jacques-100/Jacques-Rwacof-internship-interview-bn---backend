package com.rwacof.cherrytrack.controller;

import com.rwacof.cherrytrack.dto.UserDtos.UserDto;
import com.rwacof.cherrytrack.service.ImageService;
import com.rwacof.cherrytrack.service.ImageService.Branding;
import com.rwacof.cherrytrack.service.ImageService.Image;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Images")
public class ImageController {

    private final ImageService images;

    public ImageController(ImageService images) {
        this.images = images;
    }

    /** Public: the sign-in page shows the organisation's name and logo before anyone has signed in. */
    @GetMapping("/branding")
    public Branding branding() {
        return images.branding();
    }

    @GetMapping("/branding/logo")
    public ResponseEntity<byte[]> logo(WebRequest request) {
        return serve(images.logo(), request, false);
    }

    @PutMapping(value = "/branding/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public Branding uploadLogo(@RequestParam("file") MultipartFile file) {
        return images.saveLogo(file);
    }

    @DeleteMapping("/branding/logo")
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public Branding removeLogo() {
        return images.removeLogo();
    }

    /** Any signed-in user can see a colleague's photo (the app fetches it with its bearer token). */
    @GetMapping("/users/{id}/avatar")
    public ResponseEntity<byte[]> avatar(@PathVariable Long id, WebRequest request) {
        return serve(images.avatar(id), request, true);
    }

    @PutMapping(value = "/auth/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserDto uploadOwnAvatar(@RequestParam("file") MultipartFile file) {
        return images.saveOwnAvatar(file);
    }

    @DeleteMapping("/auth/me/avatar")
    public UserDto removeOwnAvatar() {
        return images.removeOwnAvatar();
    }

    /** Serves the bytes with an ETag, so a client revalidates cheaply and a replaced image shows at once. */
    private static ResponseEntity<byte[]> serve(Optional<Image> image, WebRequest request, boolean privateCache) {
        if (image.isEmpty()) return ResponseEntity.notFound().build();
        Image i = image.get();
        String etag = "\"" + i.version() + "\"";
        if (request.checkNotModified(etag)) return null;   // 304, already written by Spring
        CacheControl cache = privateCache ? CacheControl.noCache().cachePrivate() : CacheControl.noCache();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(i.contentType()))
                .cacheControl(cache)
                .eTag(etag)
                .body(i.data());
    }
}
