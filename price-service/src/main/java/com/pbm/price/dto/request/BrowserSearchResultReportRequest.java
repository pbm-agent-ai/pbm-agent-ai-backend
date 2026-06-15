package com.pbm.price.dto.request;

import com.pbm.price.dto.event.ProductCandidateDto;

import java.util.List;

/**
 * 익스텐션이 브라우저 검색 결과를 서버에 보고하는 요청 DTO.
 */
public record BrowserSearchResultReportRequest(
        Long taskId,
        List<ProductCandidateDto> candidates
) {
}
