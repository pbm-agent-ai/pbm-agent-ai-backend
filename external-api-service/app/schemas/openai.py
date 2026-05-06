"""OpenAI Chat Completions 프록시 요청/응답 스키마

command-service가 자연어 파싱을 위해 시스템 프롬프트와 사용자 프롬프트를 전송하면,
external-api-service가 OpenAI Chat Completions API를 호출하고 정규화된 파싱 결과를 반환한다.
"""

from typing import Any, Optional

from pydantic import BaseModel, Field


class OpenAiParseCommandRequest(BaseModel):
    """command-service가 전송하는 파싱 요청"""
    system_prompt: str = Field(..., description="시스템 프롬프트 (역할 및 응답 형식 지시)")
    user_prompt: str = Field(..., description="사용자 프롬프트 (파싱할 자연어 명령)")


class OpenAiParseCommandResponse(BaseModel):
    """OpenAI Chat Completions 호출 결과를 정규화한 응답

    command-service가 이 응답을 받아 자체 필드 검증을 이어서 수행한다.
    """
    parsed_json: dict[str, Any] = Field(..., description="어시스턴트 JSON content를 파싱한 dict")
    finish_reason: str = Field(..., description="OpenAI 응답의 finish_reason (예: stop)")
    confidence: Optional[float] = Field(None, description="파싱 신뢰도 (모델이 포함한 경우)")
    refusal: Optional[str] = Field(None, description="모델이 요청을 거부한 경우 사유 메시지")
