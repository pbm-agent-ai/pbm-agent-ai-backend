package com.pbm.price.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 네이버 쇼핑 API 카테고리 경로 정규화 컴포넌트.
 *
 * 네이버 쇼핑 검색 결과는 category1(대분류) ~ category4(세분류)까지 4단계로 내려온다.
 * 이 중 비어 있지 않은 세그먼트만 골라 "/"로 결합하여 하나의 카테고리 경로 문자열로 만든다.
 * 각 세그먼트는 normalizeSegment()에서 trim, "/" 제거, 공백 축소 처리를 거친다.
 */
@Component
public class NaverCategoryNormalizer {

    /**
     * 네이버 category1 ~ category4를 받아 정규화된 카테고리 경로를 반환한다.
     *
     * @param category1 대분류 (예: "디지털")
     * @param category2 중분류 (예: "휴대폰")
     * @param category3 소분류 (예: "스마트폰")
     * @param category4 세분류 (예: "아이폰")
     * @return "/"로 결합된 경로 문자열, 모든 값이 비어 있으면 null
     */
    public String normalize(String category1, String category2, String category3, String category4) {
        List<String> segments = new ArrayList<>();

        addIfPresent(segments, category1);
        addIfPresent(segments, category2);
        addIfPresent(segments, category3);
        addIfPresent(segments, category4);

        if (segments.isEmpty()) {
            return null;
        }

        return String.join("/", segments);
    }

    private void addIfPresent(List<String> segments, String value) {
        String normalized = normalizeSegment(value);
        if (normalized != null) {
            segments.add(normalized);
        }
    }

    /**
     * 개별 카테고리 세그먼트를 정규화한다.
     * - 앞뒤 공백 제거
     * - 슬래시(/) 제거 (경로 구분자와 혼동 방지)
     * - 연속된 공백은 하나의 공백으로 축소
     */
    private String normalizeSegment(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim()
                .replace("/", "")
                .replaceAll("\\s+", " ");
    }
}
