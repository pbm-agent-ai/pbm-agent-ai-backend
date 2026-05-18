"""PBM External API Service - FastAPI 애플리케이션 진입점

네이버, AliExpress 등 외부 API를 래핑하여 price-service 등 내부 서비스가 소비하기 쉬운
정규화된 형태로 제공한다.

기본적으로 실제 외부 API를 호출하며, NAVER_MOCK_ENABLED=true / ALIEXPRESS_MOCK_ENABLED=true
환경변수로 모킹 모드를 활성화하면 실제 API 자격증명 없이도 고정된 검색 결과를 반환한다.
모킹 모드는 필요 시(로컬 개발/CI 등)에만 활성화한다.
"""

import os
from pathlib import Path

from fastapi import FastAPI
from dotenv import load_dotenv

from app.routers import aliexpress, naver, openai, planner, vision_planner
from app.services.aliexpress_service import _is_mock_enabled as _is_aliexpress_mock_enabled
from app.services.naver_service import _is_mock_enabled as _is_naver_mock_enabled
from app.services.openai_service import _is_mock_enabled as _is_openai_mock_enabled


def _load_env_files() -> None:
    """루트 .env와 external-api-service/.env를 순서대로 로드한다.

    로컬 개발 편의를 위해 다음 순서로 환경변수를 읽는다.
    1. 프로젝트 루트 .env
    2. external-api-service/.env

    이미 프로세스 환경변수에 존재하는 값은 덮어쓰지 않는다.
    따라서 Docker Compose나 셸에서 직접 주입한 값이 항상 우선한다.
    """
    current_file = Path(__file__).resolve()
    service_root = current_file.parents[1]
    workspace_root = current_file.parents[2]

    load_dotenv(workspace_root / ".env", override=False)
    load_dotenv(service_root / ".env", override=False)


_load_env_files()

app = FastAPI(
    title="PBM External API Service",
    description="외부 API 래핑 서비스 - 네이버 쇼핑, AliExpress 등",
    version="0.1.0",
)

# 라우터 등록
app.include_router(naver.router)
app.include_router(aliexpress.router)
app.include_router(openai.router)
app.include_router(planner.router)
app.include_router(vision_planner.router)


@app.get("/health")
async def health_check():
    """서비스 헬스체크 엔드포인트"""
    return {
        "status": "ok",
        "service": "external-api-service",
        "mock_mode": {
            "naver": _is_naver_mock_enabled(),
            "aliexpress": _is_aliexpress_mock_enabled(),
            "openai": _is_openai_mock_enabled(),
        },
    }
