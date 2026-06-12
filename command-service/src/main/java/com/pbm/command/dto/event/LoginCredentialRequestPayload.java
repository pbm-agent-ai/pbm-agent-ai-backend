package com.pbm.command.dto.event;

/**
 * 로그인 자격증명 요청 payload.
 *
 * 역할: notification-service가 사용자에게 텔레그램으로
 *       로그인 아이디/비밀번호 입력을 요청할 때 필요한 정보를 담는다.
 */
public record LoginCredentialRequestPayload(
        Long userId,
        String runId,
        String productName
) {
}
