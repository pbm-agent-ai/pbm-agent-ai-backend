package com.pbm.price.common;

import com.pbm.price.domain.CurrencyType;

import java.math.BigDecimal;

/**
 * 가격 통화를 KRW 기준 비교 금액으로 변환하는 유틸리티.
 *
 * 역할: price-service 내부에서 USD/KRW 가격을 동일한 기준으로 비교할 수 있도록
 *       KRW 환산 금액을 계산한다.
 * 동작: KRW는 그대로 반환하고, USD는 고정 환율 1500원을 곱해 KRW 금액으로 변환한다.
 * 연관: ProductSelectionConsumer, SubscriptionMonitoringService.
 */
public final class PriceCurrencyConverter {

    /**
     * USD → KRW 고정 환율.
     *
     * 학습/테스트 목적의 단순 규칙으로, 외부 환율 API 대신 1 USD = 1500 KRW를 적용한다.
     */
    public static final BigDecimal USD_TO_KRW_EXCHANGE_RATE = BigDecimal.valueOf(1500L);

    private PriceCurrencyConverter() {
    }

    /**
     * 원본 금액을 KRW 기준 비교 금액으로 변환한다.
     *
     * @param amount   원본 금액
     * @param currency 원본 통화
     * @return KRW 기준 금액. amount/currency가 없으면 null
     */
    public static BigDecimal toKrw(BigDecimal amount, CurrencyType currency) {
        if (amount == null || currency == null) {
            return null;
        }

        return switch (currency) {
            case KRW -> amount;
            case USD -> amount.multiply(USD_TO_KRW_EXCHANGE_RATE);
        };
    }
}
