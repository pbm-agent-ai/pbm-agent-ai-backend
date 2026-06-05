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
 * 역할: 모든 카테고리 공통 필수 필드(PRODUCT_NAME, MAX_PRICE, PLATFORM) 규칙이
 *       실제 ParsedCommand 입력에 대해 기대한 결과를 내는지 검증한다.
 * 동작: 공통 필수 필드 누락 검사와 자동 결제/상품명 모호성에 따른 추가 확인 필드를 테스트한다.
 *       카테고리별 size/color/model 의존 로직은 제거되었다.
 * 연관: CommandFieldEvaluationService, FieldEvaluationResult.
 */
class CommandFieldEvaluationServiceTest {

    private final CommandFieldEvaluationService commandFieldEvaluationService =
            new CommandFieldEvaluationService(new CommandFieldPolicyService());

    @Test
    @DisplayName("PLATFORM이 누락된 자동 결제 명령은 platform을 필수 누락으로 계산한다 (카테고리 무관)")
    void evaluate_autoPurchaseMissingPlatform_returnsPlatformAsMissing() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.SHOES,
                "나이키 조던",
                "나이키",
                "조던",
                null,
                null,
                null,
                null,   // platforms=null: PLATFORM은 공통 필수 필드이므로 누락으로 감지
                200000,
                null,
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.AUTO_PURCHASE, parsedCommand);

        // 공통 필수 필드: PRODUCT_NAME(있음), MAX_PRICE(있음), PLATFORM(없음)
        assertThat(result.missingRequiredFields()).containsExactly("platform");
        // autoPurchaseClarificationFields와 broadProductClarificationFields는 빈 리스트 → 모호 필드 없음
        assertThat(result.ambiguousFields()).isEmpty();
        assertThat(result.needsClarification()).isTrue();
    }

    @Test
    @DisplayName("전자기기 가격 확인 명령은 모든 공통 필수 필드(productName/maxPrice/platform)가 있으면 추가 확인 없이 진행한다")
    void evaluate_priceCheckElectronicsWithAllFields_proceedsWithoutClarification() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.ELECTRONICS,
                "아이폰 15 프로 256GB",
                "애플",
                "아이폰",
                "15 프로 256GB",
                null,   // color는 필수 필드 아님
                null,
                java.util.List.of(PlatformType.NAVER),
                1400000,
                null,
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.PRICE_CHECK, parsedCommand);

        // 공통 필수 필드: PRODUCT_NAME(있음), MAX_PRICE(PRICE_CHECK이므로 skip), PLATFORM(NAVER있음)
        assertThat(result.missingRequiredFields()).isEmpty();
        assertThat(result.ambiguousFields()).isEmpty();
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    @DisplayName("카테고리가 UNKNOWN이고 PLATFORM이 누락되면 필수 누락으로 처리한다")
    void evaluate_unknownCategoryMissingPlatform_returnsPlatformMissing() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.UNKNOWN,
                "에어팟 프로",
                "애플",
                null,
                null,
                null,
                null,
                null,   // platforms=null: 공통 필수 필드이므로 누락
                300000,
                null,
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.PRICE_TRACK, parsedCommand);

        // 공통 필수 필드: PRODUCT_NAME(있음), MAX_PRICE(있음), PLATFORM(없음)
        assertThat(result.missingRequiredFields()).containsExactly("platform");
        assertThat(result.ambiguousFields()).isEmpty();
        assertThat(result.needsClarification()).isTrue();
    }

    @Test
    @DisplayName("PRICE_CHECK에서도 PLATFORM이 없으면 필수 누락으로 처리한다")
    void evaluate_priceCheckMissingPlatform_returnsPlatformMissing() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.SHOES,
                "나이키 에어맥스",
                "나이키",
                "에어맥스",
                null,
                null,
                "270",
                null,  // platforms=null: PRICE_CHECK이어도 PLATFORM은 공통 필수 필드
                150000,
                null,
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.PRICE_CHECK, parsedCommand);

        // 공통 필수 필드: PRODUCT_NAME(있음), MAX_PRICE(PRICE_CHECK이므로 skip), PLATFORM(없음)
        assertThat(result.missingRequiredFields()).containsExactly("platform");
        assertThat(result.ambiguousFields()).isEmpty();
        assertThat(result.needsClarification()).isTrue();
    }

    @Test
    @DisplayName("모든 공통 필수 필드가 있는 AUTO_PURCHASE는 추가 확인 없이 진행한다")
    void evaluate_autoPurchaseWithAllFields_proceedsWithoutClarification() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.ELECTRONICS,
                "맥북 프로 16인치",
                "애플",
                "맥북",
                "프로 16인치",
                "실버",
                null,
                java.util.List.of(PlatformType.NAVER),
                3000000,
                null,
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.AUTO_PURCHASE, parsedCommand);

        // 공통 필수 필드 3개 모두 있음
        assertThat(result.missingRequiredFields()).isEmpty();
        assertThat(result.ambiguousFields()).isEmpty();
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    @DisplayName("GPT가 아무것도 파싱하지 못하면 productCategory, productName이 누락으로 표시된다")
    void evaluate_nullParsedCommand_returnsBasicRequiredFields() {
        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.AUTO_PURCHASE, null);

        assertThat(result.missingRequiredFields()).contains("productCategory", "productName", "maxPrice");
        assertThat(result.needsClarification()).isTrue();
    }

    @Test
    @DisplayName("PRICE_CHECK에서 maxPrice가 누락되어도 공통 필수 필드 누락으로 처리하지 않는다")
    void evaluate_priceCheckMissingMaxPrice_skipsMaxPriceCheck() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.ELECTRONICS,
                "갤럭시 북",
                "삼성",
                null,
                null,
                null,
                null,
                java.util.List.of(PlatformType.NAVER),
                null,   // maxPrice=null: PRICE_CHECK이므로 skip
                null,
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.PRICE_CHECK, parsedCommand);

        // PRICE_CHECK이므로 maxPrice는 skip, PLATFORM은 있음, PRODUCT_NAME 있음 → 누락 없음
        assertThat(result.missingRequiredFields()).isEmpty();
        assertThat(result.needsClarification()).isFalse();
    }
}
