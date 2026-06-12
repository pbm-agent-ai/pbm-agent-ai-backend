package com.pbm.command.dto.request;

import com.pbm.command.dto.event.ProductCandidateDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 브라우저/익스텐션이 수집한 후보 상품 목록 제출 DTO.
 *
 * 역할: 서버 검색 대신 사용자 브라우저에서 수집한 상품 후보를
 *       command-service 세션에 주입할 때 사용한다.
 */
@Schema(description = "브라우저에서 수집한 후보 상품 제출 DTO")
public record BrowserCandidateSubmitRequest(
        @Schema(description = "브라우저에서 수집한 후보 상품 목록")
        List<ProductCandidateDto> candidates,
        @Schema(description = "브라우저 검색에 사용한 최종 키워드", example = "QCY T13 PRO 블랙")
        String searchKeyword
) {
    public BrowserCandidateSubmitRequest {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates는 최소 1개 이상이어야 합니다.");
        }

        candidates = candidates.stream()
                .filter(candidate -> candidate != null
                        && candidate.productId() != null && !candidate.productId().isBlank()
                        && candidate.productUrl() != null && !candidate.productUrl().isBlank()
                        && candidate.title() != null && !candidate.title().isBlank())
                .limit(30)
                .toList();

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("유효한 candidates가 없습니다.");
        }
    }
}
