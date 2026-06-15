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

import java.util.List;
import java.util.stream.Collectors;

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
        return plan(commandSession, snapshot, targetProduct, null, null);
    }

    @Retry(name = "openAiProxyService")
    @CircuitBreaker(name = "openAiProxyService", fallbackMethod = "planFallbackWithStrategy")
    public DomPlannerInstructionPayload plan(CommandSession commandSession, PageSnapshotRequest snapshot, ProductCandidateResponse targetProduct, String platform, String navigationStrategy) {
        return plan(commandSession, snapshot, targetProduct, platform, navigationStrategy, null);
    }

    @Retry(name = "openAiProxyService")
    @CircuitBreaker(name = "openAiProxyService", fallbackMethod = "planFallbackWithAgentType")
    public DomPlannerInstructionPayload plan(CommandSession commandSession, PageSnapshotRequest snapshot, ProductCandidateResponse targetProduct, String platform, String navigationStrategy, String agentType) {
        return plan(commandSession, snapshot, targetProduct, platform, navigationStrategy, agentType, null);
    }

    /**
     * triggerPrice 포함 버전.
     * <p>
     * 모니터링 후 결제 시 CATALOG_NAVIGATOR가 lprice(선택 당시 가격) 대신
     * triggerPrice(실제 조건 충족 가격)를 기준가로 사용하도록 external-api-service에 전달한다.
     */
    @Retry(name = "openAiProxyService")
    @CircuitBreaker(name = "openAiProxyService", fallbackMethod = "planFallbackWithTriggerPrice")
    public DomPlannerInstructionPayload plan(CommandSession commandSession, PageSnapshotRequest snapshot, ProductCandidateResponse targetProduct, String platform, String navigationStrategy, String agentType, Integer triggerPrice) {
        log.info(
                "[AiDomPlannerClient] DOM planner 요청 요약 - commandText={}, intent={}, status={}, currentUrl={}, title={}, visibleTextLen={}, interactiveCount={}, optionGroupCount={}, rawHtmlLen={}, platform={}, navigationStrategy={}, agentType={}, triggerPrice={}, targetProduct={}",
                abbreviate(commandSession.getOriginalCommand(), 120),
                commandSession.getCommandIntent(),
                commandSession.getStatus(),
                snapshot == null ? null : snapshot.currentUrl(),
                snapshot == null ? null : snapshot.title(),
                snapshot == null || snapshot.visibleTextSummary() == null ? 0 : snapshot.visibleTextSummary().length(),
                snapshot == null || snapshot.interactiveElements() == null ? 0 : snapshot.interactiveElements().size(),
                snapshot == null || snapshot.optionGroups() == null ? 0 : snapshot.optionGroups().size(),
                snapshot == null || snapshot.rawHtml() == null ? 0 : snapshot.rawHtml().length(),
                platform,
                navigationStrategy,
                agentType,
                triggerPrice,
                summarizeTargetProduct(targetProduct)
        );
        log.info("[AiDomPlannerClient] DOM planner 전처리 interactive 샘플={}", summarizeInteractiveElements(snapshot));
        log.info("[AiDomPlannerClient] DOM planner 전처리 optionGroups={}", summarizeOptionGroups(snapshot));
        log.info("[AiDomPlannerClient] DOM planner 전처리 visibleText={}", abbreviate(snapshot == null ? null : snapshot.visibleTextSummary(), 300));
        log.info("[AiDomPlannerClient] DOM planner 전처리 rawHtml prefix={}", rawHtmlPreview(snapshot == null ? null : snapshot.rawHtml(), true));
        log.info("[AiDomPlannerClient] DOM planner 전처리 rawHtml suffix={}", rawHtmlPreview(snapshot == null ? null : snapshot.rawHtml(), false));

        DomPlannerProxyRequest proxyRequest = new DomPlannerProxyRequest(
                commandSession.getOriginalCommand(),
                commandSession.getCommandIntent(),
                commandSession.getStatus().name(),
                snapshot == null ? null : snapshot.currentUrl(),
                snapshot == null ? null : snapshot.title(),
                snapshot == null ? null : snapshot.visibleTextSummary(),
                snapshot == null ? java.util.List.of() : snapshot.interactiveElements(),
                snapshot == null ? java.util.List.of() : snapshot.optionGroups(),
                targetProduct,
                platform,
                navigationStrategy,
                agentType,
                snapshot == null ? null : snapshot.rawHtml(),
                triggerPrice
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

    DomPlannerInstructionPayload planFallbackWithStrategy(CommandSession commandSession, PageSnapshotRequest snapshot, ProductCandidateResponse targetProduct, String platform, String navigationStrategy, Throwable t) {
        throw new ExternalApiProxyException("external-api-service DOM planner를 사용할 수 없습니다.", t);
    }

    DomPlannerInstructionPayload planFallbackWithAgentType(CommandSession commandSession, PageSnapshotRequest snapshot, ProductCandidateResponse targetProduct, String platform, String navigationStrategy, String agentType, Throwable t) {
        throw new ExternalApiProxyException("external-api-service DOM planner를 사용할 수 없습니다.", t);
    }

    DomPlannerInstructionPayload planFallbackWithTriggerPrice(CommandSession commandSession, PageSnapshotRequest snapshot, ProductCandidateResponse targetProduct, String platform, String navigationStrategy, String agentType, Integer triggerPrice, Throwable t) {
        throw new ExternalApiProxyException("external-api-service DOM planner를 사용할 수 없습니다.", t);
    }

    private String buildPlannerUrl(String baseUrl) {
        String normalizedBaseUrl = baseUrl == null ? "http://localhost:8090" : baseUrl.replaceAll("/+$", "");
        return normalizedBaseUrl + "/api/v1/planner/analyze-dom";
    }

    private String summarizeTargetProduct(ProductCandidateResponse targetProduct) {
        if (targetProduct == null) {
            return "null";
        }

        return String.format(
                "{productId=%s, title=%s, lprice=%s, mallName=%s, productUrl=%s, searchKeyword=%s, platform=%s}",
                abbreviate(targetProduct.productId(), 80),
                abbreviate(targetProduct.title(), 80),
                abbreviate(targetProduct.lprice(), 40),
                abbreviate(targetProduct.mallName(), 60),
                abbreviate(targetProduct.productUrl(), 120),
                abbreviate(targetProduct.searchKeyword(), 80),
                abbreviate(targetProduct.platform(), 40)
        );
    }

    private String summarizeInteractiveElements(PageSnapshotRequest snapshot) {
        if (snapshot == null || snapshot.interactiveElements() == null || snapshot.interactiveElements().isEmpty()) {
            return "[]";
        }

        return snapshot.interactiveElements().stream()
                .limit(10)
                .map(element -> String.format(
                        "{nodeId=%s, role=%s, label=%s, selector=%s, href=%s, visible=%s, disabled=%s}",
                        abbreviate(element.nodeId(), 40),
                        abbreviate(element.role(), 20),
                        abbreviate(element.labelText(), 80),
                        abbreviate(element.selector(), 120),
                        abbreviate(element.href(), 120),
                        element.isVisible(),
                        Boolean.TRUE.equals(element.disabled())
                ))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String summarizeOptionGroups(PageSnapshotRequest snapshot) {
        if (snapshot == null || snapshot.optionGroups() == null || snapshot.optionGroups().isEmpty()) {
            return "[]";
        }

        return snapshot.optionGroups().stream()
                .limit(10)
                .map(group -> String.format(
                        "{groupName=%s, nodeId=%s, selector=%s, options=%s, selected=%s}",
                        abbreviate(group.groupName(), 60),
                        abbreviate(group.nodeId(), 40),
                        abbreviate(group.selector(), 120),
                        group.options(),
                        abbreviate(group.selectedOption(), 60)
                ))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String rawHtmlPreview(String rawHtml, boolean prefix) {
        if (rawHtml == null || rawHtml.isBlank()) {
            return "null";
        }

        int previewLength = 400;
        if (rawHtml.length() <= previewLength) {
            return rawHtml;
        }

        return prefix
                ? rawHtml.substring(0, previewLength)
                : rawHtml.substring(Math.max(0, rawHtml.length() - previewLength));
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }

        return normalized.substring(0, Math.max(0, maxLength - 1)) + "…";
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
            @JsonProperty("target_product") ProductCandidateResponse targetProduct,
            String platform,
            @JsonProperty("navigation_strategy") String navigationStrategy,
            @JsonProperty("agent_type") String agentType,
            @JsonProperty("raw_html") String rawHtml,
            @JsonProperty("trigger_price") Integer triggerPrice
    ) {
    }

}
