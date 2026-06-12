package com.pbm.notification.dto;

import java.time.Instant;

/**
 * 텔레그램 로그인 자격증명 대기 DTO.
 */
public record PendingLoginCredentialRequest(
        String runId,
        Long userId,
        Instant expiresAt
) {
}
