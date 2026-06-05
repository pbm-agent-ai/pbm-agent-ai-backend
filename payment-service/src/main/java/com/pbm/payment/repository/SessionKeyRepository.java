package com.pbm.payment.repository;

import com.pbm.payment.domain.SessionKey;
import com.pbm.payment.domain.SessionKeyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 세션키 JPA 레포지토리.
 */
public interface SessionKeyRepository extends JpaRepository<SessionKey, Long> {

    /**
     * 특정 사용자의 모든 세션키를 조회한다.
     *
     * @param userId 사용자 식별자
     * @return 해당 사용자의 세션키 목록
     */
    List<SessionKey> findByUserId(Long userId);

    /**
     * 특정 사용자의 특정 상태 세션키 목록을 조회한다.
     *
     * @param userId 사용자 식별자
     * @param status 조회할 상태
     * @return 해당 사용자 + 상태의 세션키 목록
     */
    List<SessionKey> findByUserIdAndStatus(Long userId, SessionKeyStatus status);

    /**
     * AI 에이전트 주소로 ACTIVE 세션키를 조회한다.
     * 결제 실행 시 개인키 조회에 사용한다.
     *
     * @param aiAgentAddress AI 에이전트 이더리움 주소
     * @param status         조회할 상태
     * @return 해당 AI 에이전트의 활성 세션키 (존재하지 않을 수 있음)
     */
    Optional<SessionKey> findByAiAgentAddressAndStatus(String aiAgentAddress, SessionKeyStatus status);

    /**
     * 구독 ID로 세션키를 조회한다.
     *
     * @param subscriptionId price-service 구독 ID
     * @return 해당 구독의 세션키 (존재하지 않을 수 있음)
     */
    Optional<SessionKey> findBySubscriptionId(Long subscriptionId);

    /**
     * 구독 ID와 상태로 세션키를 조회한다.
     * 구독 종료 시 ACTIVE 세션키 존재 여부 확인에 사용한다.
     *
     * @param subscriptionId price-service 구독 ID
     * @param status         조회할 상태
     * @return 해당 구독의 특정 상태 세션키 (존재하지 않을 수 있음)
     */
    Optional<SessionKey> findBySubscriptionIdAndStatus(Long subscriptionId, SessionKeyStatus status);

    /**
     * 만료 시각이 지난 ACTIVE 세션키 목록을 조회한다.
     * 만료 스케줄러가 주기적으로 호출하여 DB 상태를 EXPIRED로 전이한다.
     *
     * @param status    조회할 상태 (ACTIVE)
     * @param threshold 기준 시각 (보통 now)
     * @return 만료된 세션키 목록
     */
    List<SessionKey> findByStatusAndExpiresAtBefore(SessionKeyStatus status, Instant threshold);
}
