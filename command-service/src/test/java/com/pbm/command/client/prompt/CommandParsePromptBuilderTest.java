package com.pbm.command.client.prompt;

import com.pbm.command.dto.request.CommandParseRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GPT 프롬프트 생성기 테스트.
 *
 * 역할: 5단계에서 정의한 프롬프트 규칙이 코드에 정확히 반영되었는지 검증한다.
 * 동작: system prompt에 허용 값과 JSON only 규칙이 포함되는지,
 *       user prompt에 실제 명령문이 삽입되는지 확인한다.
 * 연관: CommandParsePromptBuilder, CommandParsePrompt.
 */
class CommandParsePromptBuilderTest {

    private final CommandParsePromptBuilder commandParsePromptBuilder = new CommandParsePromptBuilder();

    CommandParsePromptBuilderTest() throws Exception {
        var field = CommandParsePromptBuilder.class.getDeclaredField("systemPromptTemplate");
        field.setAccessible(true);
        field.set(commandParsePromptBuilder,
                new org.springframework.core.io.ClassPathResource("prompts/command-parser-system.txt"));
    }

    @Test
    @DisplayName("system prompt는 허용 intent/category/platform과 JSON only 규칙을 포함한다")
    void buildSystemPrompt_containsAllowedValuesAndRules() {
        String systemPrompt = commandParsePromptBuilder.buildSystemPrompt();

        assertThat(systemPrompt).contains("JSON 외의 텍스트를 절대 출력하지 마라");
        assertThat(systemPrompt).contains("AUTO_PURCHASE");
        assertThat(systemPrompt).contains("PRICE_TRACK");
        assertThat(systemPrompt).contains("PRICE_CHECK");
        assertThat(systemPrompt).contains("SHOES");
        assertThat(systemPrompt).contains("ELECTRONICS");
        assertThat(systemPrompt).contains("APPAREL");
        assertThat(systemPrompt).contains("UNKNOWN");
        assertThat(systemPrompt).contains("NAVER");
        assertThat(systemPrompt).contains("ALIEXPRESS");
        assertThat(systemPrompt).contains("COUPANG");
        assertThat(systemPrompt).contains("추측하지 말고 null");
        assertThat(systemPrompt).contains("20만원=200000");
        assertThat(systemPrompt).contains("mx master 3s");
        assertThat(systemPrompt).contains("로지텍");
        assertThat(systemPrompt).contains("블랙");
        assertThat(systemPrompt).contains("black");
        assertThat(systemPrompt).contains("productCategory");
        assertThat(systemPrompt).contains("productName");
        assertThat(systemPrompt).contains("brand");
        assertThat(systemPrompt).contains("line");
        assertThat(systemPrompt).contains("model");
        assertThat(systemPrompt).contains("color");
        assertThat(systemPrompt).contains("size");
        assertThat(systemPrompt).contains("platform");
        assertThat(systemPrompt).contains("maxPrice");
        assertThat(systemPrompt).contains("minPrice");
        assertThat(systemPrompt).contains("currency");
        assertThat(systemPrompt).contains("searchCategoryHint");
        assertThat(systemPrompt).contains("MOUSE");
        assertThat(systemPrompt).contains("EARPHONES");
        assertThat(systemPrompt).doesNotContain("{{intents}}");
        assertThat(systemPrompt).doesNotContain("{{categories}}");
        assertThat(systemPrompt).doesNotContain("{{platforms}}");
        assertThat(systemPrompt).doesNotContain("{{parsedCommandFields}}");
    }

    @Test
    @DisplayName("user prompt는 실제 사용자 명령문을 포함한다")
    void buildUserPrompt_containsRequestData() {
        String userPrompt = commandParsePromptBuilder.buildUserPrompt(
                new CommandParseRequest("나이키 조던 20만원 이하면 결제해줘")
        );

        assertThat(userPrompt).contains("commandText: 나이키 조던 20만원 이하면 결제해줘");
        assertThat(userPrompt).contains("JSON만 반환하라");
    }

    @Test
    @DisplayName("build는 system prompt와 user prompt를 함께 반환한다")
    void build_returnsPromptBundle() {
        CommandParsePrompt prompt = commandParsePromptBuilder.build(
                new CommandParseRequest("에어팟 프로 가격 알려줘")
        );

        assertThat(prompt.systemPrompt()).isNotBlank();
        assertThat(prompt.userPrompt()).contains("에어팟 프로 가격 알려줘");
    }
}
