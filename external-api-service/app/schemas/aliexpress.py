"""AliExpress Affiliate API 응답 스키마 - price-service가 소비하기 쉬운 정규화된 형태"""

from pydantic import BaseModel, Field


class AliexpressCategoryItem(BaseModel):
    """AliExpress 카테고리 개별 항목"""
    category_id: str = Field(..., description="카테고리 ID")
    category_name: str = Field("", description="카테고리명")
    parent_category_id: str = Field("", description="부모 카테고리 ID")


class AliexpressCategoryResponse(BaseModel):
    """AliExpress 카테고리 조회 정규화 응답"""
    total: int = Field(..., description="전체 카테고리 수")
    items: list[AliexpressCategoryItem] = Field(default_factory=list, description="카테고리 목록")


class AliexpressProductItem(BaseModel):
    """AliExpress 상품 개별 항목 - 실사용 위주로 정규화"""
    product_id: str = Field(..., description="상품 ID")
    product_title: str = Field("", description="상품명")
    product_detail_url: str = Field("", description="상품 상세 URL")
    product_main_image_url: str = Field("", description="상품 대표 이미지 URL")
    sale_price: str = Field("", description="판매가 (원화통화)")
    target_sale_price: str = Field("", description="타겟 통화 판매가")
    target_original_price: str = Field("", description="타겟 통화 원가")
    target_app_sale_price: str = Field("", description="타겟 통화 앱 판매가")
    target_app_original_price: str = Field("", description="타겟 통화 앱 원가")
    discount: str = Field("", description="할인율")
    evaluate_rate: str = Field("", description="평가율/평점")
    commission_rate: str = Field("", description="커미션 비율")
    lastest_volume: str = Field("", description="최근 판매량")
    shop_name: str = Field("", description="상점명")
    shop_url: str = Field("", description="상점 URL")


class AliexpressSearchResponse(BaseModel):
    """AliExpress 상품 검색 정규화 응답"""
    total: int = Field(..., description="전체 검색 결과 수")
    page_no: int = Field(1, description="현재 페이지 번호")
    page_size: int = Field(10, description="페이지당 결과 수")
    items: list[AliexpressProductItem] = Field(default_factory=list, description="상품 목록")