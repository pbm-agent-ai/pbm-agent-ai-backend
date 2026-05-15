package com.pbm.command.service;

import java.util.List;

/**
 * 누락 필드/모호 필드 계산 결과 DTO.
 *
 * 역할: 규칙 기반 필드 검사 결과를 한 객체로 묶어 상위 서비스가 재사용하기 쉽게 만든다.
 * 동작: missingRequiredFields와 ambiguousFields를 불변 리스트로 보정하고,
 *       둘 중 하나라도 값이 있으면 needsClarification을 true로 계산한다.
 * 연관: CommandFieldEvaluationService, CommandParseResponse.
 */
public record FieldEvaluationResult(
        List<String> missingRequiredFields,
        List<String> ambiguousFields,
        boolean needsClarification
) {
    public FieldEvaluationResult {
        missingRequiredFields = missingRequiredFields == null ? List.of() : List.copyOf(missingRequiredFields);
        ambiguousFields = ambiguousFields == null ? List.of() : List.copyOf(ambiguousFields);
        needsClarification = needsClarification
                || !missingRequiredFields.isEmpty()
                || !ambiguousFields.isEmpty();
    }
}
