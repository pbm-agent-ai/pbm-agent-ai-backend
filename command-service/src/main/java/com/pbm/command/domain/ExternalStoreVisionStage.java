package com.pbm.command.domain;

/**
 * 외부 스토어 상품 상세페이지에서 사용하는 스크린샷 기반 vision 단계.
 *
 * 역할: 옵션 존재 확인 → 옵션 선택 → 구매 버튼 탐색의 순서를 AgentRun에 저장한다.
 * 동작: command-service가 현재 어떤 비전 프롬프트를 호출해야 하는지 결정할 때 사용한다.
 * 연관: AgentRun, AgentRunService, AgentStepPlannerService.
 */
public enum ExternalStoreVisionStage {
    NONE,
    SEARCH_RESULTS_PRODUCT,
    OPTION_PRESENCE,
    OPTION_SELECTION,
    PURCHASE
}
