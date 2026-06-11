package com.pbm.price.repository;

import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.Platform;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MonitoringSubscription 엔티티 조회/저장 레포지토리.
 *
 * 역할: 사용자별 모니터링 구독 데이터를 CRUD하며,
 *       스케줄러가 next_check_at 기준으로 수집 대상을 조회하는 쿼리를 제공한다.
 * 연관: MonitoringSubscription.
 */
public interface MonitoringSubscriptionRepository extends JpaRepository<MonitoringSubscription, Long> {

    /**
     * 특정 사용자의 모든 구독을 조회한다.
     *
     * @param userId 사용자 식별자
     * @return 해당 사용자의 MonitoringSubscription 목록
     */
    List<MonitoringSubscription> findByUserId(Long userId);

    /**
     * 특정 사용자의 특정 상품에 대한 구독을 조회한다.
     *
     * @param userId    사용자 식별자
     * @param platform  플랫폼 구분
     * @param productId 플랫폼 내 상품 식별자
     * @return 일치하는 구독 (Optional)
     */
    Optional<MonitoringSubscription> findByUserIdAndPlatformAndProductId(Long userId, Platform platform, String productId);

    /**
     * 특정 명령 세션에 해당하는 구독을 조회한다.
     *
     * @param commandId 명령 세션 ID (UUID)
     * @return 일치하는 구독 (Optional)
     */
    Optional<MonitoringSubscription> findByCommandId(String commandId);

    /**
     * 특정 명령 세션에 속한 모든 구독을 조회한다.
     *
     * @param commandId 명령 세션 ID (UUID)
     * @return 일치하는 구독 목록
     */
    List<MonitoringSubscription> findAllByCommandId(String commandId);

    /**
     * 특정 상품(platform + productId)에 대한 특정 상태의 구독 목록을 조회한다.
     * 가격 수집 후 인라인 조건 평가 시 해당 상품의 ACTIVE 구독을 찾아 목표가를 비교한다.
     *
     * @param platform  플랫폼 구분
     * @param productId 플랫폼 내 상품 식별자
     * @param status    조회할 구독 상태
     * @return 조건에 맞는 구독 목록
     */
    List<MonitoringSubscription> findByPlatformAndProductIdAndStatus(Platform platform, String productId, MonitoringSubscriptionStatus status);

    /**
     * 종료 예정 시각이 지난 ACTIVE 구독 목록을 조회한다.
     * 스케줄러가 주기적으로 이 메서드를 호출하여 기간 만료된 구독을 자동 완료 처리한다.
     *
     * @param status    조회할 구독 상태 (보통 ACTIVE)
     * @param threshold 기준 시각 (보통 now)
     * @return 종료 예정 시각이 지난 MonitoringSubscription 목록
     */
    List<MonitoringSubscription> findByStatusAndScheduledEndAtBefore(MonitoringSubscriptionStatus status, Instant threshold);

    /**
     * 특정 상품(platform + productId)에 대한 특정 상태의 구독 수를 반환한다.
     * 구독 종료 시 남은 ACTIVE 구독이 있는지 확인하여 MonitorTarget 비활성화 여부를 판단한다.
     *
     * @param platform  플랫폼 구분
     * @param productId 플랫폼 내 상품 식별자
     * @param status    조회할 구독 상태
     * @return 조건에 맞는 구독 수
     */
    long countByPlatformAndProductIdAndStatus(Platform platform, String productId, MonitoringSubscriptionStatus status);

    /**
     * 사용자의 ACTIVE URL 모니터링 구독을 모두 조회한다.
     * 스케줄링 판단은 monitor_targets.next_fetch_at에서 하므로 시각 필터 없음.
     *
     * @param userId 사용자 식별자
     * @return ACTIVE 상태인 URL 모니터링 구독 목록
     */
    @Query("SELECT s FROM MonitoringSubscription s " +
           "WHERE s.userId = :userId " +
           "AND s.monitorType = 'URL' " +
           "AND s.status = 'ACTIVE'")
    List<MonitoringSubscription> findActiveUrlSubscriptionsByUserId(@Param("userId") Long userId);
}
