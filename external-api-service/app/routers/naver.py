"""네이버 쇼핑 검색 라우터"""

from fastapi import APIRouter, Query

from app.schemas.naver import NaverSearchResponse
from app.services.naver_service import search_shopping

router = APIRouter(prefix="/api/v1/naver", tags=["naver"])


@router.get("/search", response_model=NaverSearchResponse)
async def search_naver_shopping(
    keyword: str = Query(..., description="검색 키워드"),
    display: int = Query(default=10, ge=1, le=100, description="표시할 결과 수"),
    start: int = Query(default=1, ge=1, le=1000, description="검색 시작 위치"),
) -> NaverSearchResponse:
    """네이버 쇼핑 상품 검색

    price-service 등 다른 서비스가 소비하기 쉬운 정규화된 형태로 응답합니다.
    """
    return await search_shopping(keyword=keyword, display=display, start=start)