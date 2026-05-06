"""OpenAI Chat Completions 프록시 서비스 - 외부 OpenAI API 통신 및 응답 정규화 담당

기본적으로 실제 OpenAI Chat Completions API를 호출한다.
OPENAI_MOCK_ENABLED=true 환경변수 설정 시 실제 API 호출 없이 고정된 모킹 데이터를 반환한다.
로컬 개발 및 CI 환경에서 OpenAI API Key 없이도 동작 검증이 가능하다 (필요 시에만 활성화).

중요:
- 실제 OpenAI 호출 시 Structured Outputs(json_schema, strict=true)를 사용하여
  command-service가 기대하는 JSON 계약을 강제한다.
- 따라서 parsed_json은 항상 intent / parsedCommand / confidence 키를 가지는 형태여야 한다.

환경변수:
- OPENAI_API_KEY: OpenAI API 인증 키 (필수, 모킹 모드에서는 불필요)
- OPENAI_BASE_URL: API 기본 URL (기본값: https://api.openai.com/v1)
- OPENAI_MODEL: 사용할 모델명 (기본값: gpt-5.4-mini)
- OPENAI_MOCK_ENABLED: 모킹 활성화 여부 (기본값: False)
"""

from __future__ import annotations

import json
import logging
import os
from typing import Optional

import httpx

from app.schemas.openai import OpenAiParseCommandRequest, OpenAiParseCommandResponse

logger = logging.getLogger(__name__)

# OpenAI API 기본 설정
OPENAI_DEFAULT_BASE_URL = "https://api.openai.com/v1"
OPENAI_DEFAULT_MODEL = "gpt-5.4-mini"


# command-service가 기대하는 최상위 응답 JSON 스키마.
# strict=true 사용 시 additionalProperties=false가 필요하다.
COMMAND_PARSE_SCHEMA: dict = {
    "type": "object",
    "properties": {
        "intent": {
            "type": ["string", "null"],
            "enum": ["AUTO_PURCHASE", "PRICE_TRACK", "PRICE_CHECK", None],
        },
        "parsedCommand": {
            "type": "object",
            "properties": {
                "productCategory": {
                    "type": ["string", "null"],
                    "enum": ["SHOES", "ELECTRONICS", "APPAREL", "UNKNOWN", None],
                },
                "productName": {"type": ["string", "null"]},
                "brand": {"type": ["string", "null"]},
                "line": {"type": ["string", "null"]},
                "model": {"type": ["string", "null"]},
                "color": {"type": ["string", "null"]},
                "size": {"type": ["string", "null"]},
                "platform": {
                    "type": ["string", "null"],
                    "enum": ["NAVER", "ALIEXPRESS", None],
                },
                "maxPrice": {"type": ["integer", "null"]},
                "minPrice": {"type": ["integer", "null"]},
                "currency": {"type": ["string", "null"]},
            },
            "required": [
                "productCategory",
                "productName",
                "brand",
                "line",
                "model",
                "color",
                "size",
                "platform",
                "maxPrice",
                "minPrice",
                "currency",
            ],
            "additionalProperties": False,
        },
        "confidence": {"type": "number"},
    },
    "required": ["intent", "parsedCommand", "confidence"],
    "additionalProperties": False,
}


def _is_mock_enabled() -> bool:
    """모킹 모드 여부를 환경변수에서 실시간으로 확인한다.

    모듈 로드 시점이 아닌 호출 시점에 평가하여,
    테스트에서 패치하거나 런타임에 환경변수를 변경할 수 있도록 한다.
    """
    return os.getenv("OPENAI_MOCK_ENABLED", "false").lower() == "true"


def _get_openai_config() -> tuple[str, str, str]:
    """환경변수에서 OpenAI API 설정 조회

    Returns:
        (api_key, base_url, model) 튜플

    Raises:
        ValueError: OPENAI_API_KEY가 설정되지 않은 경우
    """
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise ValueError("OPENAI_API_KEY 환경변수가 필요합니다")
    base_url = os.getenv("OPENAI_BASE_URL", OPENAI_DEFAULT_BASE_URL).rstrip("/")
    model = os.getenv("OPENAI_MODEL", OPENAI_DEFAULT_MODEL)
    return api_key, base_url, model


def _parse_structured_content(content: str | dict | None) -> dict:
    """Structured Outputs로 받은 content를 dict로 변환한다.

    Structured Outputs를 사용하므로 content는 JSON 스키마를 만족하는 문자열이어야 한다.
    계약이 깨진 경우 raw_content로 우회하지 않고 즉시 예외를 발생시켜 상위에서 실패를 알린다.
    """
    if content is None:
        raise ValueError("OpenAI 응답에 content가 없습니다.")
    if isinstance(content, dict):
        return content
    try:
        parsed = json.loads(content)
    except (json.JSONDecodeError, TypeError) as exc:
        logger.exception("OpenAI Structured Output JSON 파싱에 실패했습니다.")
        raise ValueError("OpenAI 응답 content가 유효한 JSON이 아닙니다.") from exc

    if not isinstance(parsed, dict):
        raise ValueError("OpenAI 응답 content가 JSON object가 아닙니다.")

    return parsed


# --- 모킹용 고정 데이터 ---

_MOCK_PARSED_JSON: dict = {
    "intent": "AUTO_PURCHASE",
    "parsedCommand": {
        "productName": "무선 블루투스 이어폰",
        "productCategory": "ELECTRONICS",
        "brand": None,
        "line": None,
        "model": None,
        "color": None,
        "size": None,
        "platform": "ALIEXPRESS",
        "minPrice": None,
        "maxPrice": 50000,
        "currency": "KRW",
    },
    "confidence": 0.95,
}


async def _parse_command_mock(
    request: OpenAiParseCommandRequest,
) -> OpenAiParseCommandResponse:
    """모킹 모드: 고정된 파싱 결과를 반환한다.

    실제 OpenAI API 호출 없이 로컬 개발/CI 환경에서 동작 검증이 가능하다.
    system_prompt, user_prompt와 무관하게 동일한 고정 데이터를 반환한다.
    """
    logger.info("OpenAI 모킹 모드 활성화 - 고정 파싱 데이터 반환")
    return OpenAiParseCommandResponse(
        parsed_json=_MOCK_PARSED_JSON.copy(),
        finish_reason="stop",
        confidence=0.95,
        refusal=None,
    )


async def parse_command(
    request: OpenAiParseCommandRequest,
) -> OpenAiParseCommandResponse:
    """OpenAI Chat Completions API를 호출하여 자연어 명령을 파싱한다.

    OPENAI_MOCK_ENABLED=true인 경우 실제 API 호출 없이 모킹 데이터를 반환한다.

    Args:
        request: system_prompt, user_prompt가 포함된 파싱 요청

    Returns:
        OpenAiParseCommandResponse: 정규화된 파싱 결과
            - parsed_json: 어시스턴트 JSON content를 dict로 파싱한 결과
            - finish_reason: 응답 종료 사유 (stop, length, content_filter 등)
            - confidence: 모델이 반환한 신뢰도 (있는 경우)
            - refusal: 모델이 요청을 거부한 사유 (있는 경우)

    Raises:
        ValueError: OPENAI_API_KEY 누락 또는 OpenAI API 에러 응답 시
    """
    if _is_mock_enabled():
        return await _parse_command_mock(request)

    api_key, base_url, model = _get_openai_config()

    # OpenAI Chat Completions 요청 본문 구성
    # Structured Outputs를 사용하여 command-service 계약과 동일한 JSON을 강제한다.
    request_body = {
        "model": model,
        "messages": [
            {"role": "system", "content": request.system_prompt},
            {"role": "user", "content": request.user_prompt},
        ],
        "temperature": 0.0,
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name": "command_parse_response",
                "strict": True,
                "schema": COMMAND_PARSE_SCHEMA,
            },
        },
    }

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.post(
            f"{base_url}/chat/completions",
            json=request_body,
            headers=headers,
        )
        response.raise_for_status()

    data = response.json()

    # 에러 응답 확인
    if "error" in data:
        error_info = data["error"]
        error_message = error_info.get("message", str(error_info))
        raise ValueError(f"OpenAI API 에러: {error_message}")

    # choices 추출 및 검증
    choices: list = data.get("choices", [])
    if not choices:
        raise ValueError("OpenAI 응답에 choices가 없습니다.")

    first_choice = choices[0]
    message: dict = first_choice.get("message", {})
    finish_reason: str = first_choice.get("finish_reason", "unknown")
    refusal: Optional[str] = message.get("refusal")
    content: str | None = message.get("content")

    # content가 없는 경우 refusal 확인
    if not content and not refusal:
        raise ValueError("OpenAI 응답에 content와 refusal이 모두 없습니다.")

    parsed_json = _parse_structured_content(content)

    # confidence 추출 (parsed_json 내부 또는 별도 필드)
    confidence: Optional[float] = None
    if isinstance(parsed_json.get("confidence"), (int, float)):
        confidence = float(parsed_json["confidence"])

    return OpenAiParseCommandResponse(
        parsed_json=parsed_json,
        finish_reason=finish_reason,
        confidence=confidence,
        refusal=refusal,
    )
