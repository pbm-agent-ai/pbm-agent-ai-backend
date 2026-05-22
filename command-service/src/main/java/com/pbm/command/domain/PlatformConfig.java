package com.pbm.command.domain;

import jakarta.persistence.*;

/**
 * 지원 플랫폼별 설정 엔티티.
 *
 * 역할: 알리익스프레스, 네이버 등 각 쇼핑 플랫폼의 도메인 패턴과 검색 URL 템플릿을 저장한다.
 *       기존에 하드코딩되어 있던 "aliexpress.com", 검색 URL 등을 DB에서 관리하도록 분리한다.
 * 동작: AgentStepPlannerService가 이 설정을 조회하여 플랫폼에 맞는 URL을 동적으로 생성한다.
 *       익스텐션은 /api/v1/platforms/supported-domains API로 허용 도메인 목록을 조회한다.
 * 연관: PlatformType, PlatformConfigRepository, PlatformConfigService.
 */
@Entity
@Table(name = "platform_configs")
public class PlatformConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 플랫폼 식별자 (ALIEXPRESS, NAVER 등).
     * PlatformType enum과 1:1 대응한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 30)
    private PlatformType platform;

    /**
     * 사람이 읽을 수 있는 플랫폼 이름. 예: "알리익스프레스", "네이버쇼핑"
     */
    @Column(nullable = false, length = 50)
    private String displayName;

    /**
     * 도메인 패턴. URL이 이 문자열을 포함하면 해당 플랫폼으로 판단한다.
     * 예: "aliexpress.com", "shopping.naver.com"
     */
    @Column(nullable = false, length = 100)
    private String domainPattern;

    /**
     * 키워드 검색 URL 템플릿. {keyword} 자리에 검색어를 치환한다.
     * 예: "https://www.aliexpress.com/wholesale?SearchText={keyword}"
     *     "https://search.shopping.naver.com/search/all?query={keyword}"
     */
    @Column(nullable = false, length = 300)
    private String searchUrlTemplate;

    /**
     * 이 플랫폼이 현재 활성화되어 있는지 여부.
     * false이면 익스텐션 허용 도메인 목록에서 제외된다.
     */
    @Column(nullable = false)
    private boolean active;

    /**
     * 직접 URL 접근 대신 검색 페이지를 통해 상품에 접근할지 여부.
     * true이면 상품 URL로 바로 이동하지 않고 검색 페이지에서 상품명으로 검색한다.
     * 네이버처럼 직접 URL 접근 시 봇 탐지/차단이 발생하는 플랫폼에 사용한다.
     */
    @Column(nullable = false)
    private boolean preferSearchNavigation;

    /**
     * 플랫폼 진입 시작점 URL. preferSearchNavigation=true인 플랫폼에서 사용한다.
     * null이면 "https://" + domainPattern 으로 폴백한다.
     * 예: "https://shopping.naver.com/ns/home" (네이버쇼핑 메인, 검색창 있음)
     *     "https://www.aliexpress.com/" (알리익스프레스 메인)
     */
    @Column(nullable = true, length = 300)
    private String mainPageUrl;

    protected PlatformConfig() {}

    public PlatformConfig(
            PlatformType platform,
            String displayName,
            String domainPattern,
            String searchUrlTemplate,
            boolean active,
            boolean preferSearchNavigation,
            String mainPageUrl
    ) {
        this.platform = platform;
        this.displayName = displayName;
        this.domainPattern = domainPattern;
        this.searchUrlTemplate = searchUrlTemplate;
        this.active = active;
        this.preferSearchNavigation = preferSearchNavigation;
        this.mainPageUrl = mainPageUrl;
    }

    /** mainPageUrl 없이 생성하는 레거시 생성자 (하위 호환) */
    public PlatformConfig(
            PlatformType platform,
            String displayName,
            String domainPattern,
            String searchUrlTemplate,
            boolean active,
            boolean preferSearchNavigation
    ) {
        this(platform, displayName, domainPattern, searchUrlTemplate, active, preferSearchNavigation, null);
    }

    public void updateMainPageUrl(String mainPageUrl) {
        this.mainPageUrl = mainPageUrl;
    }

    public void updateDomainPattern(String domainPattern) {
        this.domainPattern = domainPattern;
    }

    /**
     * 검색어를 URL 템플릿에 적용하여 검색 URL을 생성한다.
     *
     * @param keyword 검색어 (공백은 + 로 치환)
     * @return 완성된 검색 URL
     */
    public String buildSearchUrl(String keyword) {
        String encoded = keyword == null ? "" : keyword.trim().replace(" ", "+");
        return searchUrlTemplate.replace("{keyword}", encoded);
    }

    /**
     * 플랫폼 메인 페이지 URL을 반환한다.
     * DB에 mainPageUrl이 설정되어 있으면 그 값을 우선 사용하고,
     * 없으면 "https://" + domainPattern 으로 폴백한다.
     */
    public String buildMainPageUrl() {
        if (mainPageUrl != null && !mainPageUrl.isBlank()) {
            return mainPageUrl;
        }
        return "https://" + domainPattern;
    }

    /**
     * 주어진 URL이 이 플랫폼의 도메인인지 확인한다.
     */
    public boolean matchesDomain(String url) {
        if (url == null) {
            return false;
        }
        return url.contains(domainPattern);
    }

    public Long getId() { return id; }
    public PlatformType getPlatform() { return platform; }
    public String getDisplayName() { return displayName; }
    public String getDomainPattern() { return domainPattern; }
    public String getSearchUrlTemplate() { return searchUrlTemplate; }
    public boolean isActive() { return active; }
    public boolean isPreferSearchNavigation() { return preferSearchNavigation; }
    public String getMainPageUrl() { return mainPageUrl; }
}
