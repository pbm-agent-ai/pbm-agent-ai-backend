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
from app.schemas.planner import DomPlannerRequest, DomPlannerResponse
from app.schemas.vision_planner import VisionPlannerRequest, VisionPlannerResponse

logger = logging.getLogger(__name__)

# OpenAI API 기본 설정
OPENAI_DEFAULT_BASE_URL = "https://api.openai.com/v1"
OPENAI_DEFAULT_MODEL = "gpt-5.4-mini"

DOM_PLANNER_SCHEMA: dict = {
    "type": "object",
    "properties": {
        "action": {
            "type": "string",
            "enum": ["NAVIGATE", "CLICK", "INPUT", "SELECT", "SCROLL", "WAIT", "COMPLETE"],
        },
        "target": {
            "type": ["object", "null"],
            "properties": {
                "node_id": {"type": ["string", "null"]},
                "role": {"type": ["string", "null"]},
                "label_text": {"type": ["string", "null"]},
                "selector": {"type": ["string", "null"]},
            },
            "required": ["node_id", "role", "label_text", "selector"],
            "additionalProperties": False,
        },
        "value": {"type": ["string", "null"]},
        "confidence": {"type": "number"},
        "reason": {"type": "string"},
    },
    "required": ["action", "target", "value", "confidence", "reason"],
    "additionalProperties": False,
}


# command-service가 기대하는 최상위 응답 JSON 스키마.
# strict=true 사용 시 additionalProperties=false가 필요하다.
# OpenAI 호출 시 response_format에 이 스키마를 전달하면 GPT가 이 스키마를 벗어나는 JSON을 절대 반환하지 않음
# "additionalProperties": False → 스키마에 없는 필드는 추가 불가
# "required" → 필수 키가 무조건 포함됨
# "enum" → 지정된 값만 사용 가능
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
    # content 자체가 없으면 즉시 실패
    if content is None:
        raise ValueError("OpenAI 응답에 content가 없습니다.")
    # 이미 dict이면 즉시 반환
    if isinstance(content, dict):
        return content
    # json.loads(): 문자열을 dict로 파싱
    # from exc: 원본 예외를 체인으로 연결(Java의 cause와 동일)
    """
    json.loads()는 내부적으로 문자열을 한 글자씩 읽으면서 파싱합니다.
    예시: {"color": "black"} → dict
    문자열: {  "  c  o  l  o  r  "  :     "  b  l  a  c  k  "  }
            ↓  ↓  ↓  ↓  ↓  ↓  ↓  ↓  ↓  ↓  ↓  ↓  ↓  ↓  ↓  ↓  ↓
    단계1:  {  → "객체 시작이구나" → 빈 dict {} 생성
    단계2:     "color" → "키는 color구나"
    단계3:              : → "키 끝, 이제 값이 나오겠구나"
    단계4:                "black" → "값은 black이구나" → dict["color"] = "black"
    단계5:                         } → "객체 끝났구나" → 완료
    결과: {"color": "black"} (Python dict)

    """
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


# 비동기 함수. OpenAI 응답을 기다리는 동안 블로킹 되지 않음
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


def _build_dom_planner_system_prompt() -> str:
    return (
        "너는 브라우저 자동화 planner다. "
        "입력으로 command/session 상태와 DOM 요약을 받고, 다음 브라우저 액션 1개만 JSON으로 반환한다. "
        "반드시 주어진 interactive_elements / option_groups 안에서 target을 고르고, "
        "버튼이 보이지 않거나 페이지 아래에 있을 가능성이 있으면 SCROLL을 반환할 수 있다. "
        "결정할 수 없으면 WAIT 또는 COMPLETE를 반환한다. "
        "결제 확정/주문 제출을 추정해서 과감하게 누르지 말고, 확실한 구매/장바구니/검색/옵션 선택만 선택한다."
    )


def _build_dom_planner_user_prompt(request: DomPlannerRequest) -> str:
    return json.dumps(
        {
            "commandText": request.command_text,
            "commandIntent": request.command_intent,
            "commandStatus": request.command_status,
            "currentUrl": request.current_url,
            "title": request.title,
            "visibleTextSummary": request.visible_text_summary,
            "targetProduct": request.target_product,
            "interactiveElements": request.interactive_elements,
            "optionGroups": request.option_groups,
        },
        ensure_ascii=False,
    )


async def _plan_dom_action_mock(request: DomPlannerRequest) -> DomPlannerResponse:
    logger.info("DOM planner 모킹 모드 활성화 - deterministic planner 결과 반환")

    for element in request.interactive_elements:
        label = (element.get("labelText") or "").strip()
        role = (element.get("role") or "").strip().lower()
        if role == "button" and ("구매" in label or "장바구니" in label or "search" in label.lower()):
            return DomPlannerResponse(
                action="CLICK",
                target={
                    "node_id": element.get("nodeId"),
                    "role": element.get("role"),
                    "label_text": element.get("labelText"),
                    "selector": element.get("selector"),
                },
                value=None,
                confidence=0.81,
                reason="mock planner가 interactive element 중 실행 가능 버튼을 선택함",
            )

    if request.target_product and request.target_product.get("productUrl"):
        return DomPlannerResponse(
            action="NAVIGATE",
            target=None,
            value=request.target_product.get("productUrl"),
            confidence=0.75,
            reason="mock planner가 target product URL로 이동을 선택함",
        )

    if request.current_url and "aliexpress.com" in request.current_url and not request.interactive_elements:
        return DomPlannerResponse(
            action="SCROLL",
            target=None,
            value="500",
            confidence=0.68,
            reason="보이는 interactive element가 부족해 아래로 더 탐색함",
        )

    return DomPlannerResponse(
        action="WAIT",
        target=None,
        value=None,
        confidence=0.6,
        reason="충분한 target을 찾지 못해 대기함",
    )


async def plan_dom_action(request: DomPlannerRequest) -> DomPlannerResponse:
    """GPT-5.4-mini로 DOM snapshot 기반 다음 액션을 결정한다."""
    if _is_mock_enabled():
        return await _plan_dom_action_mock(request)

    api_key, base_url, model = _get_openai_config()
    request_body = {
        "model": model,
        "messages": [
            {"role": "system", "content": _build_dom_planner_system_prompt()},
            {"role": "user", "content": _build_dom_planner_user_prompt(request)},
        ],
        "temperature": 0.0,
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name": "dom_planner_response",
                "strict": True,
                "schema": DOM_PLANNER_SCHEMA,
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
    if "error" in data:
        error_info = data["error"]
        error_message = error_info.get("message", str(error_info))
        raise ValueError(f"OpenAI API 에러: {error_message}")

    choices: list = data.get("choices", [])
    if not choices:
        raise ValueError("OpenAI 응답에 choices가 없습니다.")

    content = choices[0].get("message", {}).get("content")
    parsed_json = _parse_structured_content(content)
    return DomPlannerResponse.model_validate(parsed_json)


def _get_gemini_config() -> tuple[str, str]:
    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        raise ValueError("GEMINI_API_KEY 환경변수가 필요합니다")
    model = os.getenv("GEMINI_VISION_MODEL", "gemini-2.5-flash")
    return api_key, model


async def _analyze_screenshot_mock(request: VisionPlannerRequest) -> VisionPlannerResponse:
    logger.info("Vision planner 모킹 모드 활성화 - 고정 클릭 좌표 반환")
    return VisionPlannerResponse(
        action="CLICK",
        viewport_x=0.5,
        viewport_y=0.8,
        target_label="mock primary button",
        confidence=0.74,
        reason="mock vision planner가 화면 하단 주요 버튼을 선택함",
    )


async def analyze_screenshot_action(request: VisionPlannerRequest) -> VisionPlannerResponse:
    """Gemini Flash 계열 모델로 스크린샷 기반 클릭 좌표를 분석한다."""
    if _is_mock_enabled():
        return await _analyze_screenshot_mock(request)

    api_key, model = _get_gemini_config()

    try:
        from google import genai
        from google.genai import types
    except ImportError as exc:
        raise ValueError("google-genai 패키지가 설치되지 않았습니다.") from exc

    client = genai.Client(api_key=api_key)
    prompt = (
        "너는 쇼핑 페이지 스크린샷을 보고 다음 클릭 목표를 찾는 비전 planner다. "
        "가장 클릭 가능성이 높은 버튼/링크 하나를 찾고, 뷰포트 기준 좌표 비율(x,y)을 0~1 범위로 반환하라. "
        "명확한 타겟이 없으면 WAIT를 반환하라."
    )

    screenshot_base64 = request.screenshot_data_url.split(",", 1)[1]
    mime_type = request.screenshot_data_url.split(";", 1)[0].replace("data:", "")

    response = client.models.generate_content(
        model=model,
        contents=[
            types.Part.from_bytes(data=__import__("base64").b64decode(screenshot_base64), mime_type=mime_type),
            types.Part.from_text(
                text=json.dumps(
                    {
                        "commandText": request.command_text,
                        "currentUrl": request.current_url,
                        "errorCode": request.error_code,
                        "errorMessage": request.error_message,
                        "responseSchema": {
                            "action": "CLICK | WAIT | COMPLETE",
                            "viewport_x": "0.0~1.0",
                            "viewport_y": "0.0~1.0",
                            "target_label": "button label or short description",
                            "confidence": "0.0~1.0",
                            "reason": "why"
                        }
                    },
                    ensure_ascii=False,
                )
            ),
        ],
        config=types.GenerateContentConfig(system_instruction=prompt),
    )

    text = getattr(response, "text", None)
    if not text:
        raise ValueError("Gemini 응답에 text 가 없습니다.")

    parsed_json = json.loads(text.strip().removeprefix("```json").removesuffix("```").strip())
    return VisionPlannerResponse.model_validate(parsed_json)
