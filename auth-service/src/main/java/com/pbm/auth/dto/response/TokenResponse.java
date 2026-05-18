package com.pbm.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인/토큰 재발급 응답 DTO")
public record TokenResponse (
        @Schema(description = "Access token", example = "eyJhbGciOiJIUzI1NiJ9...")
        String accessToken,
        @Schema(description = "Refresh token", example = "eyJhbGciOiJIUzI1NiJ9...")
        String refreshToken,
        @Schema(description = "토큰 타입", example = "Bearer")
        String tokenType,
        @Schema(description = "access token 만료 시간(ms)", example = "1800000")
        long expiresIn
){ }
