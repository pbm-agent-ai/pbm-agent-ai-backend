"""Gemini vision planner 라우터."""

from fastapi import APIRouter

from app.schemas.vision_planner import VisionPlannerRequest, VisionPlannerResponse
from app.services.openai_service import analyze_screenshot_action

router = APIRouter(prefix="/api/v1/planner", tags=["planner"])


@router.post("/analyze-screenshot", response_model=VisionPlannerResponse)
async def analyze_screenshot(request: VisionPlannerRequest) -> VisionPlannerResponse:
    """스크린샷 기반으로 다음 클릭 좌표 후보를 찾는다."""
    return await analyze_screenshot_action(request)
