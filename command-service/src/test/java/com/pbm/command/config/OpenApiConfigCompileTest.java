package com.pbm.command.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI(SpringDoc) 의존성 컴파일-세이프티 테스트.
 * springdoc-openapi 의존성이 정상적으로 포함되어 있어야 컴파일되며,
 * Bearer Auth 설정에 필요한 swagger 모델 클래스가 클래스패스에 존재하는지 검증한다.
 */
class OpenApiConfigCompileTest {

    @Test
    @DisplayName("OpenAPI/Swagger 코어 클래스가 클래스패스에 존재해야 한다")
    void openApiCoreClassesShouldBeResolvable() {
        assertThat(OpenAPI.class).isNotNull();
        assertThat(Info.class).isNotNull();
        assertThat(GroupedOpenApi.class).isNotNull();
    }

    @Test
    @DisplayName("SecurityScheme 모델이 Bearer Auth 설정에 필요한 필드를 제공해야 한다")
    void securitySchemeModelShouldSupportBearerAuth() {
        // Bearer Auth 설정에 사용되는 SecurityScheme.Type.HTTP 확인
        assertThat(SecurityScheme.Type.HTTP).isEqualTo(SecurityScheme.Type.HTTP);
        // Bearer Auth 설정에 사용되는 SecurityScheme.In.HEADER 확인
        assertThat(SecurityScheme.In.HEADER).isEqualTo(SecurityScheme.In.HEADER);
        // SecurityRequirement 생성 가능 확인
        assertThat(new SecurityRequirement()).isNotNull();
    }
}
