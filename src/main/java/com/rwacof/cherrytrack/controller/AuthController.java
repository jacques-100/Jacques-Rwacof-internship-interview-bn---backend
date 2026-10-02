package com.rwacof.cherrytrack.controller;

import com.rwacof.cherrytrack.config.AppProperties;
import com.rwacof.cherrytrack.dto.AuthDtos.AuthResponse;
import com.rwacof.cherrytrack.dto.AuthDtos.LoginRequest;
import com.rwacof.cherrytrack.dto.UserDtos.ChangePasswordRequest;
import com.rwacof.cherrytrack.dto.UserDtos.UpdateProfileRequest;
import com.rwacof.cherrytrack.dto.UserDtos.UserDto;
import com.rwacof.cherrytrack.security.CurrentUser;
import com.rwacof.cherrytrack.service.AuthService;
import com.rwacof.cherrytrack.service.AuthService.Session;
import com.rwacof.cherrytrack.service.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    static final String COOKIE = "ct_refresh";
    /** Native apps (Capacitor) send this header, and keep the refresh token themselves instead of in a cookie. */
    static final String CLIENT_HEADER = "X-Client";
    static final String REFRESH_HEADER = "X-Refresh-Token";
    private static final String NATIVE = "native";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final UserService userService;
    private final AppProperties props;

    public AuthController(AuthService authService, UserService userService, AppProperties props) {
        this.authService = authService;
        this.userService = userService;
        this.props = props;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return respond(authService.login(request.username(), request.password(), http.getRemoteAddr()), isNative(http));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(name = COOKIE, required = false) String cookieToken,
                                                @RequestHeader(name = REFRESH_HEADER, required = false) String headerToken,
                                                HttpServletRequest http) {
        boolean nativeClient = isNative(http);
        return respond(authService.refresh(nativeClient ? headerToken : cookieToken), nativeClient);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String cookieToken,
                                       @RequestHeader(name = REFRESH_HEADER, required = false) String headerToken,
                                       HttpServletRequest http) {
        authService.logout(isNative(http) ? headerToken : cookieToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    /** The signed-in user's own profile. */
    @GetMapping("/me")
    public UserDto me() {
        return userService.profile();
    }

    /** Name, email and phone. Role, stations and status can only be changed by an administrator. */
    @PutMapping("/me")
    public UserDto updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(request);
    }

    /** Changes the caller's password, signs out every other device and keeps this one signed in. */
    @PostMapping("/me/password")
    public ResponseEntity<AuthResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request, HttpServletRequest http) {
        return respond(authService.changePassword(CurrentUser.require().id(), request.currentPassword(), request.newPassword()), isNative(http));
    }

    private static boolean isNative(HttpServletRequest http) {
        return NATIVE.equalsIgnoreCase(http.getHeader(CLIENT_HEADER));
    }

    /** Browsers get the refresh token as an httpOnly cookie; native apps get it in the body to keep in app storage. */
    private ResponseEntity<AuthResponse> respond(Session s, boolean nativeClient) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (!nativeClient) response.header(HttpHeaders.SET_COOKIE, cookie(s.refreshToken(), authService.refreshTtl()).toString());
        return response.body(new AuthResponse(s.accessToken(), s.expiresInSeconds(), s.user(), nativeClient ? s.refreshToken() : null));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(props.security().refreshCookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
