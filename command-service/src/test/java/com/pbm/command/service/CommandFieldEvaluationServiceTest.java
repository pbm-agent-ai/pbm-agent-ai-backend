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
 * 역할: 공통 필수 필드(PRODUCT_NAME, MAX_PRICE) 규칙이
 *       실제 ParsedCommand 입력에 대해 기대한 결과를 내는지 검증한다.
 * 동작: PLATFORM은 선택 필드 — 누락돼도 needsClarification이 false가 되어 실행을 계속한다.
 *       카테고리별 size/color/model 의존 로직은 제거되었다.
 * 연관: CommandFieldEvaluationService, FieldEvaluationResult.
 */
class CommandFieldEvaluationServiceTest {

    private final CommandFieldEvaluationService commandFieldEvaluationService =
            new CommandFieldEvaluationService(new CommandFieldPolicyService());

    @Test
    @DisplayName("PLATFORM이 없어도 platform이 필수 누락으로 표시되지 않는다 — 실행을 계속한다")
    void evaluate_autoPurchaseMissingPlatform_doesNotReturnPlatformAsMissing() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.SHOES,
                "나이키 조던",
                "나이키",
                "조던",
                null,
                null,
                null,
                null,   // platforms=null: 선택 필드이므로 누락으로 처리하지 않음
                200000,
                null,
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.AUTO_PURCHASE, parsedCommand);

        // PLATFORM은 선택 필드 → missing에 포함되지 않아야 함
        assertThat(result.missingRequiredFields()).doesNotContain("platform");
        assertThat(result.ambiguousFields()).isEmpty();
        // platform 누락만으로는 clarification 요청 안 함
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    @DisplayName("전자기기 가격 확인 명령은 PRODUCT_NAME/MAX_PRICE가 있으면 추가 확인 없이 진행한다")
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

        // 공통 필수 필드: PRODUCT_NAME(있음), MAX_PRICE(PRICE_CHECK이므로 skip)
        assertThat(result.missingRequiredFields()).isEmpty();
        assertThat(result.ambiguousFields()).isEmpty();
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    @DisplayName("카테고리가 UNKNOWN이어도 PLATFORM이 없으면 필수 누락으로 처리하지 않는다")
    void evaluate_unknownCategoryMissingPlatform_doesNotReturnPlatformMissing() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.UNKNOWN,
                "에어팟 프로",
                "애플",
                null,
                null,
                null,
                null,
                null,   // platforms=null: 선택 필드 — 누락 처리 안 함
                300000,
                null,
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.PRICE_TRACK, parsedCommand);

        // PLATFORM은 선택 필드 → missing에 포함되지 않아야 함
        assertThat(result.missingRequiredFields()).doesNotContain("platform");
        assertThat(result.ambiguousFields()).isEmpty();
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    @DisplayName("PRICE_CHECK에서 PLATFORM이 없어도 필수 누락으로 처리하지 않는다")
    void evaluate_priceCheckMissingPlatform_doesNotReturnPlatformMissing() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.SHOES,
                "나이키 에어맥스",
                "나이키",
                "에어맥스",
                null,
                null,
                "270",
                null,  // platforms=null: 선택 필드
                150000,
                null,
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.PRICE_CHECK, parsedCommand);

        // 공통 필수 필드: PRODUCT_NAME(있음), MAX_PRICE(PRICE_CHECK이므로 skip)
        // PLATFORM은 선택 필드 → missing 없음
        assertThat(result.missingRequiredFields()).isEmpty();
        assertThat(result.ambiguousFields()).isEmpty();
        assertThat(result.needsClarification()).isFalse();
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

        // 공통 필수 필드 2개(PRODUCT_NAME, MAX_PRICE) 모두 있음
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

        // PRICE_CHECK이므로 maxPrice는 skip, PRODUCT_NAME 있음 → 누락 없음
        assertThat(result.missingRequiredFields()).isEmpty();
        assertThat(result.needsClarification()).isFalse();
    }
}
