"""네이버 쇼핑 검색 라우터 및 서비스 테스트

- 외부 API 호출은 모킹하여 처리
- NAVER_MOCK_ENABLED 모드의 동작도 검증
"""

from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.schemas.naver import NaverSearchResponse, NaverShoppingItem
from app.services.naver_service import (
    _normalize_items,
    _search_shopping_mock,
    _is_mock_enabled,
    search_shopping,
)

client = TestClient(app)


# --- 헬스체크 테스트 ---

def test_health_check():
    """헬스체크 엔드포인트가 정상 응답하는지 확인"""
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert data["service"] == "external-api-service"
    assert "mock_mode" in data


# --- 네이버 쇼핑 검색 라우터 테스트 ---

# 네이버 API 모킹용 가짜 응답
FAKE_NAVER_RESPONSE = {
    "total": 2,
    "start": 1,
    "display": 10,
    "items": [
        {
            "title": "테스트<b>상품</b> 1",
            "lprice": "15000",
            "hprice": "20000",
            "mallName": "테스트쇼핑",
            "link": "https://test.com/product/1",
            "productId": "12345",
            "image": "https://img.test.com/1.jpg",
            "maker": "테스트제조사",
            "brand": "테스트브랜드",
            "category1": "디지털/가전",
            "category2": "컴퓨터",
            "category3": "노트북",
            "category4": "",
        },
        {
            "title": "테스트<b>상품</b> 2",
            "lprice": "25000",
            "hprice": "",
            "mallName": "다른쇼핑",
            "link": "https://other.com/product/2",
            "productId": "67890",
            "image": "",
            "maker": "",
            "brand": "",
            "category1": "",
            "category2": "",
            "category3": "",
            "category4": "",
        },
    ],
}


@patch("app.routers.naver.search_shopping", new_callable=AsyncMock)
def test_search_naver_shopping_success(mock_search):
    """네이버 쇼핑 검색이 정상적으로 정규화된 응답을 반환하는지 확인"""
    # 모킹된 서비스가 반환할 정규화된 응답 준비
    mock_items = [
        NaverShoppingItem(
            title="테스트상품 1",
            lprice="15000",
            hprice="20000",
            mallName="테스트쇼핑",
            link="https://test.com/product/1",
            productId="12345",
            image="https://img.test.com/1.jpg",
            maker="테스트제조사",
            brand="테스트브랜드",
            category1="디지털/가전",
            category2="컴퓨터",
            category3="노트북",
            category4="",
        ),
        NaverShoppingItem(
            title="테스트상품 2",
            lprice="25000",
            hprice="",
            mallName="다른쇼핑",
            link="https://other.com/product/2",
            productId="67890",
            image="",
            maker="",
            brand="",
            category1="",
            category2="",
            category3="",
            category4="",
        ),
    ]
    mock_search.return_value = NaverSearchResponse(
        total=2, start=1, display=10, items=mock_items
    )

    response = client.get("/api/v1/naver/search?keyword=노트북&display=10")
    assert response.status_code == 200

    data = response.json()
    assert data["total"] == 2
    assert data["display"] == 10
    assert len(data["items"]) == 2

    # 첫 번째 상품 검증
    item1 = data["items"][0]
    assert item1["title"] == "테스트상품 1"
    assert item1["lprice"] == "15000"
    assert item1["hprice"] == "20000"
    assert item1["mallName"] == "테스트쇼핑"
    assert item1["link"] == "https://test.com/product/1"

    # 두 번째 상품 검증 (빈 필드 포함)
    item2 = data["items"][1]
    assert item2["lprice"] == "25000"
    assert item2["hprice"] == ""
    assert item2["mallName"] == "다른쇼핑"


@patch("app.routers.naver.search_shopping", new_callable=AsyncMock)
def test_search_naver_shopping_with_default_params(mock_search):
    """display, start 파라미터 기본값이 올바르게 전달되는지 확인"""
    mock_search.return_value = NaverSearchResponse(
        total=0, start=1, display=10, items=[]
    )

    response = client.get("/api/v1/naver/search?keyword=아이폰")
    assert response.status_code == 200

    # 기본값 검증: display=10, start=1
    mock_search.assert_called_once_with(keyword="아이폰", display=10, start=1)


@patch("app.routers.naver.search_shopping", new_callable=AsyncMock)
def test_search_naver_shopping_custom_display(mock_search):
    """display 파라미터 커스텀 값이 올바르게 전달되는지 확인"""
    mock_search.return_value = NaverSearchResponse(
        total=0, start=1, display=50, items=[]
    )

    response = client.get("/api/v1/naver/search?keyword=갤럭시&display=50&start=5")
    assert response.status_code == 200

    mock_search.assert_called_once_with(keyword="갤럭시", display=50, start=5)


def test_search_naver_shopping_missing_keyword():
    """keyword 파라미터 누락 시 422 에러 반환 확인"""
    response = client.get("/api/v1/naver/search")
    assert response.status_code == 422


@patch("app.routers.naver.search_shopping", new_callable=AsyncMock)
def test_search_naver_shopping_empty_results(mock_search):
    """검색 결과가 없는 경우 빈 items 리스트 반환 확인"""
    mock_search.return_value = NaverSearchResponse(
        total=0, start=1, display=10, items=[]
    )

    response = client.get("/api/v1/naver/search?keyword=존재하지않는상품12345")
    assert response.status_code == 200

    data = response.json()
    assert data["total"] == 0
    assert data["items"] == []


# --- 서비스 레이어 정규화 테스트 ---

def test_normalize_items_handles_empty_strings():
    """빈 문자열 필드가 정상적으로 처리되는지 확인"""
    raw_items = [
        {
            "title": "상품",
            "lprice": "1000",
            "hprice": "",
            "mallName": "쇼핑몰",
            "link": "https://example.com",
        }
    ]
    result = _normalize_items(raw_items)
    assert len(result) == 1
    assert result[0].title == "상품"
    assert result[0].hprice == ""
    assert result[0].productId == ""


def test_normalize_items_handles_missing_fields():
    """필드가 누락된 원본 응답이 기본값으로 채워지는지 확인"""
    raw_items = [
        {
            "title": "상품",
            "lprice": "5000",
            "mallName": "쇼핑몰",
            "link": "https://example.com",
        }
    ]
    result = _normalize_items(raw_items)
    assert len(result) == 1
    assert result[0].hprice == ""
    assert result[0].brand == ""
    assert result[0].category1 == ""


def test_normalize_items_empty_list():
    """빈 리스트 입력 시 빈 리스트 반환 확인"""
    result = _normalize_items([])
    assert result == []


# --- 모킹 모드 테스트 ---

@pytest.mark.asyncio
async def test_mock_mode_returns_fixed_data():
    """모킹 모드에서 고정된 검색 결과를 반환하는지 확인"""
    result = await _search_shopping_mock(keyword="아무키워드", display=10, start=1)

    assert isinstance(result, NaverSearchResponse)
    assert result.total > 0
    assert len(result.items) > 0
    # 모킹 데이터의 첫 번째 항목 검증
    assert result.items[0].title == "삼성전자 갤럭시 S24 울트라"
    assert result.items[0].lprice == "1199000"
    assert result.items[0].mallName == "삼성스토어"


@pytest.mark.asyncio
async def test_mock_mode_respects_display_param():
    """모킹 모드에서 display 파라미터에 따라 결과 개수가 제한되는지 확인"""
    result = await _search_shopping_mock(keyword="테스트", display=2, start=1)

    assert len(result.items) == 2
    assert result.display == 2


@pytest.mark.asyncio
async def test_mock_mode_respects_start_param():
    """모킹 모드에서 start 파라미터에 따라 결과 시작 위치가 조정되는지 확인"""
    result = await _search_shopping_mock(keyword="테스트", display=10, start=2)

    # start=2이므로 두 번째 항목부터 반환
    assert result.start == 2
    assert result.items[0].title == "애플 아이폰 15 프로 맥스"


@pytest.mark.asyncio
async def test_mock_mode_total_count():
    """모킹 모드에서 total이 전체 모킹 데이터 개수를 반환하는지 확인"""
    result = await _search_shopping_mock(keyword="테스트", display=1, start=1)

    # display=1이어도 total은 전체 개수
    assert result.total == 3
    assert len(result.items) == 1


@patch("app.services.naver_service._is_mock_enabled", return_value=True)
@pytest.mark.asyncio
async def test_search_shopping_delegates_to_mock_when_enabled(mock_check):
    """MOCK_ENABLED=True일 때 search_shopping이 모킹 데이터를 반환하는지 확인"""
    result = await search_shopping(keyword="테스트", display=10, start=1)

    assert isinstance(result, NaverSearchResponse)
    assert result.total > 0
    assert len(result.items) > 0
    # 실제 API 호출 없이 모킹 데이터가 반환됨


@patch("app.services.naver_service._is_mock_enabled", return_value=False)
@patch("app.services.naver_service._get_naver_credentials", side_effect=ValueError("NAVER_CLIENT_ID, NAVER_CLIENT_SECRET 환경변수가 필요합니다"))
@pytest.mark.asyncio
async def test_search_shopping_calls_real_api_when_mock_disabled(mock_creds, mock_check):
    """MOCK_ENABLED=False이고 Naver 자격증명이 없을 때 ValueError 발생 확인"""
    with pytest.raises(ValueError, match="NAVER_CLIENT_ID"):
        await search_shopping(keyword="테스트", display=10, start=1)