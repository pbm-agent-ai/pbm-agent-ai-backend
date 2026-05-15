package com.pbm.command.domain;

/**
 * 명령 세션의 생명주기 상태(enum).
 * <p>
 * 역할: 하나의 사용자 명령이 pre-search → searching → post-search → monitoring 으로
 *       진행되는 전체 흐름을 상태로 관리한다.
 * 동작: 각 상태 전환은 CommandSessionService의 메서드에서 이루어지며,
 *       허용되지 않은 전환이 발생하면 예외를 던진다.
 * 연관: CommandSession, CommandSessionService.
 */
public enum CommandSessionStatus {

    /** 명령 파싱 전, 추가 정보(필드 보완)가 필요함 */
    PRE_SEARCH_CLARIFICATION,

    /** 파싱 완료, GPT/Claude 검색 진행 중 */
    SEARCHING,

    /** 검색 결과에 대해 후보 상품 선택이 필요함 */
    PRODUCT_SELECTION_REQUIRED,

    /** 사용자가 선택한 여러 후보 상품을 단건 재조회로 검증 중임 */
    PRICE_VALIDATING,

    /** 기존 구독 갱신/재시작 여부에 대한 사용자 확인이 필요함 */
    RESUBSCRIBE_CONFIRMATION_REQUIRED,

    /** 최종 확인 완료, 가격 모니터링 시작됨 */
    MONITORING_STARTED,

    /** PRICE_CHECK 의도가 실시간 가격 검증까지 완료된 상태 */
    PRICE_CHECK_COMPLETED,

    /** AUTO_PURCHASE 의도에서 즉시 구매 처리가 완료된 상태 */
    AUTO_PURCHASE_COMPLETED
}
