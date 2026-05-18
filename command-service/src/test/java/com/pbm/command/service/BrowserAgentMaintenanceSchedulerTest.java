package com.pbm.command.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * BrowserAgentMaintenanceScheduler 단위 테스트.
 *
 * 역할: stale 디바이스 오프라인 전환과 승인 만료 스케줄러가
 *       각 서비스 메서드를 올바른 조건에서 호출하는지 검증한다.
 * 연관: BrowserAgentMaintenanceScheduler, BrowserDeviceService, AgentRunService.
 */
@ExtendWith(MockitoExtension.class)
class BrowserAgentMaintenanceSchedulerTest {

    @Mock
    private BrowserDeviceService browserDeviceService;

    @Mock
    private AgentRunService agentRunService;

    private BrowserAgentMaintenanceScheduler browserAgentMaintenanceScheduler;

    @BeforeEach
    void setUp() {
        browserAgentMaintenanceScheduler = new BrowserAgentMaintenanceScheduler(
                browserDeviceService,
                agentRunService,
                90L,
                10L
        );
    }

    @Test
    @DisplayName("stale 디바이스가 있으면 OFFLINE 처리 후 관련 run을 INTERRUPTED로 전환한다")
    void markOfflineDevicesAndInterruptRuns_interruptsAssignedRuns() {
        given(browserDeviceService.markOfflineDevicesBefore(any()))
                .willReturn(List.of("device-1", "device-2"));
        given(agentRunService.interruptRunsAssignedToDevices(Set.of("device-1", "device-2")))
                .willReturn(1);

        browserAgentMaintenanceScheduler.markOfflineDevicesAndInterruptRuns();

        verify(browserDeviceService).markOfflineDevicesBefore(any());
        verify(agentRunService).interruptRunsAssignedToDevices(Set.of("device-1", "device-2"));
    }

    @Test
    @DisplayName("stale 디바이스가 없으면 run 중단 처리를 호출하지 않는다")
    void markOfflineDevicesAndInterruptRuns_withoutOfflineDevices_skipsRunInterrupt() {
        given(browserDeviceService.markOfflineDevicesBefore(any())).willReturn(List.of());

        browserAgentMaintenanceScheduler.markOfflineDevicesAndInterruptRuns();

        verify(browserDeviceService).markOfflineDevicesBefore(any());
        verify(agentRunService, never()).interruptRunsAssignedToDevices(any());
    }

    @Test
    @DisplayName("승인 만료 스케줄러는 timeout 기준 이전 run 만료 처리를 호출한다")
    void expireApprovalRuns_callsExpireApprovalBefore() {
        given(agentRunService.expireApprovalBefore(any())).willReturn(2);

        browserAgentMaintenanceScheduler.expireApprovalRuns();

        verify(agentRunService).expireApprovalBefore(any());
    }
}
