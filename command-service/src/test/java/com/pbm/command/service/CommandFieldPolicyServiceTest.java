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
 * 동작: 정책 서비스가 stage 3에서 합의한 규칙(PLATFORM 필수 필드 승격)을 그대로 반환하는지 확인한다.
 * 연관: CommandFieldPolicyService, CommandFieldType.
 */
class CommandFieldPolicyServiceTest {

    private final CommandFieldPolicyService commandFieldPolicyService = new CommandFieldPolicyService();

    @Test
    @DisplayName("신발 카테고리는 productName, maxPrice, size, platform을 필수 필드로 가진다")
    void shoesPolicy_containsExpectedRequiredFields() {
        assertThat(commandFieldPolicyService.getRequiredFields(ProductCategory.SHOES))
                .containsExactly(
                        CommandFieldType.PRODUCT_NAME,
                        CommandFieldType.MAX_PRICE,
                        CommandFieldType.SIZE,
                        CommandFieldType.PLATFORM
                );
    }

    @Test
    @DisplayName("신발 카테고리는 자동 결제 시 color만 추가 확인 대상으로 가진다 (platform은 필수로 승격)")
    void shoesPolicy_containsExpectedAutoPurchaseClarificationFields() {
        assertThat(commandFieldPolicyService.getAutoPurchaseClarificationFields(ProductCategory.SHOES))
                .containsExactly(
                        CommandFieldType.COLOR
                );
    }

    @Test
    @DisplayName("전자기기 카테고리는 productName, maxPrice, platform, color를 필수 필드로 가진다")
    void electronicsPolicy_requiresProductNameMaxPricePlatformAndColor() {
        assertThat(commandFieldPolicyService.getRequiredFields(ProductCategory.ELECTRONICS))
                .containsExactly(
                        CommandFieldType.PRODUCT_NAME,
                        CommandFieldType.MAX_PRICE,
                        CommandFieldType.PLATFORM,
                        CommandFieldType.COLOR
                );
    }

    @Test
    @DisplayName("의류 카테고리는 productName, maxPrice, size, platform을 필수 필드로 가진다")
    void apparelPolicy_containsExpectedRequiredFields() {
        assertThat(commandFieldPolicyService.getRequiredFields(ProductCategory.APPAREL))
                .containsExactly(
                        CommandFieldType.PRODUCT_NAME,
                        CommandFieldType.MAX_PRICE,
                        CommandFieldType.SIZE,
                        CommandFieldType.PLATFORM
                );
    }

    @Test
    @DisplayName("전자기기 카테고리는 자동 결제 시 추가 확인 대상이 없다")
    void electronicsPolicy_autoPurchaseClarificationIsEmpty() {
        assertThat(commandFieldPolicyService.getAutoPurchaseClarificationFields(ProductCategory.ELECTRONICS))
                .isEmpty();
    }
}
