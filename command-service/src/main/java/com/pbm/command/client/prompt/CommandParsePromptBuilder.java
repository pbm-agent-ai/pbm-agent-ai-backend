package com.pbm.command.client.prompt;

import com.pbm.command.domain.CommandFieldType;
import com.pbm.command.domain.CommandIntent;
import com.pbm.command.domain.PlatformType;
import com.pbm.command.domain.ProductCategory;
import com.pbm.command.dto.request.CommandParseRequest;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;

/**
 * GPT 자연어 파싱 프롬프트 생성기.
 *
 * 역할: command-service가 사용할 system prompt와 user prompt 템플릿을 중앙에서 관리한다.
 * 동작: 허용 intent/category/platform, JSON only 규칙, null 처리 규칙,
 *       추측 금지 규칙을 모두 포함한 프롬프트를 생성한다.
 * 연관: CommandParseRequest, 향후 GPT Client, CommandParsingService.
 */
@Component
// 템플릿 파일을 읽고, enum 값을 동적으로 채워서 GPT에게 보낼 system prompt + user prompt 생성
public class CommandParsePromptBuilder {

    // AliExpress 검색 시 category_ids 매핑에 사용할 내부 세부 카테고리 힌트 목록.
    // LLM은 이 허용값 중 하나만 반환하고, 실제 AliExpress category_ids 결정은 서버가 담당한다.
    private static final List<String> SEARCH_CATEGORY_HINTS = List.of(
            "MOUSE",
            "KEYBOARD",
            "EARPHONES",
            "SPEAKER",
            "MONITOR",
            "SMARTPHONE",
            "TABLET",
            "LAPTOP",
            "SNEAKERS",
            "APPAREL_TOP",
            "APPAREL_OUTER",
            "null"
    );

    // @Value를 이용해 이 값을 주입해달라고 요청.
    @Value("classpath:prompts/command-parser-system.txt")
    // Resource: 파일 내용을 읽을 수 있는 Spring 객체 -> 이 필드에 파일 핸들이 자동으로 들어옴.
    private Resource systemPromptTemplate;
    /**
     * @Value("classpath:...")의 의미:
     * - classpath: = JVM 클래스패스 기준
     * - 실제 파일 위치: src/main/resources/prompts/command-parser-system.txt
     * - 빌드 후: build/resources/main/prompts/command-parser-system.txt
     * - Resource 타입이면 파일의 내용을 나중에 읽을 수 있음
     */

    /**
     * 자연어 명령 파싱용 system/user prompt를 생성한다.
     *
     * @param request 사용자 파싱 요청 DTO
     * @return system prompt와 user prompt를 담은 DTO
     */
    public CommandParsePrompt build(CommandParseRequest request) {
        return new CommandParsePrompt(buildSystemPrompt(), buildUserPrompt(request));
    }

    /**
     * GPT에게 전달할 system prompt를 생성한다.
     *
     * @return 자연어 파싱 규칙이 담긴 system prompt
     */
    public String buildSystemPrompt() {
        try {
            // Resource 객체에서 파일 내용을 통쨰로 문자열로 읽음.
            String template = systemPromptTemplate.getContentAsString(StandardCharsets.UTF_8);

            return template
                    .replace("{{intents}}", formatEnumValues(CommandIntent.values()))
                    .replace("{{categories}}", formatEnumValues(ProductCategory.values()))
                    .replace("{{platforms}}", formatPlatformValues())
                    .replace("{{parsedCommandFields}}", formatParsedCommandFields())
                    .replace("{{searchCategoryHints}}", formatSearchCategoryHints());
        } catch (IOException e) {
            throw new IllegalStateException("command-parser system prompt 템플릿을 읽을 수 없습니다.", e);
        }
    }

    /**
     * GPT에게 전달할 user prompt를 생성한다.
     *
     * @param request 사용자 파싱 요청 DTO
     * @return 실제 사용자 명령이 포함된 user prompt
     */
    public String buildUserPrompt(CommandParseRequest request) {
        return """
                아래 사용자 명령을 파싱하라.

                commandText: %s

                다시 한 번 강조한다.
                - JSON만 반환하라.
                - 추측하지 마라.
                - 애매하면 null을 사용하라.
                - intent/productCategory는 허용 목록 밖의 값을 쓰지 마라.
                - platforms는 반드시 JSON 배열로 반환하라. 사용자가 여러 플랫폼을 언급했으면 모두 포함하라. 예: ["NAVER", "ALIEXPRESS"]
                """.formatted(
                request.commandText() == null ? "" : request.commandText()
        );
    }

    private String formatEnumValues(Enum<?>[] values) {
        return Arrays.stream(values)
                .map(Enum::name)
                .map(this::formatBullet)
                .collect(Collectors.joining("\n"));
    }

    private String formatPlatformValues() {
        return Stream.concat(
                        Arrays.stream(PlatformType.values()).map(Enum::name),
                        Stream.of("null")
                )
                .map(this::formatBullet)
                .collect(Collectors.joining("\n"));
    }

    private String formatParsedCommandFields() {
        return Arrays.stream(CommandFieldType.values())
                .map(CommandFieldType::fieldKey)
                .map(this::formatBullet)
                .collect(Collectors.joining("\n"));
    }

    private String formatSearchCategoryHints() {
        return SEARCH_CATEGORY_HINTS.stream()
                .map(this::formatBullet)
                .collect(Collectors.joining("\n"));
    }

    private String formatBullet(String value) {
        return "   - " + value;
    }
}
