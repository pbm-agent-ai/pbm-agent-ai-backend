package com.pbm.auth.service;

import com.pbm.auth.domain.User;
import com.pbm.auth.dto.event.UserEvent;
import com.pbm.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KafkaEventPublisher 단위 테스트.
 *
 * 역할: Kafka 이벤트 발행 컴포넌트가 정상적으로 이벤트를 생성·전송하는지,
 *       그리고 Kafka 브로커 장애 시에도 예외를 삼키고 로그만 남기는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class KafkaEventPublisherTest {

    @Mock
    private KafkaTemplate<String, UserEvent> kafkaTemplate;

    @InjectMocks
    private KafkaEventPublisher kafkaEventPublisher;

    private static final String USER_EVENT_TOPIC = "user-event";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(kafkaEventPublisher, "userEventTopic", USER_EVENT_TOPIC);
    }

    @Test
    @DisplayName("정상: 로그인 성공 이벤트를 Kafka에 올바른 토픽과 키로 발행한다")
    void publishLoginSuccessEvent_sendsEventToCorrectTopicAndKey() {
        // given: 정상 사용자
        User user = createUser(1L, "user@pbm.com", "encoded-password", "tester", User.Role.USER);

        // when: 이벤트 발행
        kafkaEventPublisher.publishLoginSuccessEvent(user);

        // then: 올바른 토픽, 키(userId), 이벤트로 KafkaTemplate.send()가 호출되었는지 검증
        ArgumentCaptor<UserEvent> eventCaptor = ArgumentCaptor.forClass(UserEvent.class);
        verify(kafkaTemplate).send(eq(USER_EVENT_TOPIC), eq("1"), eventCaptor.capture());

        UserEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.eventType()).isEqualTo("LOGIN_SUCCESS");
        assertThat(publishedEvent.producer()).isEqualTo("auth-service");
        assertThat(publishedEvent.occurredAt()).isNotNull();
        assertThat(publishedEvent.eventId()).isNotBlank();
        assertThat(publishedEvent.payload().userId()).isEqualTo(1L);
        assertThat(publishedEvent.payload().email()).isEqualTo("user@pbm.com");
        assertThat(publishedEvent.payload().nickname()).isEqualTo("tester");
    }

    @Test
    @DisplayName("장애: KafkaTemplate.send()에서 예외가 발생해도 메서드는 예외를 전파하지 않는다")
    void publishLoginSuccessEvent_kafkaSendThrows_doesNotPropagateException() {
        // given: 정상 사용자
        User user = createUser(1L, "user@pbm.com", "encoded-password", "tester", User.Role.USER);

        // Kafka 브로커 불가/타임아웃 상황 시뮬레이션
        when(kafkaTemplate.send(any(), any(), any()))
                .thenThrow(new RuntimeException("Broker not available"));

        // when & then: 예외가 전파되지 않고 조용히 처리되어야 한다
        assertThatCode(() -> kafkaEventPublisher.publishLoginSuccessEvent(user))
                .doesNotThrowAnyException();
    }

    // 테스트에서 필요한 User 엔티티를 간결하게 만들기 위한 헬퍼 메서드
    private User createUser(Long id, String email, String password, String nickname, User.Role role) {
        User user = User.builder()
                .email(email)
                .password(password)
                .nickname(nickname)
                .role(role)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}