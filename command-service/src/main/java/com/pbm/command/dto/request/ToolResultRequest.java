package com.pbm.command.dto.request;

/**
 * Extension tool 실행 결과 요청 DTO.
 *
 * 역할: 스크린샷 캡처 같은 부가 도구 실행 결과를 backend에 보고한다.
 * 연관: AgentRunActionResultRequest.
 */
public record ToolResultRequest(
        String name,
        boolean success,
        ScreenshotArtifactRequest screenshot,
        String errorMessage
) {
}
