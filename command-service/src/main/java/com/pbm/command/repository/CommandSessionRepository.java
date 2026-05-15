package com.pbm.command.repository;

import com.pbm.command.domain.CommandSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// JpaRepository<엔티티, PK타입> 상속하면 기본 CRUD 자동으로 구현
public interface CommandSessionRepository extends JpaRepository<CommandSession, Long> {

    // commandId(UUID)로 세션 조회 (고유함이 보장됨)
    // findByCommandId: Spring Data JPA의 메서드 이름 자동 구현 기능
    Optional<CommandSession> findByCommandId(String commandId);
}
