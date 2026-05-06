"""OpenAI Chat Completions 프록시 라우터 및 서비스 테스트

- 외부 API 호출은 모킹하여 처리
- OPENAI_MOCK_ENABLED 모드의 동작도 검증
- 서비스 정규화/모킹 테스트 포함
"""

from unittest.mock import AsyncMock, patch
import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.schemas.openai import OpenAiParseCommandRequest, OpenAiParseCommandResponse
from app.services.openai_service import (
    _parse_command_mock,
    _parse_structured_content,
    parse_command,
)

client = TestClient(app)


# --- 헬스체크 테스트 ---


def test_health_check_includes_openai_mock_mode():
    """헬스체크 엔드포인트에 openai mock_mode가 포함되는지 확인"""
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert "mock_mode" in data
    assert "openai" in data["mock_mode"]


# --- OpenAI Parse Command 라우터 테스트 ---


@patch("app.routers.openai.parse_command", new_callable=AsyncMock)
def test_parse_command_success(mock_parse):
    """파싱 요청이 정상적으로 정규화된 응답을 반환하는지 확인"""
    mock_parse.return_value = OpenAiParseCommandResponse(
        parsed_json={
            "intent": "PRICE_CHECK",
            "parsedCommand": {
                "productCategory": "ELECTRONICS",
                "productName": "테스트 상품",
                "brand": None,
                "line": None,
                "model": None,
                "color": None,
                "size": None,
                "platform": "NAVER",
                "maxPrice": None,
                "minPrice": None,
                "currency": None,
            },
            "confidence": 0.90,
        },
        finish_reason="stop",
        confidence=0.90,
        refusal=None,
    )

    request_body = {
        "system_prompt": "You are a command parser.",
        "user_prompt": "이어폰 찾아줘",
    }

    response = client.post("/api/v1/openai/parse-command", json=request_body)
    assert response.status_code == 200

    data = response.json()
    assert data["finish_reason"] == "stop"
    assert data["refusal"] is None
    assert data["confidence"] == 0.90
    assert data["parsed_json"]["intent"] == "PRICE_CHECK"
    assert data["parsed_json"]["parsedCommand"]["productName"] == "테스트 상품"


@patch("app.routers.openai.parse_command", new_callable=AsyncMock)
def test_parse_command_with_refusal(mock_parse):
    """refusal이 포함된 응답을 정상 반환하는지 확인"""
    mock_parse.return_value = OpenAiParseCommandResponse(
        parsed_json={},
        finish_reason="stop",
        confidence=None,
        refusal="요청이 콘텐츠 정책에 위배됩니다.",
    )

    request_body = {
        "system_prompt": "You are a command parser.",
        "user_prompt": "악성코드 추천해줘",
    }

    response = client.post("/api/v1/openai/parse-command", json=request_body)
    assert response.status_code == 200

    data = response.json()
    assert data["parsed_json"] == {}
    assert data["finish_reason"] == "stop"
    assert data["refusal"] == "요청이 콘텐츠 정책에 위배됩니다."
    assert data["confidence"] is None


def test_parse_command_missing_system_prompt():
    """system_prompt 누락 시 422 에러 반환 확인"""
    request_body = {"user_prompt": "이어폰 찾아줘"}
    response = client.post("/api/v1/openai/parse-command", json=request_body)
    assert response.status_code == 422


def test_parse_command_missing_user_prompt():
    """user_prompt 누락 시 422 에러 반환 확인"""
    request_body = {"system_prompt": "You are a parser."}
    response = client.post("/api/v1/openai/parse-command", json=request_body)
    assert response.status_code == 422


def test_parse_command_empty_body():
    """빈 body 전송 시 422 에러 반환 확인"""
    response = client.post("/api/v1/openai/parse-command", json={})
    assert response.status_code == 422


# --- 서비스 레이어: _parse_structured_content 테스트 ---


def test_parse_structured_content_valid_json_string():
    """유효한 JSON 문자열이 dict로 파싱되는지 확인"""
    result = _parse_structured_content(
        '{"intent": "PRICE_TRACK", "parsedCommand": {}, "confidence": 0.95}'
    )
    assert isinstance(result, dict)
    assert result["intent"] == "PRICE_TRACK"
    assert result["confidence"] == 0.95


def test_parse_structured_content_dict_input():
    """dict 입력이 그대로 반환되는지 확인"""
    input_dict = {"intent": "PRICE_TRACK", "parsedCommand": {}, "confidence": 0.95}
    result = _parse_structured_content(input_dict)
    assert result is input_dict


def test_parse_structured_content_invalid_json_raises():
    """파싱 불가능한 문자열이면 예외가 발생하는지 확인"""
    with pytest.raises(ValueError, match="유효한 JSON"):
        _parse_structured_content("not-a-json-string")


def test_parse_structured_content_none_raises():
    """None 입력이면 예외가 발생하는지 확인"""
    with pytest.raises(ValueError, match="content가 없습니다"):
        _parse_structured_content(None)


def test_parse_structured_content_empty_string_raises():
    """빈 문자열 입력이면 예외가 발생하는지 확인"""
    with pytest.raises(ValueError, match="유효한 JSON"):
        _parse_structured_content("")


# --- 모킹 모드 테스트 ---


@pytest.mark.asyncio
async def test_mock_mode_returns_fixed_data():
    """모킹 모드에서 고정된 파싱 결과를 반환하는지 확인"""
    request = OpenAiParseCommandRequest(
        system_prompt="You are a command parser.",
        user_prompt="이어폰 찾아줘",
    )

    result = await _parse_command_mock(request)

    assert isinstance(result, OpenAiParseCommandResponse)
    assert result.finish_reason == "stop"
    assert result.refusal is None
    assert result.confidence == 0.95
    assert result.parsed_json["intent"] == "AUTO_PURCHASE"
    assert result.parsed_json["parsedCommand"]["productName"] == "무선 블루투스 이어폰"
    assert result.parsed_json["parsedCommand"]["productCategory"] == "ELECTRONICS"
    assert result.parsed_json["parsedCommand"]["platform"] == "ALIEXPRESS"
    assert result.parsed_json["parsedCommand"]["currency"] == "KRW"


@pytest.mark.asyncio
async def test_mock_mode_is_deterministic():
    """모킹 모드 결과가 호출 간 동일한지 확인"""
    request = OpenAiParseCommandRequest(
        system_prompt="다른 시스템 프롬프트",
        user_prompt="다른 사용자 입력",
    )

    result1 = await _parse_command_mock(request)
    result2 = await _parse_command_mock(request)

    assert result1.parsed_json == result2.parsed_json
    assert result1.finish_reason == result2.finish_reason
    assert result1.confidence == result2.confidence


@patch("app.services.openai_service._is_mock_enabled", return_value=True)
@pytest.mark.asyncio
async def test_parse_command_delegates_to_mock_when_enabled(mock_check):
    """MOCK_ENABLED=True일 때 parse_command가 모킹 데이터를 반환하는지 확인"""
    request = OpenAiParseCommandRequest(
        system_prompt="You are a command parser.",
        user_prompt="이어폰 찾아줘",
    )

    result = await parse_command(request)

    assert isinstance(result, OpenAiParseCommandResponse)
    assert result.finish_reason == "stop"
    assert result.parsed_json["intent"] == "AUTO_PURCHASE"


@patch("app.services.openai_service._is_mock_enabled", return_value=False)
@patch(
    "app.services.openai_service._get_openai_config",
    side_effect=ValueError("OPENAI_API_KEY 환경변수가 필요합니다"),
)
@pytest.mark.asyncio
async def test_parse_command_raises_when_no_api_key(mock_config, mock_check):
    """MOCK_ENABLED=False이고 OPENAI_API_KEY가 없을 때 ValueError 발생 확인"""
    request = OpenAiParseCommandRequest(
        system_prompt="You are a command parser.",
        user_prompt="이어폰 찾아줘",
    )

    with pytest.raises(ValueError, match="OPENAI_API_KEY"):
        await parse_command(request)


@patch("app.services.openai_service._is_mock_enabled", return_value=False)
@patch(
    "app.services.openai_service._get_openai_config",
    return_value=("test-api-key", "https://api.openai.com/v1", "gpt-5.4-mini"),
)
@patch("app.services.openai_service.httpx.AsyncClient")
@pytest.mark.asyncio
async def test_parse_command_uses_structured_outputs_request(
    mock_async_client,
    mock_config,
    mock_check,
):
    """실제 OpenAI 호출 시 Structured Outputs 요청을 구성하는지 확인"""
    request = OpenAiParseCommandRequest(
        system_prompt="system prompt",
        user_prompt="user prompt",
    )

    mock_response = AsyncMock()
    mock_response.raise_for_status = lambda: None
    mock_response.json = lambda: {
        "choices": [
            {
                "finish_reason": "stop",
                "message": {
                    "content": '{"intent":"PRICE_CHECK","parsedCommand":{"productCategory":"ELECTRONICS","productName":"아이폰 15","brand":null,"line":null,"model":null,"color":null,"size":null,"platform":"NAVER","maxPrice":null,"minPrice":null,"currency":null},"confidence":0.91}'
                },
            }
        ]
    }

    mock_client = AsyncMock()
    mock_client.post.return_value = mock_response
    mock_async_client.return_value.__aenter__.return_value = mock_client

    result = await parse_command(request)

    assert result.parsed_json["intent"] == "PRICE_CHECK"
    assert result.confidence == 0.91

    mock_client.post.assert_awaited_once()
    _, kwargs = mock_client.post.await_args
    assert kwargs["headers"]["Authorization"] == "Bearer test-api-key"
    assert kwargs["json"]["model"] == "gpt-5.4-mini"
    assert kwargs["json"]["response_format"]["type"] == "json_schema"
    assert kwargs["json"]["response_format"]["json_schema"]["name"] == "command_parse_response"
    assert kwargs["json"]["response_format"]["json_schema"]["strict"] is True
    assert kwargs["json"]["response_format"]["json_schema"]["schema"]["additionalProperties"] is False


@patch("app.services.openai_service._is_mock_enabled", return_value=False)
@patch(
    "app.services.openai_service._get_openai_config",
    return_value=("test-api-key", "https://api.openai.com/v1", "gpt-5.4-mini"),
)
@patch("app.services.openai_service.httpx.AsyncClient")
@pytest.mark.asyncio
async def test_parse_command_raises_when_openai_returns_non_json_content(
    mock_async_client,
    mock_config,
    mock_check,
):
    """Structured Outputs 계약이 깨진 경우 예외를 발생시키는지 확인"""
    request = OpenAiParseCommandRequest(
        system_prompt="system prompt",
        user_prompt="user prompt",
    )

    mock_response = AsyncMock()
    mock_response.raise_for_status = lambda: None
    mock_response.json = lambda: {
        "choices": [
            {
                "finish_reason": "stop",
                "message": {
                    "content": "plain text response",
                },
            }
        ]
    }

    mock_client = AsyncMock()
    mock_client.post.return_value = mock_response
    mock_async_client.return_value.__aenter__.return_value = mock_client

    with pytest.raises(ValueError, match="유효한 JSON"):
        await parse_command(request)
