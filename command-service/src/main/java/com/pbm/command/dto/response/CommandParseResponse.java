package com.pbm.command.dto.response;

import com.pbm.command.domain.CommandIntent;

import java.util.List;

/**
 * 자연어 명령 파싱 응답 DTO.
 *
 * 역할: 파싱된 intent와 구조화 데이터, 그리고 추가 입력이 필요한 필드를 함께 반환한다.
 * 동작: missingRequiredFields와 ambiguousFields를 항상 빈 리스트 이상으로 보정하고,
 *       둘 중 하나라도 값이 있으면 needsClarification을 true로 맞춰 프론트가 모달을 띄울 수 있게 한다.
 * 연관: ParsedCommand, CommandIntent, ApiResponse.
 */
public record CommandParseResponse(
        CommandIntent intent,
        ParsedCommand parsedCommand,
        List<String> missingRequiredFields,
        List<String> ambiguousFields,
        boolean needsClarification,
        Double confidence,
        String commandId
) {
    public CommandParseResponse {
        missingRequiredFields = missingRequiredFields == null ? List.of() : List.copyOf(missingRequiredFields);
        ambiguousFields = ambiguousFields == null ? List.of() : List.copyOf(ambiguousFields);
        needsClarification = needsClarification
                || !missingRequiredFields.isEmpty()
                || !ambiguousFields.isEmpty();
    }
}
