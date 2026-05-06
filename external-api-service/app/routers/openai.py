"""OpenAI Chat Completions 프록시 라우터 - 자연어 파싱 엔드포인트"""

from fastapi import APIRouter

from app.schemas.openai import OpenAiParseCommandRequest, OpenAiParseCommandResponse
from app.services.openai_service import parse_command

router = APIRouter(prefix="/api/v1/openai", tags=["openai"])


@router.post("/parse-command", response_model=OpenAiParseCommandResponse)
async def parse_user_command(
    request: OpenAiParseCommandRequest,
) -> OpenAiParseCommandResponse:
    """사용자 자연어 명령을 OpenAI Chat Completions로 파싱

    command-service가 system_prompt와 user_prompt를 전송하면,
    OpenAI API를 호출하여 구조화된 파싱 결과를 반환한다.
    반환된 parsed_json을 command-service에서 자체 필드 검증을 이어서 수행한다.
    """
    return await parse_command(request)
