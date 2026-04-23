package com.pbm.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication  // 기본 스프링부트 앱 선언
@EnableDiscoveryClient  // Eureka 서버에 이 서비스를 등록하고, 다른 서비스를 lb://서비스명 형식으로 찾을 수 있게 해줌(라우팅에서 사용)
public class GatewayApplication {
    public static void main(String[] args){
        SpringApplication.run(GatewayApplication.class, args);
    }
}
