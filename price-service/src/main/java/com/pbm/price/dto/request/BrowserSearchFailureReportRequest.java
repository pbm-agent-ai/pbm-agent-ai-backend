package com.pbm.price.dto.request;

/**
 * 익스텐션이 브라우저 검색 실패를 서버에 보고하는 요청 DTO.
 *
 * @param taskId 실패한 브라우저 검색 태스크 ID
 * @param reason 실패 사유 (에러 메시지)
 */
public record BrowserSearchFailureReportRequest(
        Long taskId,
        String reason
) {
}
