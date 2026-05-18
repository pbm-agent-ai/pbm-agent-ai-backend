package com.pbm.command.dto.response;

import com.pbm.command.domain.CommandIntent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 자연어 명령 파싱 응답 DTO.
 *
 * 역할: 파싱된 intent와 구조화 데이터, 그리고 추가 입력이 필요한 필드를 함께 반환한다.
 * 동작: missingRequiredFields와 ambiguousFields를 항상 빈 리스트 이상으로 보정하고,
 *       둘 중 하나라도 값이 있으면 needsClarification을 true로 맞춰 프론트가 모달을 띄울 수 있게 한다.
 * 연관: ParsedCommand, CommandIntent, ApiResponse.
 */
@Schema(description = "자연어 명령 파싱 결과 응답 DTO")
public record CommandParseResponse(
        @Schema(description = "판단된 명령 의도", example = "AUTO_PURCHASE")
        CommandIntent intent,
        ParsedCommand parsedCommand,
        @Schema(description = "필수 누락 필드 목록")
        List<String> missingRequiredFields,
        @Schema(description = "의미가 모호해 추가 확인이 필요한 필드 목록")
        List<String> ambiguousFields,
        @Schema(description = "추가 입력 필요 여부", example = "false")
        boolean needsClarification,
        @Schema(description = "AI 파싱 신뢰도", example = "0.98")
        Double confidence,
        @Schema(description = "생성된 commandId", example = "97314885-3dfb-4bca-be21-742d2155b098")
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
