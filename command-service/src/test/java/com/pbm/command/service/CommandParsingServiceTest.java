package com.pbm.command.service;

import com.pbm.command.client.OpenAiCommandClient;
import com.pbm.command.client.dto.OpenAiParsedCommandPayload;
import com.pbm.command.domain.CommandIntent;
import com.pbm.command.domain.PlatformType;
import com.pbm.command.domain.ProductCategory;
import com.pbm.command.dto.request.CommandParseRequest;
import com.pbm.command.dto.response.CommandParseResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CommandParsingService 단위 테스트.
 *
 * 역할: 공통 필수 필드(PRODUCT_NAME, MAX_PRICE) 규칙이
 *       실제 파싱 응답에 올바르게 반영되는지 검증한다.
 * 동작: PLATFORM은 선택 필드 — 누락돼도 needsClarification이 false로 실행을 계속한다.
 *       상품명과 최대가격만 필수이며, 나머지 필드는 null 허용.
 * 연관: CommandParsingService, CommandFieldEvaluationService.
 */
class CommandParsingServiceTest {

    private final OpenAiCommandClient openAiCommandClient = mock(OpenAiCommandClient.class);

    private final CommandParsingService commandParsingService =
            new CommandParsingService(openAiCommandClient, new CommandFieldEvaluationService(new CommandFieldPolicyService()));

    @Test
    @DisplayName("PLATFORM이 없어도 clarification 없이 즉시 실행을 계속한다")
    void parse_autoPurchaseShoesWithoutPlatform_proceedsWithoutClarification() {
        when(openAiCommandClient.parseCommand(any(CommandParseRequest.class)))
                .thenReturn(new OpenAiParsedCommandPayload(
                        CommandIntent.AUTO_PURCHASE,
                        new com.pbm.command.dto.response.ParsedCommand(
                                ProductCategory.SHOES,
                                "나이키 조던",
                                "나이키",
                                "조던",
                                null,
                                null,
                                null,
                                null,       // platforms = null (미지정)
                                200000,
                                null,
                                "KRW"
                        ),
                        0.91
                ));

        CommandParseResponse response = commandParsingService.parse(
                new CommandParseRequest("나이키 조던 20만원 이하면 결제해줘")
        );

        assertThat(response.intent()).isEqualTo(CommandIntent.AUTO_PURCHASE);
        assertThat(response.parsedCommand().productCategory()).isEqualTo(ProductCategory.SHOES);
        assertThat(response.parsedCommand().productName()).isEqualTo("나이키 조던");
        assertThat(response.parsedCommand().maxPrice()).isEqualTo(200000);
        // PLATFORM이 선택 필드로 변경됨 → missing에 platform 없음
        assertThat(response.missingRequiredFields()).doesNotContain("platform");
        assertThat(response.ambiguousFields()).isEmpty();
        // platform 누락만으로는 clarification 요청 안 함
        assertThat(response.needsClarification()).isFalse();
    }

    @Test
    @DisplayName("모든 공통 필수 필드가 있는 가격 확인 명령은 clarification 없이 진행한다")
    void parse_priceCheckElectronicsWithAllFields_proceedsWithoutClarification() {
        when(openAiCommandClient.parseCommand(any(CommandParseRequest.class)))
                .thenReturn(new OpenAiParsedCommandPayload(
                        CommandIntent.PRICE_CHECK,
                        new com.pbm.command.dto.response.ParsedCommand(
                                ProductCategory.ELECTRONICS,
                                "아이폰 프로",
                                "애플",
                                "아이폰",
                                "15 프로 256GB",
                                null,
                                null,
                                java.util.List.of(PlatformType.NAVER),
                                1400000,
                                null,
                                "KRW"
                        ),
                        0.95
                ));

        CommandParseResponse response = commandParsingService.parse(
                new CommandParseRequest("아이폰 15 프로 256GB 140만원 이하 가격 알려줘")
        );

        assertThat(response.intent()).isEqualTo(CommandIntent.PRICE_CHECK);
        assertThat(response.parsedCommand().productCategory()).isEqualTo(ProductCategory.ELECTRONICS);
        assertThat(response.parsedCommand().maxPrice()).isEqualTo(1400000);
        assertThat(response.parsedCommand().model()).isEqualTo("15 프로 256GB");
        assertThat(response.confidence()).isEqualTo(0.95);
        // 공통 필수 필드 모두 있음 → 누락 없음
        assertThat(response.missingRequiredFields()).isEmpty();
        assertThat(response.needsClarification()).isFalse();
    }

    @Test
    @DisplayName("카테고리가 UNKNOWN이어도 모든 공통 필수 필드가 있으면 clarification 없이 진행한다")
    void parse_unknownCategoryButAllRequiredFields_doesNotNeedClarification() {
        when(openAiCommandClient.parseCommand(any(CommandParseRequest.class)))
                .thenReturn(new OpenAiParsedCommandPayload(
                        CommandIntent.AUTO_PURCHASE,
                        new com.pbm.command.dto.response.ParsedCommand(
                                ProductCategory.UNKNOWN,
                                "칠성사이다 210ml 30개",
                                "칠성사이다",
                                null,
                                null,
                                null,
                                "210ml 30개",
                                java.util.List.of(PlatformType.NAVER),
                                50000,
                                null,
                                "KRW"
                        ),
                        0.98
                ));

        CommandParseResponse response = commandParsingService.parse(
                new CommandParseRequest("네이버에서 칠성사이다 210ml 30개가 50000원 이하면 구매해줘")
        );

        assertThat(response.intent()).isEqualTo(CommandIntent.AUTO_PURCHASE);
        assertThat(response.parsedCommand().productCategory()).isEqualTo(ProductCategory.UNKNOWN);
        // 공통 필수 필드 2개(PRODUCT_NAME, MAX_PRICE) 모두 있음 → 누락 없음
        assertThat(response.missingRequiredFields()).isEmpty();
        assertThat(response.ambiguousFields()).isEmpty();
        assertThat(response.needsClarification()).isFalse();
    }
}
