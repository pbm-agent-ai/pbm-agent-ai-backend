"""AliExpress Affiliate API 실연동 스모크 테스트.

실제 AliExpress Affiliate API를 호출해 app_key/app_secret 기반 서명이 동작하는지 확인한다.
저장소 루트의 .env 파일이 있으면 자동으로 로드한다.
"""

from __future__ import annotations

import hashlib
import hmac
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import httpx
import pytest


ALIEXPRESS_BASE_URL = os.getenv("ALIEXPRESS_BASE_URL", "https://api-sg.aliexpress.com/sync")


def _load_repo_env() -> None:
    """저장소 루트 .env 파일을 읽어 환경변수로 주입한다."""
    env_path = Path(__file__).resolve().parents[2] / ".env"
    if not env_path.exists():
        return

    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue

        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        os.environ.setdefault(key, value)


def _build_signed_params(method: str, extra_params: dict[str, Any] | None = None) -> dict[str, str]:
    """AliExpress Affiliate API 요청 파라미터에 서명을 추가한다."""
    _load_repo_env()

    app_key = os.getenv("ALIEXPRESS_APP_KEY")
    app_secret = os.getenv("ALIEXPRESS_APP_SECRET")
    if not app_key or not app_secret:
        raise ValueError("ALIEXPRESS_APP_KEY, ALIEXPRESS_APP_SECRET 환경변수가 필요합니다")

    params: dict[str, str] = {
        "app_key": app_key,
        "method": method,
        "timestamp": datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S"),
        "sign_method": "hmac-sha256",
        "format": "json",
        "v": "2.0",
    }

    if extra_params:
        for key, value in extra_params.items():
            if value is not None:
                params[key] = str(value)

    sign_source = "".join(f"{key}{params[key]}" for key in sorted(params))
    params["sign"] = hmac.new(
        app_secret.encode("utf-8"),
        sign_source.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest().upper()

    return params


def _extract_error(payload: Any) -> tuple[str | None, str | None]:
    """응답 본문에서 에러 코드를 최대한 유연하게 추출한다."""
    if not isinstance(payload, dict):
        return None, None

    error_code = payload.get("error_code") or payload.get("code")
    error_message = payload.get("error_message") or payload.get("msg") or payload.get("sub_msg")

    error_response = payload.get("error_response")
    if isinstance(error_response, dict):
        error_code = error_code or error_response.get("code")
        error_message = error_message or error_response.get("msg") or error_response.get("sub_msg")

    return error_code, error_message


def _find_non_empty_list(value: Any) -> list[Any] | None:
    """중첩 응답에서 비어 있지 않은 리스트를 탐색한다."""
    if isinstance(value, list) and value:
        return value

    if isinstance(value, dict):
        for child in value.values():
            found = _find_non_empty_list(child)
            if found is not None:
                return found

    return None


@pytest.mark.asyncio
async def test_aliexpress_affiliate_category_smoke() -> None:
    """카테고리 조회가 실제 계정 자격증명으로 성공하는지 확인한다."""
    params = _build_signed_params("aliexpress.affiliate.category.get")

    async with httpx.AsyncClient(timeout=15.0) as client:
        response = await client.post(
            ALIEXPRESS_BASE_URL,
            data=params,
            headers={"Content-Type": "application/x-www-form-urlencoded;charset=utf-8"},
        )

    assert response.status_code == 200, response.text

    try:
        payload = response.json()
    except ValueError as exc:
        raise AssertionError(f"JSON 응답 파싱 실패: {response.text}") from exc

    error_code, error_message = _extract_error(payload)
    assert not error_code, f"AliExpress API 에러: code={error_code}, message={error_message}, body={payload}"

    found_list = _find_non_empty_list(payload)
    assert found_list is not None, f"응답에서 비어 있지 않은 리스트를 찾지 못했습니다: {payload}"


if __name__ == "__main__":
    import asyncio

    asyncio.run(test_aliexpress_affiliate_category_smoke())
