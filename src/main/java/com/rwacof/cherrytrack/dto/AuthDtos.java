package com.rwacof.cherrytrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(@NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 128) String password) {}

    /** `refreshToken` is only filled for native apps, which can't use the browser's httpOnly cookie. */
    public record AuthResponse(String accessToken, long expiresInSeconds, UserDtos.UserDto user, String refreshToken) {}
}
