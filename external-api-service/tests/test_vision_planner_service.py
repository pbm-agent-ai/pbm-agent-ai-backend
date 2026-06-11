from app.schemas.vision_planner import VisionPlannerRequest
from app.services.openai_service import _analyze_screenshot_mock
import pytest


@pytest.mark.asyncio
async def test_vision_mock_external_option_presence_returns_option_present():
    request = VisionPlannerRequest(
        command_text="검은색으로 구매해줘",
        screenshot_data_url="data:image/png;base64,ZmFrZQ==",
        mode="EXTERNAL_OPTION_PRESENCE",
    )

    response = await _analyze_screenshot_mock(request)

    assert response.option_present is True
    assert response.action == "CLICK"


@pytest.mark.asyncio
async def test_vision_mock_external_option_selection_returns_groups():
    request = VisionPlannerRequest(
        command_text="검은색으로 구매해줘",
        screenshot_data_url="data:image/png;base64,ZmFrZQ==",
        mode="EXTERNAL_OPTION_SELECTION",
        target_option="블랙",
    )

    response = await _analyze_screenshot_mock(request)

    assert response.option_groups
    assert response.option_groups[0].group_name == "색상"
    assert "블랙" in response.option_groups[0].options
