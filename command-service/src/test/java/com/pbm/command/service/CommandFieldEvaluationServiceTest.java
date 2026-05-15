package com.pbm.command.service;

import com.pbm.command.domain.CommandIntent;
import com.pbm.command.domain.PlatformType;
import com.pbm.command.domain.ProductCategory;
import com.pbm.command.dto.response.ParsedCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 누락 필드/모호 필드 계산 서비스 테스트.
 *
 * 역할: stage 3 규칙(PLATFORM 필수 필드 승격 포함)이 실제 ParsedCommand 입력에 대해 기대한 결과를 내는지 검증한다.
 * 동작: 카테고리별 필수값 누락과 자동 결제/상품명 모호성에 따른 추가 확인 필드를 테스트한다.
 * 연관: CommandFieldEvaluationService, FieldEvaluationResult.
 */
class CommandFieldEvaluationServiceTest {

    private final CommandFieldEvaluationService commandFieldEvaluationService =
            new CommandFieldEvaluationService(new CommandFieldPolicyService());

    @Test
    @DisplayName("신발 자동 결제 명령은 size, platform 누락을 필수 누락으로, model/color 누락을 모호 필드로 계산한다")
    void evaluate_autoPurchaseShoes_returnsMissingAndAmbiguousFields() {
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
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.AUTO_PURCHASE, parsedCommand);

        assertThat(result.missingRequiredFields()).containsExactly("size", "platform");
        assertThat(result.ambiguousFields()).containsExactly("color", "model");
        assertThat(result.needsClarification()).isTrue();
    }

    @Test
    @DisplayName("전자기기 가격 확인 명령은 color가 없으면 color를 필수 누락으로 계산한다")
    void evaluate_priceCheckElectronicsWithoutColor_returnsColorAsMissingField() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.ELECTRONICS,
                "아이폰 15 프로 256GB",
                "애플",
                "아이폰",
                "15 프로 256GB",
                null,
                null,
                PlatformType.NAVER,
                1400000,
                null,
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.PRICE_CHECK, parsedCommand);

        assertThat(result.missingRequiredFields()).containsExactly("color");
        assertThat(result.ambiguousFields()).isEmpty();
        assertThat(result.needsClarification()).isTrue();
    }

    @Test
    @DisplayName("카테고리를 알 수 없으면 productCategory를 모호 필드로 반환한다")
    void evaluate_unknownCategory_returnsProductCategoryAsAmbiguousField() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.UNKNOWN,
                "에어팟 프로",
                "애플",
                null,
                null,
                null,
                null,
                null,
                300000,
                null,
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.PRICE_TRACK, parsedCommand);

        assertThat(result.missingRequiredFields()).isEmpty();
        assertThat(result.ambiguousFields()).containsExactly("productCategory");
        assertThat(result.needsClarification()).isTrue();
    }

    @Test
    @DisplayName("platform이 null이면 필수 필드 누락으로 감지된다 (신발 카테고리)")
    void evaluate_shoesMissingPlatform_returnsPlatformInMissingRequiredFields() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.SHOES,
                "나이키 에어맥스",
                "나이키",
                "에어맥스",
                null,
                null,
                "270",
                null,  // platform 누락
                150000,
                null,
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.PRICE_CHECK, parsedCommand);

        assertThat(result.missingRequiredFields()).contains("platform");
        assertThat(result.needsClarification()).isTrue();
    }
}
