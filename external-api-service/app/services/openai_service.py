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
    """범용 DOM planner 프롬프트 (agent_type 미지정 시 사용)."""
    return """너는 쇼핑몰 브라우저 자동화 planner다.
입력으로 현재 페이지 상태(URL, DOM 요약, interactive_elements)와 목표 상품(targetProduct)을 받고,
다음에 실행할 브라우저 액션 1개만 JSON으로 반환한다.

[페이지 유형별 행동 지침]

1. 검색 결과 페이지 (URL에 "search", "query", "SearchText", "wholesale" 등 포함):
   - targetProduct의 title과 가장 유사한 상품 링크를 interactiveElements에서 찾아 CLICK한다.
   - 일치하는 상품이 없으면 SCROLL로 더 탐색한다.
   - 검색창(input)이 비어있으면 targetProduct.title을 INPUT한 뒤 검색 버튼을 CLICK한다.

2. 상품 상세 페이지 (URL에 "/item/", "/product/", "/goods/" 등 포함):
   - 색상/사이즈 등 옵션 선택이 필요하면 optionGroups 또는 interactiveElements에서 SELECT/CLICK한다.
   - "구매하기", "Buy Now", "지금 구매", "장바구니", "Add to Cart" 버튼을 찾아 CLICK한다.
   - 버튼이 보이지 않으면 SCROLL로 아래를 탐색한다.

3. 메인 페이지 / 차단된 페이지 / 에러 페이지:
   - 검색창(input[type=search] 또는 검색 input)을 찾아 targetProduct.title을 INPUT한다.
   - 검색 버튼(돋보기, "검색", "Search")을 찾아 CLICK한다.
   - 검색창도 없으면 SCROLL로 탐색한다.

4. navigationStrategy가 "SEARCH"인 경우 (예: 네이버):
   - 상품 URL로 직접 이동하지 않는다.
   - 반드시 검색 결과 페이지 또는 메인 검색창을 통해 상품을 탐색한다.
   - 검색 결과에서 targetProduct.title과 가장 유사한 상품 링크를 클릭한다.

[공통 규칙]
- target은 반드시 interactiveElements 또는 optionGroups 안에서만 선택한다.
- 결제 확정, 주문 제출, 결제 버튼은 절대 클릭하지 않는다.
- 확신할 수 없으면 WAIT를 반환한다."""


def _build_search_navigator_system_prompt() -> str:
    """검색 결과 페이지 전문 AI 프롬프트.

    역할: 검색 결과 목록에서 targetProduct와 가장 일치하는 상품 링크를 찾아 클릭한다.
    이 AI는 오직 검색 결과 탐색과 상품 링크 클릭만 담당한다.
    """
    return """너는 쇼핑몰 검색 결과 페이지 전문 탐색 AI다.
네 유일한 임무는 검색 결과에서 targetProduct와 가장 일치하는 상품 링크를 찾아 클릭하는 것이다.

[상품 매칭 기준 - 우선순위 순]
1. 브랜드 일치: targetProduct의 brand가 있으면 반드시 동일 브랜드 상품 선택
2. 모델명 일치: line, model 키워드가 상품명에 포함되는지 확인
3. 색상/사이즈 일치: color, size가 상품명이나 옵션에 포함되는지 확인
4. 가격 범위: maxPrice 이하인 상품 우선 (가격 정보가 visible_text_summary에 있는 경우)
5. 광고 상품 회피: 라벨에 "광고", "AD", "Sponsored"가 붙은 상품보다 일반 상품 우선

[행동 순서]
1. interactiveElements에서 상품 링크(role=link 또는 role=a)를 목록화한다.
2. 위 매칭 기준으로 가장 적합한 상품 1개를 선택해 CLICK한다.
3. 스크롤해도 적합한 상품이 없으면 SCROLL로 더 탐색한다.
4. 페이지에 검색창이 있고 현재 검색어가 부정확하다면 targetProduct.title로 INPUT 후 검색 버튼 CLICK한다.

[절대 금지]
- 구매하기, Buy Now, 장바구니 버튼 클릭 금지 (검색 결과 페이지 임무가 아님)
- target은 반드시 interactiveElements 또는 optionGroups에서만 선택한다.
- 확신할 수 없으면 WAIT를 반환한다."""


def _build_catalog_navigator_system_prompt(product_name: str | None = None, price: int | None = None) -> str:
    """카탈로그 페이지 전문 AI 프롬프트.

    역할: 네이버 쇼핑 카탈로그 페이지(여러 판매처 비교)에서
          targetProduct의 가격과 일치하는 판매처의 구매 링크 URL을 추출해 NAVIGATE한다.
    """

    target_info = ""
    if product_name or price:
        target_info = f"\n[찾아야 할 상품]\n- 상품명: {product_name or '알 수 없음'}\n- 목표가격: {f'{price:,}원' if price else '알 수 없음'}\n"
    return target_info + """너는 네이버 쇼핑 카탈로그 페이지 전문 탐색 AI다.
카탈로그 페이지는 동일 상품을 여러 판매처가 각기 다른 가격에 판매하는 비교 페이지다.
네 임무는 rawHtml에서 목표가격과 정확히 일치하는 판매처의 adcr 링크 URL을 찾아 NAVIGATE로 이동하는 것이다.

[판매처 링크 추출 절차 - 반드시 이 순서대로 실행]
1. rawHtml에서 목표가격(예: "139,000원", "139000")과 정확히 일치하는 가격 텍스트를 먼저 찾는다.
2. 그 가격 텍스트와 가장 가까이 위치한 <a href="https://cr.shopping.naver.com/adcr?..."> 태그를 찾는다.
3. 해당 href 전체 URL을 value에 넣고 action=NAVIGATE로 반환한다.
4. target은 null, value에 adcr URL 전체를 담는다.

[가격 매칭 규칙 - 엄격히 준수]
- 목표가격과 정확히 일치하는 판매처만 선택한다.
- "가장 근접한" 가격이 아니라 반드시 "완전히 동일한" 가격이어야 한다.
- 예: 목표가격 139,000원 → 139,000원인 판매처만 선택, 140,000원 판매처는 절대 선택하지 않는다.
- 정확히 일치하는 가격의 판매처가 없으면 WAIT를 반환한다.

[절대 금지]
- search.shopping.naver.com/catalog/ URL 반환 금지 (카탈로그 내부 URL)
- brand.naver.com URL 반환 금지 (브랜드 스토어 메인)
- shopping.naver.com/home URL 반환 금지 (쇼핑 메인)
- 가격이 불일치하는 판매처 선택 금지

[반환 형식]
action=NAVIGATE, value=<정확히 일치하는 가격의 판매처 adcr URL 전체>, target=null"""


def _build_purchase_executor_system_prompt() -> str:
    """상품 상세 페이지 전문 AI 프롬프트.

    역할: 상품 상세 페이지에서 옵션(색상/사이즈)을 선택하고 구매 버튼을 클릭한다.
    이 AI는 오직 옵션 선택과 구매 버튼 클릭만 담당한다.
    rawHtml에서 직접 버튼 CSS selector를 추출해 반환한다.
    """
    return """너는 쇼핑몰 상품 상세 페이지 구매 실행 전문 AI다.
네 임무는 targetProduct의 옵션(색상/사이즈 등)을 선택하고 구매 버튼을 클릭하는 것이다.

[버튼 탐색 방법 - rawHtml 우선]
rawHtml이 제공된 경우, 반드시 rawHtml을 직접 분석하여 구매 버튼의 CSS selector를 추출한다.
interactiveElements의 nodeId/labelText는 .blind 처리된 버튼을 누락할 수 있으므로 rawHtml을 우선한다.

rawHtml에서 버튼을 찾는 방법:
1. "구매하기", "바로구매", "Buy Now", "지금 구매", "장바구니", "Add to Cart" 텍스트가 포함된 <button>, <a>, <span class="..."> 요소를 찾는다.
2. 해당 요소의 CSS selector를 반드시 target.selector에 넣어야 한다. (절대 null 금지)
   - id가 있으면: "#buyNow", "#purchaseBtn"
   - class가 있으면: "button.buyBtn", "a.buy-now-btn"
   - data 속성이 있으면: "button[data-nclick*='buy']", "a[data-log-click*='purchase']"
   - 형제 순서: "ul.seller-list li:first-child button"
   구매 버튼이 rawHtml에 존재하는 한 반드시 selector를 추출할 수 있다.
3. target.node_id와 target.label_text는 null로 설정해도 된다.
   (content script가 selector로 document.querySelector()를 실행해 직접 클릭)

⚠️ 중요: 구매 버튼을 확인했다면 target.selector는 반드시 비어있지 않은 문자열이어야 한다.
   selector=null로 반환하면 시스템이 버튼을 클릭할 수 없어 무한 루프에 빠진다.

[행동 순서]
1. 옵션 확인: optionGroups 또는 interactiveElements에서 미선택된 필수 옵션을 확인한다.
   - targetProduct의 color → 색상 옵션 SELECT/CLICK
   - targetProduct의 size → 사이즈 옵션 SELECT/CLICK
   - targetProduct의 model → 모델/용량 옵션 SELECT/CLICK
2. 모든 필수 옵션 선택 완료 후 구매 버튼을 찾아 CLICK한다.
   - rawHtml에서 버튼 selector 추출 → target.selector에 반환
   - rawHtml이 없으면 interactiveElements에서 labelText로 탐색
3. 버튼이 화면에 없으면 SCROLL로 아래를 탐색한다.
4. 옵션 선택이 완전히 완료되고 더 이상 할 일이 없으면 COMPLETE를 반환한다.

[절대 금지]
- "결제하기", "주문하기", "결제 완료", "Pay Now", "주문완료", "결제" 등 최종 결제 버튼 클릭 절대 금지.
  (구매하기/Add to Cart까지만 허용. 결제 버튼은 사용자 최종 확인 단계임)
- 아직 미선택 옵션이 있는 상태에서 구매 버튼 클릭 금지.
- 확신할 수 없으면 WAIT를 반환한다."""


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
        if request and request.target_product:
            product_name = request.target_product.get("title")
            price = request.target_product.get("price")
        return _build_catalog_navigator_system_prompt(product_name, price)
    return _build_dom_planner_system_prompt()


def _build_dom_planner_user_prompt(request: DomPlannerRequest) -> str:
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
    # CATALOG_NAVIGATOR / PURCHASE_EXECUTOR는 rawHtml을 직접 포함해서
    # 전처리 없이 AI가 판매처 링크 또는 구매버튼을 직접 추출하게 한다
    if request.agent_type in ("CATALOG_NAVIGATOR", "PURCHASE_EXECUTOR") and request.raw_html:
        payload["rawHtml"] = request.raw_html
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
        response = await client.post(
            f"{base_url}/chat/completions",
            json=request_body,
            headers=headers,
        )
        # 429 Rate Limit: 별도 처리 (uvicorn 크래시 방지)
        if response.status_code == 429:
            retry_after = response.headers.get("Retry-After", "60")
            logger.warning("[plan_dom_action] OpenAI rate limit (429) - Retry-After=%s", retry_after)
            raise ValueError(f"OpenAI API rate limit 초과 (429). {retry_after}초 후 재시도 가능합니다.")
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
