package com.pbm.notification.repository;

import com.pbm.notification.domain.PendingOptionSelectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * 텔레그램 옵션 선택 대기 요청 레포지토리.
 */
public interface PendingOptionSelectionRepository extends JpaRepository<PendingOptionSelectionEntity, String> {

    List<PendingOptionSelectionEntity> findAllByExpiresAtBefore(Instant threshold);
}
