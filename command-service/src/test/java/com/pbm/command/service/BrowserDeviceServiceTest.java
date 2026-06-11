package com.pbm.command.service;

import com.pbm.command.client.PriceServiceClient;
import com.pbm.command.config.BrowserAgentTokenUtil;
import com.pbm.command.domain.BrowserDevice;
import com.pbm.command.domain.BrowserDeviceStatus;
import com.pbm.command.dto.request.BrowserDeviceRegisterRequest;
import com.pbm.command.dto.response.AssignedRunResponse;
import com.pbm.command.dto.response.BrowserDeviceRegisterResponse;
import com.pbm.command.dto.response.BrowserHeartbeatResponse;
import com.pbm.command.exception.BrowserDeviceNotFoundException;
import com.pbm.command.repository.BrowserDeviceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * BrowserDeviceService 단위 테스트.
 *
 * 역할: 브라우저 디바이스 register/heartbeat와 Redis online 캐시 갱신 동작을 검증한다.
 * 동작: Mock Repository와 Mock StringRedisTemplate을 사용해 서비스 로직만 집중 테스트한다.
 * 연관: BrowserDeviceService, BrowserDeviceRepository.
 */
@ExtendWith(MockitoExtension.class)
class BrowserDeviceServiceTest {

    @Mock
    private BrowserDeviceRepository browserDeviceRepository;

    @Mock
    private BrowserAgentTokenUtil browserAgentTokenUtil;

    @Mock
    private AgentRunService agentRunService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private PriceServiceClient priceServiceClient;

    @InjectMocks
    private BrowserDeviceService browserDeviceService;

    @Test
    @DisplayName("브라우저 디바이스를 신규 등록한다")
    void register_createsNewBrowserDevice() {
        // given
        BrowserDeviceRegisterRequest request = new BrowserDeviceRegisterRequest(
                "pairing-token",
                null,
                "CHROME",
                "1.0.0",
                "macOS Chrome"
        );

        given(browserAgentTokenUtil.getPairingUserId("pairing-token")).willReturn(1L);
        given(browserAgentTokenUtil.generateDeviceToken(anyLong(), anyString())).willReturn("device-token");
        given(browserDeviceRepository.findByDeviceId(anyString())).willReturn(Optional.empty());
        given(browserDeviceRepository.save(any(BrowserDevice.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(stringRedisTemplate.opsForSet()).willReturn(setOperations);

        // when
        BrowserDeviceRegisterResponse response = browserDeviceService.register(request);

        // then
        assertThat(response.deviceId()).isNotBlank();
        assertThat(response.deviceToken()).isEqualTo("device-token");
        assertThat(response.deviceStatus()).isEqualTo(BrowserDeviceStatus.ONLINE);
        verify(browserDeviceRepository).save(any(BrowserDevice.class));
        verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
        verify(setOperations).add("browser-device:user:1", response.deviceId());
    }

    @Test
    @DisplayName("이미 존재하는 deviceId를 현재 사용자 기준으로 재등록한다")
    void register_updatesExistingBrowserDevice() {
        // given
        BrowserDevice existing = BrowserDevice.create(
                1L,
                "device-123",
                "CHROME",
                "0.9.0",
                "old-browser",
                LocalDateTime.now().minusMinutes(10)
        );
        BrowserDeviceRegisterRequest request = new BrowserDeviceRegisterRequest(
                "pairing-token",
                "device-123",
                "CHROME",
                "1.1.0",
                "new-browser"
        );

        given(browserAgentTokenUtil.getPairingUserId("pairing-token")).willReturn(2L);
        given(browserAgentTokenUtil.generateDeviceToken(2L, "device-123")).willReturn("device-token");
        given(browserDeviceRepository.findByDeviceId("device-123")).willReturn(Optional.of(existing));
        given(browserDeviceRepository.save(existing)).willReturn(existing);
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(stringRedisTemplate.opsForSet()).willReturn(setOperations);

        // when
        BrowserDeviceRegisterResponse response = browserDeviceService.register(request);

        // then
        assertThat(response.deviceId()).isEqualTo("device-123");
        assertThat(response.deviceToken()).isEqualTo("device-token");
        assertThat(existing.getUserId()).isEqualTo(2L);
        assertThat(existing.getExtensionVersion()).isEqualTo("1.1.0");
        assertThat(existing.getBrowserInfo()).isEqualTo("new-browser");
    }

    @Test
    @DisplayName("브라우저 디바이스 heartbeat를 반영한다")
    void heartbeat_updatesLastSeenAtCachesOnlineAndReturnsAssignedRun() {
        // given
        BrowserDevice browserDevice = BrowserDevice.create(
                1L,
                "device-123",
                "CHROME",
                "1.0.0",
                "macOS Chrome",
                LocalDateTime.now().minusMinutes(1)
        );
        AssignedRunResponse assignedRunResponse = new AssignedRunResponse("run-1", "agent-token", "cmd-1", "ALIEXPRESS");

        given(browserDeviceRepository.findByDeviceId("device-123"))
                .willReturn(Optional.of(browserDevice));
        given(browserDeviceRepository.save(browserDevice)).willReturn(browserDevice);
        given(agentRunService.getPendingRunForDevice("device-123")).willReturn(assignedRunResponse);
        given(priceServiceClient.getActiveUrlTasks(1L)).willReturn(List.of());
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(stringRedisTemplate.opsForSet()).willReturn(setOperations);

        // when
        BrowserHeartbeatResponse response = browserDeviceService.heartbeat("device-123");

        // then
        assertThat(response.deviceId()).isEqualTo("device-123");
        assertThat(response.deviceStatus()).isEqualTo(BrowserDeviceStatus.ONLINE);
        assertThat(response.assignedRun()).isEqualTo(assignedRunResponse);
        verify(valueOperations).set("browser-device:online:device-123", "ONLINE", Duration.ofSeconds(90));
        verify(setOperations).add("browser-device:user:1", "device-123");
    }

    @Test
    @DisplayName("존재하지 않는 브라우저 디바이스로 heartbeat를 보내면 예외를 던진다")
    void heartbeat_withUnknownDevice_throwsException() {
        // given
        given(browserDeviceRepository.findByDeviceId("missing-device"))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> browserDeviceService.heartbeat("missing-device"))
                .isInstanceOf(BrowserDeviceNotFoundException.class)
                .hasMessageContaining("브라우저 디바이스를 찾을 수 없습니다");
    }

    @Test
    @DisplayName("사용자의 디바이스 중 하나라도 online이면 true를 반환한다")
    void isAnyDeviceOnlineForUser_returnsTrueWhenAnyDeviceOnline() {
        // given
        given(stringRedisTemplate.opsForSet()).willReturn(setOperations);
        given(setOperations.members("browser-device:user:1"))
                .willReturn(Set.of("device-1", "device-2"));
        lenient().when(stringRedisTemplate.hasKey("browser-device:online:device-1")).thenReturn(false);
        given(stringRedisTemplate.hasKey("browser-device:online:device-2")).willReturn(true);

        // when
        boolean result = browserDeviceService.isAnyDeviceOnlineForUser(1L);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("stale 기준 이전 ONLINE 디바이스를 OFFLINE으로 전환한다")
    void markOfflineDevicesBefore_marksOnlineDevicesOffline() {
        BrowserDevice staleDevice = BrowserDevice.create(
                1L,
                "device-123",
                "CHROME",
                "1.0.0",
                "macOS Chrome",
                LocalDateTime.now().minusMinutes(10)
        );

        given(browserDeviceRepository.findAllByStatusAndLastSeenAtBefore(any(), any()))
                .willReturn(List.of(staleDevice));

        List<String> offlineDeviceIds = browserDeviceService.markOfflineDevicesBefore(LocalDateTime.now().minusSeconds(90));

        assertThat(offlineDeviceIds).containsExactly("device-123");
        assertThat(staleDevice.getStatus()).isEqualTo(BrowserDeviceStatus.OFFLINE);
    }
}
