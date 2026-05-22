package com.pbm.command.controller;

import com.pbm.command.common.ApiResponse;
import com.pbm.command.service.PlatformConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 플랫폼 설정 컨트롤러.
 *
 * 역할: 크롬 익스텐션이 시작 시 허용 도메인 목록을 조회할 수 있도록 API를 제공한다.
 *       인증 없이 호출 가능한 공개 API다 (플랫폼 목록은 공개 정보).
 * 연관: PlatformConfigService.
 */
@RestController
@RequestMapping("/api/v1/platforms")
public class PlatformConfigController {

    private final PlatformConfigService platformConfigService;

    public PlatformConfigController(PlatformConfigService platformConfigService) {
        this.platformConfigService = platformConfigService;
    }

    /**
     * 익스텐션이 허용할 도메인 패턴 목록을 반환한다.
     *
     * <p>익스텐션은 시작 시 이 API를 호출하여 chrome.storage에 캐싱해두고,
     * content script 활성화 및 탭 유효성 검사 시 사용한다.
     *
     * @return 활성화된 플랫폼의 도메인 패턴 목록. 예: ["aliexpress.com", "shopping.naver.com"]
     */
    @GetMapping("/supported-domains")
    public ResponseEntity<ApiResponse<List<String>>> getSupportedDomains() {
        List<String> domains = platformConfigService.findAllActive()
                .stream()
                .map(config -> config.getDomainPattern())
                .toList();

        return ResponseEntity.ok(ApiResponse.success(domains));
    }
}
