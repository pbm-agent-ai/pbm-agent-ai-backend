"""DOM planner 요청/응답 스키마.

command-service가 snapshot/command/session 정보를 보내면,
external-api-service가 GPT-5.4-mini를 사용해 다음 브라우저 액션 후보를 반환한다.
"""

from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, Field


class PlannerTarget(BaseModel):
    """LLM이 선택한 액션 타겟 정보."""

    node_id: Optional[str] = Field(None, description="snapshot interactiveElements에 포함된 nodeId")
    role: Optional[str] = Field(None, description="button, input, select 등 요소 역할")
    label_text: Optional[str] = Field(None, description="버튼/입력 요소 라벨")
    selector: Optional[str] = Field(None, description="fallback selector")


class DomPlannerRequest(BaseModel):
    """GPT DOM planner 요청."""

    command_text: str = Field(..., description="원본 사용자 명령")
    command_intent: Optional[str] = Field(None, description="AUTO_PURCHASE 등 command intent")
    command_status: str = Field(..., description="현재 command session 상태")
    current_url: Optional[str] = Field(None, description="현재 페이지 URL")
    title: Optional[str] = Field(None, description="현재 페이지 title")
    visible_text_summary: Optional[str] = Field(None, description="페이지 visible text 요약")
    interactive_elements: list[dict] = Field(default_factory=list, description="interactive element 목록")
    option_groups: list[dict] = Field(default_factory=list, description="select/option group 목록")
    target_product: Optional[dict] = Field(None, description="선택/트리거된 대상 상품 정보")
    platform: Optional[str] = Field(None, description="플랫폼 식별자. 예: ALIEXPRESS, NAVER")
    navigation_strategy: Optional[str] = Field(
        None,
        description=(
            "페이지 접근 전략. "
            "DIRECT=상품 URL 직접 접근(알리 등), "
            "SEARCH=검색 페이지 경유(네이버 등 봇 차단 우회)"
        ),
    )
    agent_type: Optional[str] = Field(
        None,
        description=(
            "호출할 전문 AI 에이전트 유형. "
            "SEARCH_NAVIGATOR=검색 결과에서 상품 링크를 찾아 클릭, "
            "PURCHASE_EXECUTOR=상품 상세 페이지에서 옵션 선택 및 구매버튼 클릭. "
            "null이면 단일 범용 planner 사용."
        ),
    )
    raw_html: Optional[str] = Field(
        None,
        description="전체 페이지 HTML (전처리 없이 AI에게 직접 전달, 최대 80KB). 제공 시 모든 agent_type에서 interactiveElements/optionGroups 대신 rawHtml을 우선 분석한다."
    )


class DomPlannerResponse(BaseModel):
    """GPT DOM planner 정규화 응답."""

    action: Literal["NAVIGATE", "CLICK", "INPUT", "SELECT", "SCROLL", "WAIT", "COMPLETE"] = Field(...)
    target: Optional[PlannerTarget] = Field(None, description="클릭/입력/select 대상 요소")
    value: Optional[str] = Field(None, description="navigate url 또는 input/select 값")
    confidence: float = Field(..., ge=0.0, le=1.0, description="planner confidence")
    reason: str = Field(..., description="planner 선택 이유")
