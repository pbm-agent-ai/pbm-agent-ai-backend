package com.pbm.auth.dto.response;

/**
 * 브라우저 extension 페어링 토큰 응답 DTO.
 *
 * 역할: 웹 앱이 현재 로그인 사용자를 대신해 extension 등록 1회에 사용할 수 있는
 *       pairing token과 만료 시간을 반환한다.
 * 연관: AuthController, AuthService.
 */
public record PairingTokenResponse(
        String pairingToken,
        String tokenType,
        long expiresIn
) {
}
