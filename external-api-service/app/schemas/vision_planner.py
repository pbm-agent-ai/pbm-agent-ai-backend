"""스크린샷 기반 vision planner 요청/응답 스키마."""

from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, Field


class VisionPlannerOptionGroupResponse(BaseModel):
    group_name: str = Field(..., description="옵션 그룹명")
    options: list[str] = Field(default_factory=list, description="옵션 후보")
    selected_option: Optional[str] = Field(None, description="현재 선택된 옵션")


class VisionPlannerRequest(BaseModel):
    run_id: Optional[str] = Field(None, description="AgentRun ID")
    step_index: Optional[int] = Field(None, description="현재 step index")
    command_text: str = Field(..., description="원본 사용자 명령")
    current_url: Optional[str] = Field(None, description="현재 페이지 URL")
    screenshot_data_url: str = Field(..., description="data:image/...;base64,... 형식 스크린샷")
    error_code: Optional[str] = Field(None, description="직전 액션 실패 코드")
    error_message: Optional[str] = Field(None, description="직전 액션 실패 메시지")
    mode: Optional[str] = Field(None, description="vision planner 동작 모드")
    target_option: Optional[str] = Field(None, description="Smartstore 옵션 선택 시 목표 옵션 값")


class _GeminiRawResponse(BaseModel):
    """Gemini가 실제로 반환하는 픽셀 좌표를 그대로 받기 위한 내부 파싱 모델.

    Gemini는 이미지를 보고 자연스럽게 픽셀 좌표를 반환하므로 상한 제약을 두지 않는다.
    외부에 노출되는 VisionPlannerResponse는 0~1 정규화된 좌표를 사용한다.
    SCROLL은 Gemini가 간혹 반환하는 비표준 액션으로, WAIT로 변환하여 처리한다.
    """
    action: Literal["CLICK", "WAIT", "COMPLETE", "SCROLL"] = Field(...)
    viewport_x: Optional[float] = Field(None, description="Gemini가 반환한 x 픽셀 좌표 (정규화 전)")
    viewport_y: Optional[float] = Field(None, description="Gemini가 반환한 y 픽셀 좌표 (정규화 전)")
    target_label: Optional[str] = Field(None)
    confidence: float = Field(..., ge=0.0, le=1.0)
    reason: str = Field(...)


class VisionPlannerResponse(BaseModel):
    """Java/Extension에 전달되는 최종 응답. 좌표는 0.0~1.0 정규화된 비율."""
    action: Literal["CLICK", "WAIT", "COMPLETE"] = Field(...)
    viewport_x: Optional[float] = Field(None, ge=0.0, le=1.0, description="뷰포트 기준 x 비율 (0.0~1.0)")
    viewport_y: Optional[float] = Field(None, ge=0.0, le=1.0, description="뷰포트 기준 y 비율 (0.0~1.0)")
    target_label: Optional[str] = Field(None, description="찾은 버튼/영역 레이블")
    option_present: Optional[bool] = Field(None, description="옵션 UI 존재 여부")
    option_groups: list[VisionPlannerOptionGroupResponse] = Field(default_factory=list, description="식별된 옵션 그룹")
    confidence: float = Field(..., ge=0.0, le=1.0, description="vision planner confidence")
    reason: str = Field(..., description="vision planner 판단 근거")
