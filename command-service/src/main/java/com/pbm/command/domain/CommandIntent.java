package com.pbm.command.domain;

/**
 * 자연어 명령 파싱 시 허용하는 사용자 의도(enum).
 *
 * 역할: GPT가 자유 텍스트 대신 미리 합의된 의도 값만 반환하도록 강제하는 기준이다.
 * 동작: command-service는 아래 3가지 intent만 MVP 범위로 허용하고,
 *       그 외 값은 파싱 실패 또는 미지원 명령으로 처리한다.
 * 연관: 향후 CommandParseRequest, CommandParseResponse, GPT 파싱 서비스.
 */
public enum CommandIntent {
    AUTO_PURCHASE,
    PRICE_TRACK,
    PRICE_CHECK,
    /** 사용자가 직접 URL을 제공하여 해당 상품을 모니터링/구매하는 경우 */
    URL_MONITOR
}
