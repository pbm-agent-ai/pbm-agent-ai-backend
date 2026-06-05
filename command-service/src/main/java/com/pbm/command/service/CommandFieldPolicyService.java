package com.pbm.command.service;

import com.pbm.command.domain.CommandFieldType;
import com.pbm.command.domain.ProductCategory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 공통 필드 정책 서비스.
 *
 * 역할: 모든 카테고리에 공통으로 적용할 필수 필드와 확인 필드를 정의한다.
 *       카테고리별 구분은 제거되고, 아래 3개 필드가 모든 상황에서 필수로 사용된다.
 *       - PRODUCT_NAME (상품명)
 *       - MAX_PRICE    (최대 가격)
 *       - PLATFORM     (구매/비교 플랫폼)
 * 동작: API 응답에서 missingRequiredFields/ambiguousFields 계산 시 호출된다.
 *       getRequiredFields는 항상 동일한 3개 필드를 반환하며,
 *       추가 확인 필드는 현재 사용하지 않는다(빈 리스트).
 * 연관: CommandFieldType, CommandFieldEvaluationService.
 */
@Service
public class CommandFieldPolicyService {

    // 모든 카테고리 공통 필수 필드: PRODUCT_NAME, MAX_PRICE, PLATFORM
    private static final List<CommandFieldType> COMMON_REQUIRED_FIELDS = List.of(
            CommandFieldType.PRODUCT_NAME,
            CommandFieldType.MAX_PRICE,
            CommandFieldType.PLATFORM
    );

    /**
     * 공통 필수 필드 목록을 반환한다 (카테고리 무관).
     *
     * @param category 상품 카테고리 (무시됨, 모든 카테고리 동일)
     * @return 공통 필수 필드 목록
     */
    public List<CommandFieldType> getRequiredFields(ProductCategory category) {
        return COMMON_REQUIRED_FIELDS;
    }

    /**
     * 자동 결제 intent일 때 추가 확인할 필드 목록을 반환한다.
     * 현재는 PLATFORM이 공통 필수 필드에 포함되어 있으므로 별도 확인 불필요.
     *
     * @param category 상품 카테고리 (무시됨)
     * @return 빈 리스트 (별도 추가 확인 필드 없음)
     */
    public List<CommandFieldType> getAutoPurchaseClarificationFields(ProductCategory category) {
        return List.of();
    }

    /**
     * 상품명이 너무 넓어 후보가 많을 때 추가 확인할 필드 목록을 반환한다.
     * 카테고리별 size/color/model 의존 로직이 제거되어 현재는 빈 리스트를 반환한다.
     *
     * @param category 상품 카테고리 (무시됨)
     * @return 빈 리스트 (별도 추가 확인 필드 없음)
     */
    public List<CommandFieldType> getBroadProductClarificationFields(ProductCategory category) {
        return List.of();
    }
}
