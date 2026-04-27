"""PBM External API Service - FastAPI 애플리케이션 진입점

네이버, AliExpress 등 외부 API를 래핑하여 price-service 등 내부 서비스가 소비하기 쉬운
정규화된 형태로 제공한다.

NAVER_MOCK_ENABLED=true / ALIEXPRESS_MOCK_ENABLED=true 환경변수로 모킹 모드를 활성화하면
실제 API 자격증명 없이도 고정된 검색 결과를 반환한다.
"""

import os

from fastapi import FastAPI

from app.routers import aliexpress, naver
from app.services.aliexpress_service import _is_mock_enabled as _is_aliexpress_mock_enabled
from app.services.naver_service import _is_mock_enabled as _is_naver_mock_enabled

app = FastAPI(
    title="PBM External API Service",
    description="외부 API 래핑 서비스 - 네이버 쇼핑, AliExpress 등",
    version="0.1.0",
)

# 라우터 등록
app.include_router(naver.router)
app.include_router(aliexpress.router)


@app.get("/health")
async def health_check():
    """서비스 헬스체크 엔드포인트"""
    return {
        "status": "ok",
        "service": "external-api-service",
        "mock_mode": {
            "naver": _is_naver_mock_enabled(),
            "aliexpress": _is_aliexpress_mock_enabled(),
        },
    }