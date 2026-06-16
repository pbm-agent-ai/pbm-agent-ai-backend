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

import base64
import json
import logging
import os
import re
import struct
from typing import Optional
from io import BytesIO
from pathlib import Path
from datetime import datetime
from PIL import Image, ImageDraw

import httpx

from app.schemas.openai import OpenAiParseCommandRequest, OpenAiParseCommandResponse
from app.schemas.planner import DomPlannerRequest, DomPlannerResponse
from app.schemas.vision_planner import VisionPlannerRequest, VisionPlannerResponse

logger = logging.getLogger(__name__)
VISION_DEBUG_DIR = Path(os.getenv("VISION_DEBUG_DIR", "/tmp/pbm-vision-debug"))

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
                "platforms": {
                    "type": "array",
                    "items": {
                        "type": "string",
                        "enum": ["NAVER", "ALIEXPRESS"],
                    },
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
                "platforms",
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


def _decode_data_url(data_url: str) -> bytes:
    return base64.b64decode(data_url.split(",", 1)[1])

def _save_vision_debug_artifacts(
        request: VisionPlannerRequest,
        raw_action: str,
        raw_x: Optional[float],
        raw_y: Optional[float],
        norm_x: Optional[float],
        norm_y: Optional[float],
        img_w: Optional[int],
        img_h: Optional[int],
        target_label: Optional[str],
        confidence: float,
        reason: str,
) -> None:
    try:
        VISION_DEBUG_DIR.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.utcnow().strftime("%Y%m%dT%H%M%S")
        run_id = request.run_id or "unknown-run"
        step_index = request.step_index if request.step_index is not None else -1
        base_name = f"{timestamp}_{run_id}_step{step_index}"
        raw_bytes = _decode_data_url(request.screenshot_data_url)
        raw_path = VISION_DEBUG_DIR / f"{base_name}_raw.png"
        annotated_path = VISION_DEBUG_DIR / f"{base_name}_annotated.png"
        meta_path = VISION_DEBUG_DIR / f"{base_name}_meta.json"
        raw_path.write_bytes(raw_bytes)
        image = Image.open(BytesIO(raw_bytes)).convert("RGBA")
        draw = ImageDraw.Draw(image)

        pixel_interpretation = None
        grid1000_interpretation = None

        if raw_x is not None and raw_y is not None:
            x = int(raw_x)
            y = int(raw_y)
            radius = 18
            pixel_interpretation = {"x": x, "y": y}

            # 빨간 원: raw 좌표를 원본 PNG 픽셀로 그대로 해석한 경우
            draw.ellipse((x - radius, y - radius, x + radius, y + radius), outline="red", width=4)
            draw.line((x - 30, y, x + 30, y), fill="red", width=3)
            draw.line((x, y - 30, x, y + 30), fill="red", width=3)
            text = f"PIXEL {raw_action} ({x}, {y}) {target_label or ''}".strip()
            text_x = x + 20
            text_y = max(10, y - 20)
            draw.rectangle((text_x - 4, text_y - 4, text_x + 320, text_y + 24), fill=(0, 0, 0, 180))
            draw.text((text_x, text_y), text, fill="yellow")

            if img_w and img_h and img_w > 0 and img_h > 0:
                alt_x = int(raw_x / 1000 * img_w)
                alt_y = int(raw_y / 1000 * img_h)
                grid1000_interpretation = {"x": alt_x, "y": alt_y}

                # 파란 원: raw 좌표를 0~1000 grid 기준으로 해석한 경우
                draw.ellipse((alt_x - radius, alt_y - radius, alt_x + radius, alt_y + radius), outline="blue", width=4)
                draw.line((alt_x - 30, alt_y, alt_x + 30, alt_y), fill="blue", width=3)
                draw.line((alt_x, alt_y - 30, alt_x, alt_y + 30), fill="blue", width=3)

                alt_text = f"GRID1000 ({alt_x}, {alt_y})"
                alt_text_x = alt_x + 20
                alt_text_y = max(10, alt_y + 20)
                draw.rectangle((alt_text_x - 4, alt_text_y - 4, alt_text_x + 260, alt_text_y + 24), fill=(0, 0, 0, 180))
                draw.text((alt_text_x, alt_text_y), alt_text, fill="cyan")
        image.save(annotated_path)
        meta = {
            "runId": request.run_id,
            "stepIndex": request.step_index,
            "currentUrl": request.current_url,
            "action": raw_action,
            "imageWidth": img_w,
            "imageHeight": img_h,
            "rawX": raw_x,
            "rawY": raw_y,
            "normalizedX": norm_x,
            "normalizedY": norm_y,
            "pixelInterpretation": pixel_interpretation,
            "grid1000Interpretation": grid1000_interpretation,
            "targetLabel": target_label,
            "confidence": confidence,
            "reason": reason,
            "errorCode": request.error_code,
            "errorMessage": request.error_message,
            "mode": request.mode,
            "targetOption": request.target_option,
            "savedAtUtc": timestamp,
            "rawPath": str(raw_path),
            "annotatedPath": str(annotated_path),
        }
        meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
        logger.info(
            "[analyze_screenshot_action] vision debug artifact saved - runId=%s stepIndex=%s raw=%s annotated=%s meta=%s",
            request.run_id,
            request.step_index,
            raw_path,
            annotated_path,
            meta_path,
        )
    except Exception as e:
        logger.warning("[analyze_screenshot_action] vision debug artifact 저장 실패: %s", e)

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
        "platforms": ["ALIEXPRESS"],
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

    # 디버깅용 로그 추가
    logger.info(
        "parse_command result - model=%s finish_reason=%s confidence=%s refusal=%s parsed_json=%s",
        model,
        finish_reason,
        confidence,
        refusal,
        parsed_json,
    )

    return OpenAiParseCommandResponse(
        parsed_json=parsed_json,
        finish_reason=finish_reason,
        confidence=confidence,
        refusal=refusal,
    )


def _build_dom_planner_system_prompt() -> str:
    """범용 DOM planner 프롬프트 (agent_type 미지정 시 사용).

    rawHtml이 제공되면 전처리된 interactiveElements 대신 원본 HTML을 직접 분석하도록 지시한다.
    """
    return """너는 쇼핑몰 브라우저 자동화 planner다.
입력으로 현재 페이지 상태(URL, rawHtml, visibleText)와 목표 상품(targetProduct)을 받고,
다음에 실행할 브라우저 액션 1개만 JSON으로 반환한다.

[핵심: rawHtml 우선 분석]
- rawHtml이 제공되면 반드시 rawHtml을 직접 분석하여 CSS selector를 추출한다.
- preprocessed interactiveElements는 제공되지 않는다. 오직 rawHtml만으로 판단한다.
- target.selector에는 브라우저 표준 CSS selector(document.querySelector() 호환)를 넣는다.
- id 기반: "#buyNow", class 기반: ".btn-primary", 속성 기반: "button[data-nclick*='buy']"
- 단독 태그명 ("button", "a", "div") selector는 금지한다.

[페이지 유형별 행동 지침]

1. 검색 결과 페이지 (URL에 "search", "query", "SearchText", "wholesale" 등 포함):
   - targetProduct의 title과 가장 유사한 상품 링크를 rawHtml에서 찾아 CLICK한다.
   - <a> 링크의 텍스트나 href를 rawHtml에서 직접 분석한다.
   - 일치하는 상품이 없으면 SCROLL로 더 탐색한다.
   - 검색창(input)이 보이면 targetProduct.title을 INPUT한 뒤 검색 버튼을 CLICK한다.

2. 상품 상세 페이지 (URL에 "/item/", "/product/", "/goods/" 등 포함):
   - 옵션 선택(색상/사이즈)이 필요하면 rawHtml에서 option/select 요소를 직접 찾아 SELECT/CLICK한다.
   - "구매하기", "Buy Now", "지금 구매", "바로구매", "바로 구매" 버튼을 rawHtml에서 찾아 CLICK한다.
   - "장바구니", "Add to Cart" 버튼은 절대 클릭하지 않는다. 반드시 "구매하기" 또는 "바로구매/바로 구매" 버튼만 클릭한다.
   - target.selector에 CSS selector를, 또는 target.labelText에 버튼 텍스트를 넣는다.
   - 버튼이 보이지 않으면 SCROLL로 아래를 탐색한다.

3. 메인 페이지 / 차단된 페이지 / 에러 페이지:
   - 검색창(input[type=search] 또는 search role 요소)을 찾아 targetProduct.title을 INPUT한다.
   - 검색 버튼(돋보기, "검색", "Search")을 찾아 CLICK한다.
   - 검색창도 없으면 SCROLL로 탐색한다.

4. navigationStrategy가 "SEARCH"인 경우 (예: 네이버):
   - 상품 URL로 직접 이동하지 않는다.
   - 반드시 검색 결과 페이지 또는 메인 검색창을 통해 상품을 탐색한다.
   - 검색 결과에서 targetProduct.title과 가장 유사한 상품 링크를 클릭한다.

[공통 규칙]
- 결제 확정, 주문 제출, 결제 버튼은 절대 클릭하지 않는다.
- 확신할 수 없으면 WAIT를 반환한다.
- target.selector를 CSS selector로, 또는 target.labelText를 텍스트 레이블로 지정한다.
- nodeId는 null로 설정한다 (rawHtml 기반 분석이므로 nodeId를 알 수 없음)."""


def _build_search_navigator_system_prompt() -> str:
    """검색 결과 페이지 전문 AI 프롬프트.

    역할: 검색 결과 목록에서 targetProduct와 가장 일치하는 상품 링크를 찾아 클릭한다.
    이 AI는 오직 검색 결과 탐색과 상품 링크 클릭만 담당한다.
    rawHtml이 제공되면 전처리된 interactiveElements 없이 원본 HTML을 직접 분석한다.
    """
    return """너는 쇼핑몰 검색 결과 페이지 전문 탐색 AI다.
네 유일한 임무는 검색 결과에서 targetProduct와 가장 일치하는 상품 링크를 찾아 클릭하는 것이다.

[핵심: rawHtml 우선 분석]
- rawHtml이 제공되면 반드시 rawHtml에서 직접 상품 링크(<a> 태그), href, data-* 속성, inline JSON 문자열을 분석한다.
- preprocessed interactiveElements는 제공되지 않는다.
- target.selector에 CSS selector를, selector를 특정하기 어렵다면 target.labelText에 링크 텍스트를 넣는다.
- nodeId는 null로 설정한다.

[상품 매칭 기준 - 우선순위 순]
1. productId 직접 일치:
   - targetProduct.productId가 있으면 rawHtml에서 그 값이 포함된 요소를 최우선으로 찾는다.
   - productId는 href, data-* 속성, inline JSON 문자열(예: chnl_prod_no, nvMid, catalog_nv_mid) 어디에 있어도 매칭으로 인정한다.
   - productId가 포함된 요소가 <a> 태그면 그 링크를 최우선 클릭 대상으로 선택한다.
   - productId가 <a>가 아닌 상위/하위 요소에 있으면 같은 상품 카드/컨테이너 안의 가장 가까운 클릭 가능한 상품 <a>를 선택한다.
2. 브랜드 일치: targetProduct의 brand가 있으면 반드시 동일 브랜드 상품 선택
3. 모델명 일치: line, model 키워드가 상품명에 포함되는지 확인
4. 색상/사이즈 일치: color, size가 상품명이나 옵션에 포함되는지 확인
5. 가격 범위: maxPrice 이하인 상품 우선 (가격 정보가 visibleTextSummary에 있는 경우)
6. 광고 상품 회피: "광고", "AD", "Sponsored"가 붙은 상품보다 일반 상품 우선

[행동 순서]
1. rawHtml에서 상품 카드/상품 링크(<a>)와 그 주변 data-* 속성을 직접 분석한다.
2. targetProduct.productId와 직접 일치하는 상품 카드 또는 링크가 있으면 그것을 최우선으로 CLICK한다.
3. productId 직접 일치가 없을 때만 브랜드/모델/가격 기준으로 가장 적합한 상품 링크를 선택한다.
4. 상품 링크를 selector로 특정할 수 있으면 target.selector에 넣는다.
5. selector를 특정하기 어렵지만 링크 텍스트가 명확하면 target.labelText에 상품명 또는 링크 텍스트를 넣는다.
6. 페이지에 검색창이 있고 현재 검색어가 부정확하다면 targetProduct.title로 INPUT 후 검색 버튼 CLICK한다.
7. 적합한 상품 링크가 없으면 SCROLL 또는 WAIT를 반환한다.

[절대 금지]
- 글로벌 네비게이션(홈, 메일, 로그인, 장바구니, 검색에서 더보기, 카테고리 이동 링크 등) 선택 금지
- 반드시 상품 카드/상품 컨테이너에 속한 클릭 가능한 상품 링크만 선택한다.
- 구매하기, Buy Now, 장바구니 버튼 클릭 금지 (검색 결과 페이지 임무가 아님)
- 확신할 수 없으면 WAIT를 반환한다."""


def _build_catalog_navigator_system_prompt(
    product_name: str | None = None,
    price: int | None = None,
    mall_name: str | None = None,
) -> str:
    """카탈로그 페이지 전문 AI 프롬프트.

    역할: 네이버 쇼핑 카탈로그 페이지(여러 판매처 비교)에서
          targetProduct의 가격과 일치하는 판매처의 구매 링크 URL을 추출해 NAVIGATE한다.
    """

    target_info = ""
    if product_name or price or mall_name:
        target_info = (
            f"\n[찾아야 할 상품]\n"
            f"- 상품명: {product_name or '알 수 없음'}\n"
            f"- 목표가격: {f'{price:,}원' if price else '알 수 없음'}\n"
            f"- 판매처명: {mall_name or '알 수 없음'}\n"
        )
    return target_info + """너는 네이버 쇼핑 카탈로그 페이지 전문 탐색 AI다.
카탈로그 페이지는 동일 상품을 여러 판매처가 각기 다른 가격에 판매하는 비교 페이지다.
네 임무는 rawHtml에서 목표가격과 정확히 일치하는 판매처의 adcr 링크 URL을 찾아 NAVIGATE로 이동하는 것이다.

[판매처 링크 추출 절차 - 반드시 이 순서대로 실행]
1. rawHtml에서 판매처 행(row) 컨테이너를 모두 식별한다.
   - 네이버 카탈로그의 판매처 행은 <li>, <div class="...seller_item..."> 등의 컨테이너로 구성된다.
   - 각 컨테이너에는 판매처명, 가격 텍스트, adcr 링크가 함께 포함되어 있다.
2. 판매처명과 가격을 동시에 확인하여 정확한 행을 찾는다.
   - 목표 판매처명(예: "GL SHOP")이 포함된 컨테이너를 먼저 찾는다.
   - 판매처명이 일치하는 컨테이너에서 가격도 목표가격과 일치하는지 확인한다.
   - 판매처명이 "알 수 없음"인 경우에는 가격만으로 매칭한다.
3. 그 컨테이너 안에 있는 <a href="https://cr.shopping.naver.com/adcr?..."> 링크를 추출한다.
   - 반드시 판매처명+가격 텍스트와 동일한 컨테이너(행) 안의 adcr 링크여야 한다.
   - 가격 텍스트가 adcr 태그 바깥에 위치하더라도, 같은 행 컨테이너에 속하면 올바른 링크다.
   - 다른 행의 adcr 링크를 가져오면 절대 안 된다.
4. 해당 href 전체 URL을 value에 넣고 action=NAVIGATE로 반환한다.
   - target은 null, value에 adcr URL 전체를 담는다.

[주의: HTML 구조상 흔한 함정]
- 같은 가격(예: 139,000원)의 판매처가 여러 개일 수 있다. 판매처명으로 구분하라.
  예: "GL SHOP 139,000원"과 "로지텍 코리아 공식 139,000원"이 모두 있을 때
      → 판매처명 "GL SHOP"이 있는 행의 adcr 링크를 선택해야 한다.
- 네이버 카탈로그 HTML 구조에서 adcr 링크는 가격 텍스트 앞에 위치하는 경우가 많다.
  예: <a href="adcr?...nvMid=GL_SHOP">GL SHOP</a> ... <strong>139,000</strong>원
  이 경우 "가격 다음에 오는 adcr"을 찾으면 다음 행(다른 판매처)의 adcr이 잡힌다 → 오류!
- 반드시 "같은 컨테이너(행) 안의 adcr"을 기준으로 찾아야 한다.
- 페이지 상단 최저가 요약(최저 139,000원 배너)과 실제 판매처 행을 혼동하지 말 것.

[가격+판매처 매칭 규칙 - 엄격히 준수]
- 판매처명과 목표가격이 모두 일치하는 판매처 행만 선택한다.
- 판매처명을 모르는 경우(알 수 없음)에만 가격만으로 매칭한다.
- "가장 근접한" 가격이 아니라 반드시 "완전히 동일한" 가격이어야 한다.
- 예: 판매처 "GL SHOP", 목표가격 139,000원 → "GL SHOP"이면서 139,000원인 행만 선택
- 정확히 일치하는 판매처가 없으면 WAIT를 반환한다.

[절대 금지]
- search.shopping.naver.com/catalog/ URL 반환 금지 (카탈로그 내부 URL)
- brand.naver.com URL 반환 금지 (브랜드 스토어 메인)
- shopping.naver.com/home URL 반환 금지 (쇼핑 메인)
- 판매처명 또는 가격이 불일치하는 판매처 선택 금지
- 목표 판매처 행이 아닌 다른 행의 adcr 링크 반환 금지

[반환 형식]
action=NAVIGATE, value=<판매처명+가격이 모두 일치하는 행의 adcr URL 전체>, target=null"""


def _build_purchase_executor_system_prompt() -> str:
    """상품 상세 페이지 전문 AI 프롬프트.

    역할: 상품 상세 페이지에서 옵션(색상/사이즈)을 선택하고 구매 버튼을 클릭한다.
    이 AI는 오직 옵션 선택과 구매 버튼 클릭만 담당한다.
    rawHtml만 제공되므로 직접 HTML을 파싱하여 CSS selector를 추출한다.
    """
    return """너는 쇼핑몰 상품 상세 페이지 구매 실행 전문 AI다.
네 임무는 targetProduct의 옵션(색상/사이즈 등)을 선택하고 구매 버튼을 클릭하는 것이다.

[핵심: rawHtml만 사용]
- 제공되는 데이터는 rawHtml(전체 페이지 HTML)뿐이다. preprocessed interactiveElements/optionGroups는 없다.
- 모든 분석과 selector 추출은 rawHtml에서 직접 수행해야 한다.
- target.selector에 CSS selector를, 또는 target.labelText에 텍스트 레이블을 넣는다.
- nodeId는 null로 설정한다 (rawHtml 기반이므로 nodeId를 알 수 없음).

[옵션 선택 - rawHtml 분석]
1. rawHtml에서 <select> 요소 또는 옵션 버튼(색상/사이즈 선택 UI)을 직접 찾는다.
2. targetProduct의 color, size, model 정보와 일치하는 옵션 값을 찾는다.
3. 찾은 옵션을 SELECT하거나 CLICK한다.
4. 옵션 선택 UI가 rawHtml에 없으면 (= 옵션 불필요한 상품) 즉시 구매 버튼 탐색으로 이동한다.

[구매 버튼 CSS selector 추출 - rawHtml 직접 분석]
1. "구매하기", "바로구매", "바로 구매", "Buy Now", "지금 구매" 텍스트가 포함된 <button>, <a>, <span> 요소를 rawHtml에서 찾는다.
   ⚠️ "장바구니", "Add to Cart" 버튼은 절대 클릭 대상이 아니다. 무시하라.
2. 해당 요소의 CSS selector를 반드시 target.selector에 넣어야 한다.
   - id가 있으면: "#buyNow", "#purchaseBtn"
   - class가 있으면: "button.buyBtn", "a.buy-now-btn"
   - data 속성이 있으면: "button[data-nclick*='buy']", "a[data-log-click*='purchase']"
   - 형제 순서: "ul.seller-list li:first-child button"
3. target.node_id는 null, target.label_text는 null 또는 버튼 텍스트로 설정한다.
   (content script가 selector로 document.querySelector()를 실행해 직접 클릭)

⚠️ 중요: 구매 버튼을 확인했다면 target.selector는 반드시 비어있지 않은 문자열이어야 한다.
   selector=null로 반환하면 시스템이 버튼을 클릭할 수 없어 무한 루프에 빠진다.

⚠️ CSS selector 규칙 (반드시 준수):
   - 브라우저 표준 CSS selector만 사용한다. (document.querySelector()로 실행됨)
   - 절대 금지: :has-text(), :visible, :contains(), >> 등 Playwright/jQuery 전용 문법
   - 여러 후보를 쉼표로 나열할 경우 모든 항목이 표준 CSS여야 한다.
   - 절대 금지: "button", "a", "span", "div" 등 단독 태그명만 있는 범용 selector
     → 이런 selector는 시스템이 자동으로 거부하므로 아무 효과가 없다.
   - 특정 selector를 찾기 어려울 때는 selector=null로 설정하고,
     대신 target.label_text에 버튼 텍스트를 넣어라.
     예) selector=null, label_text="바로 구매"
     예) selector=null, label_text="Buy Now"

[행동 순서]
1. 옵션 확인 (rawHtml 분석):
   - rawHtml에 <select>나 옵션 선택 UI가 없으면 → 선택할 옵션이 없는 상품. 즉시 2번으로 이동한다.
   - 옵션 UI가 있으면 → 미선택 항목을 rawHtml에서 찾아 SELECT/CLICK한다.
2. 구매 버튼 CLICK:
   - rawHtml에서 구매 버튼 selector를 추출해 target.selector에 넣는다.
   - selector를 특정하기 어려우면 target.selector=null, target.label_text="구매하기" 로 반환한다.
   - 버튼이 아직 화면에 없으면 SCROLL로 아래를 탐색한다.
3. COMPLETE는 페이지에 "카드 간편 결제" 또는 "후불 결제"라는 단어가 있을 시에만 반환한다.

⚠️ WAIT 반환 기준 (엄격히 제한):
   - WAIT는 오직 다음 경우에만 반환한다:
     (a) 페이지가 아직 로딩 중이어서 버튼이 전혀 보이지 않을 때
     (b) 필수 옵션이 있는데 어떤 값을 선택해야 할지 정보가 부족할 때
   - rawHtml에서 구매 버튼이 확인됐는데 WAIT를 반환하는 것은 금지한다.

[절대 금지]
- "장바구니", "Add to Cart", "카트에 담기" 버튼 클릭 절대 금지. 반드시 "구매하기" 또는 "바로구매/바로 구매" 버튼만 클릭.
- "결제하기", "주문하기", "결제 완료", "Pay Now", "주문완료", "결제" 등 최종 결제 버튼 클릭 절대 금지.
  (구매하기/바로구매까지만 허용. 결제 버튼은 사용자 최종 확인 단계임)
- 아직 미선택 필수 옵션이 있는 상태에서 구매 버튼 클릭 금지."""


def _select_system_prompt(agent_type: str | None, request: DomPlannerRequest | None = None) -> str:
    """agent_type에 따라 적절한 시스템 프롬프트를 선택한다.

    Args:
        agent_type: SEARCH_NAVIGATOR | PURCHASE_EXECUTOR | None(범용)

    Returns:
        해당 agent_type의 시스템 프롬프트 문자열
    """
    if agent_type == "SEARCH_NAVIGATOR":
        return _build_search_navigator_system_prompt()
    if agent_type == "PURCHASE_EXECUTOR":
        return _build_purchase_executor_system_prompt()
    if agent_type == "CATALOG_NAVIGATOR":
        product_name = None
        price = None
        mall_name = None
        if request and request.target_product:
            product_name = request.target_product.get("title")
            lprice_str = request.target_product.get("lprice")  # Java ProductCandidateResponse의 가격 필드명은 lprice(String)
            price = int(lprice_str) if lprice_str and str(lprice_str).isdigit() else None
            mall_name = request.target_product.get("mallName")  # 판매처명 (예: "GL SHOP")
        return _build_catalog_navigator_system_prompt(product_name, price, mall_name)
    return _build_dom_planner_system_prompt()


def _build_dom_planner_user_prompt(request: DomPlannerRequest) -> str:
    # rawHtml이 있으면 전처리된 interactiveElements/optionGroups 없이
    # 원본 HTML을 LLM에 직접 전달한다 (모든 agent_type에 동일 적용)
    if request.raw_html:
        payload: dict = {
            "commandText": request.command_text,
            "commandIntent": request.command_intent,
            "commandStatus": request.command_status,
            "platform": request.platform,
            "navigationStrategy": request.navigation_strategy,
            "agentType": request.agent_type,
            "currentUrl": request.current_url,
            "title": request.title,
            "visibleTextSummary": request.visible_text_summary,
            "targetProduct": request.target_product,
            "rawHtml": request.raw_html,
        }
    else:
        # rawHtml 없을 때만 legacy interactiveElements/optionGroups 포함
        payload: dict = {
            "commandText": request.command_text,
            "commandIntent": request.command_intent,
            "commandStatus": request.command_status,
            "platform": request.platform,
            "navigationStrategy": request.navigation_strategy,
            "agentType": request.agent_type,
            "currentUrl": request.current_url,
            "title": request.title,
            "visibleTextSummary": request.visible_text_summary,
            "targetProduct": request.target_product,
            "interactiveElements": request.interactive_elements,
            "optionGroups": request.option_groups,
        }
    return json.dumps(payload, ensure_ascii=False)


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
    """GPT-5.4-mini로 DOM snapshot 기반 다음 액션을 결정한다.

    agent_type에 따라 전문화된 프롬프트를 선택한다:
    - SEARCH_NAVIGATOR: 검색 결과 → 상품 링크 클릭 전문
    - PURCHASE_EXECUTOR: 상품 상세 → 옵션 선택 + 구매버튼 전문
    - None(미지정): 범용 planner
    """
    if _is_mock_enabled():
        return await _plan_dom_action_mock(request)

    api_key, base_url, model = _get_openai_config()
    system_prompt = _select_system_prompt(request.agent_type, request)
    logger.info("[plan_dom_action] agent_type=%s → prompt 선택 완료", request.agent_type or "GENERIC")
    if request.agent_type == "CATALOG_NAVIGATOR":
        elements_summary = [
            {"nodeId": el.get("nodeId"), "role": el.get("role"), "labelText": el.get("labelText")}
            for el in (request.interactive_elements or [])
        ]
        logger.info("[plan_dom_action] CATALOG_NAVIGATOR interactiveElements=%s", json.dumps(elements_summary, ensure_ascii=False))
    request_body = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
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

    async with httpx.AsyncClient(timeout=60.0) as client:
        response = None
        max_attempts = 3
        for attempt in range(1, max_attempts + 1):
            response = await client.post(
                f"{base_url}/chat/completions",
                json=request_body,
                headers=headers,
            )
            if response.status_code != 429:
                break

            retry_after = int(response.headers.get("Retry-After", "1"))
            logger.warning("[plan_dom_action] OpenAI rate limit (429) - attempt=%d/%d Retry-After=%s",
                           attempt, max_attempts, retry_after)
            if attempt < max_attempts:
                await asyncio.sleep(retry_after)
            else:
                return DomPlannerResponse(
                    action="WAIT",
                    target=None,
                    value=None,
                    confidence=0.0,
                    reason="OpenAI rate limit으로 DOM planner를 일시 사용할 수 없어 대기합니다."
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


def _get_png_dimensions(data_url: str) -> tuple[int, int]:
    """PNG data URL에서 이미지 너비/높이를 추출한다 (PIL 없이 헤더 직접 파싱).

    PNG 파일 포맷 규격:
    - bytes 0-7:   PNG 시그니처
    - bytes 8-11:  IHDR 청크 길이 (4바이트)
    - bytes 12-15: 'IHDR' 문자열
    - bytes 16-19: 너비 (big-endian uint32)
    - bytes 20-23: 높이 (big-endian uint32)

    PNG가 아니거나 파싱 실패 시 (0, 0) 반환.
    """
    try:
        raw = base64.b64decode(data_url.split(",", 1)[1])
        # PNG 시그니처 검증 (89 50 4E 47 0D 0A 1A 0A)
        if raw[:8] != b"\x89PNG\r\n\x1a\n":
            logger.warning("[_get_png_dimensions] PNG 시그니처 불일치 → 크기 추출 불가")
            return 0, 0
        width = struct.unpack(">I", raw[16:20])[0]
        height = struct.unpack(">I", raw[20:24])[0]
        return width, height
    except Exception as e:
        logger.warning("[_get_png_dimensions] 이미지 크기 파싱 실패: %s", e)
        return 0, 0


async def _analyze_screenshot_mock(request: VisionPlannerRequest) -> VisionPlannerResponse:
    logger.info("Vision planner 모킹 모드 활성화 - 고정 클릭 좌표 반환")
    if request.mode == "EXTERNAL_OPTION_PRESENCE":
        target_label = "옵션"
        raw_x, raw_y = 500, 380
        norm_x, norm_y = 0.5, 0.38
        reason = "mock vision planner가 옵션 존재 여부를 반환함"
        return VisionPlannerResponse(
            action="CLICK",
            viewport_x=norm_x,
            viewport_y=norm_y,
            target_label=target_label,
            option_present=True,
            option_groups=[],
            confidence=0.74,
            reason=reason,
        )
    if request.mode == "EXTERNAL_OPTION_SELECTION":
        target_label = request.target_option or "옵션"
        raw_x, raw_y = 500, 420
        norm_x, norm_y = 0.5, 0.42
        reason = "mock vision planner가 선택 대상 옵션을 반환함"
        return VisionPlannerResponse(
            action="CLICK",
            viewport_x=norm_x,
            viewport_y=norm_y,
            target_label=target_label,
            option_present=True,
            option_groups=[
                {"group_name": "색상", "options": ["블랙", "화이트"], "selected_option": None},
            ],
            confidence=0.74,
            reason=reason,
        )
    if request.mode == "SMARTSTORE_OPTION_PRESENCE":
        target_label = "옵션"
        raw_x, raw_y = 500, 380
        norm_x, norm_y = 0.5, 0.38
        reason = "mock vision planner가 옵션 영역을 선택함"
    elif request.mode == "SMARTSTORE_OPTION_SELECTION":
        target_label = request.target_option or "옵션"
        raw_x, raw_y = 500, 420
        norm_x, norm_y = 0.5, 0.42
        reason = "mock vision planner가 선택 대상 옵션을 선택함"
    elif request.mode == "SMARTSTORE_PURCHASE_BUTTON":
        target_label = "구매하기"
        raw_x, raw_y = 500, 820
        norm_x, norm_y = 0.5, 0.82
        reason = "mock vision planner가 구매 버튼을 선택함"
    else:
        target_label = "mock primary button"
        raw_x, raw_y = 500, 800
        norm_x, norm_y = 0.5, 0.8
        reason = "mock vision planner가 화면 하단 주요 버튼을 선택함"

    _save_vision_debug_artifacts(
        request=request,
        raw_action="CLICK",
        raw_x=raw_x,
        raw_y=raw_y,
        norm_x=norm_x,
        norm_y=norm_y,
        img_w=1280,
        img_h=800,
        target_label=target_label,
        confidence=0.74,
        reason=reason,
    )
    return VisionPlannerResponse(
        action="CLICK",
        viewport_x=norm_x,
        viewport_y=norm_y,
        target_label=target_label,
        confidence=0.74,
        reason=reason,
    )


async def analyze_screenshot_action(request: VisionPlannerRequest) -> VisionPlannerResponse:
    """Gemini Flash 계열 모델로 스크린샷 기반 클릭 좌표를 분석한다.

    503 UNAVAILABLE (서버 과부하) 발생 시 최대 3회 재시도한다.
    대기 시간: 1초 → 2초 → 4초 (exponential backoff)
    """
    if _is_mock_enabled():
        return await _analyze_screenshot_mock(request)

    api_key, model = _get_gemini_config()

    try:
        from google import genai
        from google.genai import types
        from google.genai.errors import ServerError
    except ImportError as exc:
        raise ValueError("google-genai 패키지가 설치되지 않았습니다.") from exc

    import asyncio

    client = genai.Client(api_key=api_key)
    prompt = (
        "너는 쇼핑 페이지 스크린샷을 보고 다음 클릭 목표를 찾는 비전 planner다. "
        "가장 클릭 가능성이 높은 버튼/링크/CTA 하나를 찾고, CLICK인 경우에만 해당 요소의 중심 좌표(x, y)를 반환하라. "
        "좌표는 실제 이미지 픽셀이 아니라 0~1000 기준 grid 정수값이다. 예: 화면 중앙 버튼이면 viewport_x=500, viewport_y=500처럼 반환하라. "
        "가격 텍스트, 상품명, 배너, 일반 설명문은 클릭 타겟이 아니다. 버튼/링크/CTA가 아니면 CLICK을 반환하지 마라. "
        "WAIT나 COMPLETE인 경우 viewport_x, viewport_y, target_label은 반드시 null이어야 한다. "
        "명확한 타겟이 없으면 WAIT를 반환하라. "
        "mode가 SEARCH_RESULTS_PRODUCT이면 target_option에 들어있는 JSON 문자열의 title, price, productId를 기준으로 현재 검색 결과 화면에서 대상 상품을 찾아라. "
        "이 모드에서는 상품 카드 전체가 아니라 상품명 링크 텍스트 영역의 정중앙을 우선 클릭해야 한다. "
        "이미지, 가격 숫자, 구매정보, 광고 배지, 쿠폰, 장바구니, 구매 버튼은 절대 클릭하지 마라. "
        "현재 화면에 해당 상품명 링크가 보이지 않으면 반드시 WAIT를 반환하라. "
        "mode가 SMARTSTORE_OPTION_PRESENCE이면 스마트스토어 상품 상세에서 옵션 UI(색상/사이즈 토글, listbox opener, role=radio 옵션 버튼)만 대상으로 삼아라. "
        "쿠폰 받기, 혜택, 구매하기, 장바구니, 선물하기, 리뷰, 배송, 가격 영역 등 일반 CTA는 절대 클릭하지 마라. "
        "옵션 UI가 현재 화면에 보이면 그 요소만 CLICK하고, 옵션 UI가 안 보이면 반드시 WAIT를 반환하라. "
        "mode가 SMARTSTORE_OPTION_SELECTION이면 target_option을 우선 기준으로 삼아, 화면에 보이는 해당 옵션 텍스트 또는 그 옵션을 펼치는 opener를 클릭하라. "
        "target_option이 현재 화면에 없으면 해당 옵션 목록을 열 수 있는 toggle/button을 클릭하고, 그래도 불명확하면 WAIT를 반환하라. "
        "mode가 SMARTSTORE_PURCHASE_BUTTON이면 스마트스토어 상품 상세에서 옵션 선택이 이미 반영된 뒤의 구매 버튼만 대상으로 삼아라. "
        "\"구매하기\", \"바로구매\", \"바로 구매\", \"N구매하기\", \"N구매\", \"Buy Now\", \"지금 구매\" 텍스트가 있는 버튼만 CLICK하고, "
        "\"쿠폰 받기\", \"혜택\", \"장바구니\", \"선물하기\", \"리뷰\", \"배송\", \"가격\" 영역은 절대 클릭하지 마라. "
        "구매 버튼이 현재 화면에 보이지 않으면 반드시 WAIT를 반환하라. "
    )

    screenshot_base64 = request.screenshot_data_url.split(",", 1)[1]
    mime_type = request.screenshot_data_url.split(";", 1)[0].replace("data:", "")

    contents = [
        types.Part.from_bytes(data=__import__("base64").b64decode(screenshot_base64), mime_type=mime_type),
        types.Part.from_text(
            text=json.dumps(
                {
                    "commandText": request.command_text,
                    "currentUrl": request.current_url,
                    "errorCode": request.error_code,
                    "errorMessage": request.error_message,
                    "mode": request.mode,
                    "targetOption": request.target_option,
                },
                ensure_ascii=False,
            )
        ),
    ]

    # 비동기 클라이언트 사용: async def 안에서 동기 generate_content()를 호출하면
    # asyncio 이벤트 루프 전체가 블로킹되어 다른 요청 처리가 불가능해진다.
    # client.aio.models.generate_content()는 진짜 async API로 이 문제를 해결한다.
    # 503 과부하 대비 최대 3회 재시도 (1초 → 2초 → 4초)
    max_attempts = 3
    last_error: Exception | None = None
    for attempt in range(1, max_attempts + 1):
        try:
            # gemini api 호출부 코드
            response = await client.aio.models.generate_content(
                model=model,
                contents=contents,
                config=types.GenerateContentConfig(
                    system_instruction=prompt,
                    tools=[types.Tool(function_declarations=[
                        # Gemini에게 이런 함수를 호출할 수 있다고 알려줌
                        types.FunctionDeclaration(
                            name="report_vision_action",        # 함수 이름
                            description="스크린샷에서 클릭 대상을 분석한 결과를 보고한다.",  # 함수 설명
                            parameters=types.Schema(        # 함수 인자 타입 정의
                                type="OBJECT",
                                properties={
                                    "action":       types.Schema(type="STRING", enum=["CLICK", "WAIT", "COMPLETE"]),
                                    "viewport_x":   types.Schema(
                                        type="INTEGER",
                                        description="CLICK일 때만 클릭 대상의 x 좌표. 0~1000 grid 정수값, WAIT/COMPLETE면 null",
                                        nullable=True,
                                    ),
                                    "viewport_y":   types.Schema(
                                        type="INTEGER",
                                        description="CLICK일 때만 클릭 대상의 y 좌표. 0~1000 grid 정수값, WAIT/COMPLETE면 null",
                                        nullable=True,
                                    ),
                                    "target_label": types.Schema(type="STRING", description="클릭 대상 버튼/링크 레이블. WAIT/COMPLETE면 null", nullable=True),
                                    "confidence":   types.Schema(type="NUMBER", description="0.0~1.0"),
                                    "reason":       types.Schema(type="STRING", description="판단 근거"),
                                },
                                required=["action", "confidence", "reason"],
                            ),
                        )
                    ])],
                    tool_config=types.ToolConfig(
                        # mode [AUTO-알아서 판단(텍스트를 쓰든 함수를 쓰든 자유), ANY-반드시 함수 호출로만 응답, NONE-함수 사용 x)
                        function_calling_config=types.FunctionCallingConfig(mode="ANY")
                    ),
                ),
            )
            break  # 성공 시 루프 탈출
        except ServerError as e:
            last_error = e
            # SDK는 status_code가 아닌 code 속성 사용 (google-genai APIError.__init__ 참고)
            if e.code == 503 and attempt < max_attempts:
                wait_sec = 2 ** (attempt - 1)  # 1, 2, 4초
                logger.warning(
                    "[analyze_screenshot_action] Gemini 503 과부하 - %d/%d회 재시도 대기 %ds. error=%s",
                    attempt, max_attempts, wait_sec, e,
                )
                await asyncio.sleep(wait_sec)
            else:
                raise
    else:
        # for-else: break 없이 루프가 끝난 경우 (모든 재시도 실패)
        raise last_error  # type: ignore[misc]

    # Function Calling 응답에서 args 추출
    try:
        part = response.candidates[0].content.parts[0]
        logger.info("[analyze_screenshot_action] Gemini 응답 part 타입: %s, has function_call: %s",
                    type(part).__name__, hasattr(part, 'function_call') and part.function_call is not None)
        func_call = part.function_call
        parsed_json = dict(func_call.args)

        if parsed_json.get("action") != "CLICK":
            parsed_json["viewport_x"] = None
            parsed_json["viewport_y"] = None
            parsed_json["target_label"] = None

        label = (parsed_json.get("target_label") or "").strip()
        if parsed_json.get("action") == "CLICK" and re.search(r"\d{1,3}(,\d{3})*원", label):
            logger.warning("[analyze_screenshot_action] 가격 텍스트를 클릭 대상으로 반환 → WAIT로 강등. label=%s", label)
            parsed_json["action"] = "WAIT"
            parsed_json["viewport_x"] = None
            parsed_json["viewport_y"] = None
            parsed_json["target_label"] = None

        logger.info("[analyze_screenshot_action] func_call.args raw dict: %s", parsed_json)
    except (IndexError, AttributeError) as e:
        # function_call이 없으면 text 응답인지 확인
        text_fallback = getattr(response, "text", None)
        logger.warning("[analyze_screenshot_action] Function Call 파싱 실패: %s, text fallback: %s", e, text_fallback)
        raise ValueError(f"Gemini Function Call 응답 파싱 실패: {e}")

    from app.schemas.vision_planner import _GeminiRawResponse
    raw = _GeminiRawResponse.model_validate(parsed_json)

    # 좌표가 null인 경우: 강화된 프롬프트로 1회 재시도
    if raw.action == "CLICK" and (raw.viewport_x is None or raw.viewport_y is None):
        logger.warning(
            "[analyze_screenshot_action] Gemini 좌표 null 반환 (action=%s) → 강화 프롬프트로 재시도",
            raw.action,
        )
        retry_contents = [
            types.Part.from_bytes(data=__import__("base64").b64decode(screenshot_base64), mime_type=mime_type),
            types.Part.from_text(text=(
                json.dumps(
                    {
                        "commandText": request.command_text,
                        "currentUrl": request.current_url,
                        "errorCode": request.error_code,
                        "errorMessage": request.error_message,
                        "mode": request.mode,
                        "targetOption": request.target_option,
                    },
                    ensure_ascii=False,
                )
                + "\n\n[중요] CLICK일 때만 viewport_x와 viewport_y에 0~1000 grid 정수 좌표를 넣어라. "
                "WAIT/COMPLETE면 viewport_x, viewport_y, target_label을 반드시 null로 반환하라. "
                "가격 텍스트/상품명은 클릭 대상이 아니다. "
                "단, mode가 SEARCH_RESULTS_PRODUCT이면 targetOption의 title/price/productId에 맞는 상품명 링크 텍스트 중앙만 클릭한다. "
                "이미지, 가격 숫자, 구매정보, 광고 배지, 구매 버튼은 클릭하지 않는다. "
                "mode가 SMARTSTORE_OPTION_PRESENCE이면 옵션 UI만 클릭하고 쿠폰/혜택/구매/장바구니/선물하기는 클릭하지 않는다. "
                "mode가 SMARTSTORE_OPTION_SELECTION이면 targetOption에 맞는 옵션 또는 opener를 우선 찾는다. "
                "mode가 SMARTSTORE_PURCHASE_BUTTON이면 옵션이 이미 선택된 뒤의 구매 버튼만 클릭하고 쿠폰/혜택/장바구니/리뷰는 절대 클릭하지 않는다."
            )),
        ]
        retry_response = await client.aio.models.generate_content(
            model=model,
            contents=retry_contents,
            config=types.GenerateContentConfig(
                system_instruction=prompt,
                tools=[types.Tool(function_declarations=[
                    types.FunctionDeclaration(
                        name="report_vision_action",
                        description="스크린샷에서 클릭 대상을 분석한 결과를 보고한다.",
                        parameters=types.Schema(
                            type="OBJECT",
                            properties={
                                "action":       types.Schema(type="STRING", enum=["CLICK", "WAIT", "COMPLETE"]),
                                "viewport_x":   types.Schema(
                                    type="INTEGER",
                                    description="CLICK일 때만 클릭 대상의 x 좌표. 0~1000 grid 정수값, WAIT/COMPLETE면 null",
                                    nullable=True,
                                ),
                                "viewport_y":   types.Schema(
                                    type="INTEGER",
                                    description="CLICK일 때만 클릭 대상의 y 좌표. 0~1000 grid 정수값, WAIT/COMPLETE면 null",
                                    nullable=True,
                                ),
                                "target_label": types.Schema(type="STRING", description="클릭 대상 버튼/링크 레이블. WAIT/COMPLETE면 null", nullable=True),
                                "confidence":   types.Schema(type="NUMBER", description="0.0~1.0"),
                                "reason":       types.Schema(type="STRING", description="판단 근거"),
                            },
                            required=["action", "confidence", "reason"],
                        ),
                    )
                ])],
                tool_config=types.ToolConfig(
                    function_calling_config=types.FunctionCallingConfig(mode="ANY")
                ),
            ),
        )
        try:
            retry_func_call = retry_response.candidates[0].content.parts[0].function_call
            retry_json = dict(retry_func_call.args)
            logger.info("[analyze_screenshot_action] 재시도 func_call.args: %s", retry_json)
            raw = _GeminiRawResponse.model_validate(retry_json)
        except Exception as retry_err:
            logger.warning("[analyze_screenshot_action] 재시도 파싱 실패: %s → 원본 응답 유지", retry_err)

    # SCROLL은 Gemini가 간혹 반환하는 비표준 액션 → WAIT로 변환 (Java 쪽 계약 유지)
    resolved_action = "WAIT" if raw.action == "SCROLL" else raw.action
    if raw.action == "SCROLL":
        logger.info("[analyze_screenshot_action] Gemini SCROLL 반환 → WAIT로 변환")

    logger.info(
        "[analyze_screenshot_action] Gemini raw 응답 - action=%s, raw_x=%s, raw_y=%s, confidence=%s",
        resolved_action, raw.viewport_x, raw.viewport_y, raw.confidence,
    )

    # 픽셀 좌표를 이미지 크기 기준으로 0~1로 정규화한다.
    norm_x: float | None = None
    norm_y: float | None = None
    img_w: int | None = None
    img_h: int | None = None
    if raw.viewport_x is not None and raw.viewport_y is not None:
        img_w, img_h = _get_png_dimensions(request.screenshot_data_url)
        logger.info("[analyze_screenshot_action] PNG 크기 추출 - img_w=%d, img_h=%d", img_w, img_h)
        if raw.viewport_x > 1 or raw.viewport_y > 1:
            norm_x = max(0.0, min(1.0, raw.viewport_x / 1000))
            norm_y = max(0.0, min(1.0, raw.viewport_y / 1000))
            logger.info(
                "[analyze_screenshot_action] 1000-grid→정규화 변환 - 원본=(%s, %s), 정규화=(%.3f, %.3f)",
                raw.viewport_x, raw.viewport_y, norm_x, norm_y,
            )
        else:
            norm_x = max(0.0, min(1.0, raw.viewport_x))
            norm_y = max(0.0, min(1.0, raw.viewport_y))
            logger.info(
                "[analyze_screenshot_action] 이미 정규화된 좌표 사용 - 원본=(%s, %s), 정규화=(%.3f, %.3f)",
                raw.viewport_x, raw.viewport_y, norm_x, norm_y,
            )
    else:
        logger.warning("[analyze_screenshot_action] Gemini가 좌표 null 반환 - action=%s", raw.action)

    _save_vision_debug_artifacts(
        request=request,
        raw_action=resolved_action,
        raw_x=raw.viewport_x,
        raw_y=raw.viewport_y,
        norm_x=norm_x,
        norm_y=norm_y,
        img_w=img_w,
        img_h=img_h,
        target_label=raw.target_label,
        confidence=raw.confidence,
        reason=raw.reason,
    )

    return VisionPlannerResponse(
        action=resolved_action,
        viewport_x=norm_x,
        viewport_y=norm_y,
        target_label=raw.target_label,
        confidence=raw.confidence,
        reason=raw.reason,
    )
