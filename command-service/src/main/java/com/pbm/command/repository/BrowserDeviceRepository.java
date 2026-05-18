package com.pbm.command.repository;

import com.pbm.command.domain.BrowserDevice;
import com.pbm.command.domain.BrowserDeviceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 브라우저 디바이스 JPA 레포지토리.
 *
 * 역할: BrowserDevice 엔티티의 저장/조회 접근을 담당한다.
 * 동작: deviceId 또는 userId 기준으로 디바이스를 조회하여
 *       register/heartbeat/update 흐름에서 사용된다.
 * 연관: BrowserDeviceService, BrowserDevice.
 */
public interface BrowserDeviceRepository extends JpaRepository<BrowserDevice, Long> {

    Optional<BrowserDevice> findByDeviceId(String deviceId);

    Optional<BrowserDevice> findByUserIdAndDeviceId(Long userId, String deviceId);

    Optional<BrowserDevice> findFirstByUserIdAndLastSeenAtAfterOrderByLastSeenAtDesc(Long userId, java.time.LocalDateTime threshold);

    List<BrowserDevice> findAllByStatusAndLastSeenAtBefore(BrowserDeviceStatus status, java.time.LocalDateTime threshold);

    List<BrowserDevice> findAllByUserId(Long userId);
}
