package com.pbm.auth.repository;

import com.pbm.auth.config.JpaConfig;
import com.pbm.auth.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserRepository의 JPA 쿼리 동작을 검증하는 슬라이스 테스트.
 *
 * 역할: 실제 DB(H2)에 데이터를 저장/조회해 Repository 메서드의 결과를 검증한다.
 * 동작: 테스트 데이터 저장 -> Repository 메서드 호출 -> Optional/boolean/ID 생성 여부 확인.
 * 연관: User 엔티티, UserRepository, JPA Auditing(JpaConfig).
 */
@DataJpaTest
@Import(JpaConfig.class)
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("findByEmail: 존재하는 이메일이면 Optional<User>를 반환한다")
    void findByEmail_returnsUserWhenEmailExists() {
        // given: DB에 조회 대상 이메일을 가진 사용자를 미리 저장한다.
        User savedUser = userRepository.save(createUser("exists@pbm.com", "encoded-password", "테스터"));

        // when: 저장된 이메일로 사용자 조회를 수행한다.
        Optional<User> result = userRepository.findByEmail("exists@pbm.com");

        // then: Optional이 비어있지 않고, 조회된 사용자 정보가 저장값과 동일해야 한다.
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(savedUser.getId());
        assertThat(result.get().getEmail()).isEqualTo("exists@pbm.com");
    }

    @Test
    @DisplayName("findByEmail: 존재하지 않는 이메일이면 Optional.empty를 반환한다")
    void findByEmail_returnsEmptyWhenEmailNotFound() {
        // given: 해당 이메일로 저장된 사용자가 없는 상태를 만든다.
        userRepository.save(createUser("another@pbm.com", "encoded-password", "다른유저"));

        // when: 존재하지 않는 이메일로 조회를 수행한다.
        Optional<User> result = userRepository.findByEmail("not-found@pbm.com");

        // then: 결과는 비어있는 Optional이어야 한다.
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("existsByEmail: 존재하는 이메일이면 true를 반환한다")
    void existsByEmail_returnsTrueWhenEmailExists() {
        // given: 중복 체크 대상 이메일을 가진 사용자를 저장한다.
        userRepository.save(createUser("duplicate@pbm.com", "encoded-password", "중복유저"));

        // when: existsByEmail로 존재 여부를 확인한다.
        boolean exists = userRepository.existsByEmail("duplicate@pbm.com");

        // then: 해당 이메일이 실제로 존재하므로 true가 반환되어야 한다.
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("existsByEmail: 존재하지 않는 이메일이면 false를 반환한다")
    void existsByEmail_returnsFalseWhenEmailNotFound() {
        // given: 저장된 사용자가 없는 이메일을 준비한다.

        // when: existsByEmail로 존재 여부를 확인한다.
        boolean exists = userRepository.existsByEmail("missing@pbm.com");

        // then: 해당 이메일이 없으므로 false가 반환되어야 한다.
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("save: 사용자 저장 시 ID가 생성되고 DB에 영속화된다")
    void save_persistsUserAndGeneratesId() {
        // given: 아직 DB에 저장되지 않은 새 User 엔티티를 만든다.
        User user = createUser("save@pbm.com", "encoded-password", "저장테스트");

        // when: Repository의 save를 호출해 영속화한다.
        User savedUser = userRepository.save(user);

        // then: DB에서 자동 생성한 ID가 채워지고, 재조회도 가능해야 한다.
        assertThat(savedUser.getId()).isNotNull();
        assertThat(userRepository.findById(savedUser.getId())).isPresent();
    }

    private User createUser(String email, String password, String nickname) {
        return User.builder()
                .email(email)
                .password(password)
                .nickname(nickname)
                .role(User.Role.USER)
                .build();
    }
}
