package com.pbm.command.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 내부 서비스 간 HTTP 통신 설정.
 *
 * 역할: Eureka 서비스 디스커버리를 통한 로드밸런싱이 적용된 RestTemplate을 제공한다.
 *       lb://price-service 형식의 URL로 price-service를 직접 호출할 때 사용한다.
 *       게이트웨이를 거치지 않는 내부 서비스 간 통신 전용이다.
 * 연관: PriceServiceClient
 */
@Configuration
public class InternalServiceConfig {

    /**
     * Spring Cloud LoadBalancer가 적용된 RestTemplate.
     * lb://service-name 형식의 URL을 Eureka 서비스 목록으로 해석하여 라운드로빈 분산한다.
     */
    @Bean
    @LoadBalanced
    public RestTemplate loadBalancedRestTemplate() {
        return new RestTemplate();
    }
}
