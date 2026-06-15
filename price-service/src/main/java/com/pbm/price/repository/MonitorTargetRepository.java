package com.pbm.price.repository;

import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.Platform;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MonitorTarget 엔티티 조회/저장 레포지토리.
 */
public interface MonitorTargetRepository extends JpaRepository<MonitorTarget, Long> {

    /**
     * 플랫폼 + 상품 ID로 공통 수집 대상을 조회한다.
     * product-unique 식별 기준이다.
     */
    Optional<MonitorTarget> findByPlatformAndProductId(Platform platform, String productId);

    /**
     * 수집 예정 시각이 도래한 대상 목록을 조회한다.
     * nextFetchAt이 null인 대상은 아직 활성화되지 않은 대상이므로 제외된다.
     * 스케줄러가 주기적으로 이 메서드를 호출하여 만료된 모니터링 대상을 찾는다.
     *
     * @param threshold 기준 시각 (보통 now)
     * @return 수집해야 할 MonitorTarget 목록
     */
    List<MonitorTarget> findByNextFetchAtBefore(Instant threshold);

    /**
     * URL 플랫폼 수집 대상 중 nextFetchAt이 도래했거나 아직 초기화되지 않은 대상을 조회한다.
     * 익스텐션 heartbeat에서 크롤링 대상을 결정할 때 사용된다.
     *
     * @param productIds 대상 productId 목록 (사용자의 ACTIVE URL 구독에서 추출)
     * @param now        현재 시각
     * @return 크롤링이 필요한 MonitorTarget 목록
     */
    @Query("SELECT mt FROM MonitorTarget mt " +
           "WHERE mt.platform = 'URL' " +
           "AND mt.productId IN :productIds " +
           "AND (mt.nextFetchAt IS NULL OR mt.nextFetchAt <= :now)")
    List<MonitorTarget> findDueUrlTargetsByProductIds(@Param("productIds") List<String> productIds,
                                                      @Param("now") Instant now);
}
