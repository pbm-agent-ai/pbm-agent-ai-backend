"""AliExpress Affiliate API 호출 서비스 - 외부 API 통신 및 응답 정규화 담당

기본적으로 실제 AliExpress Affiliate API를 호출한다.
ALIEXPRESS_MOCK_ENABLED=true 환경변수 설정 시 실제 API 호출 없이 고정된 모킹 데이터를 반환한다.
로컬 개발 및 CI 환경에서 AliExpress API 자격증명 없이도 동작 검증이 가능하다 (필요 시에만 활성화).

서명 규칙:
- 엔드포인트: https://api-sg.aliexpress.com/sync (기본값, 환경변수로 변경 가능)
- method 파라미터 방식 사용
- sign_method: hmac-sha256
- timestamp: GMT+8 yyyy-MM-dd HH:mm:ss
- sign: sign 제외 파라미터를 key 오름차순으로 key+value 이어붙여 HMAC-SHA256(app_secret) 후 대문자 HEX
"""

from __future__ import annotations

import hashlib
import hmac
import logging
import os
from datetime import datetime, timedelta, timezone
from typing import Optional

import httpx

from app.schemas.aliexpress import (
    AliexpressCategoryItem,
    AliexpressCategoryResponse,
    AliexpressProductDetailResponse,
    AliexpressProductItem,
    AliexpressSearchResponse,
)

logger = logging.getLogger(__name__)

# AliExpress Affiliate API 기본 URL
ALIEXPRESS_API_BASE_URL = "https://api-sg.aliexpress.com/sync"


def _is_mock_enabled() -> bool:
    """모킹 모드 여부를 환경변수에서 실시간으로 확인한다.

    모듈 로드 시점이 아닌 호출 시점에 평가하여,
    테스트에서 패치하거나 런타임에 환경변수를 변경할 수 있도록 한다.
    """
    return os.getenv("ALIEXPRESS_MOCK_ENABLED", "false").lower() == "true"


def _get_credentials() -> tuple[str, str]:
    """환경변수에서 AliExpress API 자격증명 조회

    Returns:
        (app_key, app_secret) 튜플

    Raises:
        ValueError: 자격증명이 설정되지 않은 경우
    """
    app_key = os.getenv("ALIEXPRESS_APP_KEY")
    app_secret = os.getenv("ALIEXPRESS_APP_SECRET")
    if not app_key or not app_secret:
        raise ValueError("ALIEXPRESS_APP_KEY, ALIEXPRESS_APP_SECRET 환경변수가 필요합니다")
    return app_key, app_secret


def _build_signed_params(method: str, extra_params: dict[str, str] | None = None) -> dict[str, str]:
    """AliExpress Affiliate API 요청 파라미터에 서명을 추가한다.

    서명 규칙:
    1. 공통 파라미터(app_key, method, timestamp, sign_method, format, v) 구성
    2. extra_params 병합
    3. sign 제외 파라미터를 key 오름차순으로 key+value 이어붙임
    4. HMAC-SHA256(app_secret) 해시 후 대문자 HEX 문자열 생성

    Args:
        method: API 메서드명 (예: aliexpress.affiliate.category.get)
        extra_params: 추가 파라미터 (선택)

    Returns:
        서명이 포함된 전체 파라미터 딕셔너리
    """
    app_key, app_secret = _get_credentials()

    # GMT+8 타임스탬프 생성
    timestamp = datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S")

    params: dict[str, str] = {
        "app_key": app_key,
        "method": method,
        "timestamp": timestamp,
        "sign_method": "hmac-sha256",
        "format": "json",
        "v": "2.0",
    }

    # 추가 파라미터 병합
    if extra_params:
        for key, value in extra_params.items():
            if value is not None:
                params[key] = str(value)

    # 서명 생성: sign 제외 파라미터를 key 오름차순으로 key+value 이어붙임
    sign_source = "".join(f"{key}{params[key]}" for key in sorted(params))
    sign = hmac.new(
        app_secret.encode("utf-8"),
        sign_source.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest().upper()

    params["sign"] = sign
    return params


def _extract_error(payload: dict) -> tuple[str | None, str | None]:
    """응답 본문에서 에러 코드/메시지를 추출한다.

    AliExpress API는 다양한 에러 응답 형식을 사용하므로
    여러 위치에서 에러 정보를 찾는다.

    Args:
        payload: API 응답 JSON 딕셔너리

    Returns:
        (error_code, error_message) 튜플. 에러가 없으면 (None, None)
    """
    if not isinstance(payload, dict):
        return None, None

    error_code = payload.get("error_code") or payload.get("code")
    error_message = payload.get("error_message") or payload.get("msg") or payload.get("sub_msg")

    error_response = payload.get("error_response")
    if isinstance(error_response, dict):
        error_code = error_code or error_response.get("code")
        error_message = error_message or error_response.get("msg") or error_response.get("sub_msg")

    return error_code, error_message


def _normalize_categories(raw_categories: list[dict]) -> list[AliexpressCategoryItem]:
    """AliExpress API 원본 카테고리 응답을 정규화된 스키마로 변환

    Args:
        raw_categories: API 응답의 category 리스트

    Returns:
        정규화된 카테고리 항목 리스트
    """
    normalized = []
    for item in raw_categories:
        normalized.append(AliexpressCategoryItem(
            category_id=str(item.get("category_id", "")),
            category_name=item.get("category_name", ""),
            parent_category_id=str(item.get("parent_category_id", "")),
        ))
    return normalized


def _normalize_products(raw_products: list[dict]) -> list[AliexpressProductItem]:
    """AliExpress API 원본 상품 응답을 정규화된 스키마로 변환

    상품 데이터는 다양한 필드명으로 올 수 있어 안전하게 .get()으로 처리한다.

    Args:
        raw_products: API 응답의 product 리스트

    Returns:
        정규화된 상품 항목 리스트
    """
    normalized = []
    for item in raw_products:
        # shop_name: 원본 필드명 그대로 사용, 없으면 빈 문자열
        shop_name = item.get("shop_name", "")
        normalized.append(AliexpressProductItem(
            product_id=str(item.get("product_id", "")),
            product_title=item.get("product_title", ""),
            product_detail_url=item.get("product_detail_url", ""),
            product_main_image_url=item.get("product_main_image_url", ""),
            sale_price=str(item.get("sale_price", "")),
            target_sale_price=str(item.get("target_sale_price", "")),
            target_original_price=str(item.get("target_original_price", "")),
            target_app_sale_price=str(item.get("target_app_sale_price", "")),
            target_app_original_price=str(item.get("target_app_original_price", "")),
            discount=str(item.get("discount", "")),
            evaluate_rate=str(item.get("evaluate_rate", "")),
            commission_rate=str(item.get("commission_rate", "")),
            lastest_volume=str(item.get("lastest_volume", "")),
            shop_name=shop_name,
            shop_url=item.get("shop_url", ""),
            first_level_category_id=str(item.get("first_level_category_id", "")),
            first_level_category_name=str(item.get("first_level_category_name", "")),
            second_level_category_id=str(item.get("second_level_category_id", "")),
            second_level_category_name=str(item.get("second_level_category_name", "")),
        ))
    return normalized


# --- 모킹용 고정 데이터 ---

_MOCK_CATEGORIES: list[dict] = [
    {"category_id": "200000345", "category_name": "전자제품", "parent_category_id": "0"},
    {"category_id": "200000346", "category_name": "컴퓨터 및 네트워킹", "parent_category_id": "0"},
    {"category_id": "200000347", "category_name": "의류 및 액세서리", "parent_category_id": "0"},
    {"category_id": "200000348", "category_name": "가전제품", "parent_category_id": "0"},
    {"category_id": "200000349", "category_name": "가구", "parent_category_id": "0"},
]

_MOCK_PRODUCTS: list[dict] = [
    {
        "product_id": "1005006212345678",
        "product_title": "무선 블루투스 이어폰 TWS 노이즈캔슬링",
        "product_detail_url": "https://www.aliexpress.com/item/1005006212345678.html",
        "product_main_image_url": "https://ae01.alicdn.com/kf/mock-earbuds.jpg",
        "sale_price": "15.99",
        "target_sale_price": "21500",
        "target_original_price": "28000",
        "target_app_sale_price": "19900",
        "target_app_original_price": "26000",
        "discount": "7%",
        "evaluate_rate": "4.8",
        "commission_rate": "5.0%",
        "lastest_volume": "2500",
        "shop_name": "TechGadget Store",
        "shop_url": "https://www.aliexpress.com/store/912345678",
        "first_level_category_id": "200000345",
        "first_level_category_name": "Consumer Electronics",
        "second_level_category_id": "200001234",
        "second_level_category_name": "Earphones & Headphones",
    },
    {
        "product_id": "1005005890123456",
        "product_title": "USB C 허브 7포트 멀티 어댑터",
        "product_detail_url": "https://www.aliexpress.com/item/1005005890123456.html",
        "product_main_image_url": "https://ae01.alicdn.com/kf/mock-hub.jpg",
        "sale_price": "22.50",
        "target_sale_price": "32000",
        "target_original_price": "38000",
        "target_app_sale_price": "29900",
        "target_app_original_price": "35000",
        "discount": "6%",
        "evaluate_rate": "4.6",
        "commission_rate": "3.5%",
        "lastest_volume": "1800",
        "shop_name": "DigitalLife Official",
        "shop_url": "https://www.aliexpress.com/store/923456789",
        "first_level_category_id": "200000345",
        "first_level_category_name": "Consumer Electronics",
        "second_level_category_id": "200001235",
        "second_level_category_name": "USB Hubs & Adapters",
    },
    {
        "product_id": "1005004567890123",
        "product_title": "기계식 키보드 RGB 백라이트 87키",
        "product_detail_url": "https://www.aliexpress.com/item/1005004567890123.html",
        "product_main_image_url": "https://ae01.alicdn.com/kf/mock-keyboard.jpg",
        "sale_price": "35.00",
        "target_sale_price": "49000",
        "target_original_price": "58000",
        "target_app_sale_price": "45000",
        "target_app_original_price": "55000",
        "discount": "8%",
        "evaluate_rate": "4.9",
        "commission_rate": "4.0%",
        "lastest_volume": "3200",
        "shop_name": "KeyboardWorld",
        "shop_url": "https://www.aliexpress.com/store/934567890",
        "first_level_category_id": "200000346",
        "first_level_category_name": "Computer & Office",
        "second_level_category_id": "200001236",
        "second_level_category_name": "Keyboards & Mice",
    },
]


async def _get_affiliate_categories_mock() -> AliexpressCategoryResponse:
    """모킹 모드: 고정된 카테고리 데이터를 반환한다.

    실제 AliExpress API 호출 없이 로컬 개발/CI 환경에서 동작 검증이 가능하다.
    """
    logger.info("모킹 모드 활성화 - 고정 카테고리 데이터 반환")
    items = _normalize_categories(_MOCK_CATEGORIES)
    return AliexpressCategoryResponse(total=len(items), items=items)


async def _search_affiliate_products_mock(
    keyword: str,
    page_no: int = 1,
    page_size: int = 10,
    sort: str | None = None,
    target_currency: str = "KRW",
    target_language: str = "KO",
    ship_to_country: str = "KR",
    category_ids: str | None = None,
    tracking_id: str | None = None,
) -> AliexpressSearchResponse:
    """모킹 모드: 고정된 검색 결과를 반환한다.

    키워드와 무관하게 동일한 고정 데이터를 반환하며,
    page_no/page_size 파라미터에 따라 결과 개수와 시작 위치를 조정한다.
    """
    logger.info("모킹 모드 활성화 - 고정 검색 데이터 반환 (키워드: %s)", keyword)

    # page_no, page_size 파라미터에 맞춰 슬라이싱
    start_index = (page_no - 1) * page_size
    end_index = min(start_index + page_size, len(_MOCK_PRODUCTS))
    sliced_products = _MOCK_PRODUCTS[start_index:end_index]

    items = _normalize_products(sliced_products)
    return AliexpressSearchResponse(
        total=len(_MOCK_PRODUCTS),
        page_no=page_no,
        page_size=page_size,
        items=items,
    )


async def _get_affiliate_product_detail_mock(
    product_id: str,
    arget_currency: str = "KRW",
    target_language: str = "KO",
    ship_to_country: str = "KR",) -> AliexpressProductDetailResponse:
    """모킹 모드: _MOCK_PRODUCTS에서 product_id와 일치하는 상품을 반환한다.

    Args:
        product_id: 조회할 상품 ID

    Returns:
        일치하는 상품이 있으면 AliexpressProductDetailResponse, 없으면 product=None
    """
    logger.info("모킹 모드 활성화 - 상품 단건 조회 모킹 (product_id: %s)", product_id)
    for raw in _MOCK_PRODUCTS:
        if raw.get("product_id") == product_id:
            items = _normalize_products([raw])
            return AliexpressProductDetailResponse(product=items[0])
    return AliexpressProductDetailResponse(product=None)


async def get_affiliate_product_detail(
    product_id: str,
    target_currency: str = "KRW",
    target_language: str = "KO",
    ship_to_country: str = "KR",
    ) -> AliexpressProductDetailResponse:
    """AliExpress Affiliate 상품 단건 상세 조회 API 호출 및 정규화된 응답 반환

    공식 메서드: aliexpress.affiliate.productdetail.get

    ALIEXPRESS_MOCK_ENABLED=true인 경우 실제 API 호출 없이 모킹 데이터를 반환한다.

    Args:
        product_id: 조회할 상품 ID

    Returns:
        AliexpressProductDetailResponse: 정규화된 상품 상세 정보

    Raises:
        ValueError: API 에러 응답 또는 자격증명 누락 시
    """
    if _is_mock_enabled():
        return await _get_affiliate_product_detail_mock(
        product_id,
        target_currency,
        target_language,
        ship_to_country,)

    # extra_params는 알리 API에 보낼 요청값 묶음임.
    extra_params: dict[str, str] = {
        "product_ids": product_id,
        "target_currency": target_currency,
        "target_language": target_language,
        "country": ship_to_country,
    }
    # 이 값들을 받아서 공통 파라미터랑 합치고, 서명도 만들고, 최종 요청 파라미터를 완성한다.
    params = _build_signed_params(method="aliexpress.affiliate.productdetail.get", extra_params=extra_params)

    base_url = os.getenv("ALIEXPRESS_BASE_URL", ALIEXPRESS_API_BASE_URL)

    # client.post가 HTTP request를 만들고 그 request를 전송함
    # 응답을 받아서 response에 담음
    # request = ..., response = ... 이런식으로 분리할 수 있지만 단순 호출이라 post()로 한번에 처리한 모습임
    """
    request = client.build_request(
    "POST",
    base_url,
    data=params,
    headers={"Content-Type": "application/x-www-form-urlencoded;charset=utf-8"},
    )
    response = await client.send(request)
    이렇게 변경 가능
    """
    async with httpx.AsyncClient(timeout=15.0) as client:
        response = await client.post(
            base_url,
            data=params,
            headers={"Content-Type": "application/x-www-form-urlencoded;charset=utf-8"},
        )
        response.raise_for_status()

    data = response.json()

    # 에러 응답 확인
    error_code, error_message = _extract_error(data)
    if error_code:
        raise ValueError(f"AliExpress API 에러: code={error_code}, message={error_message}")

    # 상품 상세 응답 파싱: aliexpress_affiliate_productdetail_get_response > resp_result > result > products > product
    resp_result = data.get(
        "aliexpress_affiliate_productdetail_get_response",
        data.get("resp_result", {}),
    )
    if isinstance(resp_result, dict) and "resp_result" in resp_result:
        resp_result = resp_result["resp_result"]

    result = resp_result.get("result", {}) if isinstance(resp_result, dict) else {}

    products_data = result.get("products", result) if isinstance(result, dict) else {}
    if isinstance(products_data, dict):
        raw_products = products_data.get("product", [])
    elif isinstance(products_data, list):
        raw_products = products_data
    else:
        raw_products = []

    if raw_products:
        items = _normalize_products(raw_products)
        return AliexpressProductDetailResponse(product=items[0])

    return AliexpressProductDetailResponse(product=None)


async def get_affiliate_categories() -> AliexpressCategoryResponse:
    """AliExpress Affiliate 카테고리 조회 API 호출 및 정규화된 응답 반환

    ALIEXPRESS_MOCK_ENABLED=true인 경우 실제 API 호출 없이 모킹 데이터를 반환한다.

    Returns:
        AliexpressCategoryResponse: 정규화된 카테고리 목록

    Raises:
        ValueError: API 에러 응답 또는 자격증명 누락 시
    """
    if _is_mock_enabled():
        return await _get_affiliate_categories_mock()

    params = _build_signed_params(method="aliexpress.affiliate.category.get")

    base_url = os.getenv("ALIEXPRESS_BASE_URL", ALIEXPRESS_API_BASE_URL)

    async with httpx.AsyncClient(timeout=15.0) as client:
        response = await client.post(
            base_url,
            data=params,
            headers={"Content-Type": "application/x-www-form-urlencoded;charset=utf-8"},
        )
        response.raise_for_status()

    data = response.json()

    # 에러 응답 확인
    error_code, error_message = _extract_error(data)
    if error_code:
        raise ValueError(f"AliExpress API 에러: code={error_code}, message={error_message}")

    # 카테고리 응답 파싱: ...resp_result.result.categories.category 경로
    resp_result = data.get("aliexpress_affiliate_category_get_response", data.get("resp_result", {}))
    # 1. instance함수를 통해 resp_result가 딕셔너리인지 확인
    # 2. 딕셔너리가 맞으면 그 안에 "resp_result"키가 있는지 확인
    if isinstance(resp_result, dict) and "resp_result" in resp_result:
        resp_result = resp_result["resp_result"]

    result = resp_result.get("result", {}) if isinstance(resp_result, dict) else {}
    categories_data = result.get("categories", {}) if isinstance(result, dict) else {}
    raw_categories = categories_data.get("category", []) if isinstance(categories_data, dict) else []

    # total: total_record_count 우선, 없으면 items 길이
    total = result.get("total_record_count", result.get("total_result_count", len(raw_categories)))

    items = _normalize_categories(raw_categories)
    return AliexpressCategoryResponse(total=total, items=items)


async def search_affiliate_products(
    keyword: str,
    page_no: int = 1,
    page_size: int = 10,
    sort: str | None = None,
    target_currency: str = "KRW",
    target_language: str = "KO",
    ship_to_country: str = "KR",
    category_ids: str | None = None,
    tracking_id: str | None = None,
) -> AliexpressSearchResponse:
    """AliExpress Affiliate 상품 검색 API 호출 및 정규화된 응답 반환

    ALIEXPRESS_MOCK_ENABLED=true인 경우 실제 API 호출 없이 모킹 데이터를 반환한다.

    Args:
        keyword: 검색 키워드 (AliExpress API 전송 시 'keywords'로 매핑)
        page_no: 페이지 번호 (기본 1)
        page_size: 페이지당 결과 수 (기본 10, 최대 50)
        sort: 정렬 기준 (SALE_PRICE_ASC, SALE_PRICE_DESC, LAST_VOLUME_ASC, LAST_VOLUME_DESC)
        target_currency: 타겟 통화 (기본 KRW)
        target_language: 타겟 언어 (기본 KO)
        ship_to_country: 배송 국가 (기본 KR)
        tracking_id: 트래킹 ID (선택)

    Returns:
        AliexpressSearchResponse: 정규화된 검색 결과

    Raises:
        ValueError: API 에러 응답, 자격증명 누락, 또는 잘못된 파라미터 시
    """
    if _is_mock_enabled():
        return await _search_affiliate_products_mock(
            keyword=keyword,
            page_no=page_no,
            page_size=page_size,
            sort=sort,
            target_currency=target_currency,
            target_language=target_language,
            ship_to_country=ship_to_country,
            category_ids=category_ids,
            tracking_id=tracking_id,
        )

    # page_size 최대 50 제한
    page_size = min(page_size, 50)

    # 검색 파라미터 구성 (keyword → keywords 매핑)
    extra_params: dict[str, str] = {
        "keywords": keyword,
        "page_no": str(page_no),
        "page_size": str(page_size),
        "target_currency": target_currency,
        "target_language": target_language,
        "ship_to_country": ship_to_country,
    }

    if sort:
        allowed_sorts = {"SALE_PRICE_ASC", "SALE_PRICE_DESC", "LAST_VOLUME_ASC", "LAST_VOLUME_DESC"}
        if sort not in allowed_sorts:
            raise ValueError(f"지원하지 않는 정렬값: {sort}. 허용값: {', '.join(allowed_sorts)}")
        extra_params["sort"] = sort

    if tracking_id:
        extra_params["tracking_id"] = tracking_id

    if category_ids:
        extra_params["category_ids"] = category_ids

    params = _build_signed_params(method="aliexpress.affiliate.product.query", extra_params=extra_params)

    base_url = os.getenv("ALIEXPRESS_BASE_URL", ALIEXPRESS_API_BASE_URL)

    async with httpx.AsyncClient(timeout=15.0) as client:
        response = await client.post(
            base_url,
            data=params,
            headers={"Content-Type": "application/x-www-form-urlencoded;charset=utf-8"},
        )
        response.raise_for_status()

    data = response.json()

    # 에러 응답 확인
    error_code, error_message = _extract_error(data)
    if error_code:
        raise ValueError(f"AliExpress API 에러: code={error_code}, message={error_message}")

    # 상품 응답 파싱: ...resp_result.result.products 또는 {product:[...]} 형태 모두 처리
    resp_result = data.get("aliexpress_affiliate_product_query_response", data.get("resp_result", {}))
    if isinstance(resp_result, dict) and "resp_result" in resp_result:
        resp_result = resp_result["resp_result"]

    result = resp_result.get("result", {}) if isinstance(resp_result, dict) else {}

    # products 데이터 추출: products.product 또는 직접 product 리스트
    products_data = result.get("products", result) if isinstance(result, dict) else {}
    if isinstance(products_data, dict):
        raw_products = products_data.get("product", [])
    elif isinstance(products_data, list):
        raw_products = products_data
    else:
        raw_products = []

    # total: total_record_count 또는 total_result_count 우선, 없으면 items 길이
    total = result.get("total_record_count", result.get("total_result_count", len(raw_products)))

    items = _normalize_products(raw_products)
    return AliexpressSearchResponse(
        total=total,
        page_no=page_no,
        page_size=page_size,
        items=items,
    )
