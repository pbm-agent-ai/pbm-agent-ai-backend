package com.pbm.payment.service;

import com.pbm.payment.domain.SessionKey;
import com.pbm.payment.domain.SessionKeyStatus;
import com.pbm.payment.repository.SessionKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 세션키 만료 스케줄러.
 * <p>
 * 역할: expiresAt이 지난 ACTIVE 세션키를 주기적으로 조회하여 DB 상태를 EXPIRED로 전이한다.
 *       블록체인 on-chain 만료(validSeconds 기반)는 스마트컨트랙트가 자체 처리하므로
 *       이 스케줄러는 DB 상태 동기화만 담당한다.
 * 주기: app.session-key.expiry-check-interval-ms (기본 1시간)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionKeyExpiryScheduler {

    private final SessionKeyRepository sessionKeyRepository;

    @Transactional
    @Scheduled(fixedDelayString = "${app.session-key.expiry-check-interval-ms:3600000}")
    public void expireSessionKeys() {
        Instant now = Instant.now();

        List<SessionKey> expired = sessionKeyRepository.findByStatusAndExpiresAtBefore(
                SessionKeyStatus.ACTIVE, now
        );

        if (expired.isEmpty()) {
            log.debug("만료 처리할 세션키 없음 - now: {}", now);
            return;
        }

        log.info("세션키 만료 처리 시작 - count: {}, now: {}", expired.size(), now);

        for (SessionKey sessionKey : expired) {
            sessionKey.expire();
            sessionKeyRepository.save(sessionKey);
            log.info("세션키 만료 처리 완료 - sessionKeyId: {}, subscriptionId: {}, expiresAt: {}",
                    sessionKey.getId(), sessionKey.getSubscriptionId(), sessionKey.getExpiresAt());
        }

        log.info("세션키 만료 처리 종료 - count: {}", expired.size());
    }
}
