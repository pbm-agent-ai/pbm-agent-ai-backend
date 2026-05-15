package com.pbm.price.domain;

/**
 * 가격 수집 대상 플랫폼 구분 enum.
 *
 * 역할: monitor_target, product가 어느 플랫폼 소속인지 구분한다.
 * 동작: DB에는 문자열(EnumType.STRING)로 저장하여 사람이 읽기 쉽게 유지한다.
 * 연관: MonitorTarget, Product.
 */
public enum Platform {
    NAVER,
    ALIEXPRESS
}
