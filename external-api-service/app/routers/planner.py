"""DOM 기반 AI planner 라우터."""

from fastapi import APIRouter

from app.schemas.planner import DomPlannerRequest, DomPlannerResponse
from app.services.openai_service import plan_dom_action

router = APIRouter(prefix="/api/v1/planner", tags=["planner"])


@router.post("/analyze-dom", response_model=DomPlannerResponse)
async def analyze_dom(request: DomPlannerRequest) -> DomPlannerResponse:
    """DOM snapshot과 명령 정보를 바탕으로 다음 브라우저 액션을 결정한다."""
    return await plan_dom_action(request)
