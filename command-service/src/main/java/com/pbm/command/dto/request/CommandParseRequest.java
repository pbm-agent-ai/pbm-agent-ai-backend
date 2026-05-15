package com.pbm.command.dto.request;

/**
 * 자연어 명령 파싱 요청 DTO.
 *
 * 역할: 사용자가 입력한 자연어 명령문을 command-service가 파싱할 수 있도록 전달한다.
 * 동작: userId와 commandText를 받아 GPT 파싱 서비스로 넘기며,
 *       commandText는 앞뒤 공백을 제거하여 불필요한 오탐을 줄인다.
 * 연관: 향후 CommandParseController, CommandParsingService.
 */
public record CommandParseRequest(
        Long userId,
        String commandText
) {
    public CommandParseRequest {
        commandText = commandText == null ? null : commandText.trim();
    }
}
