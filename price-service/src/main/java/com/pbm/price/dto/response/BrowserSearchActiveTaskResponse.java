package com.pbm.price.dto.response;

/**
 * heartbeat 응답용 브라우저 검색 활성 태스크 DTO.
 */
public record BrowserSearchActiveTaskResponse(
        Long taskId,
        String commandId,
        String platform,
        String keyword,
        String searchUrl,
        Integer maxResults
) {
}
