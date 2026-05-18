package com.pbm.command.dto.response;

/**
 * 승인 대기 액션 부가 정보 응답 DTO.
 */
public record ApprovalContextResponse(
        String summaryText,
        Integer timeoutMs
) {
}
