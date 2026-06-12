"""AliExpress 검색/상세 조회 서비스 - 외부 데이터 수집 및 응답 정규화 담당

카테고리/상품 상세는 기존 Affiliate API를 유지하고,
상품 검색은 AliExpress 검색 URL을 Playwright로 렌더링한 뒤 카드 DOM을 크롤링한다.
ALIEXPRESS_MOCK_ENABLED=true 환경변수 설정 시 실제 API/브라우저 호출 없이 고정된 모킹 데이터를 반환한다.

검색 URL 기반 흐름:
- https://ko.aliexpress.com/w/wholesale-{keyword}.html?spm=a2g0o.home.search.0
- Playwright로 렌더링 후 a.search-card-item 카드 DOM을 수집
- 수집한 카드에서 상품명/링크/이미지/가격 등을 정규화해서 반환

기존 Affiliate API 인증/서명 흐름:
- 엔드포인트: https://api-sg.aliexpress.com/sync (기본값, 환경변수로 변경 가능)
- method 파라미터 방식 사용
- sign_method: hmac-sha256
- timestamp: GMT+8 yyyy-MM-dd HH:mm:ss
- sign: sign 제외 파라미터를 key 오름차순으로 key+value 이어붙여 HMAC-SHA256(app_secret) 후 대문자 HEX
"""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import re
from datetime import datetime, timedelta, timezone
from urllib.parse import quote

import httpx
from playwright.async_api import async_playwright

from app.schemas.aliexpress import (
    AliexpressCategoryItem,
    AliexpressCategoryResponse,
    AliexpressProductDetailResponse,
    AliexpressProductItem,
    AliexpressSearchResponse,
)

logger = logging.getLogger(__name__)

# AliExpress Affiliate API 기본 URL
ALIEXPRESS_API_BASE_URL = "https://api-sg.aliexpress.com/sync"
ALIEXPRESS_SEARCH_URL_TEMPLATE = "https://ko.aliexpress.com/w/wholesale-{keyword}.html?spm=a2g0o.home.search.0"
ALIEXPRESS_SEARCH_INITIAL_WAIT_MS = int(os.getenv("ALIEXPRESS_SEARCH_INITIAL_WAIT_MS", "5000"))
ALIEXPRESS_SEARCH_SCROLL_WAIT_MS = int(os.getenv("ALIEXPRESS_SEARCH_SCROLL_WAIT_MS", "1500"))
ALIEXPRESS_SEARCH_MAX_SCROLLS = int(os.getenv("ALIEXPRESS_SEARCH_MAX_SCROLLS", "12"))
ALIEXPRESS_SEARCH_HEADLESS = os.getenv("ALIEXPRESS_SEARCH_HEADLESS", "true").lower() == "true"
ALIEXPRESS_SEARCH_USER_DATA_DIR = os.getenv("ALIEXPRESS_SEARCH_USER_DATA_DIR")
ALIEXPRESS_SEARCH_STORAGE_STATE = os.getenv("ALIEXPRESS_SEARCH_STORAGE_STATE")
ALIEXPRESS_SEARCH_USER_AGENT = os.getenv(
    "ALIEXPRESS_SEARCH_USER_AGENT",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
)


def _is_mock_enabled() -> bool:
    """모킹 모드 여부를 환경변수에서 실시간으로 확인한다.

    모듈 로드 시점이 아닌 호출 시점에 평가하여,
    테스트에서 패치하거나 런타임에 환경변수를 변경할 수 있도록 한다.
    """
    return os.getenv("ALIEXPRESS_MOCK_ENABLED", "false").lower() == "true"


def _get_credentials() -> tuple[str, str]:
    """환경변수에서 AliExpress API 자격증명 조회

    Returns:
        (app_key, app_secret) 튜플

    Raises:
        ValueError: 자격증명이 설정되지 않은 경우
    """
    app_key = os.getenv("ALIEXPRESS_APP_KEY")
    app_secret = os.getenv("ALIEXPRESS_APP_SECRET")
    if not app_key or not app_secret:
        raise ValueError("ALIEXPRESS_APP_KEY, ALIEXPRESS_APP_SECRET 환경변수가 필요합니다")
    return app_key, app_secret


def _build_signed_params(method: str, extra_params: dict[str, str] | None = None) -> dict[str, str]:
    """AliExpress Affiliate API 요청 파라미터에 서명을 추가한다.

    서명 규칙:
    1. 공통 파라미터(app_key, method, timestamp, sign_method, format, v) 구성
    2. extra_params 병합
    3. sign 제외 파라미터를 key 오름차순으로 key+value 이어붙임
    4. HMAC-SHA256(app_secret) 해시 후 대문자 HEX 문자열 생성

    Args:
        method: API 메서드명 (예: aliexpress.affiliate.category.get)
        extra_params: 추가 파라미터 (선택)

    Returns:
        서명이 포함된 전체 파라미터 딕셔너리
    """
    app_key, app_secret = _get_credentials()

    # GMT+8 타임스탬프 생성
    timestamp = datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S")

    params: dict[str, str] = {
        "app_key": app_key,
        "method": method,
        "timestamp": timestamp,
        "sign_method": "hmac-sha256",
        "format": "json",
        "v": "2.0",
    }

    # 추가 파라미터 병합
    if extra_params:
        for key, value in extra_params.items():
            if value is not None:
                params[key] = str(value)

    # 서명 생성: sign 제외 파라미터를 key 오름차순으로 key+value 이어붙임
    sign_source = "".join(f"{key}{params[key]}" for key in sorted(params))
    sign = hmac.new(
        app_secret.encode("utf-8"),
        sign_source.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest().upper()

    params["sign"] = sign
    return params


def _extract_error(payload: dict) -> tuple[str | None, str | None]:
    """응답 본문에서 에러 코드/메시지를 추출한다.

    AliExpress API는 다양한 에러 응답 형식을 사용하므로
    여러 위치에서 에러 정보를 찾는다.

    Args:
        payload: API 응답 JSON 딕셔너리

    Returns:
        (error_code, error_message) 튜플. 에러가 없으면 (None, None)
    """
    if not isinstance(payload, dict):
        return None, None

    error_code = payload.get("error_code") or payload.get("code")
    error_message = payload.get("error_message") or payload.get("msg") or payload.get("sub_msg")

    error_response = payload.get("error_response")
    if isinstance(error_response, dict):
        error_code = error_code or error_response.get("code")
        error_message = error_message or error_response.get("msg") or error_response.get("sub_msg")

    return error_code, error_message


def _normalize_categories(raw_categories: list[dict]) -> list[AliexpressCategoryItem]:
    """AliExpress API 원본 카테고리 응답을 정규화된 스키마로 변환

    Args:
        raw_categories: API 응답의 category 리스트

    Returns:
        정규화된 카테고리 항목 리스트
    """
    normalized = []
    for item in raw_categories:
        normalized.append(AliexpressCategoryItem(
            category_id=str(item.get("category_id", "")),
            category_name=item.get("category_name", ""),
            parent_category_id=str(item.get("parent_category_id", "")),
        ))
    return normalized


def _normalize_products(raw_products: list[dict]) -> list[AliexpressProductItem]:
    """AliExpress API 원본 상품 응답을 정규화된 스키마로 변환

    상품 데이터는 다양한 필드명으로 올 수 있어 안전하게 .get()으로 처리한다.

    Args:
        raw_products: API 응답의 product 리스트

    Returns:
        정규화된 상품 항목 리스트
    """
    normalized = []
    for item in raw_products:
        # shop_name: 원본 필드명 그대로 사용, 없으면 빈 문자열
        shop_name = item.get("shop_name", "")
        normalized.append(AliexpressProductItem(
            product_id=str(item.get("product_id", "")),
            product_title=item.get("product_title", ""),
            product_detail_url=item.get("product_detail_url", ""),
            product_main_image_url=item.get("product_main_image_url", ""),
            sale_price=str(item.get("sale_price", "")),
            target_sale_price=str(item.get("target_sale_price", "")),
            target_original_price=str(item.get("target_original_price", "")),
            target_app_sale_price=str(item.get("target_app_sale_price", "")),
            target_app_original_price=str(item.get("target_app_original_price", "")),
            discount=str(item.get("discount", "")),
            evaluate_rate=str(item.get("evaluate_rate", "")),
            commission_rate=str(item.get("commission_rate", "")),
            lastest_volume=str(item.get("lastest_volume", "")),
            shop_name=shop_name,
            shop_url=item.get("shop_url", ""),
            first_level_category_id=str(item.get("first_level_category_id", "")),
            first_level_category_name=str(item.get("first_level_category_name", "")),
            second_level_category_id=str(item.get("second_level_category_id", "")),
            second_level_category_name=str(item.get("second_level_category_name", "")),
        ))
    return normalized


def _build_aliexpress_search_url(keyword: str, page_no: int = 1) -> str:
    """AliExpress 검색 URL을 생성한다."""
    encoded_keyword = quote(keyword, safe="")
    url = ALIEXPRESS_SEARCH_URL_TEMPLATE.format(keyword=encoded_keyword)
    if page_no > 1:
        url = f"{url}&page={page_no}"
    return url


def _parse_price_text(value: str | None) -> str:
    """가격 텍스트에서 숫자만 추출한다."""
    if not value:
        return ""
    match = re.search(r"\d[\d,]*(?:\.\d+)?", str(value).replace("\xa0", " "))
    if not match:
        return ""
    return match.group(0).replace(",", "")


def _normalize_crawled_product_prices(raw_products: list[dict]) -> list[dict]:
    """크롤링한 원본 상품 딕셔너리의 가격 필드를 후처리한다."""
    normalized: list[dict] = []
    for item in raw_products:
        cloned = dict(item)
        price_candidates = cloned.get("debug_price_candidates") or []
        first_candidate = price_candidates[0] if len(price_candidates) > 0 else ""
        second_candidate = price_candidates[1] if len(price_candidates) > 1 else ""

        current_price = _parse_price_text(
            cloned.get("target_sale_price")
            or cloned.get("sale_price")
            or cloned.get("debug_primary_price_text")
            or first_candidate
        )
        original_price = _parse_price_text(
            cloned.get("target_original_price")
            or cloned.get("target_app_original_price")
            or cloned.get("debug_secondary_price_text")
            or second_candidate
        )

        if current_price:
            cloned["sale_price"] = current_price
            cloned["target_sale_price"] = current_price
            if not cloned.get("target_app_sale_price"):
                cloned["target_app_sale_price"] = current_price

        if original_price:
            cloned["target_original_price"] = original_price
            if not cloned.get("target_app_original_price"):
                cloned["target_app_original_price"] = original_price

        normalized.append(cloned)

    return normalized


def _extract_product_id_from_href(href: str | None) -> str:
    """상품 링크에서 product_id를 추출한다."""
    if not href:
        return ""
    patterns = [
        r"(?:productId|product_id)=([0-9]{8,})",
        r"/item/([0-9]{8,})\.html",
        r"/i/([0-9]{8,})\.html",
        r"([0-9]{12,})",
    ]
    for pattern in patterns:
        match = re.search(pattern, href)
        if match:
            return match.group(1)
    return ""


def _dedupe_products(raw_products: list[dict]) -> list[dict]:
    """product_id 또는 상세 URL 기준으로 중복을 제거한다."""
    seen: set[str] = set()
    deduped: list[dict] = []
    for item in raw_products:
        key = (item.get("product_id") or item.get("product_detail_url") or item.get("product_title") or "").strip()
        if not key or key in seen:
            continue
        seen.add(key)
        deduped.append(item)
    return deduped


def _sort_products_for_request(raw_products: list[dict], sort: str | None) -> list[dict]:
    """검색 정렬 요청을 로컬에서 가능한 범위만 반영한다."""
    if not sort:
        return raw_products

    if sort not in {"SALE_PRICE_ASC", "SALE_PRICE_DESC", "LAST_VOLUME_ASC", "LAST_VOLUME_DESC"}:
        return raw_products

    if sort.startswith("SALE_PRICE"):
        reverse = sort.endswith("DESC")

        def sort_key(item: dict) -> tuple[int, float]:
            price = _parse_price_text(item.get("target_sale_price") or item.get("sale_price") or "")
            if not price:
                return (1, float("inf"))
            try:
                return (0, float(price))
            except ValueError:
                return (1, float("inf"))

        return sorted(raw_products, key=sort_key, reverse=reverse)

    return raw_products


async def _extract_crawl_products_from_page(page) -> list[dict]:
    """렌더링된 AliExpress 검색 결과 페이지에서 카드 정보를 추출한다."""
    raw_json = await page.evaluate(
        """
        () => {
          const normalize = (value) => (value || "").replace(/\\s+/g, " ").trim();
          const absUrl = (value) => {
            if (!value) return "";
            try {
              return new URL(value, window.location.href).href;
            } catch (error) {
              return String(value);
            }
          };
          const pickText = (root, selectors) => {
            for (const selector of selectors) {
              const el = root.querySelector(selector);
              if (el) {
                const text = normalize(el.getAttribute("alt") || el.textContent || "");
                if (text) return text;
              }
            }
            return "";
          };
          const pickFirst = (root, selectors) => {
            for (const selector of selectors) {
              const el = root.querySelector(selector);
              if (el) return el;
            }
            return null;
          };
          const getPriceText = (root, selectors) => {
            for (const selector of selectors) {
              const el = root.querySelector(selector);
              if (el) {
                const text = normalize(
                  el.getAttribute("aria-label")
                  || el.getAttribute("content")
                  || el.getAttribute("data-price")
                  || el.textContent
                  || ""
                );
                if (text) return text;
              }
            }
            return "";
          };
          const extractPriceCandidates = (value) => {
            const text = normalize(value);
            if (!text) return [];
            const patterns = [
              /(?:US\\s*\\$|USD\\s*)\\s*([\\d,.]+)/gi,
              /\\$\\s*([\\d,.]+)/g,
              /(?:₩|￦|KRW)\\s*([\\d,.]+)/gi,
              /([\\d,.]+)\\s*원/g,
              /\\b\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?\\b/g,
              /\\b\\d+\\.\\d{2}\\b/g,
            ];
            const prices = [];
            for (const pattern of patterns) {
              let match;
              while ((match = pattern.exec(text)) !== null) {
                const candidate = normalize(match[1] || match[0]).replace(/[^0-9.,]/g, "");
                if (!candidate) continue;
                if (!prices.includes(candidate)) {
                  prices.push(candidate);
                }
              }
            }
            return prices;
          };
          const extractProductId = (href, fallback) => {
            const source = `${href || ""} ${fallback || ""}`;
            const patterns = [
              /(?:productId|product_id)=([0-9]{8,})/i,
              /\\/item\\/([0-9]{8,})\\.html/i,
              /\\/i\\/([0-9]{8,})\\.html/i,
              /([0-9]{12,})/i,
            ];
            for (const pattern of patterns) {
              const match = source.match(pattern);
              if (match) return match[1];
            }
            return "";
          };

          const cards = Array.from(document.querySelectorAll("a.search-card-item"));
          return cards.map((card) => {
            const img = card.querySelector("img");
            const href = absUrl(card.getAttribute("href") || card.href || "");
            const cardText = normalize(card.innerText || card.textContent || "");
            const cardHtml = card.innerHTML || "";
            const title = pickText(card, [
              ".item-title-wrap",
              "[class*='item-title']",
              "[class*='title']",
            ]) || normalize(img?.getAttribute("alt") || img?.alt || "");
            const currentPrice = getPriceText(card, [
              ".price-current",
              "[class*='price-current']",
              "[class*='current']",
            ]);
            const originalPrice = getPriceText(card, [
              ".price-original",
              "[class*='price-original']",
              "[class*='original']",
            ]);
            const priceCandidates = extractPriceCandidates(
              `${currentPrice} ${originalPrice} ${cardText} ${cardHtml}`
            );
            const shopLink = pickFirst(card, [
              "a[href*='/store/']",
              "a[href*='store/']",
              "a[href*='shop/']",
            ]);
            const storeName = pickText(card, [
              "[class*='store-name']",
              "[class*='shop-name']",
              "[class*='store']",
            ]) || normalize(shopLink?.textContent || "");

            return {
              product_id: extractProductId(href, card.getAttribute("data-product-id") || card.getAttribute("data-id") || ""),
              product_title: title,
              product_detail_url: href,
              product_main_image_url: absUrl(img?.getAttribute("src") || img?.getAttribute("data-src") || img?.getAttribute("data-lazy-src") || img?.currentSrc || img?.src || ""),
              sale_price: (currentPrice || priceCandidates[0] || "").replace(/[^0-9.]/g, ""),
              target_sale_price: (currentPrice || priceCandidates[0] || "").replace(/[^0-9.]/g, ""),
              target_original_price: (originalPrice || priceCandidates[1] || "").replace(/[^0-9.]/g, ""),
              target_app_sale_price: (currentPrice || priceCandidates[0] || "").replace(/[^0-9.]/g, ""),
              target_app_original_price: (originalPrice || priceCandidates[1] || "").replace(/[^0-9.]/g, ""),
              discount: "",
              evaluate_rate: "",
              commission_rate: "",
              lastest_volume: "",
              shop_name: storeName,
              shop_url: absUrl(shopLink?.getAttribute("href") || shopLink?.href || ""),
              first_level_category_id: "",
              first_level_category_name: "",
              second_level_category_id: "",
              second_level_category_name: "",
              debug_primary_price_text: currentPrice || priceCandidates[0] || "",
              debug_secondary_price_text: originalPrice || priceCandidates[1] || "",
              debug_price_candidates: priceCandidates,
              debug_card_text: cardText,
            };
          });
        }
        """
    )
    if isinstance(raw_json, str):
        try:
            return json.loads(raw_json)
        except json.JSONDecodeError:
            logger.warning("AliExpress 검색 카드 JSON 파싱 실패")
            return []
    if isinstance(raw_json, list):
        return raw_json
    return []


async def _crawl_aliexpress_search_products(
    keyword: str,
    page_no: int = 1,
    page_size: int = 10,
    sort: str | None = None,
) -> list[dict]:
    """AliExpress 검색 페이지를 브라우저 렌더링 후 카드 목록을 수집한다."""
    search_url = _build_aliexpress_search_url(keyword, page_no)
    target_collect_count = max(page_no * page_size, page_size)
    max_scrolls = max(1, ALIEXPRESS_SEARCH_MAX_SCROLLS)

    logger.info(
        "AliExpress URL 검색 크롤링 시작 - keyword=%s, page_no=%s, page_size=%s, sort=%s, url=%s",
        keyword,
        page_no,
        page_size,
        sort,
        search_url,
    )

    async with async_playwright() as playwright:
        browser = None
        context = None
        page = None
        try:
            context_kwargs: dict = {
                "locale": "ko-KR",
                "viewport": {"width": 1440, "height": 2200},
                "user_agent": ALIEXPRESS_SEARCH_USER_AGENT,
                "extra_http_headers": {
                    "accept-language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
                },
            }
            if ALIEXPRESS_SEARCH_STORAGE_STATE and not ALIEXPRESS_SEARCH_USER_DATA_DIR:
                context_kwargs["storage_state"] = ALIEXPRESS_SEARCH_STORAGE_STATE
            if ALIEXPRESS_SEARCH_USER_DATA_DIR:
                logger.info("AliExpress 검색 크롤링 - persistent user_data_dir 사용: %s", ALIEXPRESS_SEARCH_USER_DATA_DIR)
                context = await playwright.chromium.launch_persistent_context(
                    ALIEXPRESS_SEARCH_USER_DATA_DIR,
                    headless=ALIEXPRESS_SEARCH_HEADLESS,
                    args=["--disable-blink-features=AutomationControlled"],
                    **context_kwargs,
                )
            else:
                browser = await playwright.chromium.launch(
                    headless=ALIEXPRESS_SEARCH_HEADLESS,
                    args=["--disable-blink-features=AutomationControlled"],
                )
                context = await browser.new_context(**context_kwargs)
            page = await context.new_page()

            await page.goto(search_url, wait_until="domcontentloaded", timeout=60000)
            await page.wait_for_timeout(ALIEXPRESS_SEARCH_INITIAL_WAIT_MS)

            if "punish" in page.url or "Captcha Interception" in await page.title():
                raise ValueError(
                    "AliExpress 검색 페이지가 CAPTCHA/차단 페이지로 응답했습니다. "
                    "브라우저 쿠키 또는 수동 세션이 필요할 수 있습니다."
                )

            collected: list[dict] = []
            seen: set[str] = set()
            stagnant_rounds = 0
            viewport_height = 2200

            for attempt in range(max_scrolls):
                batch = _normalize_crawled_product_prices(await _extract_crawl_products_from_page(page))
                before_count = len(collected)
                for item in batch:
                    key = (item.get("product_id") or item.get("product_detail_url") or item.get("product_title") or "").strip()
                    if not key or key in seen:
                        continue
                    seen.add(key)
                    collected.append(item)

                logger.info(
                    "AliExpress 검색 크롤링 스냅샷 - attempt=%s, batch=%s, total=%s, target=%s",
                    attempt + 1,
                    len(batch),
                    len(collected),
                    target_collect_count,
                )

                if len(collected) >= target_collect_count:
                    break

                if len(collected) == before_count:
                    stagnant_rounds += 1
                else:
                    stagnant_rounds = 0

                if stagnant_rounds >= 2:
                    break

                await page.mouse.wheel(0, viewport_height * 2)
                await page.wait_for_timeout(ALIEXPRESS_SEARCH_SCROLL_WAIT_MS)

            collected = _dedupe_products(_sort_products_for_request(collected, sort))
            missing_price_items = [
                {
                    "product_id": item.get("product_id"),
                    "title": item.get("product_title"),
                    "primary": item.get("debug_primary_price_text"),
                    "secondary": item.get("debug_secondary_price_text"),
                    "candidates": item.get("debug_price_candidates"),
                    "card_text": (item.get("debug_card_text") or "")[:240],
                }
                for item in collected
                if not _parse_price_text(item.get("target_sale_price") or item.get("sale_price"))
            ]
            logger.info(
                "AliExpress 검색 크롤링 완료 - keyword=%s, collected=%s, missingPrice=%s",
                keyword,
                len(collected),
                len(missing_price_items),
            )
            if missing_price_items:
                logger.info("AliExpress 가격 미추출 샘플: %s", missing_price_items[:3])
            return collected
        finally:
            if page is not None:
                await page.close()
            if context is not None:
                await context.close()
            if browser is not None:
                await browser.close()


# --- 모킹용 고정 데이터 ---

_MOCK_CATEGORIES: list[dict] = [
    {"category_id": "200000345", "category_name": "전자제품", "parent_category_id": "0"},
    {"category_id": "200000346", "category_name": "컴퓨터 및 네트워킹", "parent_category_id": "0"},
    {"category_id": "200000347", "category_name": "의류 및 액세서리", "parent_category_id": "0"},
    {"category_id": "200000348", "category_name": "가전제품", "parent_category_id": "0"},
    {"category_id": "200000349", "category_name": "가구", "parent_category_id": "0"},
]

_MOCK_PRODUCTS: list[dict] = [
    {
        "product_id": "1005006212345678",
        "product_title": "무선 블루투스 이어폰 TWS 노이즈캔슬링",
        "product_detail_url": "https://www.aliexpress.com/item/1005006212345678.html",
        "product_main_image_url": "https://ae01.alicdn.com/kf/mock-earbuds.jpg",
        "sale_price": "15.99",
        "target_sale_price": "21500",
        "target_original_price": "28000",
        "target_app_sale_price": "19900",
        "target_app_original_price": "26000",
        "discount": "7%",
        "evaluate_rate": "4.8",
        "commission_rate": "5.0%",
        "lastest_volume": "2500",
        "shop_name": "TechGadget Store",
        "shop_url": "https://www.aliexpress.com/store/912345678",
        "first_level_category_id": "200000345",
        "first_level_category_name": "Consumer Electronics",
        "second_level_category_id": "200001234",
        "second_level_category_name": "Earphones & Headphones",
    },
    {
        "product_id": "1005005890123456",
        "product_title": "USB C 허브 7포트 멀티 어댑터",
        "product_detail_url": "https://www.aliexpress.com/item/1005005890123456.html",
        "product_main_image_url": "https://ae01.alicdn.com/kf/mock-hub.jpg",
        "sale_price": "22.50",
        "target_sale_price": "32000",
        "target_original_price": "38000",
        "target_app_sale_price": "29900",
        "target_app_original_price": "35000",
        "discount": "6%",
        "evaluate_rate": "4.6",
        "commission_rate": "3.5%",
        "lastest_volume": "1800",
        "shop_name": "DigitalLife Official",
        "shop_url": "https://www.aliexpress.com/store/923456789",
        "first_level_category_id": "200000345",
        "first_level_category_name": "Consumer Electronics",
        "second_level_category_id": "200001235",
        "second_level_category_name": "USB Hubs & Adapters",
    },
    {
        "product_id": "1005004567890123",
        "product_title": "기계식 키보드 RGB 백라이트 87키",
        "product_detail_url": "https://www.aliexpress.com/item/1005004567890123.html",
        "product_main_image_url": "https://ae01.alicdn.com/kf/mock-keyboard.jpg",
        "sale_price": "35.00",
        "target_sale_price": "49000",
        "target_original_price": "58000",
        "target_app_sale_price": "45000",
        "target_app_original_price": "55000",
        "discount": "8%",
        "evaluate_rate": "4.9",
        "commission_rate": "4.0%",
        "lastest_volume": "3200",
        "shop_name": "KeyboardWorld",
        "shop_url": "https://www.aliexpress.com/store/934567890",
        "first_level_category_id": "200000346",
        "first_level_category_name": "Computer & Office",
        "second_level_category_id": "200001236",
        "second_level_category_name": "Keyboards & Mice",
    },
]


async def _get_affiliate_categories_mock() -> AliexpressCategoryResponse:
    """모킹 모드: 고정된 카테고리 데이터를 반환한다.

    실제 AliExpress API 호출 없이 로컬 개발/CI 환경에서 동작 검증이 가능하다.
    """
    logger.info("모킹 모드 활성화 - 고정 카테고리 데이터 반환")
    items = _normalize_categories(_MOCK_CATEGORIES)
    return AliexpressCategoryResponse(total=len(items), items=items)


async def _search_affiliate_products_mock(
    keyword: str,
    page_no: int = 1,
    page_size: int = 10,
    sort: str | None = None,
    target_currency: str = "KRW",
    target_language: str = "KO",
    ship_to_country: str = "KR",
    category_ids: str | None = None,
    tracking_id: str | None = None,
) -> AliexpressSearchResponse:
    """모킹 모드: 고정된 검색 결과를 반환한다.

    키워드와 무관하게 동일한 고정 데이터를 반환하며,
    page_no/page_size 파라미터에 따라 결과 개수와 시작 위치를 조정한다.
    """
    logger.info("모킹 모드 활성화 - 고정 검색 데이터 반환 (키워드: %s)", keyword)

    # page_no, page_size 파라미터에 맞춰 슬라이싱
    start_index = (page_no - 1) * page_size
    end_index = min(start_index + page_size, len(_MOCK_PRODUCTS))
    sliced_products = _MOCK_PRODUCTS[start_index:end_index]

    items = _normalize_products(sliced_products)
    return AliexpressSearchResponse(
        total=len(_MOCK_PRODUCTS),
        page_no=page_no,
        page_size=page_size,
        items=items,
    )


async def _get_affiliate_product_detail_mock(
    product_id: str,
    arget_currency: str = "KRW",
    target_language: str = "KO",
    ship_to_country: str = "KR",) -> AliexpressProductDetailResponse:
    """모킹 모드: _MOCK_PRODUCTS에서 product_id와 일치하는 상품을 반환한다.

    Args:
        product_id: 조회할 상품 ID

    Returns:
        일치하는 상품이 있으면 AliexpressProductDetailResponse, 없으면 product=None
    """
    logger.info("모킹 모드 활성화 - 상품 단건 조회 모킹 (product_id: %s)", product_id)
    for raw in _MOCK_PRODUCTS:
        if raw.get("product_id") == product_id:
            items = _normalize_products([raw])
            return AliexpressProductDetailResponse(product=items[0])
    return AliexpressProductDetailResponse(product=None)


async def get_affiliate_product_detail(
    product_id: str,
    target_currency: str = "KRW",
    target_language: str = "KO",
    ship_to_country: str = "KR",
    ) -> AliexpressProductDetailResponse:
    """AliExpress Affiliate 상품 단건 상세 조회 API 호출 및 정규화된 응답 반환

    공식 메서드: aliexpress.affiliate.productdetail.get

    ALIEXPRESS_MOCK_ENABLED=true인 경우 실제 API 호출 없이 모킹 데이터를 반환한다.

    Args:
        product_id: 조회할 상품 ID

    Returns:
        AliexpressProductDetailResponse: 정규화된 상품 상세 정보

    Raises:
        ValueError: API 에러 응답 또는 자격증명 누락 시
    """
    if _is_mock_enabled():
        return await _get_affiliate_product_detail_mock(
        product_id,
        target_currency,
        target_language,
        ship_to_country,)

    # extra_params는 알리 API에 보낼 요청값 묶음임.
    extra_params: dict[str, str] = {
        "product_ids": product_id,
        "target_currency": target_currency,
        "target_language": target_language,
        "country": ship_to_country,
    }
    # 이 값들을 받아서 공통 파라미터랑 합치고, 서명도 만들고, 최종 요청 파라미터를 완성한다.
    params = _build_signed_params(method="aliexpress.affiliate.productdetail.get", extra_params=extra_params)

    base_url = os.getenv("ALIEXPRESS_BASE_URL", ALIEXPRESS_API_BASE_URL)

    # client.post가 HTTP request를 만들고 그 request를 전송함
    # 응답을 받아서 response에 담음
    # request = ..., response = ... 이런식으로 분리할 수 있지만 단순 호출이라 post()로 한번에 처리한 모습임
    """
    request = client.build_request(
    "POST",
    base_url,
    data=params,
    headers={"Content-Type": "application/x-www-form-urlencoded;charset=utf-8"},
    )
    response = await client.send(request)
    이렇게 변경 가능
    """
    async with httpx.AsyncClient(timeout=15.0) as client:
        response = await client.post(
            base_url,
            data=params,
            headers={"Content-Type": "application/x-www-form-urlencoded;charset=utf-8"},
        )
        response.raise_for_status()

    data = response.json()

    # 에러 응답 확인
    error_code, error_message = _extract_error(data)
    if error_code:
        raise ValueError(f"AliExpress API 에러: code={error_code}, message={error_message}")

    # 상품 상세 응답 파싱: aliexpress_affiliate_productdetail_get_response > resp_result > result > products > product
    resp_result = data.get(
        "aliexpress_affiliate_productdetail_get_response",
        data.get("resp_result", {}),
    )
    if isinstance(resp_result, dict) and "resp_result" in resp_result:
        resp_result = resp_result["resp_result"]

    result = resp_result.get("result", {}) if isinstance(resp_result, dict) else {}

    products_data = result.get("products", result) if isinstance(result, dict) else {}
    if isinstance(products_data, dict):
        raw_products = products_data.get("product", [])
    elif isinstance(products_data, list):
        raw_products = products_data
    else:
        raw_products = []

    if raw_products:
        items = _normalize_products(raw_products)
        return AliexpressProductDetailResponse(product=items[0])

    return AliexpressProductDetailResponse(product=None)


async def get_affiliate_categories() -> AliexpressCategoryResponse:
    """AliExpress Affiliate 카테고리 조회 API 호출 및 정규화된 응답 반환

    ALIEXPRESS_MOCK_ENABLED=true인 경우 실제 API 호출 없이 모킹 데이터를 반환한다.

    Returns:
        AliexpressCategoryResponse: 정규화된 카테고리 목록

    Raises:
        ValueError: API 에러 응답 또는 자격증명 누락 시
    """
    if _is_mock_enabled():
        return await _get_affiliate_categories_mock()

    params = _build_signed_params(method="aliexpress.affiliate.category.get")

    base_url = os.getenv("ALIEXPRESS_BASE_URL", ALIEXPRESS_API_BASE_URL)

    async with httpx.AsyncClient(timeout=15.0) as client:
        response = await client.post(
            base_url,
            data=params,
            headers={"Content-Type": "application/x-www-form-urlencoded;charset=utf-8"},
        )
        response.raise_for_status()

    data = response.json()

    # 에러 응답 확인
    error_code, error_message = _extract_error(data)
    if error_code:
        raise ValueError(f"AliExpress API 에러: code={error_code}, message={error_message}")

    # 카테고리 응답 파싱: ...resp_result.result.categories.category 경로
    resp_result = data.get("aliexpress_affiliate_category_get_response", data.get("resp_result", {}))
    # 1. instance함수를 통해 resp_result가 딕셔너리인지 확인
    # 2. 딕셔너리가 맞으면 그 안에 "resp_result"키가 있는지 확인
    if isinstance(resp_result, dict) and "resp_result" in resp_result:
        resp_result = resp_result["resp_result"]

    result = resp_result.get("result", {}) if isinstance(resp_result, dict) else {}
    categories_data = result.get("categories", {}) if isinstance(result, dict) else {}
    raw_categories = categories_data.get("category", []) if isinstance(categories_data, dict) else []

    # total: total_record_count 우선, 없으면 items 길이
    total = result.get("total_record_count", result.get("total_result_count", len(raw_categories)))

    items = _normalize_categories(raw_categories)
    return AliexpressCategoryResponse(total=total, items=items)


async def search_affiliate_products(
    keyword: str,
    page_no: int = 1,
    page_size: int = 10,
    sort: str | None = None,
    target_currency: str = "KRW",
    target_language: str = "KO",
    ship_to_country: str = "KR",
    category_ids: str | None = None,
    tracking_id: str | None = None,
) -> AliexpressSearchResponse:
    """AliExpress 검색 URL을 렌더링한 뒤 카드 DOM을 크롤링하고 정규화된 응답을 반환한다.

    ALIEXPRESS_MOCK_ENABLED=true인 경우 실제 브라우저 호출 없이 모킹 데이터를 반환한다.

    Args:
        keyword: 검색 키워드
        page_no: 페이지 번호 (기본 1)
        page_size: 페이지당 결과 수 (기본 10, 최대 50)
        sort: 정렬 기준 (SALE_PRICE_ASC, SALE_PRICE_DESC, LAST_VOLUME_ASC, LAST_VOLUME_DESC)
        target_currency: 타겟 통화 (기본 KRW)
        target_language: 타겟 언어 (기본 KO)
        ship_to_country: 배송 국가 (기본 KR)
        tracking_id: 트래킹 ID (선택)

    Returns:
        AliexpressSearchResponse: 정규화된 검색 결과

    Raises:
        ValueError: 잘못된 파라미터 또는 검색 페이지 차단/크롤링 실패 시
    """
    if _is_mock_enabled():
        return await _search_affiliate_products_mock(
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

    # page_size 최대 50 제한
    page_size = min(page_size, 50)

    if sort:
        allowed_sorts = {"SALE_PRICE_ASC", "SALE_PRICE_DESC", "LAST_VOLUME_ASC", "LAST_VOLUME_DESC"}
        if sort not in allowed_sorts:
            raise ValueError(f"지원하지 않는 정렬값: {sort}. 허용값: {', '.join(allowed_sorts)}")
    raw_products = await _crawl_aliexpress_search_products(
        keyword=keyword,
        page_no=page_no,
        page_size=page_size,
        sort=sort,
    )

    total = len(raw_products)
    start_index = (page_no - 1) * page_size
    end_index = start_index + page_size
    page_items = raw_products[start_index:end_index]

    items = _normalize_products(page_items)
    return AliexpressSearchResponse(
        total=total,
        page_no=page_no,
        page_size=page_size,
        items=items,
    )
