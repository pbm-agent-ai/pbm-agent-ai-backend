package com.pbm.price.repository;

import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.Platform;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * 수집 예정 시각이 도래한 ACTIVE 상태의 구독 목록을 조회한다.
     * 스케줄러가 주기적으로 이 메서드를 호출하여 만료된 모니터링 대상을 찾는다.
     *
     * @param threshold 기준 시각 (보통 now)
     * @return 수집해야 할 MonitoringSubscription 목록
     */
    List<MonitoringSubscription> findByStatusAndNextCheckAtBefore(MonitoringSubscriptionStatus status, Instant threshold);
}
