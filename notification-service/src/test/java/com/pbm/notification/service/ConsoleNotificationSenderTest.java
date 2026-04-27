package com.pbm.notification.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ConsoleNotificationSender 단위 테스트.
 *
 * 역할: 콘솔(로그) 출력 발송처리기가 메시지를 정상적으로 처리하는지 검증한다.
 * 동작: send() 메서드 호출 시 로그 출력이 예외 없이 완료되는지 확인한다.
 * 연관: ConsoleNotificationSender, NotificationSender.
 *
 * 검증 관점:
 * - null 메시지, 빈 문자열, 일반 메시지 모두 예외 없이 처리되어야 한다.
 * - 실제 로그 출력은 SLF4J에 위임하므로, 예외 발생 여부만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ConsoleNotificationSenderTest {

    // 테스트 대상: 콘솔 발송처리기 (의존성 없음)
    @InjectMocks
    private ConsoleNotificationSender consoleNotificationSender;

    /**
     * 정상 메시지를 전송할 때 예외가 발생하지 않는지 검증한다.
     *
     * 검증 내용:
     * - 일반적인 알림 메시지 문자열이 send()에 전달될 때 예외 없이 처리된다
     */
    @Test
    @DisplayName("정상: 일반 메시지를 전송하면 예외 없이 로그를 출력한다")
    void send_normalMessage_completesWithoutException() {
        // given: 포맷팅된 일반 알림 메시지
        String message = "💰 가격 알림\n사용자 ID: 1\n상품명: 아이폰 16 Pro 256GB\n현재 가격: 1,200,000원";

        // when & then: send() 호출 시 예외가 발생하지 않아야 한다
        assertThatCode(() -> consoleNotificationSender.send(message))
                .doesNotThrowAnyException();
    }

    /**
     * 빈 문자열 메시지를 전송할 때 예외가 발생하지 않는지 검증한다.
     *
     * 검증 내용:
     * - 빈 문자열도 로그 출력에는 문제가 없어야 한다
     */
    @Test
    @DisplayName("정상: 빈 문자열 메시지도 예외 없이 처리한다")
    void send_emptyMessage_completesWithoutException() {
        // given: 빈 문자열 메시지
        String message = "";

        // when & then: 빈 문자열도 예외 없이 처리되어야 한다
        assertThatCode(() -> consoleNotificationSender.send(message))
                .doesNotThrowAnyException();
    }

    /**
     * null 메시지를 전송할 때 예외가 발생하지 않는지 검증한다.
     *
     * 검증 내용:
     * - null 메시지도 로그 출력에는 문제가 없어야 한다 (SLF4J가 null을 처리함)
     */
    @Test
    @DisplayName("정상: null 메시지도 예외 없이 처리한다")
    void send_nullMessage_completesWithoutException() {
        // given: null 메시지
        String message = null;

        // when & then: null도 예외 없이 처리되어야 한다
        assertThatCode(() -> consoleNotificationSender.send(message))
                .doesNotThrowAnyException();
    }
}