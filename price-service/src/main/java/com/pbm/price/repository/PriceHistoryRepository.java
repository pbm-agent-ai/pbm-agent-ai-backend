package com.pbm.price.repository;

import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * PriceHistory 엔티티 조회/저장 레포지토리.
 */
public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

    /**
     * 특정 MonitorTarget의 가장 최근 가격 이력을 조회한다.
     * 가격 변동 감지(중복 INSERT 방지)에 사용된다.
     *
     * @param monitorTarget 수집 대상
     * @return 가장 최근 PriceHistory (없으면 empty)
     */
    Optional<PriceHistory> findTopByMonitorTargetOrderByCheckedAtDesc(MonitorTarget monitorTarget);

    /**
     * 특정 MonitorTarget의 기간별 가격 이력을 최신순으로 조회한다.
     *
     * @param monitorTarget 수집 대상
     * @param since         조회 시작 시각
     * @return 기간 내 PriceHistory 목록 (최신순)
     */
    List<PriceHistory> findByMonitorTargetAndCheckedAtAfterOrderByCheckedAtDesc(
            MonitorTarget monitorTarget, Instant since);

    /**
     * 특정 MonitorTarget의 전체 가격 이력을 최신순으로 조회한다.
     *
     * @param monitorTarget 수집 대상
     * @return 전체 PriceHistory 목록 (최신순)
     */
    List<PriceHistory> findByMonitorTargetOrderByCheckedAtDesc(MonitorTarget monitorTarget);
}
