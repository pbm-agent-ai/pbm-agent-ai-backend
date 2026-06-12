"""AliExpress 라우터 - 카테고리 조회 및 상품 검색 엔드포인트"""

from __future__ import annotations

from typing import Optional

from fastapi import APIRouter, Path, Query

from app.schemas.aliexpress import (
    AliexpressCategoryResponse,
    AliexpressProductDetailResponse,
    AliexpressSearchResponse,
)
from app.services.aliexpress_service import (
    get_affiliate_categories,
    get_affiliate_product_detail,
    search_affiliate_products,
)

router = APIRouter(prefix="/api/v1/aliexpress", tags=["aliexpress"])


@router.get("/categories", response_model=AliexpressCategoryResponse)
async def get_categories() -> AliexpressCategoryResponse:
    """AliExpress Affiliate 카테고리 목록 조회

    price-service 등 다른 서비스가 소비하기 쉬운 정규화된 형태로 응답합니다.
    """
    return await get_affiliate_categories()


@router.get("/search", response_model=AliexpressSearchResponse)
async def search_products(
    keyword: str = Query(..., description="검색 키워드"),
    page_no: int = Query(default=1, ge=1, description="페이지 번호"),
    page_size: int = Query(default=10, ge=1, le=50, description="페이지당 결과 수 (최대 50)"),
    sort: Optional[str] = Query(
        default=None,
        description="정렬 기준 (SALE_PRICE_ASC, SALE_PRICE_DESC, LAST_VOLUME_ASC, LAST_VOLUME_DESC)",
    ),
    target_currency: str = Query(default="KRW", description="타겟 통화"),
    target_language: str = Query(default="KO", description="타겟 언어"),
    ship_to_country: str = Query(default="KR", description="배송 국가"),
    category_ids: Optional[str] = Query(default=None, description="카테고리 ID 목록 (콤마 구분)"),
    tracking_id: Optional[str] = Query(default=None, description="트래킹 ID"),
) -> AliexpressSearchResponse:
    """AliExpress 상품 검색

    keyword는 Affiliate API 전송 시 `keywords`로 매핑되며,
    price-service 등 다른 서비스가 소비하기 쉬운 정규화된 형태로 응답합니다.
    """
    return await search_affiliate_products(
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


@router.get("/products/{product_id}", response_model=AliexpressProductDetailResponse)
async def get_product_detail(
    product_id: str = Path(..., description="AliExpress 상품 ID"),
    target_currency: str = "KRW",
    target_language: str = "KO",
    ship_to_country: str = "KR"
) -> AliexpressProductDetailResponse:
    """AliExpress 상품 단건 상세 조회

    단일 상품의 상세 정보를 반환합니다.
    """
    return await get_affiliate_product_detail(
        product_id,
        target_currency,
        target_language,
        ship_to_country,
        )
