"""YouTube 자막 수집 및 리뷰 분석 API 스키마"""

from typing import Optional
from pydantic import BaseModel, Field


class YoutubeTranscriptRequest(BaseModel):
    """자막 수집 요청 DTO"""

    video_id: str = Field(
        description="YouTube 영상 ID (예: dQw4w9WgXcQ) 또는 전체 URL",
        examples=["dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"],
    )
    languages: list[str] = Field(
        default=["ko", "ko-KR", "en"],
        description="선호 언어 코드 목록. 앞에 있을수록 우선 시도한다.",
    )
    conclusion_ratio: float = Field(
        default=0.3,
        ge=0.0,
        le=1.0,
        description=(
            "전체 영상 시간 중 결론 구간으로 간주할 뒷부분 비율 (0.0~1.0). "
            "0.0이면 conclusion_text를 반환하지 않는다."
        ),
    )
    include_entries: bool = Field(
        default=False,
        description="True이면 응답에 타임스탬프별 entries 배열을 포함한다.",
    )


class YoutubeTranscriptEntry(BaseModel):
    """자막 단위 항목"""

    text: str
    start: float = Field(description="시작 시간 (초)")
    duration: float = Field(description="지속 시간 (초)")


class YoutubeTranscriptResponse(BaseModel):
    """자막 수집 응답 DTO"""

    video_id: str
    language: str = Field(description="실제 수집된 자막의 언어 코드")
    is_generated: bool = Field(description="True이면 유튜브 자동 생성 자막")
    total_duration: float = Field(description="영상 전체 길이 (초)")
    full_text: str = Field(description="전체 자막을 공백으로 이어붙인 텍스트")
    conclusion_text: Optional[str] = Field(
        default=None,
        description="conclusion_ratio 기준으로 잘라낸 뒷부분 자막 텍스트. conclusion_ratio=0이면 null.",
    )
    conclusion_start_time: Optional[float] = Field(
        default=None,
        description="conclusion_text 시작 시각 (초)",
    )
    entries: Optional[list[YoutubeTranscriptEntry]] = Field(
        default=None,
        description="include_entries=True일 때만 포함되는 타임스탬프별 자막 배열",
    )


# ── 리뷰 분석 스키마 ──────────────────────────────────────────────────────────

class YoutubeReviewAnalysisRequest(BaseModel):
    """자막 수집 + AI 리뷰 분석 통합 요청 DTO"""

    video_id: str = Field(
        description="YouTube 영상 ID 또는 전체 URL",
    )
    youtuber_name: str = Field(
        description="유튜버 이름 (예: 잇섭, 귀곰). 분석 컨텍스트로 활용된다.",
    )
    languages: list[str] = Field(
        default=["ko", "ko-KR", "en"],
        description="선호 자막 언어 코드 목록",
    )
    conclusion_ratio: float = Field(
        default=0.3,
        ge=0.0,
        le=1.0,
        description="뒷부분 결론 구간 비율. 0.0이면 전체 자막을 분석한다.",
    )
    category_hint: Optional[str] = Field(
        default=None,
        description="상품 카테고리 힌트 (예: EARPHONE, KEYBOARD, VACUUM). 없으면 AI가 자동 감지.",
    )


class ReviewedProduct(BaseModel):
    """AI가 분석한 상품 1건"""

    rank: Optional[int] = Field(default=None, description="순위 (1위=1). 순위 언급 없으면 null.")
    product_name: str = Field(description="상품명")
    brand: Optional[str] = Field(default=None, description="브랜드명")
    pros: list[str] = Field(default_factory=list, description="장점 목록")
    cons: list[str] = Field(default_factory=list, description="단점 목록")
    verdict: Optional[str] = Field(default=None, description="유튜버의 총평 한 줄")
    recommended_for: Optional[str] = Field(
        default=None, description="어떤 사용자에게 추천하는지"
    )


class YoutubeReviewAnalysisResponse(BaseModel):
    """자막 수집 + AI 리뷰 분석 통합 응답 DTO"""

    video_id: str
    youtuber_name: str
    language: str
    is_generated: bool
    total_duration: float
    category: str = Field(description="AI가 감지하거나 category_hint로 지정한 상품 카테고리")
    analyzed_text_start_time: Optional[float] = Field(
        default=None,
        description="분석에 사용된 자막 구간의 시작 시각 (초). 전체 자막 분석 시 null.",
    )
    products: list[ReviewedProduct] = Field(description="분석된 상품 목록 (순위순)")
    raw_conclusion_text: str = Field(description="AI 분석에 사용된 자막 원문")
