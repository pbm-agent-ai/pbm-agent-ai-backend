package com.pbm.command.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.domain.CommandIntent;
import com.pbm.command.domain.PlatformType;
import com.pbm.command.domain.ProductCategory;
import com.pbm.command.dto.request.CommandParseRequest;
import com.pbm.command.dto.response.CommandParseResponse;
import com.pbm.command.dto.response.ParsedCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파싱 API DTO 계약 테스트.
 *
 * 역할: 2단계에서 설계한 request/response record가 의도한 JSON 구조로 직렬화되는지 검증한다.
 * 동작: 요청 DTO의 공백 정리와 응답 DTO의 clarification 계산 규칙을 함께 확인한다.
 * 연관: CommandParseRequest, ParsedCommand, CommandParseResponse.
 */
class CommandParseDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("파싱 요청 DTO는 commandText 앞뒤 공백을 제거한다")
    void commandParseRequest_trimsCommandText() {
        CommandParseRequest request = new CommandParseRequest("  나이키 조던 20만원 이하면 결제해줘  ");

        assertThat(request.commandText()).isEqualTo("나이키 조던 20만원 이하면 결제해줘");
    }

    @Test
    @DisplayName("파싱 응답 DTO는 누락 필드가 있으면 needsClarification을 true로 보정한다")
    void commandParseResponse_setsNeedsClarificationWhenFieldsExist() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.SHOES,
                "나이키 조던",
                "나이키",
                "조던",
                null,
                null,
                null,
                null,
                200000,
                null,
                "KRW",
                "SNEAKERS"
        );

        CommandParseResponse response = new CommandParseResponse(
                CommandIntent.AUTO_PURCHASE,
                parsedCommand,
                List.of("size"),
                List.of("model", "platform"),
                false,
                0.91,
                null
        );

        assertThat(response.needsClarification()).isTrue();
        assertThat(response.missingRequiredFields()).containsExactly("size");
        assertThat(response.ambiguousFields()).containsExactly("model", "platform");
    }

    @Test
    @DisplayName("파싱 응답 DTO는 parse API 응답용 JSON 구조로 직렬화된다")
    void commandParseResponse_serializesToExpectedJsonShape() throws Exception {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.SHOES,
                "나이키 조던",
                "나이키",
                "조던",
                null,
                null,
                "270",
                PlatformType.NAVER,
                200000,
                null,
                "KRW",
                "SNEAKERS"
        );

        CommandParseResponse response = new CommandParseResponse(
                CommandIntent.AUTO_PURCHASE,
                parsedCommand,
                List.of(),
                List.of("model"),
                false,
                0.91,
                null
        );

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(json.get("intent").asText()).isEqualTo("AUTO_PURCHASE");
        assertThat(json.get("parsedCommand").get("productCategory").asText()).isEqualTo("SHOES");
        assertThat(json.get("parsedCommand").get("platform").asText()).isEqualTo("NAVER");
        assertThat(json.get("parsedCommand").get("productName").asText()).isEqualTo("나이키 조던");
        assertThat(json.get("parsedCommand").get("searchCategoryHint").asText()).isEqualTo("SNEAKERS");
        assertThat(json.get("needsClarification").asBoolean()).isTrue();
        assertThat(json.get("ambiguousFields").get(0).asText()).isEqualTo("model");
    }
}
