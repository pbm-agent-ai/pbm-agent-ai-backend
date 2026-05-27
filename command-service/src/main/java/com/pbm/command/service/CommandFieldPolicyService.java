package com.pbm.command.service;

import com.pbm.command.domain.CommandFieldType;
import com.pbm.command.domain.ProductCategory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 상품 카테고리별 필드 정책 서비스.
 *
 * 역할: 카테고리마다 어떤 필드가 필수인지, 어떤 필드가 추가 확인 대상인지 정의한다.
 * 동작: ParsedCommand의 productCategory를 기준으로 필수 필드 목록과
 *       자동 결제/상품명 모호성 상황에서 확인할 필드 목록을 반환한다.
 * 연관: CommandFieldType, ProductCategory, CommandFieldEvaluationService.
 */
@Service
public class CommandFieldPolicyService {

    private static final CategoryFieldPolicy EMPTY_POLICY = new CategoryFieldPolicy(
            List.of(),
            List.of(),
            List.of()
    );

    // 카테고리별 정책:
    // PLATFORM은 필수 필드에서 제외한다. 빈 platforms = 전체 플랫폼 대상 검색으로 허용한다.
    // AUTO_PURCHASE 시에는 특정 플랫폼을 명시해야 하므로 autoPurchaseClarificationFields에 유지한다.
    // COLOR는 자동 결제 시 여전히 추가 확인이 필요한 모호 필드로 남는다.
    private static final Map<ProductCategory, CategoryFieldPolicy> CATEGORY_POLICIES = Map.of(
            ProductCategory.SHOES, new CategoryFieldPolicy(
                    List.of(CommandFieldType.PRODUCT_NAME, CommandFieldType.MAX_PRICE, CommandFieldType.SIZE),
                    List.of(CommandFieldType.PLATFORM, CommandFieldType.COLOR),
                    List.of(CommandFieldType.MODEL)
            ),
            ProductCategory.ELECTRONICS, new CategoryFieldPolicy(
                    List.of(CommandFieldType.PRODUCT_NAME, CommandFieldType.MAX_PRICE),
                    List.of(CommandFieldType.PLATFORM),
                    List.of()
            ),
            ProductCategory.APPAREL, new CategoryFieldPolicy(
                    List.of(CommandFieldType.PRODUCT_NAME, CommandFieldType.MAX_PRICE, CommandFieldType.SIZE),
                    List.of(CommandFieldType.PLATFORM, CommandFieldType.COLOR),
                    List.of()
            )
    );

    /**
     * 카테고리별 필수 필드 목록을 반환한다.
     *
     * @param category 상품 카테고리
     * @return 필수 필드 목록
     */
    public List<CommandFieldType> getRequiredFields(ProductCategory category) {
        return getPolicy(category).requiredFields();
    }

    /**
     * 자동 결제 intent일 때 추가 확인할 필드 목록을 반환한다.
     *
     * @param category 상품 카테고리
     * @return 자동 결제 추가 확인 필드 목록
     */
    public List<CommandFieldType> getAutoPurchaseClarificationFields(ProductCategory category) {
        return getPolicy(category).autoPurchaseClarificationFields();
    }

    /**
     * 상품명이 너무 넓어 후보가 많을 때 추가 확인할 필드 목록을 반환한다.
     *
     * @param category 상품 카테고리
     * @return 상품명 모호성 해소용 필드 목록
     */
    public List<CommandFieldType> getBroadProductClarificationFields(ProductCategory category) {
        return getPolicy(category).broadProductClarificationFields();
    }

    private CategoryFieldPolicy getPolicy(ProductCategory category) {
        if (category == null || category == ProductCategory.UNKNOWN) {
            return EMPTY_POLICY;
        }
        return CATEGORY_POLICIES.getOrDefault(category, EMPTY_POLICY);
    }

    private record CategoryFieldPolicy(
            List<CommandFieldType> requiredFields,
            List<CommandFieldType> autoPurchaseClarificationFields,
            List<CommandFieldType> broadProductClarificationFields
    ) {
    }
}
