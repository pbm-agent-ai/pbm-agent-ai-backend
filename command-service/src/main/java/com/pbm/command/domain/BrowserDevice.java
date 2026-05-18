package com.pbm.command.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 브라우저 확장프로그램 디바이스(Entity).
 *
 * 역할: 사용자의 특정 브라우저/확장프로그램 인스턴스를 식별하고,
 *       마지막 heartbeat 시각과 메타데이터를 DB에 보관한다.
 * 동작: register 호출 시 생성 또는 재등록되며,
 *       heartbeat 호출 시 마지막 접속 시각(lastSeenAt)과 상태를 갱신한다.
 * 연관: BrowserDeviceStatus, BrowserDeviceService, BrowserDeviceRepository.
 */
@Entity
@Table(name = "browser_devices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class BrowserDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String deviceId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BrowserDeviceStatus status;

    @Column(nullable = false, length = 30)
    private String platform;

    @Column(length = 50)
    private String extensionVersion;

    @Column(length = 255)
    private String browserInfo;

    @Column(nullable = false)
    private LocalDateTime lastSeenAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private BrowserDevice(
            String deviceId,
            Long userId,
            BrowserDeviceStatus status,
            String platform,
            String extensionVersion,
            String browserInfo,
            LocalDateTime lastSeenAt
    ) {
        this.deviceId = deviceId;
        this.userId = userId;
        this.status = status;
        this.platform = platform;
        this.extensionVersion = extensionVersion;
        this.browserInfo = browserInfo;
        this.lastSeenAt = lastSeenAt;
    }

    /**
     * 신규 브라우저 디바이스를 ONLINE 상태로 생성한다.
     *
     * @param userId           현재 로그인한 사용자 ID
     * @param deviceId         확장프로그램이 보관 중인 고유 디바이스 식별자
     * @param platform         플랫폼 문자열 (예: CHROME)
     * @param extensionVersion 확장프로그램 버전
     * @param browserInfo      브라우저/OS 정보
     * @param now              현재 시각
     * @return 신규 BrowserDevice 엔티티
     */
    public static BrowserDevice create(
            Long userId,
            String deviceId,
            String platform,
            String extensionVersion,
            String browserInfo,
            LocalDateTime now
    ) {
        return BrowserDevice.builder()
                .deviceId(deviceId)
                .userId(userId)
                .status(BrowserDeviceStatus.ONLINE)
                .platform(platform)
                .extensionVersion(extensionVersion)
                .browserInfo(browserInfo)
                .lastSeenAt(now)
                .build();
    }

    /**
     * 동일 deviceId를 재등록하면서 소유 사용자와 메타데이터를 갱신한다.
     *
     * @param userId           현재 로그인한 사용자 ID
     * @param platform         플랫폼 문자열
     * @param extensionVersion 확장프로그램 버전
     * @param browserInfo      브라우저/OS 정보
     * @param now              현재 시각
     */
    public void refreshRegistration(
            Long userId,
            String platform,
            String extensionVersion,
            String browserInfo,
            LocalDateTime now
    ) {
        this.userId = userId;
        this.platform = platform;
        this.extensionVersion = extensionVersion;
        this.browserInfo = browserInfo;
        this.status = BrowserDeviceStatus.ONLINE;
        this.lastSeenAt = now;
    }

    /**
     * heartbeat 수신 시 마지막 접속 시각과 상태를 갱신한다.
     *
     * @param now 현재 시각
     */
    public void markHeartbeat(LocalDateTime now) {
        this.status = BrowserDeviceStatus.ONLINE;
        this.lastSeenAt = now;
    }

    /**
     * 향후 스케줄러가 오프라인 판정을 저장할 때 사용한다.
     */
    public void markOffline() {
        this.status = BrowserDeviceStatus.OFFLINE;
    }
}
