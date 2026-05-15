package com.pbm.price.service;

import com.pbm.price.domain.CategoryNode;
import com.pbm.price.domain.Platform;
import com.pbm.price.repository.CategoryNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.util.StringUtils.hasText;

/**
 * AliExpress API 카테고리 경로 정규화 컴포넌트.
 *
 * AliExpress 검색 결과는 first_level_category_name(1차 카테고리명)과
 * second_level_category_name(2차 카테고리명) 두 단계로 내려온다.
 * 이 중 비어 있지 않은 세그먼트만 골라 "/"로 결합하여 하나의 카테고리 경로 문자열로 만든다.
 * 각 세그먼트는 normalizeSegment()에서 trim, "/" 제거, 공백 축소 처리를 거친다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AliExpressCategoryNormalizer {

    // category_nodes 테이블에서 second_level_category_id 기반 조회를 하기 위한 레포지토리
    private final CategoryNodeRepository categoryNodeRepository;

    /**
     * AliExpress 카테고리 정보를 받아 최종 categoryPath를 반환한다.
     *
     * 우선순위:
     * 1. second_level_category_id -> category_nodes 조회
     * 2. 조회 실패 시 first/second 카테고리명 join fallback
     *
     * @param firstLevelCategoryName  상품 API가 내려준 1차 카테고리명
     * @param secondLevelCategoryId   상품 API가 내려준 2차 카테고리 ID
     * @param secondLevelCategoryName 상품 API가 내려준 2차 카테고리명
     * @return 정규화된 categoryPath, 모두 비어 있으면 null
     */
    public String normalize(String firstLevelCategoryName, String secondLevelCategoryId, String secondLevelCategoryName) {

        // second_level_category_id를 source of truth로 사용
        if (hasText(secondLevelCategoryId)){
            CategoryNode categoryNode = categoryNodeRepository
                    .findByPlatformAndSourceCategoryId(Platform.ALIEXPRESS, secondLevelCategoryId.trim())
                    .orElse(null);

            if (categoryNode != null && hasText(categoryNode.getCategoryPath())){
                // API의 1차 카테고리명과 DB 루트 경로가 다르더라도 DB 경로를 신뢰하고 경고만 남김
                logFirstCategoryMismatchIfNeeded(firstLevelCategoryName, categoryNode.getCategoryPath(), secondLevelCategoryId);
                return categoryNode.getCategoryPath();
            }
        }

        // DB에 매핑이 없으면 기존 방식대로 카테고리명 join
        return buildFallbackPath(firstLevelCategoryName, secondLevelCategoryName);
    }

    // DB조회에 실패했을 때 기존 방식대로 first/second 카테고리명을 조합
    private String buildFallbackPath (String firstLevelCategoryName, String secondLevelCategoryName){
        List<String> segments = new ArrayList<>();

        addIfPresent(segments, firstLevelCategoryName);
        addIfPresent(segments, secondLevelCategoryName);

        if (segments.isEmpty()){
            return null;
        }

        return String.join("/", segments);
    }

    /**
     * 상품 API의 1차 카테고리명과 DB에서 조회한 categoryPath의 루트 세그먼트가 다를 경우 경고 로그를 남긴다.
     *
     * 주의:
     * - 이 불일치는 runtime fallback 조건이 아니다.
     * - second_level_category_id 기준 DB path를 그대로 신뢰한다.
     */
    private void logFirstCategoryMismatchIfNeeded(String firstLevelCategoryName, String categoryPath, String secondLevelCategoryId){
        String normalizedApiFirstCategory = normalizeSegment(firstLevelCategoryName);
        String dbRootCategory = extracRootCategory(categoryPath);

        if (normalizedApiFirstCategory == null || dbRootCategory == null){
            return;
        }

        if (!normalizedApiFirstCategory.equals(dbRootCategory)){
            log.warn("AliExpress 1차 카테고리 불일치 감지 - secondLevelCategoryId: {}, apiFirstCategory: {}, dbRootCategory: {}. DB categoryPath를 사용합니다.",
                    secondLevelCategoryId,
                    normalizedApiFirstCategory,
                    dbRootCategory
            );
        }
    }
    /**
     * categoryPath의 첫 번째 세그먼트를 추출한다.
     *
     * 예:
     * - 전자제품/오디오/이어폰 -> 전자제품
     */
    private String extracRootCategory(String categoryPath){
        if (!hasText(categoryPath)){
            return null;
        }

        String[] segments = categoryPath.split("/");
        if(segments.length == 0){
            return null;
        }

        return normalizeSegment(segments[0]);
    }

    private void addIfPresent(List<String> segments, String value){
        String normalized = normalizeSegment(value);
        if (normalized != null){
            segments.add(normalized);
        }
    }

    /**
     * 개별 카테고리 세그먼트를 정규화한다.
     * - 앞뒤 공백 제거
     * - 슬래시(/) 제거 (경로 구분자와 혼동 방지)
     * - 연속된 공백은 하나의 공백으로 축소
     */
    private String normalizeSegment(String value){
        if (value == null || value.isBlank()){
            return null;
        }

        return value.trim()
                .replace("/", "")
                .replaceAll("\\s+", " ");
    }

}
