package com.pbm.command.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pbm.command.client.dto.OpenAiParsedCommandPayload;
import com.pbm.command.client.prompt.CommandParsePrompt;
import com.pbm.command.client.prompt.CommandParsePromptBuilder;
import com.pbm.command.config.OpenAiProperties;
import com.pbm.command.config.RestClientConfig;
import com.pbm.command.exception.ExternalApiProxyException;
import com.pbm.command.dto.request.CommandParseRequest;
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
 * OpenAI 기반 자연어 파싱 클라이언트.
 *
 * 역할: Pro  mptBuilder가 만든 system/user prompt를 external-api-service의 OpenAI 프록시로 전송하고,
 *       정규화된 응답에서 parsed_json을 꺼내 구조화 파싱 결과로 변환한다.
 * 동작: 프록시 요청 body 생성 → `/api/v1/openai/parse-command` 호출 → refusal/finishReason 검사 → parsed_json 파싱.
 * 연관: CommandParsePromptBuilder, OpenAiProperties, CommandParsingService.
 */
// Component 어노테이션은 기술적 도구 느낌, 여기서 HTTP 클라이언트 역할이므로 Component 사용.
@Component
public class OpenAiCommandClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCommandClient.class);

    private final RestClient externalApiRestClient;     // external-api-service로 HTTP 요청을 보내는 클라이언트
    private final ObjectMapper objectMapper;            // JSON 파싱 도구, Jackson 라이브러리 핵심 클래스
    private final CommandParsePromptBuilder commandParsePromptBuilder;  // 사용자 문장을 GPT 프롬프트로 변환해 주는 빌더
    private final String openAiParseCommandUrl;         // 외부 API 호출 URL, 생성 시점에 한 번 계산하고 이후 재사용

    @Autowired
    public OpenAiCommandClient(
            ObjectMapper objectMapper,      // Sprig이 제공하는 기본 ObjectMapper
            CommandParsePromptBuilder commandParsePromptBuilder,    // Spring이 생성한 PromptBuilder
            OpenAiProperties openAiProperties   // application.yml에서 읽은 설정값
    ) {
        /**
         * RestClientConfig라는 별도 클래스에 HTTP 클라이언트 생성 위임
         * 이렇게 분리한 이유:
         * 1) 연결/읽기 타임아웃 설정이 복잡함
         * 2) 다른 클라이언트에서도 같은 설정 재사용 가능
         * 3) Apache HttpClient 5 기반으로 생성 (404 버그 해결 목적)
         */
        this.externalApiRestClient = RestClientConfig.createExternalApiRestClient(openAiProperties);
        this.objectMapper = objectMapper;
        this.commandParsePromptBuilder = commandParsePromptBuilder;
        this.openAiParseCommandUrl = buildOpenAiParseCommandUrl(openAiProperties.getBaseUrl());
        log.info("[OpenAiCommandClient] 초기화 완료 - 프록시 URL: {}", this.openAiParseCommandUrl);
    }

    /**
     * 테스트나 특수한 경우 사용할 생성자.
     *
     * @param externalApiRestClient external-api-service 호출용 RestClient
     * @param objectMapper          JSON 직렬화/역직렬화 도구
     * @param commandParsePromptBuilder 프롬프트 생성기
     */
    public OpenAiCommandClient(
            RestClient externalApiRestClient,
            ObjectMapper objectMapper,
            CommandParsePromptBuilder commandParsePromptBuilder
    ) {
        this.externalApiRestClient = externalApiRestClient;
        this.objectMapper = objectMapper;
        this.commandParsePromptBuilder = commandParsePromptBuilder;
        this.openAiParseCommandUrl = "http://localhost:8090/api/v1/openai/parse-command";
    }

    /**
     * 사용자 자연어 명령을 OpenAI로 파싱 요청한다.
     *
     * @param request 사용자 자연어 명령 요청 DTO
     * @return OpenAI가 반환한 구조화 파싱 결과
     */
    @Retry(name = "openAiProxyService")
    @CircuitBreaker(name = "openAiProxyService", fallbackMethod = "parseCommandFallback")
    public OpenAiParsedCommandPayload parseCommand(CommandParseRequest request) {
        CommandParsePrompt prompt = commandParsePromptBuilder.build(request);   // 사용자 자연어 문장을 GPT가 이해할 수 있는 프롬프트로 변환
        OpenAiProxyParseRequest proxyRequest = new OpenAiProxyParseRequest(
                prompt.systemPrompt(),
                prompt.userPrompt()
        );

        log.debug("[OpenAiCommandClient] 프록시 요청 URL: {}", openAiParseCommandUrl);

        OpenAiProxyParseResponse response;
        try {
            response = externalApiRestClient.post()
                    .uri(openAiParseCommandUrl)
                    .body(proxyRequest)
                    .retrieve()
                    .body(OpenAiProxyParseResponse.class);
        } catch (RestClientException e) {
            log.error("[OpenAiCommandClient] external-api-service 호출 실패 - URL: {}, 에러: {}", openAiParseCommandUrl, e.getMessage());
            throw new OpenAiClientException("external-api-service OpenAI 프록시 호출 중 오류가 발생했습니다.", e);
        }

        log.info("[OpenAiCommandClient] OpenAI 프록시 응답 수신 - finishReason={}, confidence={}, refusal={}, parsedJson={}",
                response == null ? null : response.finishReason(),
                response == null ? null : response.confidence(),
                response == null ? null : response.refusal(),
                response == null ? null : response.parsedJson());

        validateProxyResponse(response);    // HTTP 200응답이 왔어도 내용이 이상할 수 있으니 추가 검증

        try {
            // response.parseJson(): JSON 타입을 -> OpenAiParsedCommandPayload: 반환할 목표 타입
            OpenAiParsedCommandPayload payload = objectMapper.treeToValue(response.parsedJson(), OpenAiParsedCommandPayload.class);
            validateParsedPayload(payload);
            log.info("[OpenAiCommandClient] OpenAI 프록시 응답 역직렬화 완료 - intent={}, parsedCommand={}, confidence={}",
                    payload.intent(), payload.parsedCommand(), payload.confidence());
            return payload;
        } catch (OpenAiResponseParseException e) {
            throw e;
        } catch (Exception e) {
            throw new OpenAiResponseParseException("external-api-service가 반환한 parsed_json을 파싱할 수 없습니다.", e);
        }
    }

    /**
     * external-api-service 프록시 호출 실패 시 fallback 메서드.
     *
     * @param request 사용자 자연어 명령 요청 DTO
     * @param t       발생 원인 예외
     * @return 반환하지 않고 예외를 발생시킨다
     */
    OpenAiParsedCommandPayload parseCommandFallback(CommandParseRequest request, Throwable t) {
        throw new ExternalApiProxyException(
                "external-api-service OpenAI 프록시를 사용할 수 없습니다.",
                t
        );
    }

    private void validateProxyResponse(OpenAiProxyParseResponse response) {
        if (response == null) {
            throw new OpenAiClientException("external-api-service 응답이 없습니다.");
        }
        if (response.refusal() != null && !response.refusal().isBlank()) {
            throw new OpenAiClientException("OpenAI 모델이 요청을 거부했습니다: " + response.refusal());
        }
        if (!"stop".equals(response.finishReason())) {
            throw new OpenAiClientException("OpenAI 응답이 정상 종료되지 않았습니다. finishReason=" + response.finishReason());
        }
        if (response.parsedJson() == null || response.parsedJson().isNull()) {
            throw new OpenAiResponseParseException("external-api-service 응답 parsed_json이 비어 있습니다.");
        }
    }

    private void validateParsedPayload(OpenAiParsedCommandPayload payload) {
        if (payload == null) {
            throw new OpenAiResponseParseException("OpenAI 파싱 결과가 null입니다.");
        }
        // intent == null은 GPT가 의도를 파악하지 못한 정상 비즈니스 케이스이므로 예외 처리하지 않음.
        // 상위 서비스에서 needsClarification=true로 프론트에 모달 요청을 보낸다.
        if (payload.parsedCommand() == null) {
            throw new OpenAiResponseParseException("OpenAI 파싱 결과에 parsedCommand가 없습니다.");
        }
        if (payload.confidence() == null) {
            throw new OpenAiResponseParseException("OpenAI 파싱 결과에 confidence가 없습니다.");
        }
    }

    private String buildOpenAiParseCommandUrl(String baseUrl) {
        // 삼항 연산자를 이용해 baseUrl이 Null이면 기본값, 아니면 끝의 / 제거
        String normalizedBaseUrl = baseUrl == null ? "http://localhost:8090" : baseUrl.replaceAll("/+$", "");
        return normalizedBaseUrl + "/api/v1/openai/parse-command";
    }

    private record OpenAiProxyParseRequest(
            @JsonProperty("system_prompt") String systemPrompt,
            @JsonProperty("user_prompt") String userPrompt
    ) {
    }

    private record OpenAiProxyParseResponse(
            @JsonProperty("parsed_json") com.fasterxml.jackson.databind.JsonNode parsedJson,
            @JsonProperty("finish_reason") String finishReason,
            Double confidence,
            String refusal
    ) {
    }
}
