package com.pbm.command.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.client.dto.DomPlannerInstructionPayload;
import com.pbm.command.config.OpenAiProperties;
import com.pbm.command.config.RestClientConfig;
import com.pbm.command.domain.CommandSession;
import com.pbm.command.dto.request.PageSnapshotRequest;
import com.pbm.command.dto.response.ProductCandidateResponse;
import com.pbm.command.exception.ExternalApiProxyException;
import com.pbm.command.exception.OpenAiClientException;
import com.pbm.command.exception.OpenAiResponseParseException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * DOM snapshot 기반 AI planner 클라이언트.
 *
 * 역할: command-service가 external-api-service의 DOM planner 프록시를 호출해
 *       GPT-5.4-mini 결과를 받아온다.
 * 동작: snapshot/command 정보를 요청 body로 전송하고, strict JSON 응답을 내부 payload로 변환한다.
 * 연관: AgentStepPlannerService, OpenAiProperties.
 */
@Component
public class AiDomPlannerClient {

    private static final Logger log = LoggerFactory.getLogger(AiDomPlannerClient.class);

    private final RestClient externalApiRestClient;
    private final ObjectMapper objectMapper;
    private final String plannerUrl;

    @Autowired
    public AiDomPlannerClient(ObjectMapper objectMapper, OpenAiProperties openAiProperties) {
        this.externalApiRestClient = RestClientConfig.createExternalApiRestClient(openAiProperties);
        this.objectMapper = objectMapper;
        this.plannerUrl = buildPlannerUrl(openAiProperties.getBaseUrl());
    }

    public AiDomPlannerClient(RestClient externalApiRestClient, ObjectMapper objectMapper) {
        this.externalApiRestClient = externalApiRestClient;
        this.objectMapper = objectMapper;
        this.plannerUrl = "http://localhost:8090/api/v1/planner/analyze-dom";
    }

    @Retry(name = "openAiProxyService")
    @CircuitBreaker(name = "openAiProxyService", fallbackMethod = "planFallback")
    public DomPlannerInstructionPayload plan(CommandSession commandSession, PageSnapshotRequest snapshot, ProductCandidateResponse targetProduct) {
        DomPlannerProxyRequest proxyRequest = new DomPlannerProxyRequest(
                commandSession.getOriginalCommand(),
                commandSession.getCommandIntent(),
                commandSession.getStatus().name(),
                snapshot == null ? null : snapshot.currentUrl(),
                snapshot == null ? null : snapshot.title(),
                snapshot == null ? null : snapshot.visibleTextSummary(),
                snapshot == null ? java.util.List.of() : snapshot.interactiveElements(),
                snapshot == null ? java.util.List.of() : snapshot.optionGroups(),
                targetProduct
        );

        DomPlannerInstructionPayload response;
        try {
            response = externalApiRestClient.post()
                    .uri(plannerUrl)
                    .body(proxyRequest)
                    .retrieve()
                    .body(DomPlannerInstructionPayload.class);
        } catch (RestClientException e) {
            log.error("[AiDomPlannerClient] external-api-service planner 호출 실패 - URL: {}, 에러: {}", plannerUrl, e.getMessage());
            throw new OpenAiClientException("external-api-service DOM planner 호출 중 오류가 발생했습니다.", e);
        }

        if (response == null) {
            throw new OpenAiClientException("external-api-service DOM planner 응답이 없습니다.");
        }

        if (response.action() == null || response.action().isBlank()) {
            throw new OpenAiResponseParseException("external-api-service DOM planner 응답에 action이 없습니다.");
        }

        return response;
    }

    DomPlannerInstructionPayload planFallback(CommandSession commandSession, PageSnapshotRequest snapshot, ProductCandidateResponse targetProduct, Throwable t) {
        throw new ExternalApiProxyException("external-api-service DOM planner를 사용할 수 없습니다.", t);
    }

    private String buildPlannerUrl(String baseUrl) {
        String normalizedBaseUrl = baseUrl == null ? "http://localhost:8090" : baseUrl.replaceAll("/+$", "");
        return normalizedBaseUrl + "/api/v1/planner/analyze-dom";
    }

    private record DomPlannerProxyRequest(
            @JsonProperty("command_text") String commandText,
            @JsonProperty("command_intent") String commandIntent,
            @JsonProperty("command_status") String commandStatus,
            @JsonProperty("current_url") String currentUrl,
            String title,
            @JsonProperty("visible_text_summary") String visibleTextSummary,
            @JsonProperty("interactive_elements") java.util.List<?> interactiveElements,
            @JsonProperty("option_groups") java.util.List<?> optionGroups,
            @JsonProperty("target_product") ProductCandidateResponse targetProduct
    ) {
    }

}
