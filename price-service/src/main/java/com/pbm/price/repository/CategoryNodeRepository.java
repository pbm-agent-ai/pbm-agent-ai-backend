package com.pbm.price.repository;

import com.pbm.price.domain.CategoryNode;
import com.pbm.price.domain.Platform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * CategoryNode 엔티티 조회/저장 레포지토리.
 *
 * 역할: 플랫폼별 카테고리 노드를 조회하고, 원본 카테고리 ID 기준으로 매핑 정보를 찾는다.
 * 동작: AliExpress 카테고리 동기화 시 upsert 대상 조회에 사용하고,
 *       이후 상품 저장 시 categoryPath lookup에도 사용할 수 있다.
 * 연관: CategoryNode, Platform, AliExpressCategorySyncService, AliExpressCategoryNormalizer.
 */
public interface CategoryNodeRepository extends JpaRepository<CategoryNode, Long> {
    /**
     * 플랫폼 + 원본 카테고리 ID로 카테고리 노드 1건을 조회한다.
     *
     * @param platform 플랫폼 구분
     * @param sourceCategoryId 외부 플랫폼 원본 카테고리 ID
     * @return 일치하는 CategoryNode
     */
    Optional<CategoryNode> findByPlatformAndSourceCategoryId(Platform platform, String sourceCategoryId);
    /**
     * 특정 플랫폼의 카테고리 노드 전체를 조회한다.
     *
     * @param platform 플랫폼 구분
     * @return 해당 플랫폼의 전체 카테고리 노드 목록
     */
    List<CategoryNode> findAllByPlatform(Platform platform);
}
