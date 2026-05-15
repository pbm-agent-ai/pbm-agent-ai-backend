package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.domain.CategoryNode;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.response.AliExpressCategoryItem;
import com.pbm.price.repository.CategoryNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AliExpress 카테고리 트리를 외부 API에서 가져와 DB에 동기화하는 서비스.
 *
 * 역할: external-api-service를 통해 AliExpress 카테고리 목록을 조회하고,
 *       parent_category_id를 따라 categoryPath를 계산하여 category_nodes 테이블에 저장한다.
 * 동작: platform + sourceCategoryId 기준으로 기존 row가 있으면 update,
 *       없으면 create 하여 upsert 형태로 동작한다.
 * 연관: ExternalApiClient, CategoryNodeRepository, CategoryNode.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AliExpressCategorySyncService {

    // 부모 체인을 따라 올라갈 때 비정상적인 순환 참조를 조기에 막기 위한 최대 깊이 제한
    private static final int MAX_DEPTH_LIMIT = 100;

    /**
     * depth와 categoryPath 계산 결과를 담는 내부 record.
     *
     * Service 스타일 가이드에 맞춰 내부 멤버 타입은 필드보다 먼저 배치한다.
     * 이렇게 두면 코드를 위에서부터 작성할 때 IDE에서 타입 미완성으로 인한 빨간 줄이 길게 생기는 불편을 줄일 수 있다.
     *
     * @param depth        카테고리 깊이 (루트=0)
     * @param categoryPath "/"로 연결된 전체 카테고리 경로
     */
    private record DepthAndPath(int depth, String categoryPath) {
    }

    // external-api-service를 호출하여 AliExpress categories 원본을 받아오는 클라이언트
    private final ExternalApiClient externalApiClient;

    // category_nodes 테이블 접근용 레포지토리
    private final CategoryNodeRepository categoryNodeRepository;

    /**
     * AliExpress 카테고리 전체를 조회하여 DB에 동기화한다.
     *
     * 동작 흐름:
     * 1. external-api-service를 통해 카테고리 목록 전체 조회
     * 2. category_id 기준 맵 구성
     * 3. 각 카테고리별로 부모를 따라 올라가 categoryPath 계산
     * 4. category_nodes 테이블에 upsert 저장
     */
    @Transactional
    public void syncCategories() {
        // external-api-service에서 카테고리 목록을 가져온다.
        List<AliExpressCategoryItem> categories = externalApiClient.fetchAliExpressCategories();

        if (categories.isEmpty()) {
            log.warn("AliExpress 카테고리 동기화 건너뜀 - 외부 API 응답이 비어 있음");
            return;
        }

        // category_id -> raw category 맵 구성
        Map<String, AliExpressCategoryItem> categoryMap = categories.stream()
                .collect(Collectors.toMap(
                        AliExpressCategoryItem::category_id,
                        category -> category,
                        (existing, replacement) -> replacement
                ));

        // 기존 노드를 한 번에 preload하면 카테고리마다 DB를 다시 조회하지 않아도 된다.
        Map<String, CategoryNode> existingNodeMap = categoryNodeRepository.findAllByPlatform(Platform.ALIEXPRESS)
                .stream()
                .collect(Collectors.toMap(
                        CategoryNode::getSourceCategoryId,
                        node -> node,
                        (existing, replacement) -> replacement
                ));

        List<CategoryNode> nodesToSave = new ArrayList<>(categories.size());
        int syncedCount = 0;

        for (AliExpressCategoryItem category : categories) {
            DepthAndPath depthAndPath = computeDepthAndPath(category, categoryMap);

            CategoryNode categoryNode = existingNodeMap.get(category.category_id());

            if (categoryNode == null) {
                categoryNode = CategoryNode.create(
                        Platform.ALIEXPRESS,
                        category.category_id(),
                        category.category_name(),
                        normalizeParentId(category.parent_category_id()),
                        depthAndPath.depth(),
                        depthAndPath.categoryPath()
                );
            } else {
                // 기존 노드면 최신 스냅샷으로 갱신한다.
                categoryNode.updateSnapshot(
                        category.category_name(),
                        normalizeParentId(category.parent_category_id()),
                        depthAndPath.depth(),
                        depthAndPath.categoryPath()
                );
            }

            nodesToSave.add(categoryNode);
            syncedCount++;
        }

        categoryNodeRepository.saveAll(nodesToSave);
        log.info("AliExpress 카테고리 동기화 완료 - 총 {}건", syncedCount);
    }

    /**
     * 현재 카테고리에서 부모 카테고리를 따라 올라가 depth와 categoryPath를 계산한다.
     *
     * 예:
     * 111 -> 이어폰
     * parent 110 -> 오디오
     * parent 100 -> 전자제품
     * 결과: 전자제품/오디오/이어폰
     *
     * @param category    시작 카테고리
     * @param categoryMap category_id -> category 맵
     * @return 계산된 depth, categoryPath
     */
    DepthAndPath computeDepthAndPath(AliExpressCategoryItem category,
                                     Map<String, AliExpressCategoryItem> categoryMap) {
        if (isRootParent(category.parent_category_id())) {
            return new DepthAndPath(0, normalizeCategoryName(category.category_name()));
        }

        List<String> segments = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        String currentCategoryId = category.category_id();
        int depth = 0;

        while (currentCategoryId != null && !currentCategoryId.isBlank()) {
            if (!visited.add(currentCategoryId)) {
                throw new IllegalStateException(
                        "순환 참조 감지: category_id=" + currentCategoryId
                                + "가 이미 방문한 노드입니다. 카테고리 데이터에 사이클이 존재합니다."
                );
            }

            if (depth > MAX_DEPTH_LIMIT) {
                throw new IllegalStateException(
                        "카테고리 깊이가 최대 제한(" + MAX_DEPTH_LIMIT + ")을 초과했습니다. category_id="
                                + category.category_id() + ", parent chain에 순환 참조가 있을 수 있습니다."
                );
            }

            AliExpressCategoryItem currentCategory = categoryMap.get(currentCategoryId);
            if (currentCategory == null) {
                log.warn("카테고리(ID: {})를 categoryMap에서 찾을 수 없습니다. 현재까지의 경로만 사용합니다.", currentCategoryId);
                break;
            }

            String normalizedName = normalizeCategoryName(currentCategory.category_name());
            if (normalizedName != null) {
                // leaf -> parent -> root 순서로 올라가므로 앞에 쌓기 위해 마지막에 reverse한다.
                segments.add(normalizedName);
            }

            String parentCategoryId = currentCategory.parent_category_id();
            if (isRootParent(parentCategoryId)) {
                break;
            }

            currentCategoryId = parentCategoryId;
            depth++;
        }

        java.util.Collections.reverse(segments);

        if (segments.isEmpty()) {
            return new DepthAndPath(0, "unknown");
        }

        return new DepthAndPath(depth, String.join("/", segments));
    }

    /**
     * 부모 카테고리 ID를 저장 가능한 값으로 정규화한다.
     *
     * 최상위 카테고리는 AliExpress 응답에서 "0"으로 내려올 수 있는데,
     * DB에서는 부모 없음 의미로 null로 바꾸는 쪽이 더 자연스럽다.
     */
    private String normalizeParentId(String parentCategoryId) {
        if (parentCategoryId == null || parentCategoryId.isBlank() || "0".equals(parentCategoryId)) {
            return null;
        }
        return parentCategoryId.trim();
    }

    /**
     * 현재 parentCategoryId가 최상위 루트를 의미하는지 확인한다.
     */
    private boolean isRootParent(String parentCategoryId) {
        return parentCategoryId == null || parentCategoryId.isBlank() || "0".equals(parentCategoryId);
    }

    /**
     * path 세그먼트 1개를 저장 가능한 문자열로 정규화한다.
     *
     * 구현 원칙:
     * - null/blank는 제외
     * - 앞뒤 공백 제거
     * - "/"는 path 구분자와 충돌하므로 제거
     * - 내부 연속 공백은 하나의 공백으로 축소
     */
    private String normalizeCategoryName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim()
                .replace("/", "")
                .replaceAll("\\s+", " ");
    }
}
