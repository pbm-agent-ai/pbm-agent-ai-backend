package com.pbm.price.repository;

import com.pbm.price.domain.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * PriceHistory 엔티티 조회/저장 레포지토리.
 */
public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {
}
