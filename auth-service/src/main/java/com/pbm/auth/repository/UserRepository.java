package com.pbm.auth.repository;

import com.pbm.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// JpaRepository<엔티티, PK타입> 상속하면 기본 CRUD 자동으로 구현
public interface UserRepository extends JpaRepository<User, Long> {
    // 이메일로 사용자 조회
    Optional<User> findByEmail(String email);
    // 이메일 중복 체크
    boolean existsByEmail(String email);
}
