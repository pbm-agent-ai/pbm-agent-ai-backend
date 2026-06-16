"""네이버 쇼핑 API 호출 서비스 - 외부 API 통신 및 응답 정규화 담당

기본적으로 실제 네이버 쇼핑 API를 호출한다.
NAVER_MOCK_ENABLED=true 환경변수 설정 시 실제 API 호출 없이 고정된 모킹 데이터를 반환한다.
로컬 개발 및 CI 환경에서 Naver API 자격증명 없이도 동작 검증이 가능하다 (필요 시에만 활성화).
"""

import os
import re
import logging
from typing import Optional

import httpx
from app.schemas.naver import NaverSearchResponse, NaverShoppingItem

logger = logging.getLogger(__name__)

# 네이버 쇼핑 API 기본 URL
NAVER_SHOPPING_API_URL = "https://openapi.naver.com/v1/search/shop.json"


def _is_mock_enabled() -> bool:
    """모킹 모드 여부를 환경변수에서 실시간으로 확인한다.

    모듈 로드 시점이 아닌 호출 시점에 평가하여,
    테스트에서 패치하거나 런타임에 환경변수를 변경할 수 있도록 한다.
    """
    return os.getenv("NAVER_MOCK_ENABLED", "false").lower() == "true"


"""
os.getenv()는 에러를 발생시키지 않는다. 파이썬에서 환경 변수를 가져오는 방법은 2가지가 있다.
- os.environ['key']: 키가 없으면 KeyError 예외를 발생
- os.getenv('key'): 키가 없으면 에러를 발생시키는 대신 None을 반환

만약 environ을 상요한다고 해도 개발자가 실수로 환경변수를 빈 문자열로 설정해 둔 경우 걸러내지 못한다.
"""
def _get_naver_credentials() -> tuple[str, str]:
    """환경변수에서 네이버 API 자격증명 조회"""
    client_id = os.getenv("NAVER_CLIENT_ID")
    client_secret = os.getenv("NAVER_CLIENT_SECRET")
    if not client_id or not client_secret:
        raise ValueError("NAVER_CLIENT_ID, NAVER_CLIENT_SECRET 환경변수가 필요합니다")
    return client_id, client_secret


def _is_catalog_link(link: str | None) -> bool:
    """네이버 카탈로그 페이지 링크인지 확인한다."""
    normalized = (link or "").lower()
    return "/catalog/" in normalized


def _strip_html_tags(text: str) -> str:
    """HTML 태그를 제거한다. 네이버 API는 검색 키워드에 <b> 태그를 붙여 반환한다."""
    return re.sub(r"<[^>]+>", "", text)


def _normalize_items(raw_items: list[dict]) -> list[NaverShoppingItem]:
    """네이버 API 원본 응답을 정규화된 스키마로 변환한다.

    네이버 카탈로그 페이지 링크는 실제 상품 상세 페이지가 아니므로 제외한다.
    """
    normalized = []
    filtered_catalog_count = 0
    for item in raw_items:
        link = item.get("link", "")
        if _is_catalog_link(link):
            filtered_catalog_count += 1
            continue

        normalized.append(NaverShoppingItem(
            title=_strip_html_tags(item.get("title", "")),
            lprice=item.get("lprice", "0"),
            hprice=item.get("hprice", ""),
            mallName=item.get("mallName", ""),
            link=link,
            productId=item.get("productId", ""),
            image=item.get("image", ""),
            maker=item.get("maker", ""),
            brand=item.get("brand", ""),
            category1=item.get("category1", ""),
            category2=item.get("category2", ""),
            category3=item.get("category3", ""),
            category4=item.get("category4", ""),
        ))

    if filtered_catalog_count > 0:
        logger.info("네이버 쇼핑 검색 결과에서 카탈로그 링크 %d건 제외", filtered_catalog_count)

    return normalized


# --- 모킹용 고정 데이터 ---

_MOCK_ITEMS: list[dict] = [
    {
        "title": "삼성전자 갤럭시 S24 울트라",
        "lprice": "1199000",
        "hprice": "1599000",
        "mallName": "삼성스토어",
        "link": "https://mock.test/product/galaxy-s24-ultra",
        "productId": "mock-001",
        "image": "https://mock.test/img/galaxy-s24-ultra.jpg",
        "maker": "삼성전자",
        "brand": "삼성",
        "category1": "디지털/가전",
        "category2": "휴대폰",
        "category3": "스마트폰",
        "category4": "",
    },
    {
        "title": "애플 아이폰 15 프로 맥스",
        "lprice": "1550000",
        "hprice": "1899000",
        "mallName": "애플스토어",
        "link": "https://mock.test/product/iphone-15-pro-max",
        "productId": "mock-002",
        "image": "https://mock.test/img/iphone-15-pro-max.jpg",
        "maker": "애플",
        "brand": "Apple",
        "category1": "디지털/가전",
        "category2": "휴대폰",
        "category3": "스마트폰",
        "category4": "",
    },
    {
        "title": "에어팟 프로 2세대",
        "lprice": "299000",
        "hprice": "359000",
        "mallName": "애플스토어",
        "link": "https://mock.test/product/airpods-pro-2",
        "productId": "mock-003",
        "image": "https://mock.test/img/airpods-pro-2.jpg",
        "maker": "애플",
        "brand": "Apple",
        "category1": "디지털/가전",
        "category2": "이어폰/헤드폰",
        "category3": "무선이어폰",
        "category4": "",
    },
]


async def _search_shopping_mock(keyword: str, display: int, start: int) -> NaverSearchResponse:
    """모킹 모드: 고정된 검색 결과를 반환한다.

    실제 Naver API 호출 없이 로컬 개발/CI 환경에서 동작 검증이 가능하다.
    키워드와 무관하게 동일한 고정 데이터를 반환하며,
    display/start 파라미터에 따라 결과 개수와 시작 위치를 조정한다.
    """
    logger.info("모킹 모드 활성화 - 고정 데이터 반환 (키워드: %s)", keyword)

    # display, start 파라미터에 맞춰 슬라이싱
    end_index = min(start - 1 + display, len(_MOCK_ITEMS))
    sliced_items = _MOCK_ITEMS[start - 1:end_index]

    items = _normalize_items(sliced_items)
    return NaverSearchResponse(
        total=len(_MOCK_ITEMS),
        start=start,
        display=len(items),
        items=items,
    )


async def search_shopping(keyword: str, display: int = 10, start: int = 1) -> NaverSearchResponse:
    """네이버 쇼핑 검색 API 호출 및 정규화된 응답 반환

    NAVER_MOCK_ENABLED=true인 경우 실제 API 호출 없이 모킹 데이터를 반환한다.

    Args:
        keyword: 검색 키워드
        display: 표시할 결과 수 (최대 100)
        start: 검색 시작 위치 (최대 1000)

    Returns:
        NaverSearchResponse: 정규화된 검색 결과
    """
    if _is_mock_enabled():
        return await _search_shopping_mock(keyword=keyword, display=display, start=start)

    client_id, client_secret = _get_naver_credentials()

    headers = {
        "X-Naver-Client-Id": client_id,
        "X-Naver-Client-Secret": client_secret,
    }

    params = {
        "query": keyword,
        "display": min(display, 100),
        "start": min(start, 1000),
    }

    async with httpx.AsyncClient(timeout=10.0) as client:
        response = await client.get(NAVER_SHOPPING_API_URL, headers=headers, params=params)
        response.raise_for_status()

    data = response.json()
    items = _normalize_items(data.get("items", []))

    return NaverSearchResponse(
        total=data.get("total", 0),
        start=data.get("start", start),
        display=data.get("display", display),
        items=items,
    )
