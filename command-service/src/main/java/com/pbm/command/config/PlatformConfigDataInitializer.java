package com.pbm.command.config;

import com.pbm.command.domain.PlatformConfig;
import com.pbm.command.domain.PlatformType;
import com.pbm.command.repository.PlatformConfigRepository;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랫폼 설정 초기 데이터 주입기.
 *
 * 역할: 서버 최초 기동 시 platform_configs 테이블이 비어 있으면 기본 플랫폼 데이터를 삽입한다.
 *       데이터가 이미 있으면 아무 것도 하지 않으므로 재기동 시 중복 삽입이 발생하지 않는다.
 * 동작: ddl-auto=update로 JPA가 테이블을 생성한 직후 ApplicationRunner가 실행된다.
 * 연관: PlatformConfigRepository, PlatformConfig.
 */
@Slf4j
@Component
public class PlatformConfigDataInitializer implements ApplicationRunner {

    private final PlatformConfigRepository platformConfigRepository;

    public PlatformConfigDataInitializer(PlatformConfigRepository platformConfigRepository) {
        this.platformConfigRepository = platformConfigRepository;
    }

    /** 플랫폼별 올바른 메인 페이지 URL 정의 */
    private static final Map<PlatformType, String> MAIN_PAGE_URLS = Map.of(
            PlatformType.ALIEXPRESS, "https://www.aliexpress.com/",
            PlatformType.NAVER,      "https://search.shopping.naver.com/home"
    );

    /**
     * 플랫폼별 올바른 도메인 패턴 정의.
     * NAVER: "shopping.naver.com" → "naver.com" 으로 확장.
     *   smartstore.naver.com, nid.naver.com(로그인), cr.shopping.naver.com(광고 리다이렉트)
     *   등 모든 네이버 하위 도메인을 커버하기 위함.
     */
    private static final Map<PlatformType, String> DOMAIN_PATTERNS = Map.of(
            PlatformType.ALIEXPRESS, "aliexpress.com",
            PlatformType.NAVER,      "naver.com"
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (platformConfigRepository.count() == 0) {
            log.info("platform_configs 초기 데이터를 삽입합니다.");
            insertDefaults();
        } else {
            // 기존 데이터가 있어도 mainPageUrl이 없으면 채워준다
            updateMainPageUrlsIfMissing();
        }
    }

    private void insertDefaults() {
        platformConfigRepository.save(new PlatformConfig(
                PlatformType.ALIEXPRESS,
                "알리익스프레스",
                "aliexpress.com",
                "https://www.aliexpress.com/wholesale?SearchText={keyword}",
                true,
                false,
                MAIN_PAGE_URLS.get(PlatformType.ALIEXPRESS)
        ));

        platformConfigRepository.save(new PlatformConfig(
                PlatformType.NAVER,
                "네이버쇼핑",
                DOMAIN_PATTERNS.get(PlatformType.NAVER),
                "https://search.shopping.naver.com/search/all?query={keyword}",
                true,
                true,
                MAIN_PAGE_URLS.get(PlatformType.NAVER)
        ));

        log.info("platform_configs 초기 데이터 삽입 완료 (ALIEXPRESS, NAVER)");
    }

    private void updateMainPageUrlsIfMissing() {
        // null/blank인 경우뿐 아니라 값이 변경된 경우도 항상 최신값으로 덮어씀
        platformConfigRepository.findAll().forEach(config -> {
            String url = MAIN_PAGE_URLS.get(config.getPlatform());
            if (url != null && !url.equals(config.getMainPageUrl())) {
                config.updateMainPageUrl(url);
                log.info("platform_configs mainPageUrl 업데이트 - platform={}, url={}", config.getPlatform(), url);
            }

            // 도메인 패턴도 최신값으로 업데이트
            // (예: 기존 DB에 "shopping.naver.com" 저장된 경우 → "naver.com" 으로 확장)
            String domain = DOMAIN_PATTERNS.get(config.getPlatform());
            if (domain != null && !domain.equals(config.getDomainPattern())) {
                config.updateDomainPattern(domain);
                log.info("platform_configs domainPattern 업데이트 - platform={}, domain={}", config.getPlatform(), domain);
            }
        });
    }
}
