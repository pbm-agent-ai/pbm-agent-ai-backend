from __future__ import annotations

import os
from typing import Any

import requests


DEFAULT_DEMO_API_BASE_URL = "http://localhost:8082"
DEFAULT_PAYMENT_API_BASE_URL = "http://localhost:8084"
DEFAULT_EXTERNAL_API_BASE_URL = "http://localhost:8090"


def get_demo_api_base_url() -> str:
    return os.getenv("DEMO_API_BASE_URL", DEFAULT_DEMO_API_BASE_URL).rstrip("/")


def get_command_service_base_url() -> str:
    return os.getenv("DEMO_COMMAND_SERVICE_URL", get_demo_api_base_url()).rstrip("/")


def get_payment_service_base_url() -> str:
    return os.getenv("DEMO_PAYMENT_SERVICE_URL", DEFAULT_PAYMENT_API_BASE_URL).rstrip("/")


def get_external_api_base_url() -> str:
    return os.getenv("DEMO_EXTERNAL_API_URL", DEFAULT_EXTERNAL_API_BASE_URL).rstrip("/")


def parse_command(user_id: int, command_text: str) -> dict[str, Any]:
    url = f"{get_command_service_base_url()}/api/v1/commands/parse"
    payload = {
        "userId": user_id,
        "commandText": command_text,
    }

    response = requests.post(url, json=payload, timeout=30)
    response.raise_for_status()
    return response.json()


def fetch_payments(user_id: int) -> dict[str, Any]:
    url = f"{get_payment_service_base_url()}/api/v1/payments"
    response = requests.get(url, params={"userId": user_id}, timeout=30)
    response.raise_for_status()
    return response.json()


def fetch_payment_detail(payment_id: str) -> dict[str, Any]:
    url = f"{get_payment_service_base_url()}/api/v1/payments/{payment_id}"
    response = requests.get(url, timeout=30)
    response.raise_for_status()
    return response.json()


def search_naver(keyword: str, display: int = 5) -> dict[str, Any]:
    url = f"{get_external_api_base_url()}/api/v1/naver/search"
    response = requests.get(url, params={"keyword": keyword, "display": display}, timeout=15)
    response.raise_for_status()
    return response.json()


def search_aliexpress(keyword: str, page_size: int = 5) -> dict[str, Any]:
    url = f"{get_external_api_base_url()}/api/v1/aliexpress/search"
    response = requests.get(url, params={
        "keyword": keyword,
        "page_no": 1,
        "page_size": page_size,
        "target_currency": "KRW",
        "target_language": "KO",
        "ship_to_country": "KR",
    }, timeout=15)
    response.raise_for_status()
    return response.json()
