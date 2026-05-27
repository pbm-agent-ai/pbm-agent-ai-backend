"""YouTube 자막 수집 서비스

youtube-transcript-api를 이용해 영상 자막을 수집하고,
결론 구간(뒷부분 N%)을 분리하여 반환한다.

지원 기능:
- 영상 ID 또는 전체 URL 입력 모두 허용
- 선호 언어 순서대로 폴백 (ko → ko-KR → en)
- 수동 자막 우선, 없으면 자동 생성 자막 사용
- conclusion_ratio로 뒷부분 결론 구간 텍스트 분리
"""

from __future__ import annotations

import logging
import re
from typing import Optional

from youtube_transcript_api import (
    YouTubeTranscriptApi,
    NoTranscriptFound,
    TranscriptsDisabled,
    VideoUnavailable,
)
try:
    from youtube_transcript_api._errors import YouTubeRequestFailed
except ImportError:
    # v1.1+ 에서 모듈 경로 변경
    from youtube_transcript_api import YouTubeRequestFailed  # type: ignore

# v1.1+: 인스턴스 기반 API 사용
_yta = YouTubeTranscriptApi()

from app.schemas.youtube import (
    YoutubeTranscriptEntry,
    YoutubeTranscriptRequest,
    YoutubeTranscriptResponse,
)

logger = logging.getLogger(__name__)

# URL에서 video ID를 추출하기 위한 패턴
_VIDEO_ID_PATTERNS = [
    re.compile(r"(?:v=|youtu\.be/)([A-Za-z0-9_-]{11})"),  # 일반 URL / 단축 URL
    re.compile(r"^([A-Za-z0-9_-]{11})$"),                 # ID만 입력한 경우
]


def _extract_video_id(video_id_or_url: str) -> str:
    """영상 ID 또는 URL에서 11자리 video ID를 추출한다.

    Args:
        video_id_or_url: YouTube 영상 ID 또는 전체/단축 URL

    Returns:
        11자리 video ID 문자열

    Raises:
        ValueError: video ID를 추출할 수 없는 경우
    """
    text = video_id_or_url.strip()
    for pattern in _VIDEO_ID_PATTERNS:
        match = pattern.search(text)
        if match:
            return match.group(1)
    raise ValueError(f"YouTube video ID를 추출할 수 없습니다: {video_id_or_url!r}")


def _fetch_transcript(video_id: str, languages: list[str]) -> tuple[list[dict], str, bool]:
    """youtube-transcript-api를 통해 자막을 가져온다.

    수동 자막 → 자동 생성 자막 순으로 시도한다.

    Args:
        video_id: 11자리 YouTube video ID
        languages: 선호 언어 코드 목록

    Returns:
        (entries, language_code, is_generated) 튜플

    Raises:
        ValueError: 자막을 가져올 수 없는 경우
    """
    try:
        transcript_list = _yta.list(video_id)
    except VideoUnavailable:
        raise ValueError(f"영상을 찾을 수 없습니다: {video_id}")
    except TranscriptsDisabled:
        raise ValueError(f"자막이 비활성화된 영상입니다: {video_id}")
    except YouTubeRequestFailed as e:
        raise ValueError(f"YouTube API 요청 실패: {e}")

    # 1차: 수동 업로드 자막
    for lang in languages:
        try:
            transcript = transcript_list.find_manually_created_transcript([lang])
            fetched = transcript.fetch()
            logger.info("수동 자막 수집 완료 - video_id=%s language=%s", video_id, transcript.language_code)
            return _snippets_to_dicts(fetched), transcript.language_code, False
        except NoTranscriptFound:
            continue

    # 2차: 자동 생성 자막
    for lang in languages:
        try:
            transcript = transcript_list.find_generated_transcript([lang])
            fetched = transcript.fetch()
            logger.info("자동 생성 자막 수집 완료 - video_id=%s language=%s", video_id, transcript.language_code)
            return _snippets_to_dicts(fetched), transcript.language_code, True
        except NoTranscriptFound:
            continue

    raise ValueError(
        f"요청한 언어({languages})의 자막을 찾을 수 없습니다. video_id={video_id}"
    )


def _snippets_to_dicts(fetched) -> list[dict]:
    """FetchedTranscript(또는 snippet 목록)을 dict 목록으로 변환한다.

    v1.x의 FetchedTranscript는 이터러블이며 각 요소가 FetchedTranscriptSnippet이다.
    """
    result = []
    for e in fetched:
        if isinstance(e, dict):
            result.append(e)
        else:
            result.append({"text": e.text, "start": e.start, "duration": e.duration})
    return result


def _entries_to_dicts(entries: list) -> list[dict]:
    """이미 변환된 dict 목록을 그대로 반환한다 (하위 호환용)."""
    return _snippets_to_dicts(entries)


def _build_conclusion(
    entries: list[dict],
    total_duration: float,
    conclusion_ratio: float,
) -> tuple[Optional[str], Optional[float]]:
    """전체 자막에서 결론 구간(뒷부분 N%)을 분리하여 텍스트와 시작 시각을 반환한다.

    Args:
        entries: 전체 자막 entry 목록
        total_duration: 영상 전체 길이 (초)
        conclusion_ratio: 뒷부분 비율 (0.0~1.0)

    Returns:
        (conclusion_text, conclusion_start_time) 또는 (None, None)
    """
    if conclusion_ratio <= 0.0 or not entries:
        return None, None

    conclusion_start = total_duration * (1.0 - conclusion_ratio)
    conclusion_entries = [e for e in entries if e["start"] >= conclusion_start]

    if not conclusion_entries:
        return None, None

    text = " ".join(e["text"].strip() for e in conclusion_entries if e["text"].strip())
    return text, conclusion_entries[0]["start"]


async def fetch_transcript(request: YoutubeTranscriptRequest) -> YoutubeTranscriptResponse:
    """YouTube 영상의 자막을 수집하여 정규화된 응답으로 반환한다.

    Args:
        request: 자막 수집 요청 DTO

    Returns:
        YoutubeTranscriptResponse: 전체/결론 자막 텍스트 포함 응답

    Raises:
        ValueError: video ID 파싱 실패 또는 자막 수집 실패
    """
    video_id = _extract_video_id(request.video_id)
    logger.info("자막 수집 시작 - video_id=%s languages=%s", video_id, request.languages)

    raw_entries, language_code, is_generated = _fetch_transcript(video_id, request.languages)
    entries = _entries_to_dicts(raw_entries)

    if not entries:
        raise ValueError(f"자막이 비어 있습니다: {video_id}")

    # 전체 영상 길이: 마지막 entry의 start + duration
    last = entries[-1]
    total_duration = last["start"] + last.get("duration", 0.0)

    # 전체 자막 텍스트
    full_text = " ".join(e["text"].strip() for e in entries if e["text"].strip())

    # 결론 구간 분리
    conclusion_text, conclusion_start_time = _build_conclusion(
        entries, total_duration, request.conclusion_ratio
    )

    logger.info(
        "자막 수집 완료 - video_id=%s entries=%d total_duration=%.1fs conclusion_start=%.1fs",
        video_id,
        len(entries),
        total_duration,
        conclusion_start_time or 0.0,
    )

    return YoutubeTranscriptResponse(
        video_id=video_id,
        language=language_code,
        is_generated=is_generated,
        total_duration=total_duration,
        full_text=full_text,
        conclusion_text=conclusion_text,
        conclusion_start_time=conclusion_start_time,
        entries=(
            [YoutubeTranscriptEntry(**e) for e in entries]
            if request.include_entries
            else None
        ),
    )
