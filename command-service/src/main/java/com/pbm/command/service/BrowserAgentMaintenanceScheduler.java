package com.pbm.command.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 브라우저 에이전트 유지보수 스케줄러.
 *
 * 역할: heartbeat가 끊긴 디바이스를 OFFLINE으로 전환하고,
 *       해당 디바이스에 묶인 AgentRun을 INTERRUPTED로 바꾸며 승인 만료도 함께 정리한다.
 * 동작: 일정 주기마다 stale threshold / approval timeout 기준 시각을 계산해
 *       BrowserDeviceService와 AgentRunService에 실제 상태 전이를 위임한다.
 * 연관: BrowserDeviceService, AgentRunService.
 */
@Slf4j
@Component
public class BrowserAgentMaintenanceScheduler {

    private final BrowserDeviceService browserDeviceService;
    private final AgentRunService agentRunService;
    private final long staleThresholdSeconds;
    private final long approvalTimeoutMinutes;

    public BrowserAgentMaintenanceScheduler(
            BrowserDeviceService browserDeviceService,
            AgentRunService agentRunService,
            @Value("${browser-agent.stale-threshold-seconds}") long staleThresholdSeconds,
            @Value("${browser-agent.approval-timeout-minutes}") long approvalTimeoutMinutes
    ) {
        this.browserDeviceService = browserDeviceService;
        this.agentRunService = agentRunService;
        this.staleThresholdSeconds = staleThresholdSeconds;
        this.approvalTimeoutMinutes = approvalTimeoutMinutes;
    }

    /**
     * heartbeat가 일정 시간 끊긴 디바이스를 OFFLINE으로 전환하고 관련 run을 중단 감지 상태로 바꾼다.
     */
    @Scheduled(fixedDelayString = "${browser-agent.offline-check-fixed-delay-ms}")
    public void markOfflineDevicesAndInterruptRuns() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(staleThresholdSeconds);
        List<String> offlineDeviceIds = browserDeviceService.markOfflineDevicesBefore(threshold);

        if (offlineDeviceIds.isEmpty()) {
            return;
        }

        int interruptedRuns = agentRunService.interruptRunsAssignedToDevices(Set.copyOf(offlineDeviceIds));
        log.info("browser-agent stale 점검 완료 - offlineDevices={}, interruptedRuns={}", offlineDeviceIds.size(), interruptedRuns);
    }

    /**
     * 승인 대기 시간이 지난 run을 APPROVAL_EXPIRED로 전환한다.
     */
    @Scheduled(fixedDelayString = "${browser-agent.approval-expire-fixed-delay-ms}")
    public void expireApprovalRuns() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(approvalTimeoutMinutes);
        int expiredCount = agentRunService.expireApprovalBefore(threshold);

        if (expiredCount == 0) {
            return;
        }

        log.info("browser-agent 승인 만료 점검 완료 - expiredRuns={}", expiredCount);
    }
}
