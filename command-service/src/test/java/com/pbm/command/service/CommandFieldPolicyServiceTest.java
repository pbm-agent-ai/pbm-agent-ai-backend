package com.pbm.command.service;

import com.pbm.command.domain.CommandFieldType;
import com.pbm.command.domain.ProductCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카테고리별 필드 정책 테스트.
 *
 * 역할: 상품 카테고리마다 어떤 필드가 필수인지, 어떤 필드가 추가 확인 대상인지 검증한다.
 * 동작: 멀티 플랫폼 지원 이후 PLATFORM은 requiredFields에서 제외되어
 *       빈 platforms = 전체 플랫폼 대상 검색이 허용된다.
 *       AUTO_PURCHASE 시에만 PLATFORM이 autoPurchaseClarificationFields에 포함된다.
 * 연관: CommandFieldPolicyService, CommandFieldType.
 */
class CommandFieldPolicyServiceTest {

    private final CommandFieldPolicyService commandFieldPolicyService = new CommandFieldPolicyService();

    @Test
    @DisplayName("신발 카테고리는 productName, maxPrice, size를 필수 필드로 가진다 (PLATFORM은 제외, 멀티플랫폼 지원)")
    void shoesPolicy_containsExpectedRequiredFields() {
        assertThat(commandFieldPolicyService.getRequiredFields(ProductCategory.SHOES))
                .containsExactly(
                        CommandFieldType.PRODUCT_NAME,
                        CommandFieldType.MAX_PRICE,
                        CommandFieldType.SIZE
                );
    }

    @Test
    @DisplayName("신발 카테고리는 자동 결제 시 platform, color를 추가 확인 대상으로 가진다")
    void shoesPolicy_containsExpectedAutoPurchaseClarificationFields() {
        assertThat(commandFieldPolicyService.getAutoPurchaseClarificationFields(ProductCategory.SHOES))
                .containsExactly(
                        CommandFieldType.PLATFORM,
                        CommandFieldType.COLOR
                );
    }

    @Test
    @DisplayName("전자기기 카테고리는 productName, maxPrice, color를 필수 필드로 가진다 (PLATFORM은 제외)")
    void electronicsPolicy_requiresProductNameMaxPriceAndColor() {
        assertThat(commandFieldPolicyService.getRequiredFields(ProductCategory.ELECTRONICS))
                .containsExactly(
                        CommandFieldType.PRODUCT_NAME,
                        CommandFieldType.MAX_PRICE,
                        CommandFieldType.COLOR
                );
    }

    @Test
    @DisplayName("의류 카테고리는 productName, maxPrice, size를 필수 필드로 가진다 (PLATFORM은 제외)")
    void apparelPolicy_containsExpectedRequiredFields() {
        assertThat(commandFieldPolicyService.getRequiredFields(ProductCategory.APPAREL))
                .containsExactly(
                        CommandFieldType.PRODUCT_NAME,
                        CommandFieldType.MAX_PRICE,
                        CommandFieldType.SIZE
                );
    }

    @Test
    @DisplayName("전자기기 카테고리는 자동 결제 시 platform을 추가 확인 대상으로 가진다")
    void electronicsPolicy_autoPurchaseClarificationIncludesPlatform() {
        assertThat(commandFieldPolicyService.getAutoPurchaseClarificationFields(ProductCategory.ELECTRONICS))
                .containsExactly(CommandFieldType.PLATFORM);
    }
}
