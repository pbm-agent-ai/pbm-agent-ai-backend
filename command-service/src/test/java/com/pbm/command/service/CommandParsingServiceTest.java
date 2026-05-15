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
 * 역할: 현재 단계의 mock 파싱 규칙이 의도한 구조화 결과를 반환하는지 검증한다.
 * 동작: 대표 명령어를 입력하여 intent, category, 필수 누락/모호 필드 계산 결과를 확인한다.
 *       PLATFORM이 필수 필드로 승격된 rule을 반영한다.
 * 연관: CommandParsingService, CommandFieldEvaluationService.
 */
class CommandParsingServiceTest {

    private final OpenAiCommandClient openAiCommandClient = mock(OpenAiCommandClient.class);

    private final CommandParsingService commandParsingService =
            new CommandParsingService(openAiCommandClient, new CommandFieldEvaluationService(new CommandFieldPolicyService()));

    @Test
    @DisplayName("나이키 조던 자동 결제 명령을 신발 자동 결제로 파싱한다 (platform 누락은 필수 누락으로)")
    void parse_autoPurchaseShoesCommand_returnsStructuredResponse() {
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
                                null,
                                200000,
                                null,
                                "KRW"
                        ),
                        0.91
                ));

        CommandParseResponse response = commandParsingService.parse(
                new CommandParseRequest(1L, "나이키 조던 20만원 이하면 결제해줘")
        );

        assertThat(response.intent()).isEqualTo(CommandIntent.AUTO_PURCHASE);
        assertThat(response.parsedCommand().productCategory()).isEqualTo(ProductCategory.SHOES);
        assertThat(response.parsedCommand().productName()).isEqualTo("나이키 조던");
        assertThat(response.parsedCommand().maxPrice()).isEqualTo(200000);
        assertThat(response.missingRequiredFields()).containsExactly("size", "platform");
        assertThat(response.ambiguousFields()).containsExactly("color", "model");
        assertThat(response.needsClarification()).isTrue();
    }

    @Test
    @DisplayName("아이폰 가격 확인 명령을 전자기기 가격 확인으로 파싱한다 (color 누락은 필수 누락으로 계산)")
    void parse_priceCheckElectronicsCommandWithPlatform_returnsColorMissing() {
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
                                PlatformType.NAVER,
                                1400000,
                                null,
                                "KRW"
                        ),
                        0.95
                ));

        CommandParseResponse response = commandParsingService.parse(
                new CommandParseRequest(1L, "아이폰 15 프로 256GB 140만원 이하 가격 알려줘")
        );

        assertThat(response.intent()).isEqualTo(CommandIntent.PRICE_CHECK);
        assertThat(response.parsedCommand().productCategory()).isEqualTo(ProductCategory.ELECTRONICS);
        assertThat(response.parsedCommand().maxPrice()).isEqualTo(1400000);
        assertThat(response.parsedCommand().model()).isEqualTo("15 프로 256GB");
        assertThat(response.confidence()).isEqualTo(0.95);
        assertThat(response.missingRequiredFields()).containsExactly("color");
        assertThat(response.needsClarification()).isTrue();
    }
}
