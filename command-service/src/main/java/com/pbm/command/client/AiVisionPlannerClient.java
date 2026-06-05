package com.pbm.command.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pbm.command.client.dto.VisionPlannerInstructionPayload;
import com.pbm.command.config.OpenAiProperties;
import com.pbm.command.config.RestClientConfig;
import com.pbm.command.dto.request.AgentRunActionResultRequest;
import com.pbm.command.dto.request.ScreenshotArtifactRequest;
import com.pbm.command.exception.ExternalApiProxyException;
import com.pbm.command.exception.OpenAiClientException;
import com.pbm.command.exception.OpenAiResponseParseException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 스크린샷 기반 vision planner 클라이언트.
 */
@Component
public class AiVisionPlannerClient {

    private final RestClient externalApiRestClient;
    private final String plannerUrl;

    public AiVisionPlannerClient(OpenAiProperties openAiProperties) {
        this.externalApiRestClient = RestClientConfig.createExternalApiRestClient(openAiProperties);
        this.plannerUrl = buildPlannerUrl(openAiProperties.getBaseUrl());
    }

    @Retry(name = "openAiProxyService")
    @CircuitBreaker(name = "openAiProxyService", fallbackMethod = "planFallback")
    public VisionPlannerInstructionPayload analyze(
            String runId,
            Integer stepIndex,
            String commandText,
            String currentUrl,
            AgentRunActionResultRequest previousActionResult,
            ScreenshotArtifactRequest screenshotArtifact
    ) {
        VisionPlannerRequest request = new VisionPlannerRequest(
                runId,
                stepIndex,
                commandText,
                currentUrl,
                screenshotArtifact.dataUrl(),
                previousActionResult.errorCode() == null ? null : previousActionResult.errorCode().name(),
                previousActionResult.errorMessage()
        );

        try {
            VisionPlannerInstructionPayload response = externalApiRestClient.post()
                    .uri(plannerUrl)
                    .body(request)
                    .retrieve()
                    .body(VisionPlannerInstructionPayload.class);

            if (response == null || response.action() == null || response.action().isBlank()) {
                throw new OpenAiResponseParseException("vision planner 응답에 action이 없습니다.");
            }

            return response;
        } catch (RestClientException e) {
            throw new OpenAiClientException("external-api-service vision planner 호출 중 오류가 발생했습니다.", e);
        }
    }

    VisionPlannerInstructionPayload planFallback(
            String runId,
            Integer stepIndex,
            String commandText,
            String currentUrl,
            AgentRunActionResultRequest previousActionResult,
            ScreenshotArtifactRequest screenshotArtifact,
            Throwable t
    ) {
        throw new ExternalApiProxyException("external-api-service vision planner를 사용할 수 없습니다.", t);
    }

    private String buildPlannerUrl(String baseUrl) {
        String normalizedBaseUrl = baseUrl == null ? "http://localhost:8090" : baseUrl.replaceAll("/+$", "");
        return normalizedBaseUrl + "/api/v1/planner/analyze-screenshot";
    }

    private record VisionPlannerRequest(
            @JsonProperty("run_id") String runId,
            @JsonProperty("step_index") Integer stepIndex,
            @JsonProperty("command_text") String commandText,
            @JsonProperty("current_url") String currentUrl,
            @JsonProperty("screenshot_data_url") String screenshotDataUrl,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("error_message") String errorMessage
    ) {
    }
}
