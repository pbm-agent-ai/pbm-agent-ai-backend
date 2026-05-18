package com.pbm.command.service;

import com.pbm.command.config.BrowserAgentTokenUtil;
import com.pbm.command.domain.BrowserDevice;
import com.pbm.command.domain.BrowserDeviceStatus;
import com.pbm.command.dto.request.BrowserDeviceRegisterRequest;
import com.pbm.command.dto.response.AssignedRunResponse;
import com.pbm.command.dto.response.BrowserDeviceRegisterResponse;
import com.pbm.command.dto.response.BrowserHeartbeatResponse;
import com.pbm.command.exception.BrowserDeviceNotFoundException;
import com.pbm.command.exception.InvalidBrowserAgentTokenException;
import com.pbm.command.repository.BrowserDeviceRepository;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 브라우저 디바이스 관리 서비스.
 *
 * 역할: extension 디바이스의 register/heartbeat 흐름과 Redis online 캐시를 조합한다.
 * 동작: DB에는 브라우저 메타데이터와 lastSeenAt을 남기고,
 *       Redis TTL 키로 현재 online 여부를 빠르게 판정할 수 있게 한다.
 * 연관: BrowserDeviceRepository, BrowserDeviceController, StringRedisTemplate.
 */
@Service
@Transactional(readOnly = true)
public class BrowserDeviceService {

    private static final long ONLINE_TTL_SECONDS = 90L;
    private static final String DEFAULT_PLATFORM = "CHROME";

    private final BrowserDeviceRepository browserDeviceRepository;
    private final BrowserAgentTokenUtil browserAgentTokenUtil;
    private final AgentRunService agentRunService;
    private final StringRedisTemplate stringRedisTemplate;

    public BrowserDeviceService(
            BrowserDeviceRepository browserDeviceRepository,
            BrowserAgentTokenUtil browserAgentTokenUtil,
            AgentRunService agentRunService,
            StringRedisTemplate stringRedisTemplate
    ) {
        this.browserDeviceRepository = browserDeviceRepository;
        this.browserAgentTokenUtil = browserAgentTokenUtil;
        this.agentRunService = agentRunService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 브라우저 디바이스를 등록하거나 기존 deviceId를 현재 사용자 기준으로 재등록한다.
     *
     * 동작 흐름:
     * 1. 요청에 deviceId가 없으면 새 UUID 발급
     * 2. 기존 deviceId가 있으면 메타데이터와 userId를 갱신
     * 3. 없으면 신규 BrowserDevice 생성
     * 4. DB 저장 후 Redis online TTL과 user-device 매핑 캐시 갱신
     *
     * @param request 디바이스 등록 요청 DTO
     * @return 등록된 디바이스 응답 DTO
     */
    @Transactional
    public BrowserDeviceRegisterResponse register(BrowserDeviceRegisterRequest request) {
        if (request.pairingToken() == null) {
            throw new InvalidBrowserAgentTokenException("pairing token이 필요합니다.");
        }

        final Long userId;
        try {
            userId = browserAgentTokenUtil.getPairingUserId(request.pairingToken());
        } catch (RuntimeException e) {
            throw new InvalidBrowserAgentTokenException("유효하지 않은 pairing token입니다.");
        }

        LocalDateTime now = LocalDateTime.now();
        String resolvedDeviceId = request.deviceId() != null ? request.deviceId() : UUID.randomUUID().toString();
        String resolvedPlatform = request.platform() != null ? request.platform() : DEFAULT_PLATFORM;

        BrowserDevice browserDevice = browserDeviceRepository.findByDeviceId(resolvedDeviceId)
                .map(existing -> {
                    existing.refreshRegistration(
                            userId,
                            resolvedPlatform,
                            request.extensionVersion(),
                            request.browserInfo(),
                            now
                    );
                    return existing;
                })
                .orElseGet(() -> BrowserDevice.create(
                        userId,
                        resolvedDeviceId,
                        resolvedPlatform,
                        request.extensionVersion(),
                        request.browserInfo(),
                        now
                ));

        BrowserDevice saved = browserDeviceRepository.save(browserDevice);
        cacheOnline(saved.getUserId(), saved.getDeviceId());
        String deviceToken = browserAgentTokenUtil.generateDeviceToken(saved.getUserId(), saved.getDeviceId());
        return BrowserDeviceRegisterResponse.from(saved, deviceToken);
    }

    /**
     * 특정 사용자의 브라우저 디바이스 heartbeat를 반영한다.
     *
     * @param deviceId heartbeat를 보낸 디바이스 식별자
     * @return heartbeat 처리 결과 DTO
     */
    @Transactional
    public BrowserHeartbeatResponse heartbeat(String deviceId) {
        BrowserDevice browserDevice = browserDeviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new BrowserDeviceNotFoundException(
                        "브라우저 디바이스를 찾을 수 없습니다. deviceId=" + deviceId
                ));

        browserDevice.markHeartbeat(LocalDateTime.now());
        BrowserDevice saved = browserDeviceRepository.save(browserDevice);
        cacheOnline(saved.getUserId(), saved.getDeviceId());

        // heartbeat 직후 online 상태가 최신으로 반영되므로,
        // 이 시점에 현재 디바이스에 할당된 run 또는 새로 할당 가능한 queued run을 함께 내려준다.
        AssignedRunResponse assignedRun = agentRunService.getPendingRunForDevice(saved.getDeviceId());
        return BrowserHeartbeatResponse.from(saved, assignedRun);
    }

    /**
     * Redis TTL 기준으로 특정 디바이스가 online 상태인지 확인한다.
     */
    public boolean isOnline(String deviceId) {
        Boolean exists = stringRedisTemplate.hasKey(buildOnlineKey(deviceId));
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 특정 사용자에게 연결된 디바이스 중 하나라도 online이면 true를 반환한다.
     */
    public boolean isAnyDeviceOnlineForUser(Long userId) {
        SetOperations<String, String> setOperations = stringRedisTemplate.opsForSet();
        Set<String> deviceIds = setOperations.members(buildUserDevicesKey(userId));

        if (deviceIds == null || deviceIds.isEmpty()) {
            return false;
        }

        return deviceIds.stream().anyMatch(this::isOnline);
    }

    /**
     * stale 기준을 지난 ONLINE 디바이스를 OFFLINE으로 전환한다.
     *
     * @param threshold 마지막 heartbeat 허용 기준 시각
     * @return OFFLINE으로 바뀐 디바이스 ID 목록
     */
    @Transactional
    public List<String> markOfflineDevicesBefore(LocalDateTime threshold) {
        List<BrowserDevice> staleDevices = browserDeviceRepository.findAllByStatusAndLastSeenAtBefore(
                BrowserDeviceStatus.ONLINE,
                threshold
        );

        staleDevices.forEach(BrowserDevice::markOffline);
        return staleDevices.stream()
                .map(BrowserDevice::getDeviceId)
                .toList();
    }

    private void cacheOnline(Long userId, String deviceId) {
        ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();
        valueOperations.set(buildOnlineKey(deviceId), "ONLINE", Duration.ofSeconds(ONLINE_TTL_SECONDS));

        // userId -> deviceId 매핑은 이후 online 판단 및 다중 디바이스 확장에 사용한다.
        SetOperations<String, String> setOperations = stringRedisTemplate.opsForSet();
        setOperations.add(buildUserDevicesKey(userId), deviceId);
    }

    private String buildOnlineKey(String deviceId) {
        return "browser-device:online:" + deviceId;
    }

    private String buildUserDevicesKey(Long userId) {
        return "browser-device:user:" + userId;
    }
}
