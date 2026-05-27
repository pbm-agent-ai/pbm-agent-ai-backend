"""YouTube 리뷰 자막 AI 분석 서비스

자막 수집(youtube_service) → GPT 분석의 두 단계를 조합하여
상품 순위·장단점·총평을 구조화된 JSON으로 반환한다.

분석 모델: OpenAI Chat Completions API
 - 환경변수: OPENAI_API_KEY, OPENAI_BASE_URL, OPENAI_YOUTUBE_MODEL (기본: gpt-4o-mini)
"""

from __future__ import annotations

import json
import logging
import os
from typing import Optional

import httpx

from app.schemas.youtube import (
    ReviewedProduct,
    YoutubeReviewAnalysisRequest,
    YoutubeReviewAnalysisResponse,
)
from app.schemas.youtube import YoutubeTranscriptRequest
from app.services.youtube_service import fetch_transcript

logger = logging.getLogger(__name__)

OPENAI_DEFAULT_BASE_URL = "https://api.openai.com/v1"
OPENAI_DEFAULT_YOUTUBE_MODEL = "gpt-4o-mini"

# ── 프롬프트 ──────────────────────────────────────────────────────────────────

_SYSTEM_PROMPT = """너는 한국 테크 유튜버 리뷰 영상의 자막을 분석하는 전문가다.
입력으로 유튜버 이름, 카테고리 힌트, 결론/총평 구간의 자막 텍스트가 주어진다.

다음 작업을 수행하라:
1. 자막에 등장하는 모든 상품을 추출한다.
2. 유튜버가 명시적으로 순위를 언급했다면 그 순위를 따른다.
   순위 언급이 없으면 추천 강도를 기준으로 rank를 매긴다.
3. 각 상품의 장점(pros)·단점(cons)을 최대 5개씩 추출한다.
4. 유튜버가 내린 총평(verdict)을 한 문장으로 요약한다.
5. 어떤 사용자에게 적합한지(recommended_for)를 한 문장으로 정리한다.
6. 상품 카테고리를 영문 대문자로 감지한다. 예: EARPHONE, KEYBOARD, VACUUM, MONITOR, SMARTPHONE

반드시 아래 JSON 형식만 반환하라. 설명·마크다운·코드블록 금지.

{
  "category": "EARPHONE",
  "products": [
    {
      "rank": 1,
      "product_name": "QCY T13 ANC",
      "brand": "QCY",
      "pros": ["노이즈캔슬링 우수", "가성비 최강"],
      "cons": ["배터리 짧음"],
      "verdict": "입문용 ANC 이어폰으로 최고의 선택",
      "recommended_for": "처음 ANC 이어폰을 구매하는 분"
    }
  ]
}"""


def _build_user_message(
    youtuber_name: str,
    category_hint: Optional[str],
    transcript_text: str,
) -> str:
    """GPT에게 전달할 user 메시지를 구성한다."""
    category_line = f"카테고리 힌트: {category_hint}" if category_hint else "카테고리 힌트: 없음 (자동 감지)"
    return f"""유튜버: {youtuber_name}
{category_line}

--- 자막 텍스트 ---
{transcript_text}
--- 끝 ---

위 자막을 분석하여 JSON을 반환하라."""


def _get_openai_config() -> tuple[str, str, str]:
    """환경변수에서 OpenAI API 설정을 가져온다."""
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise ValueError("OPENAI_API_KEY 환경변수가 필요합니다.")
    base_url = os.getenv("OPENAI_BASE_URL", OPENAI_DEFAULT_BASE_URL).rstrip("/")
    model = os.getenv("OPENAI_YOUTUBE_MODEL", OPENAI_DEFAULT_YOUTUBE_MODEL)
    return api_key, base_url, model


async def _call_gpt(
    youtuber_name: str,
    category_hint: Optional[str],
    transcript_text: str,
) -> dict:
    """GPT API를 호출하여 리뷰 분석 JSON을 반환한다.

    Args:
        youtuber_name: 유튜버 이름
        category_hint: 상품 카테고리 힌트
        transcript_text: 분석할 자막 텍스트

    Returns:
        GPT가 반환한 분석 결과 dict

    Raises:
        ValueError: API 호출 실패 또는 응답 파싱 실패
    """
    api_key, base_url, model = _get_openai_config()

    request_body = {
        "model": model,
        "max_tokens": 4096,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": _SYSTEM_PROMPT},
            {
                "role": "user",
                "content": _build_user_message(youtuber_name, category_hint, transcript_text),
            },
        ],
    }

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    logger.info(
        "GPT API 호출 시작 - model=%s youtuber=%s category_hint=%s text_len=%d",
        model, youtuber_name, category_hint, len(transcript_text),
    )

    async with httpx.AsyncClient(timeout=60.0) as client:
        response = await client.post(
            f"{base_url}/chat/completions",
            json=request_body,
            headers=headers,
        )
        response.raise_for_status()

    data = response.json()

    # 에러 응답 처리
    if "error" in data:
        raise ValueError(f"GPT API 오류: {data['error'].get('message', str(data['error']))}")

    choices = data.get("choices", [])
    if not choices:
        raise ValueError("GPT 응답에 choices가 없습니다.")

    message = choices[0].get("message", {})
    finish_reason = choices[0].get("finish_reason")
    raw_text = message.get("content", "").strip()

    logger.info("GPT 응답 수신 - finish_reason=%s", finish_reason)

    # JSON 파싱
    try:
        return json.loads(raw_text)
    except json.JSONDecodeError as e:
        logger.error("GPT 응답 JSON 파싱 실패 - raw=%s", raw_text[:500])
        raise ValueError(f"GPT 응답을 JSON으로 파싱할 수 없습니다: {e}") from e


def _parse_products(raw: dict) -> tuple[str, list[ReviewedProduct]]:
    """GPT 응답 dict에서 category와 ReviewedProduct 목록을 추출한다."""
    category = str(raw.get("category") or "UNKNOWN").upper()

    products = []
    for item in raw.get("products", []):
        products.append(
            ReviewedProduct(
                rank=item.get("rank"),
                product_name=item.get("product_name", "알 수 없음"),
                brand=item.get("brand"),
                pros=item.get("pros", []),
                cons=item.get("cons", []),
                verdict=item.get("verdict"),
                recommended_for=item.get("recommended_for"),
            )
        )

    # rank가 있는 것은 rank 순, 없는 것은 뒤로
    products.sort(key=lambda p: (p.rank is None, p.rank or 0))
    return category, products


async def analyze_review(
    request: YoutubeReviewAnalysisRequest,
) -> YoutubeReviewAnalysisResponse:
    """YouTube 영상 자막을 수집하고 GPT로 리뷰를 분석한다.

    동작:
    1. youtube_service.fetch_transcript()로 자막 수집
    2. conclusion_ratio > 0이면 결론 구간만, 0이면 전체 자막 사용
    3. GPT API로 상품 순위·장단점·총평 추출
    4. 구조화된 응답 반환

    Args:
        request: 자막 수집 + 분석 통합 요청

    Returns:
        YoutubeReviewAnalysisResponse
    """
    # 1. 자막 수집
    transcript_request = YoutubeTranscriptRequest(
        video_id=request.video_id,
        languages=request.languages,
        conclusion_ratio=request.conclusion_ratio,
        include_entries=False,
    )
    transcript = await fetch_transcript(transcript_request)

    # 2. 분석 대상 텍스트 결정
    if request.conclusion_ratio > 0.0 and transcript.conclusion_text:
        analysis_text = transcript.conclusion_text
        analysis_start = transcript.conclusion_start_time
        logger.info(
            "결론 구간 분석 - video_id=%s start=%.1fs",
            transcript.video_id, analysis_start or 0,
        )
    else:
        analysis_text = transcript.full_text
        analysis_start = None
        logger.info("전체 자막 분석 - video_id=%s", transcript.video_id)

    if not analysis_text.strip():
        raise ValueError("분석할 자막 텍스트가 비어 있습니다.")

    # 3. GPT 분석
    raw = await _call_gpt(
        youtuber_name=request.youtuber_name,
        category_hint=request.category_hint,
        transcript_text=analysis_text,
    )

    category, products = _parse_products(raw)

    logger.info(
        "리뷰 분석 완료 - video_id=%s youtuber=%s category=%s products=%d",
        transcript.video_id, request.youtuber_name, category, len(products),
    )

    return YoutubeReviewAnalysisResponse(
        video_id=transcript.video_id,
        youtuber_name=request.youtuber_name,
        language=transcript.language,
        is_generated=transcript.is_generated,
        total_duration=transcript.total_duration,
        category=category,
        analyzed_text_start_time=analysis_start,
        products=products,
        raw_conclusion_text=analysis_text,
    )
