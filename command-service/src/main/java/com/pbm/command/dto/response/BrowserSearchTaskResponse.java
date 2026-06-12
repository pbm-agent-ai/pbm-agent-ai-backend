package com.pbm.command.dto.response;

/**
 * 브라우저 검색 태스크 응답 DTO.
 *
 * 역할: heartbeat 응답에 포함되어 익스텐션에 AliExpress 검색 키워드와 검색 URL을 전달한다.
 */
public record BrowserSearchTaskResponse(
        Long taskId,
        String commandId,
        String platform,
        String keyword,
        String searchUrl,
        Integer maxResults
) {
}
