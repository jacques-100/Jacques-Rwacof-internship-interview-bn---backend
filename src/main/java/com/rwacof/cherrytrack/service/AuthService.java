package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.config.AppProperties;
import com.rwacof.cherrytrack.dto.UserDtos.UserDto;
import com.rwacof.cherrytrack.exception.BusinessRuleException;
import com.rwacof.cherrytrack.exception.InvalidCredentialsException;
import com.rwacof.cherrytrack.model.AuditAction;
import com.rwacof.cherrytrack.model.RefreshToken;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.repository.RefreshTokenRepository;
import com.rwacof.cherrytrack.repository.UserRepository;
import com.rwacof.cherrytrack.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class AuthService {

    /** Result of login/refresh: the access token plus the raw refresh token to set as a cookie. */
    public record Session(String accessToken, long expiresInSeconds, String refreshToken, UserDto user) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginRateLimiter rateLimiter;
    private final AuditService auditService;
    private final AppProperties props;
    private final Clock clock;
    /** Used to spend the same time on unknown usernames, so response timing doesn't reveal which exist. */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder, JwtService jwtService, LoginRateLimiter rateLimiter,
                       AuditService auditService, AppProperties props, Clock clock) {
        this.auditService = auditService;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional
    public Session login(String username, String password, String clientAddress) {
        String key = clientAddress + "|" + username.toLowerCase();
        rateLimiter.checkAllowed(key);

        User user = userRepository.findByUsername(username.trim().toLowerCase()).orElse(null);
        boolean ok = passwordEncoder.matches(password, user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !ok || !user.isActive()) {
            rateLimiter.recordFailure(key);
            throw new InvalidCredentialsException("Invalid username or password.");
        }
        rateLimiter.reset(key);
        return issue(user);
    }

    /**
     * Rotates the refresh token. Replaying an already-ROTATED token looks like theft and revokes every
     * session of that user; a token revoked by logout, password change or deactivation simply fails.
     */
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public Session refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidCredentialsException("Session expired. Please sign in again.");
        }
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new InvalidCredentialsException("Session expired. Please sign in again."));
        if (stored.isRevoked()) {
            if (stored.isRotated()) {
                refreshTokenRepository.revokeAllForUser(stored.getUser().getId());
            }
            throw new InvalidCredentialsException("Session expired. Please sign in again.");
        }
        if (stored.getExpiresAt().isBefore(clock.instant()) || !stored.getUser().isActive()) {
            throw new InvalidCredentialsException("Session expired. Please sign in again.");
        }
        stored.setRevoked(true);
        stored.setRotated(true);
        return issue(stored.getUser());
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            refreshTokenRepository.findByTokenHash(hash(rawToken)).ifPresent(t -> t.setRevoked(true));
        }
    }

    /**
     * Changes the caller's own password. Every other session is revoked and a fresh one is issued
     * for this device. A wrong current password is a 422, never a 401: the client treats 401 as
     * "session expired" and would sign the user out.
     */
    @Transactional
    public Session changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId).orElseThrow(() -> new InvalidCredentialsException("Session expired. Please sign in again."));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessRuleException("WRONG_PASSWORD", "Your current password is incorrect.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessRuleException("PASSWORD_UNCHANGED", "Choose a password different from the current one.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.touch(clock.instant());
        refreshTokenRepository.revokeAllForUser(userId);
        auditService.record(AuditAction.PASSWORD_CHANGED, "USER", userId, user.getUsername() + " changed their password", null);
        return issue(user);
    }

    public Duration refreshTtl() {
        return Duration.ofDays(props.security().refreshTokenDays());
    }

    private Session issue(User user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        refreshTokenRepository.save(new RefreshToken(user, hash(raw), now.plus(refreshTtl()), now));
        return new Session(jwtService.issueAccessToken(user), jwtService.accessTokenTtlSeconds(), raw, UserDto.of(user));
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
