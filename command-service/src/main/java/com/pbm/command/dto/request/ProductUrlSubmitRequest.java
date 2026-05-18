package com.pbm.command.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 플랫폼 상품 URL 직접 입력 요청 DTO.
 *
 * 역할: 사용자가 1차 검색 결과 대신 직접 찾은 쇼핑몰 상품 링크를
 *       command-service로 전달할 때 사용한다.
 * 동작: 비어있는 링크는 제거하고, 중복 링크는 1회만 유지한다.
 *       초기 버전에서는 사용성/안정성을 위해 최대 5개까지 허용한다.
 * 연관: CommandSessionController, CommandExecutionService.
 */
@Schema(description = "사용자 직접 입력 상품 URL 제출 DTO")
public record ProductUrlSubmitRequest(
        @Schema(description = "직접 입력한 상품 URL 목록", example = "[\"https://ko.aliexpress.com/item/1005006918061844.html\"]")
        List<String> productUrls
) {
    public ProductUrlSubmitRequest {
        if (productUrls == null || productUrls.isEmpty()) {
            throw new IllegalArgumentException("productUrls는 최소 1개 이상이어야 합니다.");
        }

        productUrls = productUrls.stream()
                .map(url -> url == null ? null : url.trim())
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .toList();

        if (productUrls.isEmpty()) {
            throw new IllegalArgumentException("productUrls는 최소 1개 이상이어야 합니다.");
        }

        if (productUrls.size() > 5) {
            throw new IllegalArgumentException("productUrls는 최대 5개까지 입력할 수 있습니다.");
        }
    }
}
