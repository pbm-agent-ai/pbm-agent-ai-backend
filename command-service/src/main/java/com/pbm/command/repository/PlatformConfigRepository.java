package com.pbm.command.repository;

import com.pbm.command.domain.PlatformConfig;
import com.pbm.command.domain.PlatformType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 플랫폼 설정 JPA 레포지토리.
 *
 * 역할: platform_configs 테이블의 조회를 담당한다.
 * 연관: PlatformConfig, PlatformConfigService.
 */
public interface PlatformConfigRepository extends JpaRepository<PlatformConfig, Long> {

    /** 플랫폼 타입으로 설정을 조회한다. */
    Optional<PlatformConfig> findByPlatform(PlatformType platform);

    /** 활성화된 플랫폼 설정 목록을 조회한다. */
    List<PlatformConfig> findAllByActiveTrue();
}
