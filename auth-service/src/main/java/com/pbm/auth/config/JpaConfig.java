package com.pbm.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing  // User.java의 @CreateDate, @LastModifiedDate 동작하게 해줌
public class JpaConfig {
}
