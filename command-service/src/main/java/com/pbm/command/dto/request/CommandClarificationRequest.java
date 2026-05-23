package com.pbm.command.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 명령 보완(Clarification) 제출 요청 DTO.
 *
 * 역할: 사용자가 프론트에서 누락/모호 필드를 보완하여 제출한 데이터를 서비스로 전달한다.
 *       기존 free-text(clarificationInput)와 structured answers(answers)를 모두 지원한다.
 *       PRODUCT_SELECTION_REQUIRED 단계의 후보 선택은 별도 ProductSelectionRequest가 담당한다.
 * 동작: commandId는 path param에서 받고, body로는 세 가지 형태 중 하나를 전달받는다.
 *       1) free-text(clarificationInput)
 *       2) structured answers(answers)
 *       toMergedText() 헬퍼로 두 입력을 하나의 텍스트 블록으로 병합하여
 *       현재 MVP 재파싱 플로우에 사용한다.
 * 연관: CommandSessionController, CommandExecutionService.
 */
@Schema(description = "명령 보완 입력 요청 DTO")
public record CommandClarificationRequest(
        @Schema(description = "사용자 자유 텍스트 보완 입력", example = "productCategory는 탄산음료야")
        String clarificationInput,          // 사용자 자유 텍스트 추가 입력 (nullable, e.g. "검은색 270mm")
        @Schema(description = "필드별 구조화 답변 맵", example = "{\"productCategory\":\"BEVERAGE\",\"color\":\"black\"}")
        Map<String, String> answers         // 구조화된 필드별 답변 맵 (nullable, e.g. {"size":"270","platform":"NAVER"})
) {
    public CommandClarificationRequest {
        clarificationInput = clarificationInput == null ? null : clarificationInput.trim();
        // answers는 불변 복사하여 외부에서 맵을 변경할 수 없도록 보호한다
        answers = answers == null ? null : Collections.unmodifiableMap(new TreeMap<>(answers));
    }

    /**
     * clarificationInput과 answers를 하나의 텍스트 블록으로 병합한다.
     * <p>
     * 우선순위:
     * 1. free-text clarificationInput이 있으면 먼저 포함한다.
     * 2. structured answers가 있으면 "key: value" 형식으로 deterministic하게 정렬하여 덧붙인다.
     * <p>
     * 현재 MVP에서는 재파싱 입력으로 사용된다.
     *
     * @return 병합된 텍스트 (두 입력 모두 null/empty이면 빈 문자열)
     */
    public String toMergedText() {
        StringBuilder sb = new StringBuilder();

        if (clarificationInput != null && !clarificationInput.isEmpty()) {
            sb.append(clarificationInput);
        }

        if (answers != null && !answers.isEmpty()) {
            // TreeMap으로 이미 정렬되어 있으므로 deterministic한 순서 보장
            String answersPart = answers.entrySet().stream()
                    .map(entry -> entry.getKey() + ": " + entry.getValue())
                    .collect(Collectors.joining(", "));
            if (!sb.isEmpty()) {
                sb.append(" ");
            }
            sb.append(answersPart);
        }

        return sb.toString();
    }
}
