package com.pbm.price.domain;

/**
 * 가격 통화 구분 enum.
 *
 * 역할: PriceHistory에 저장되는 가격이 어떤 통화 기준인지 명확히 구분한다.
 * 동작: 우선 네이버/알리 연동에 필요한 KRW, USD만 선언한다.
 * 연관: PriceHistory.
 */
public enum CurrencyType {
    KRW,
    USD
}
