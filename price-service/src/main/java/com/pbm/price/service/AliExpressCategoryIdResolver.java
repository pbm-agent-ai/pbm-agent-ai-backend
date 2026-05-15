package com.pbm.price.service;

import com.pbm.price.domain.CategoryNode;
import com.pbm.price.domain.Platform;
import com.pbm.price.repository.CategoryNodeRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 내부 searchCategoryHint를 AliExpress category_ids 문자열로 변환하는 서비스.
 *
 * 역할: command-service가 생성한 검색용 세부 카테고리 힌트를 받아,
 *       category_nodes DB에 저장된 AliExpress 카테고리 중 검색에 사용할 ID를 결정한다.
 * 동작: 힌트별로 기대하는 카테고리 경로 키워드를 찾고,
 *       일치하는 가장 깊은 카테고리의 sourceCategoryId를 category_ids 형식으로 반환한다.
 * 연관: CategoryNodeRepository, CategoryNode, PriceTopicConsumer.
 */
@Service
public class AliExpressCategoryIdResolver {

    // 내부 힌트 -> category_path 포함 키워드 매핑.
    // category_nodes 원본을 그대로 노출하지 않고, 제한된 내부 힌트를 DB 카테고리로 연결한다.
    private static final Map<String, List<String>> CATEGORY_PATH_KEYWORDS = Map.ofEntries(
            Map.entry("MOUSE", List.of("mouse")),
            Map.entry("KEYBOARD", List.of("keyboard")),
            Map.entry("EARPHONES", List.of("earphones", "headphones", "earbuds")),
            Map.entry("SPEAKER", List.of("speaker")),
            Map.entry("MONITOR", List.of("monitor")),
            Map.entry("SMARTPHONE", List.of("mobile phone", "cellphones")),
            Map.entry("TABLET", List.of("tablet")),
            Map.entry("LAPTOP", List.of("laptop")),
            Map.entry("SNEAKERS", List.of("sneakers", "running shoes", "casual shoes")),
            Map.entry("APPAREL_TOP", List.of("t shirts", "shirts", "tops")),
            Map.entry("APPAREL_OUTER", List.of("hoodies", "jackets", "sweatshirts", "outerwear"))
    );

    private final CategoryNodeRepository categoryNodeRepository;

    public AliExpressCategoryIdResolver(CategoryNodeRepository categoryNodeRepository) {
        this.categoryNodeRepository = categoryNodeRepository;
    }

    /**
     * searchCategoryHint를 AliExpress category_ids 문자열로 변환한다.
     *
     * @param searchCategoryHint command-service가 생성한 내부 카테고리 힌트
     * @return category_ids 문자열 (없으면 Optional.empty)
     */
    public Optional<String> resolveCategoryIds(String searchCategoryHint) {
        if (searchCategoryHint == null || searchCategoryHint.isBlank()) {
            return Optional.empty();
        }

        List<String> pathKeywords = CATEGORY_PATH_KEYWORDS.get(searchCategoryHint.trim().toUpperCase(Locale.ROOT));
        if (pathKeywords == null || pathKeywords.isEmpty()) {
            return Optional.empty();
        }

        List<CategoryNode> matchedNodes = categoryNodeRepository.findAllByPlatform(Platform.ALIEXPRESS)
                .stream()
                .filter(node -> matchesAnyPathKeyword(node, pathKeywords))
                .toList();

        if (matchedNodes.isEmpty()) {
            return Optional.empty();
        }

        int deepestDepth = matchedNodes.stream()
                .map(CategoryNode::getDepth)
                .max(Integer::compareTo)
                .orElse(0);

        String categoryIds = matchedNodes.stream()
                .filter(node -> node.getDepth() == deepestDepth)
                .map(CategoryNode::getSourceCategoryId)
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));

        return categoryIds.isBlank() ? Optional.empty() : Optional.of(categoryIds);
    }

    private boolean matchesAnyPathKeyword(CategoryNode node, List<String> pathKeywords) {
        String normalizedPath = normalize(node.getCategoryPath());
        String normalizedName = normalize(node.getSourceCategoryName());

        return pathKeywords.stream()
                .map(this::normalize)
                .anyMatch(keyword -> normalizedPath.contains(keyword) || normalizedName.contains(keyword));
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value.toLowerCase(Locale.ROOT)
                .replace("&", " and ")
                .replaceAll("[^0-9a-z가-힣\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
