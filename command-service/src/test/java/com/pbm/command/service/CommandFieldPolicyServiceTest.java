package com.pbm.command.service;

import com.pbm.command.domain.CommandFieldType;
import com.pbm.command.domain.ProductCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공통 필드 정책 테스트.
 *
 * 역할: 모든 카테고리에 동일하게 적용되는 공통 필수 필드와 확인 필드를 검증한다.
 * 동작: getRequiredFields는 모든 카테고리에서 PRODUCT_NAME, MAX_PRICE를 반환한다.
 *       PLATFORM은 선택 필드 — 미지정 시 null로 두고 실행을 계속한다.
 *       getAutoPurchaseClarificationFields와 getBroadProductClarificationFields는
 *       카테고리별 size/color/model 의존 로직이 제거되어 빈 리스트를 반환한다.
 * 연관: CommandFieldPolicyService, CommandFieldType.
 */
class CommandFieldPolicyServiceTest {

    private final CommandFieldPolicyService commandFieldPolicyService = new CommandFieldPolicyService();

    @Test
    @DisplayName("모든 카테고리는 productName, maxPrice를 공통 필수 필드로 가진다 (platform은 선택)")
    void allCategories_shareCommonRequiredFields() {
        // PLATFORM이 선택 필드로 변경됨 — PRODUCT_NAME, MAX_PRICE만 필수
        var expected = java.util.List.of(
                CommandFieldType.PRODUCT_NAME,
                CommandFieldType.MAX_PRICE
        );

        assertThat(commandFieldPolicyService.getRequiredFields(ProductCategory.SHOES))
                .containsExactlyElementsOf(expected);
        assertThat(commandFieldPolicyService.getRequiredFields(ProductCategory.ELECTRONICS))
                .containsExactlyElementsOf(expected);
        assertThat(commandFieldPolicyService.getRequiredFields(ProductCategory.APPAREL))
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("UNKNOWN 카테고리도 동일한 공통 필수 필드를 가진다")
    void unknownCategory_usesCommonRequiredFields() {
        assertThat(commandFieldPolicyService.getRequiredFields(ProductCategory.UNKNOWN))
                .containsExactly(
                        CommandFieldType.PRODUCT_NAME,
                        CommandFieldType.MAX_PRICE
                );
    }

    @Test
    @DisplayName("null 카테고리도 동일한 공통 필수 필드를 가진다")
    void nullCategory_usesCommonRequiredFields() {
        assertThat(commandFieldPolicyService.getRequiredFields(null))
                .containsExactly(
                        CommandFieldType.PRODUCT_NAME,
                        CommandFieldType.MAX_PRICE
                );
    }

    @Test
    @DisplayName("자동 결제 추가 확인 필드는 모든 카테고리에서 빈 리스트를 반환한다")
    void autoPurchaseClarificationFields_emptyForAllCategories() {
        assertThat(commandFieldPolicyService.getAutoPurchaseClarificationFields(ProductCategory.SHOES)).isEmpty();
        assertThat(commandFieldPolicyService.getAutoPurchaseClarificationFields(ProductCategory.ELECTRONICS)).isEmpty();
        assertThat(commandFieldPolicyService.getAutoPurchaseClarificationFields(ProductCategory.APPAREL)).isEmpty();
    }

    @Test
    @DisplayName("상품명 모호성 확인 필드는 모든 카테고리에서 빈 리스트를 반환한다")
    void broadProductClarificationFields_emptyForAllCategories() {
        assertThat(commandFieldPolicyService.getBroadProductClarificationFields(ProductCategory.SHOES)).isEmpty();
        assertThat(commandFieldPolicyService.getBroadProductClarificationFields(ProductCategory.ELECTRONICS)).isEmpty();
        assertThat(commandFieldPolicyService.getBroadProductClarificationFields(ProductCategory.APPAREL)).isEmpty();
    }
}
