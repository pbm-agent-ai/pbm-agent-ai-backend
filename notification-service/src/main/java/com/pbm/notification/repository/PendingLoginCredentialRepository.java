package com.pbm.notification.repository;

import com.pbm.notification.domain.PendingLoginCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * 텔레그램 로그인 자격증명 대기 요청 레포지토리.
 */
public interface PendingLoginCredentialRepository extends JpaRepository<PendingLoginCredentialEntity, String> {

    List<PendingLoginCredentialEntity> findAllByExpiresAtBefore(Instant threshold);
}
