package com.pbm.command.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pbm.command.client.dto.OpenAiParsedCommandPayload;
import com.pbm.command.client.prompt.CommandParsePromptBuilder;
import com.pbm.command.config.OpenAiProperties;
import com.pbm.command.domain.CommandIntent;
import com.pbm.command.domain.ProductCategory;
import com.pbm.command.dto.request.CommandParseRequest;
import com.pbm.command.exception.ExternalApiProxyException;
import com.pbm.command.exception.OpenAiClientException;
import com.pbm.command.exception.OpenAiResponseParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * OpenAiCommandClient 단위 테스트.
 *
 * 역할: external-api-service의 OpenAI 프록시 응답을 정상 파싱하는지, refusal/invalid JSON을 올바르게 예외 처리하는지 검증한다.
 * 동작: MockRestServiceServer로 `/api/v1/openai/parse-command` 응답을 가짜로 만들어 client 동작을 확인한다.
 * 연관: OpenAiCommandClient, CommandParsePromptBuilder.
 */
class OpenAiCommandClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("OpenAI 응답이 정상 JSON이면 구조화 파싱 결과를 반환한다")
    void parseCommand_returnsParsedPayload() throws Exception {
        TestFixture fixture = createFixture();
        fixture.server.expect(requestTo("http://localhost:8090/api/v1/openai/parse-command"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(successResponseJson(), MediaType.APPLICATION_JSON));

        OpenAiParsedCommandPayload payload = fixture.client.parseCommand(
                new CommandParseRequest(1L, "나이키 조던 20만원 이하면 결제해줘")
        );

        assertThat(payload.intent()).isEqualTo(CommandIntent.AUTO_PURCHASE);
        assertThat(payload.parsedCommand().productCategory()).isEqualTo(ProductCategory.SHOES);
        assertThat(payload.parsedCommand().productName()).isEqualTo("나이키 조던");
        assertThat(payload.confidence()).isEqualTo(0.91);
        fixture.server.verify();
    }

    @Test
    @DisplayName("OpenAI 모델이 refusal을 반환하면 OpenAiClientException을 던진다")
    void parseCommand_throwsWhenModelRefuses() throws Exception {
        TestFixture fixture = createFixture();
        fixture.server.expect(requestTo("http://localhost:8090/api/v1/openai/parse-command"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(refusalResponseJson(), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.parseCommand(new CommandParseRequest(1L, "위험한 요청")))
                .isInstanceOf(OpenAiClientException.class)
                .hasMessageContaining("거부");
    }

    @Test
    @DisplayName("OpenAI content가 잘못된 JSON이면 OpenAiResponseParseException을 던진다")
    void parseCommand_throwsWhenJsonIsInvalid() throws Exception {
        TestFixture fixture = createFixture();
        fixture.server.expect(requestTo("http://localhost:8090/api/v1/openai/parse-command"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(invalidJsonResponse(), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.parseCommand(new CommandParseRequest(1L, "에어팟 프로 가격 알려줘")))
                .isInstanceOf(OpenAiResponseParseException.class);
    }

    @Test
    @DisplayName("fallback 메서드는 ExternalApiProxyException을 던진다")
    void parseCommandFallback_throwsExternalApiProxyException() throws Exception {
        TestFixture fixture = createFixture();

        assertThatThrownBy(() -> fixture.client.parseCommandFallback(
                new CommandParseRequest(1L, "나이키 조던 20만원 이하면 결제해줘"),
                new RuntimeException("circuit open")
        )).isInstanceOf(ExternalApiProxyException.class)
                .hasMessageContaining("external-api-service OpenAI 프록시");
    }

    private TestFixture createFixture() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        CommandParsePromptBuilder promptBuilder = new CommandParsePromptBuilder();
        var field = CommandParsePromptBuilder.class.getDeclaredField("systemPromptTemplate");
        field.setAccessible(true);
        field.set(promptBuilder,
                new org.springframework.core.io.ClassPathResource("prompts/command-parser-system.txt"));

        OpenAiProperties properties = new OpenAiProperties();
        properties.setBaseUrl("http://localhost:8090");

        OpenAiCommandClient client = new OpenAiCommandClient(restClient, objectMapper, promptBuilder);
        return new TestFixture(client, server);
    }

    private String successResponseJson() {
        return buildProxyResponseJson(
                """
                        {"intent":"AUTO_PURCHASE","parsedCommand":{"productCategory":"SHOES","productName":"나이키 조던","brand":"나이키","line":"조던","model":null,"color":null,"size":null,"platform":null,"maxPrice":200000,"minPrice":null,"currency":"KRW"},"confidence":0.91}
                        """.trim(),
                null,
                "stop"
        );
    }

    private String refusalResponseJson() {
        return buildProxyResponseJson(null, "I'm sorry, I cannot assist with that request.", "stop");
    }

    private String invalidJsonResponse() {
        return buildProxyResponseJson("{\"intent\":\"PRICE_CHECK\",", null, "stop");
    }

    private String buildProxyResponseJson(String parsedJsonContent, String refusal, String finishReason) {
        ObjectNode root = objectMapper.createObjectNode();
        if (parsedJsonContent == null) {
            root.putNull("parsed_json");
        } else {
            try {
                root.set("parsed_json", objectMapper.readTree(parsedJsonContent));
            } catch (Exception e) {
                root.put("parsed_json", parsedJsonContent);
            }
        }

        root.put("finish_reason", finishReason);
        root.put("confidence", 0.91);
        if (refusal == null) {
            root.putNull("refusal");
        } else {
            root.put("refusal", refusal);
        }
        return root.toString();
    }

    private record TestFixture(OpenAiCommandClient client, MockRestServiceServer server) {
    }
}
