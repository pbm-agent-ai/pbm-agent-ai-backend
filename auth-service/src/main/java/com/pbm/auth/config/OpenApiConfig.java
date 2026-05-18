package com.pbm.auth.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI에서 JWT Bearer 인증을 사용할 수 있도록 OpenAPI 보안 스키마를 등록한다.
 *
 * 역할: auth-service Swagger 우측 상단에 Authorize 버튼을 노출하고,
 *       사용자가 Bearer 토큰을 입력해 인증이 필요한 API를 테스트할 수 있게 한다.
 * 동작: HTTP bearer(JWT) 스키마를 전역으로 선언한다.
 * 연관: AuthController, springdoc-openapi.
 */
@Configuration
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {
}
