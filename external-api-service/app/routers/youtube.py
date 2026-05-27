"""YouTube 자막 수집 및 리뷰 분석 라우터"""

import logging

from fastapi import APIRouter, HTTPException

from app.schemas.youtube import (
    YoutubeTranscriptRequest,
    YoutubeTranscriptResponse,
    YoutubeReviewAnalysisRequest,
    YoutubeReviewAnalysisResponse,
)
from app.services.youtube_service import fetch_transcript
from app.services.youtube_analysis_service import analyze_review

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/youtube", tags=["youtube"])


@router.post("/transcript", response_model=YoutubeTranscriptResponse)
async def get_transcript(request: YoutubeTranscriptRequest) -> YoutubeTranscriptResponse:
    """YouTube 영상의 자막을 수집한다.

    - 영상 ID 또는 전체 URL 모두 허용
    - 선호 언어 순서대로 폴백 (수동 자막 우선, 없으면 자동 생성)
    - conclusion_ratio로 뒷부분 결론 구간 텍스트를 분리하여 반환
    - include_entries=True이면 타임스탬프별 자막 배열도 포함

    **요청 예시**
    ```json
    {
      "video_id": "dQw4w9WgXcQ",
      "languages": ["ko", "en"],
      "conclusion_ratio": 0.3,
      "include_entries": false
    }
    ```
    """
    try:
        return await fetch_transcript(request)
    except ValueError as e:
        logger.warning("자막 수집 실패 - video_id=%s error=%s", request.video_id, e)
        raise HTTPException(status_code=422, detail=str(e))
    except Exception as e:
        logger.exception("자막 수집 중 예상치 못한 오류 - video_id=%s", request.video_id)
        raise HTTPException(status_code=500, detail=f"자막 수집 중 오류가 발생했습니다: {e}")


@router.post("/analyze-review", response_model=YoutubeReviewAnalysisResponse)
async def analyze_youtube_review(
    request: YoutubeReviewAnalysisRequest,
) -> YoutubeReviewAnalysisResponse:
    """YouTube 리뷰 영상 자막을 수집하고 Claude로 상품 순위·장단점을 분석한다.

    동작 순서:
    1. youtube-transcript-api로 자막 수집 (언어 폴백 포함)
    2. conclusion_ratio 기준으로 결론 구간 자막 추출 (0.0이면 전체)
    3. Claude API로 등장 상품의 순위·장단점·총평 추출
    4. 구조화된 JSON 반환

    **요청 예시**
    ```json
    {
      "video_id": "abc123XYZ",
      "youtuber_name": "귀곰",
      "languages": ["ko", "ko-KR", "en"],
      "conclusion_ratio": 0.3,
      "category_hint": "EARPHONE"
    }
    ```

    **응답 예시**
    ```json
    {
      "video_id": "abc123XYZ",
      "youtuber_name": "귀곰",
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
    }
    ```
    """
    try:
        return await analyze_review(request)
    except ValueError as e:
        logger.warning(
            "리뷰 분석 실패 - video_id=%s youtuber=%s error=%s",
            request.video_id, request.youtuber_name, e,
        )
        raise HTTPException(status_code=422, detail=str(e))
    except Exception as e:
        logger.exception(
            "리뷰 분석 중 예상치 못한 오류 - video_id=%s youtuber=%s",
            request.video_id, request.youtuber_name,
        )
        raise HTTPException(status_code=500, detail=f"리뷰 분석 중 오류가 발생했습니다: {e}")
