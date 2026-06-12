"""AliExpress Affiliate API 라우터 및 서비스 테스트

- 외부 API 호출은 모킹하여 처리
- ALIEXPRESS_MOCK_ENABLED 모드의 동작도 검증
- 서비스 정규화/모킹 테스트 포함
"""

from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.schemas.aliexpress import (
    AliexpressCategoryItem,
    AliexpressCategoryResponse,
    AliexpressProductDetailResponse,
    AliexpressProductItem,
    AliexpressSearchResponse,
)
from app.services.aliexpress_service import (
    _build_signed_params,
    _build_aliexpress_search_url,
    _extract_error,
    _extract_product_id_from_href,
    _get_affiliate_categories_mock,
    _get_affiliate_product_detail_mock,
    _is_mock_enabled,
    _normalize_categories,
    _normalize_products,
    _search_affiliate_products_mock,
    get_affiliate_categories,
    get_affiliate_product_detail,
    search_affiliate_products,
)

client = TestClient(app)


# --- 헬스체크 테스트 ---


def test_health_check_includes_aliexpress_mock_mode():
    """헬스체크 엔드포인트에 aliexpress mock_mode가 포함되는지 확인"""
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert "mock_mode" in data
    assert "aliexpress" in data["mock_mode"]
    assert "naver" in data["mock_mode"]


# --- AliExpress 카테고리 라우터 테스트 ---


@patch("app.routers.aliexpress.get_affiliate_categories", new_callable=AsyncMock)
def test_get_categories_success(mock_categories):
    """카테고리 조회가 정상적으로 정규화된 응답을 반환하는지 확인"""
    mock_categories.return_value = AliexpressCategoryResponse(
        total=2,
        items=[
            AliexpressCategoryItem(
                category_id="200000345",
                category_name="전자제품",
                parent_category_id="0",
            ),
            AliexpressCategoryItem(
                category_id="200000346",
                category_name="컴퓨터",
                parent_category_id="0",
            ),
        ],
    )

    response = client.get("/api/v1/aliexpress/categories")
    assert response.status_code == 200

    data = response.json()
    assert data["total"] == 2
    assert len(data["items"]) == 2
    assert data["items"][0]["category_id"] == "200000345"
    assert data["items"][0]["category_name"] == "전자제품"


# --- AliExpress 상품 검색 라우터 테스트 ---


@patch("app.routers.aliexpress.search_affiliate_products", new_callable=AsyncMock)
def test_search_products_success(mock_search):
    """상품 검색이 정상적으로 정규화된 응답을 반환하는지 확인"""
    mock_search.return_value = AliexpressSearchResponse(
        total=1,
        page_no=1,
        page_size=10,
        items=[
            AliexpressProductItem(
                product_id="1005006212345678",
                product_title="무선 블루투스 이어폰",
                product_detail_url="https://www.aliexpress.com/item/1005006212345678.html",
                product_main_image_url="https://ae01.alicdn.com/kf/mock.jpg",
                sale_price="15.99",
                target_sale_price="21500",
                target_original_price="28000",
                target_app_sale_price="19900",
                target_app_original_price="26000",
                discount="7%",
                evaluate_rate="4.8",
                commission_rate="5.0%",
                lastest_volume="2500",
                shop_name="TechGadget Store",
                shop_url="https://www.aliexpress.com/store/912345678",
            ),
        ],
    )

    response = client.get("/api/v1/aliexpress/search?keyword=이어폰")
    assert response.status_code == 200

    data = response.json()
    assert data["total"] == 1
    assert data["page_no"] == 1
    assert data["page_size"] == 10
    assert len(data["items"]) == 1
    assert data["items"][0]["product_id"] == "1005006212345678"
    assert data["items"][0]["product_title"] == "무선 블루투스 이어폰"


@patch("app.routers.aliexpress.search_affiliate_products", new_callable=AsyncMock)
def test_search_products_with_default_params(mock_search):
    """검색 파라미터 기본값이 올바르게 전달되는지 확인"""
    mock_search.return_value = AliexpressSearchResponse(
        total=0, page_no=1, page_size=10, items=[]
    )

    response = client.get("/api/v1/aliexpress/search?keyword=아이폰")
    assert response.status_code == 200

    mock_search.assert_called_once_with(
        keyword="아이폰",
        page_no=1,
        page_size=10,
        sort=None,
        target_currency="KRW",
        target_language="KO",
        ship_to_country="KR",
        category_ids=None,
        tracking_id=None,
    )


@patch("app.routers.aliexpress.search_affiliate_products", new_callable=AsyncMock)
def test_search_products_with_custom_params(mock_search):
    """커스텀 검색 파라미터가 올바르게 전달되는지 확인"""
    mock_search.return_value = AliexpressSearchResponse(
        total=0, page_no=2, page_size=20, items=[]
    )

    response = client.get(
        "/api/v1/aliexpress/search?keyword=갤럭시&page_no=2&page_size=20"
        "&sort=SALE_PRICE_ASC&target_currency=USD&target_language=EN"
        "&ship_to_country=US&category_ids=10,20&tracking_id=mytrack123"
    )
    assert response.status_code == 200

    mock_search.assert_called_once_with(
        keyword="갤럭시",
        page_no=2,
        page_size=20,
        sort="SALE_PRICE_ASC",
        target_currency="USD",
        target_language="EN",
        ship_to_country="US",
        category_ids="10,20",
        tracking_id="mytrack123",
    )


def test_search_products_missing_keyword():
    """keyword 파라미터 누락 시 422 에러 반환 확인"""
    response = client.get("/api/v1/aliexpress/search")
    assert response.status_code == 422


def test_search_products_page_size_over_50():
    """page_size가 50 초과 시 422 에러 반환 확인"""
    response = client.get("/api/v1/aliexpress/search?keyword=테스트&page_size=51")
    assert response.status_code == 422


@patch("app.routers.aliexpress.search_affiliate_products", new_callable=AsyncMock)
def test_search_products_empty_results(mock_search):
    """검색 결과가 없는 경우 빈 items 리스트 반환 확인"""
    mock_search.return_value = AliexpressSearchResponse(
        total=0, page_no=1, page_size=10, items=[]
    )

    response = client.get("/api/v1/aliexpress/search?keyword=존재하지않는상품99999")
    assert response.status_code == 200

    data = response.json()
    assert data["total"] == 0
    assert data["items"] == []


# --- AliExpress 상품 단건 조회 라우터 테스트 ---


@patch("app.routers.aliexpress.get_affiliate_product_detail", new_callable=AsyncMock)
def test_get_product_detail_success(mock_detail):
    """상품 단건 조회가 정상적으로 상세 정보를 반환하는지 확인"""
    mock_detail.return_value = AliexpressProductDetailResponse(
        product=AliexpressProductItem(
            product_id="1005006212345678",
            product_title="무선 블루투스 이어폰 TWS 노이즈캔슬링",
            product_detail_url="https://www.aliexpress.com/item/1005006212345678.html",
            product_main_image_url="https://ae01.alicdn.com/kf/mock-earbuds.jpg",
            sale_price="15.99",
            target_sale_price="21500",
            target_original_price="28000",
            target_app_sale_price="19900",
            target_app_original_price="26000",
            discount="7%",
            evaluate_rate="4.8",
            commission_rate="5.0%",
            lastest_volume="2500",
            shop_name="TechGadget Store",
            shop_url="https://www.aliexpress.com/store/912345678",
        ),
    )

    response = client.get("/api/v1/aliexpress/products/1005006212345678")
    assert response.status_code == 200

    data = response.json()
    assert data["product"] is not None
    assert data["product"]["product_id"] == "1005006212345678"
    assert data["product"]["product_title"] == "무선 블루투스 이어폰 TWS 노이즈캔슬링"
    assert data["product"]["shop_name"] == "TechGadget Store"


@patch("app.routers.aliexpress.get_affiliate_product_detail", new_callable=AsyncMock)
def test_get_product_detail_not_found(mock_detail):
    """존재하지 않는 상품 ID 조회 시 product가 null인 응답을 반환하는지 확인"""
    mock_detail.return_value = AliexpressProductDetailResponse(product=None)

    response = client.get("/api/v1/aliexpress/products/9999999999999999")
    assert response.status_code == 200

    data = response.json()
    assert data["product"] is None


# --- 서비스 레이어 정규화 테스트 ---


def test_normalize_categories_handles_empty_strings():
    """빈 문자열 필드가 정상적으로 처리되는지 확인"""
    raw_categories = [
        {"category_id": "123", "category_name": "", "parent_category_id": "0"},
    ]
    result = _normalize_categories(raw_categories)
    assert len(result) == 1
    assert result[0].category_id == "123"
    assert result[0].category_name == ""
    assert result[0].parent_category_id == "0"


def test_normalize_categories_handles_missing_fields():
    """필드가 누락된 원본 응답이 기본값으로 채워지는지 확인"""
    raw_categories = [
        {"category_id": "456"},
    ]
    result = _normalize_categories(raw_categories)
    assert len(result) == 1
    assert result[0].category_id == "456"
    assert result[0].category_name == ""
    assert result[0].parent_category_id == ""


def test_normalize_categories_empty_list():
    """빈 리스트 입력 시 빈 리스트 반환 확인"""
    result = _normalize_categories([])
    assert result == []


def test_normalize_products_handles_empty_strings():
    """상품 빈 문자열 필드가 정상적으로 처리되는지 확인"""
    raw_products = [
        {
            "product_id": "100",
            "product_title": "",
            "sale_price": "",
        },
    ]
    result = _normalize_products(raw_products)
    assert len(result) == 1
    assert result[0].product_id == "100"
    assert result[0].product_title == ""
    assert result[0].sale_price == ""


def test_normalize_products_handles_missing_fields():
    """상품 필드가 누락된 원본 응답이 기본값으로 채워지는지 확인"""
    raw_products = [
        {"product_id": "200"},
    ]
    result = _normalize_products(raw_products)
    assert len(result) == 1
    assert result[0].product_id == "200"
    assert result[0].product_title == ""
    assert result[0].shop_name == ""
    assert result[0].shop_url == ""


def test_normalize_products_empty_list():
    """빈 상품 리스트 입력 시 빈 리스트 반환 확인"""
    result = _normalize_products([])
    assert result == []


# --- 에러 추출 테스트 ---


def test_extract_error_from_top_level():
    """최상위 레벨 에러 응답에서 에러 코드/메시지 추출 확인"""
    payload = {"error_code": "401", "error_message": "Unauthorized"}
    error_code, error_message = _extract_error(payload)
    assert error_code == "401"
    assert error_message == "Unauthorized"


def test_extract_error_from_error_response():
    """error_response 중첩 구조에서 에러 코드/메시지 추출 확인"""
    payload = {"error_response": {"code": "400", "msg": "Bad Request"}}
    error_code, error_message = _extract_error(payload)
    assert error_code == "400"
    assert error_message == "Bad Request"


def test_extract_error_no_error():
    """에러가 없는 응답에서 (None, None) 반환 확인"""
    payload = {"resp_result": {"result": {"total": 10}}}
    error_code, error_message = _extract_error(payload)
    assert error_code is None
    assert error_message is None


def test_extract_error_non_dict():
    """딕셔너리가 아닌 입력에 대해 (None, None) 반환 확인"""
    error_code, error_message = _extract_error("not a dict")
    assert error_code is None
    assert error_message is None


# --- 서명 파라미터 빌드 테스트 ---


@patch("app.services.aliexpress_service._get_credentials", return_value=("test_app_key", "test_app_secret"))
def test_build_signed_params_contains_required_fields(mock_creds):
    """서명 파라미터에 필수 필드가 포함되어 있는지 확인"""
    params = _build_signed_params(method="aliexpress.affiliate.category.get")
    assert "app_key" in params
    assert "method" in params
    assert "timestamp" in params
    assert "sign_method" in params
    assert "sign" in params
    assert "format" in params
    assert "v" in params
    assert params["method"] == "aliexpress.affiliate.category.get"
    assert params["sign_method"] == "hmac-sha256"
    assert params["format"] == "json"
    assert params["v"] == "2.0"


@patch("app.services.aliexpress_service._get_credentials", return_value=("test_app_key", "test_app_secret"))
def test_build_signed_params_includes_extra_params(mock_creds):
    """추가 파라미터가 서명에 포함되는지 확인"""
    extra = {"keywords": "이어폰", "page_no": "1"}
    params = _build_signed_params(method="aliexpress.affiliate.product.query", extra_params=extra)
    assert params["keywords"] == "이어폰"
    assert params["page_no"] == "1"


@patch("app.services.aliexpress_service._get_credentials", return_value=("test_app_key", "test_app_secret"))
def test_build_signed_params_sign_is_uppercase_hex(mock_creds):
    """서명이 대문자 HEX 문자열인지 확인"""
    params = _build_signed_params(method="aliexpress.affiliate.category.get")
    sign = params["sign"]
    assert sign == sign.upper()
    # HEX 문자열인지 확인 (0-9, A-F만 포함)
    assert all(c in "0123456789ABCDEF" for c in sign)


# --- 모킹 모드 테스트 ---


@pytest.mark.asyncio
async def test_mock_mode_categories_returns_fixed_data():
    """모킹 모드에서 고정된 카테고리 데이터를 반환하는지 확인"""
    result = await _get_affiliate_categories_mock()

    assert isinstance(result, AliexpressCategoryResponse)
    assert result.total > 0
    assert len(result.items) > 0
    assert result.items[0].category_id == "200000345"
    assert result.items[0].category_name == "전자제품"


@pytest.mark.asyncio
async def test_mock_mode_search_returns_fixed_data():
    """모킹 모드에서 고정된 검색 결과를 반환하는지 확인"""
    result = await _search_affiliate_products_mock(keyword="아무키워드")

    assert isinstance(result, AliexpressSearchResponse)
    assert result.total > 0
    assert len(result.items) > 0
    assert result.items[0].product_id == "1005006212345678"
    assert result.items[0].product_title == "무선 블루투스 이어폰 TWS 노이즈캔슬링"


@pytest.mark.asyncio
async def test_mock_mode_search_respects_page_size():
    """모킹 모드에서 page_size 파라미터에 따라 결과 개수가 제한되는지 확인"""
    result = await _search_affiliate_products_mock(keyword="테스트", page_no=1, page_size=2)

    assert len(result.items) == 2
    assert result.page_size == 2


@pytest.mark.asyncio
async def test_mock_mode_search_respects_page_no():
    """모킹 모드에서 page_no 파라미터에 따라 결과 시작 위치가 조정되는지 확인"""
    result = await _search_affiliate_products_mock(keyword="테스트", page_no=2, page_size=1)

    # page_no=2, page_size=1이므로 두 번째 항목 반환
    assert result.page_no == 2
    assert result.items[0].product_id == "1005005890123456"


@pytest.mark.asyncio
async def test_mock_mode_search_total_count():
    """모킹 모드에서 total이 전체 모킹 데이터 개수를 반환하는지 확인"""
    result = await _search_affiliate_products_mock(keyword="테스트", page_no=1, page_size=1)

    # page_size=1이어도 total은 전체 개수
    assert result.total == 3
    assert len(result.items) == 1


@pytest.mark.asyncio
async def test_mock_mode_product_detail_existing_product():
    """모킹 모드에서 존재하는 product_id로 조회 시 정상 데이터 반환 확인"""
    result = await _get_affiliate_product_detail_mock("1005006212345678")

    assert isinstance(result, AliexpressProductDetailResponse)
    assert result.product is not None
    assert result.product.product_id == "1005006212345678"
    assert result.product.product_title == "무선 블루투스 이어폰 TWS 노이즈캔슬링"


@pytest.mark.asyncio
async def test_mock_mode_product_detail_not_found():
    """모킹 모드에서 존재하지 않는 product_id로 조회 시 product=None 반환 확인"""
    result = await _get_affiliate_product_detail_mock("NONEXISTENT_ID")

    assert isinstance(result, AliexpressProductDetailResponse)
    assert result.product is None


@patch("app.services.aliexpress_service._is_mock_enabled", return_value=True)
@pytest.mark.asyncio
async def test_get_affiliate_product_detail_delegates_to_mock_when_enabled(mock_check):
    """MOCK_ENABLED=True일 때 get_affiliate_product_detail가 모킹 데이터를 반환하는지 확인"""
    result = await get_affiliate_product_detail("1005006212345678")

    assert isinstance(result, AliexpressProductDetailResponse)
    assert result.product is not None
    assert result.product.product_id == "1005006212345678"


@patch("app.services.aliexpress_service._is_mock_enabled", return_value=False)
@patch(
    "app.services.aliexpress_service._get_credentials",
    side_effect=ValueError("ALIEXPRESS_APP_KEY, ALIEXPRESS_APP_SECRET 환경변수가 필요합니다"),
)
@pytest.mark.asyncio
async def test_get_affiliate_product_detail_raises_when_no_credentials(mock_creds, mock_check):
    """MOCK_ENABLED=False이고 자격증명이 없을 때 ValueError 발생 확인"""
    with pytest.raises(ValueError, match="ALIEXPRESS_APP_KEY"):
        await get_affiliate_product_detail("1005006212345678")


@patch("app.services.aliexpress_service._is_mock_enabled", return_value=True)
@pytest.mark.asyncio
async def test_get_affiliate_categories_delegates_to_mock_when_enabled(mock_check):
    """MOCK_ENABLED=True일 때 get_affiliate_categories가 모킹 데이터를 반환하는지 확인"""
    result = await get_affiliate_categories()

    assert isinstance(result, AliexpressCategoryResponse)
    assert result.total > 0
    assert len(result.items) > 0


@patch("app.services.aliexpress_service._is_mock_enabled", return_value=True)
@pytest.mark.asyncio
async def test_search_affiliate_products_delegates_to_mock_when_enabled(mock_check):
    """MOCK_ENABLED=True일 때 search_affiliate_products가 모킹 데이터를 반환하는지 확인"""
    result = await search_affiliate_products(keyword="테스트")

    assert isinstance(result, AliexpressSearchResponse)
    assert result.total > 0
    assert len(result.items) > 0


@patch("app.services.aliexpress_service._is_mock_enabled", return_value=False)
@patch(
    "app.services.aliexpress_service._crawl_aliexpress_search_products",
    new_callable=AsyncMock,
)
@pytest.mark.asyncio
async def test_search_affiliate_products_delegates_to_crawl_when_disabled(mock_crawl, mock_check):
    """MOCK_ENABLED=False일 때 검색이 크롤러 결과를 정규화해서 반환하는지 확인"""
    mock_crawl.return_value = [
        {
            "product_id": "1005001111111111",
            "product_title": "테스트 상품 A",
            "product_detail_url": "https://www.aliexpress.com/item/1005001111111111.html",
            "product_main_image_url": "https://img.example.com/a.jpg",
            "sale_price": "12.34",
            "target_sale_price": "12.34",
            "target_original_price": "15.00",
            "target_app_sale_price": "12.34",
            "target_app_original_price": "15.00",
            "discount": "",
            "evaluate_rate": "",
            "commission_rate": "",
            "lastest_volume": "",
            "shop_name": "Demo Store",
            "shop_url": "https://www.aliexpress.com/store/1",
            "first_level_category_id": "",
            "first_level_category_name": "",
            "second_level_category_id": "",
            "second_level_category_name": "",
        },
    ]

    result = await search_affiliate_products(keyword="테스트", page_no=1, page_size=10)

    assert isinstance(result, AliexpressSearchResponse)
    assert result.total == 1
    assert result.items[0].product_id == "1005001111111111"
    assert result.items[0].product_title == "테스트 상품 A"
    mock_crawl.assert_awaited_once_with(
        keyword="테스트",
        page_no=1,
        page_size=10,
        sort=None,
    )


@patch("app.services.aliexpress_service._is_mock_enabled", return_value=False)
@patch(
    "app.services.aliexpress_service._get_credentials",
    side_effect=ValueError("ALIEXPRESS_APP_KEY, ALIEXPRESS_APP_SECRET 환경변수가 필요합니다"),
)
@pytest.mark.asyncio
async def test_get_affiliate_categories_raises_when_no_credentials(mock_creds, mock_check):
    """MOCK_ENABLED=False이고 자격증명이 없을 때 ValueError 발생 확인"""
    with pytest.raises(ValueError, match="ALIEXPRESS_APP_KEY"):
        await get_affiliate_categories()


@patch("app.services.aliexpress_service._is_mock_enabled", return_value=False)
@patch(
    "app.services.aliexpress_service._crawl_aliexpress_search_products",
    new_callable=AsyncMock,
)
@pytest.mark.asyncio
async def test_search_affiliate_products_raises_when_crawl_fails(mock_crawl, mock_check):
    """MOCK_ENABLED=False이고 크롤링이 실패하면 ValueError가 전파되는지 확인"""
    mock_crawl.side_effect = ValueError("AliExpress 검색 페이지가 CAPTCHA/차단 페이지로 응답했습니다")
    with pytest.raises(ValueError, match="CAPTCHA/차단"):
        await search_affiliate_products(keyword="테스트")


def test_search_affiliate_products_invalid_sort():
    """지원하지 않는 정렬값으로 호출 시 ValueError 발생 확인"""
    import asyncio

    with pytest.raises(ValueError, match="지원하지 않는 정렬값"):
        asyncio.get_event_loop().run_until_complete(
            search_affiliate_products(keyword="테스트", sort="INVALID_SORT")
        )


# --- 서비스 정규화 - 중첩 응답 파싱 테스트 ---


def test_normalize_products_converts_ids_to_strings():
    """숫자형 product_id가 문자열로 변환되는지 확인"""
    raw_products = [
        {"product_id": 12345, "sale_price": 15.99},
    ]
    result = _normalize_products(raw_products)
    assert len(result) == 1
    assert result[0].product_id == "12345"
    assert result[0].sale_price == "15.99"


def test_normalize_categories_converts_ids_to_strings():
    """숫자형 category_id가 문자열로 변환되는지 확인"""
    raw_categories = [
        {"category_id": 200000345, "category_name": "전자제품", "parent_category_id": 0},
    ]
    result = _normalize_categories(raw_categories)
    assert len(result) == 1
    assert result[0].category_id == "200000345"
    assert result[0].parent_category_id == "0"


def test_build_aliexpress_search_url_encodes_keyword():
    """검색 URL이 키워드를 안전하게 인코딩하는지 확인"""
    url = _build_aliexpress_search_url("삼성 갤럭시 버즈", page_no=2)
    assert "wholesale-%EC%82%BC%EC%84%B1%20%EA%B0%A4%EB%9F%AD%EC%8B%9C%20%EB%B2%84%EC%A6%88.html" in url
    assert "page=2" in url


def test_extract_product_id_from_href_handles_common_patterns():
    """검색 링크에서 product_id를 추출할 수 있는지 확인"""
    assert _extract_product_id_from_href("https://www.aliexpress.com/item/1005006212345678.html") == "1005006212345678"
    assert _extract_product_id_from_href("https://www.aliexpress.com/wholesale?productId=1005009999999999") == "1005009999999999"
