package com.pbm.price.repository;

import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.SourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MonitorTarget 엔티티 조회/저장 레포지토리.
 */
public interface MonitorTargetRepository extends JpaRepository<MonitorTarget, Long> {

    Optional<MonitorTarget> findBySourceTypeAndNormalizedKeyword(SourceType sourceType, String normalizedKeyword);

    /**
     * 수집 예정 시각이 도래한 대상 목록을 조회한다.
     * 스케줄러가 주기적으로 이 메서드를 호출하여 만료된 모니터링 대상을 찾는다.
     *
     * @param threshold 기준 시각 (보통 now)
     * @return 수집해야 할 MonitorTarget 목록
     */
    List<MonitorTarget> findByNextFetchAtBefore(Instant threshold);
}