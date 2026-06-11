package com.pbm.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화 설정.
 *
 * 역할: @CreatedDate, @LastModifiedDate 어노테이션이 동작하도록 Auditing을 활성화한다.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
