package com.pbm.command.client.dto;

import com.pbm.command.domain.CommandIntent;
import com.pbm.command.dto.response.ParsedCommand;

/**
 * OpenAI가 반환하는 구조화 파싱 결과 DTO.
 *
 * 역할: 모델이 생성한 JSON 본문을 우리 서비스의 내부 DTO로 역직렬화한다.
 * 동작: intent, parsedCommand, confidence만 받으며, 이후 CommandParsingService가 필드 검증을 이어서 수행한다.
 * 연관: OpenAiCommandClient, CommandParsingService.
 */
public record OpenAiParsedCommandPayload(
        CommandIntent intent,
        ParsedCommand parsedCommand,
        Double confidence
) {
}
