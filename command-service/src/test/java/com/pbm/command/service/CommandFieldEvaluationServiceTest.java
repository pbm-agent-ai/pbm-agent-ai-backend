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
    @DisplayName("신발 자동 결제 명령은 size 누락을 필수 누락으로, platform/color/model 누락을 모호 필드로 계산한다")
    void evaluate_autoPurchaseShoes_returnsMissingAndAmbiguousFields() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.SHOES,
                "나이키 조던",
                "나이키",
                "조던",
                null,
                null,
                null,
                null,   // platforms=null: PRICE_CHECK는 허용이지만 AUTO_PURCHASE에서는 모호 필드로 감지
                200000,
                null,
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.AUTO_PURCHASE, parsedCommand);

        // PLATFORM은 requiredFields에서 제거되어 필수 누락에는 포함되지 않는다.
        assertThat(result.missingRequiredFields()).containsExactly("size");
        // AUTO_PURCHASE 시: platform, color 가 autoPurchaseClarificationFields에 있고,
        // "나이키 조던" (2토큰)은 상품명이 모호하여 broadProduct → model도 추가
        assertThat(result.ambiguousFields()).containsExactly("platform", "color", "model");
        assertThat(result.needsClarification()).isTrue();
    }

    @Test
    @DisplayName("전자기기 가격 확인 명령은 productName과 maxPrice가 있으면 추가 확인 없이 바로 진행한다 (color는 필수 아님)")
    void evaluate_priceCheckElectronicsWithoutColor_proceedsWithoutClarification() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.ELECTRONICS,
                "아이폰 15 프로 256GB",
                "애플",
                "아이폰",
                "15 프로 256GB",
                null,   // color 없어도 OK - ELECTRONICS에서 필수 아님
                null,
                java.util.List.of(PlatformType.NAVER),
                1400000,
                null,
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.PRICE_CHECK, parsedCommand);

        // ELECTRONICS 필수 필드: PRODUCT_NAME, MAX_PRICE (COLOR 제거됨)
        assertThat(result.missingRequiredFields()).isEmpty();
        assertThat(result.ambiguousFields()).isEmpty();
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    @DisplayName("카테고리가 UNKNOWN이어도 다른 검색 핵심 정보가 있으면 키워드 검색을 위해 모호 필드를 비워둔다")
    void evaluate_unknownCategoryWithSearchableFields_allowsKeywordSearchWithoutClarification() {
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
        assertThat(result.ambiguousFields()).isEmpty();
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    @DisplayName("platforms가 비어있어도 PRICE_CHECK에서는 필수 누락으로 처리하지 않는다 (전체 플랫폼 대상 검색 허용)")
    void evaluate_shoesMissingPlatform_allowedForPriceCheck() {
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.SHOES,
                "나이키 에어맥스",
                "나이키",
                "에어맥스",
                null,
                null,
                "270",
                null,  // platforms=null: PRICE_CHECK에서는 전체 플랫폼 검색으로 허용
                150000,
                null,
                "KRW"
        );

        FieldEvaluationResult result = commandFieldEvaluationService.evaluate(CommandIntent.PRICE_CHECK, parsedCommand);

        // PLATFORM은 requiredFields에서 제거되었으므로 누락으로 처리하지 않는다.
        assertThat(result.missingRequiredFields()).doesNotContain("platform");
        // "나이키 에어맥스" (2토큰)은 상품명이 모호하여 broadProduct → model이 모호 필드로 추가될 수 있음
        // PRICE_CHECK이므로 autoPurchaseClarificationFields는 확인하지 않음 → platform은 모호 필드에도 없음
        assertThat(result.ambiguousFields()).doesNotContain("platform");
    }
}
