package com.pbm.payment.repository;

import com.pbm.payment.domain.TokenTransaction;
import com.pbm.payment.domain.TokenTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigInteger;
import java.util.List;

/**
 * 토큰 충전·차감 내역 레포지토리.
 */
public interface TokenTransactionRepository extends JpaRepository<TokenTransaction, Long> {

    /** 사용자별 전체 거래 내역을 최신순으로 조회한다. */
    List<TokenTransaction> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 사용자별 특정 유형(CHARGE/DEDUCT/FEE)의 거래 내역을 최신순으로 조회한다. */
    List<TokenTransaction> findByUserIdAndTypeOrderByCreatedAtDesc(Long userId, TokenTransactionType type);

    /** 사용자/구독/유형 기준 거래 내역을 최신순으로 조회한다. */
    List<TokenTransaction> findByUserIdAndSubscriptionIdAndTypeOrderByCreatedAtDesc(
            Long userId, Long subscriptionId, TokenTransactionType type);

    /**
     * 사용자의 DB 기반 토큰 잔액을 계산한다.
     * 충전(CHARGE) 합계 - 차감(DEDUCT) 합계 = 잔액.
     * 실시간 잔액은 블록체인 조회를 우선하고, 이 값은 보조 참고용으로 사용한다.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN t.type = 'CHARGE' THEN t.amountWei ELSE -t.amountWei END), 0)
            FROM TokenTransaction t
            WHERE t.userId = :userId AND t.status = 'SUCCESS'
            """)
    BigInteger calculateNetBalance(@Param("userId") Long userId);
}
