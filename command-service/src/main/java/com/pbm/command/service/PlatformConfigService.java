package com.pbm.command.service;

import com.pbm.command.domain.PlatformConfig;
import com.pbm.command.domain.PlatformType;
import com.pbm.command.repository.PlatformConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 플랫폼 설정 서비스.
 *
 * 역할: platform_configs 테이블에서 플랫폼별 도메인 패턴과 검색 URL 템플릿을 조회한다.
 *       AgentStepPlannerService와 PlatformConfigController가 이 서비스를 통해 설정을 읽는다.
 * 연관: PlatformConfigRepository, AgentStepPlannerService, PlatformConfigController.
 */
@Service
@Transactional(readOnly = true)
public class PlatformConfigService {

    private final PlatformConfigRepository platformConfigRepository;

    public PlatformConfigService(PlatformConfigRepository platformConfigRepository) {
        this.platformConfigRepository = platformConfigRepository;
    }

    /**
     * 플랫폼 타입으로 설정을 조회한다.
     *
     * @param platform 조회할 플랫폼
     * @return 설정이 있으면 Optional에 담아 반환, 없으면 Optional.empty()
     */
    public Optional<PlatformConfig> findByPlatform(PlatformType platform) {
        return platformConfigRepository.findByPlatform(platform);
    }

    /**
     * 활성화된 모든 플랫폼 설정 목록을 반환한다.
     * 익스텐션이 허용 도메인 목록을 조회할 때 사용한다.
     */
    public List<PlatformConfig> findAllActive() {
        return platformConfigRepository.findAllByActiveTrue();
    }
}
