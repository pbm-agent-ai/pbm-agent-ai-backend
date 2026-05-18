"""스크린샷 기반 vision planner 요청/응답 스키마."""

from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, Field


class VisionPlannerRequest(BaseModel):
    command_text: str = Field(..., description="원본 사용자 명령")
    current_url: Optional[str] = Field(None, description="현재 페이지 URL")
    screenshot_data_url: str = Field(..., description="data:image/...;base64,... 형식 스크린샷")
    error_code: Optional[str] = Field(None, description="직전 액션 실패 코드")
    error_message: Optional[str] = Field(None, description="직전 액션 실패 메시지")


class VisionPlannerResponse(BaseModel):
    action: Literal["CLICK", "WAIT", "COMPLETE"] = Field(...)
    viewport_x: Optional[float] = Field(None, ge=0.0, le=1.0, description="뷰포트 기준 x 비율")
    viewport_y: Optional[float] = Field(None, ge=0.0, le=1.0, description="뷰포트 기준 y 비율")
    target_label: Optional[str] = Field(None, description="찾은 버튼/영역 레이블")
    confidence: float = Field(..., ge=0.0, le=1.0, description="vision planner confidence")
    reason: str = Field(..., description="vision planner 판단 근거")
