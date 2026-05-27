package com.pbm.price.common;

import com.pbm.price.domain.CurrencyType;
import com.pbm.price.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 가격 통화를 KRW 기준 비교 금액으로 변환하는 컴포넌트.
 *
 * 역할: price-service 내부에서 USD/KRW 가격을 동일한 기준으로 비교할 수 있도록
 *       KRW 환산 금액을 계산한다.
 * 동작: KRW는 그대로 반환하고, USD는 한국수출입은행 환율 API의 실시간 환율을 적용하여
 *       KRW 금액으로 변환한다. 환율은 당일 기준으로 캐싱된다.
 * 연관: ExchangeRateService, ProductSelectionConsumer, SubscriptionMonitoringService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceCurrencyConverter {

    private final ExchangeRateService exchangeRateService;

    /**
     * 원본 금액을 KRW 기준 비교 금액으로 변환한다.
     *
     * @param amount   원본 금액
     * @param currency 원본 통화
     * @return KRW 기준 금액. amount/currency가 없으면 null
     */
    public BigDecimal toKrw(BigDecimal amount, CurrencyType currency) {
        if (amount == null || currency == null) {
            return null;
        }

        return switch (currency) {
            case KRW -> amount;
            case USD -> {
                BigDecimal rate = exchangeRateService.getUsdToKrwRate();
                BigDecimal converted = amount.multiply(rate);
                log.debug("USD → KRW 환산 - amount: {} USD, rate: {}, converted: {} KRW",
                        amount, rate, converted);
                yield converted;
            }
        };
    }
}
