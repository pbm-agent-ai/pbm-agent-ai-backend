package com.pbm.command.dto.event;

/**
 * 로그인 자격증명 응답 payload.
 *
 * 역할: notification-service가 텔레그램 웹훅으로 받은
 *       아이디/비밀번호를 command-service에 전달한다.
 */
public record LoginCredentialResponsePayload(
        Long userId,
        String runId,
        String username,
        String password
) {
}
