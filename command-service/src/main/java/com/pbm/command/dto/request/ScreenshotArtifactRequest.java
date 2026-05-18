package com.pbm.command.dto.request;

/**
 * Extension이 캡처한 스크린샷 artifact 요청 DTO.
 *
 * 역할: vision fallback 분석에 사용할 현재 화면 이미지를 backend로 전달한다.
 * 동작: MVP 단계에서는 dataUrl(base64)를 그대로 전달하고, 이후 필요하면 업로드 URL 방식으로 대체할 수 있다.
 * 연관: ToolResultRequest, AgentRunActionResultRequest.
 */
public record ScreenshotArtifactRequest(
        String dataUrl,
        String mimeType,
        Integer byteLength
) {
}
