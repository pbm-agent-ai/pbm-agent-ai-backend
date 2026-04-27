"""네이버 쇼핑 검색 응답 스키마 - price-service가 소비하기 쉬운 정규화된 형태"""

from pydantic import BaseModel, Field


class NaverShoppingItem(BaseModel):
    """네이버 쇼핑 개별 상품 항목"""
    title: str = Field(..., description="상품명")
    lprice: str = Field(..., description="최저가")
    hprice: str = Field("", description="최고가")
    mallName: str = Field(..., description="쇼핑몰명")
    link: str = Field(..., description="상품 링크")
    productId: str = Field("", description="상품 ID")
    image: str = Field("", description="상품 이미지 URL")
    maker: str = Field("", description="제조사")
    brand: str = Field("", description="브랜드")
    category1: str = Field("", description="대분류")
    category2: str = Field("", description="중분류")
    category3: str = Field("", description="소분류")
    category4: str = Field("", description="세분류")


class NaverSearchResponse(BaseModel):
    """네이버 쇼핑 검색 정규화 응답"""
    total: int = Field(..., description="전체 검색 결과 수")
    start: int = Field(1, description="검색 시작 위치")
    display: int = Field(..., description="표시할 결과 수")
    items: list[NaverShoppingItem] = Field(default_factory=list, description="상품 목록")