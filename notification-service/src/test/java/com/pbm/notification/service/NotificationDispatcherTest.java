package com.pbm.notification.service;

import com.pbm.notification.client.TelegramBotClient;
import com.pbm.notification.domain.NotificationPreference;
import com.pbm.notification.repository.NotificationPreferenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * NotificationDispatcher 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    @Mock
    private NotificationPreferenceRepository preferenceRepository;

    @Mock
    private EmailNotificationSender emailNotificationSender;

    @Mock
    private TelegramBotClient telegramBotClient;

    @Mock
    private ConsoleNotificationSender consoleNotificationSender;

    @InjectMocks
    private NotificationDispatcher notificationDispatcher;

    @Test
    @DisplayName("이메일만 활성화된 사용자에게는 이메일로만 발송한다")
    void dispatch_emailOnly() {
        // given
        Long userId = 1L;
        NotificationPreference preference = NotificationPreference.createDefault(userId);
        preference.enableEmail("user@test.com");

        given(preferenceRepository.findByUserId(userId)).willReturn(Optional.of(preference));

        // when
        notificationDispatcher.dispatch(userId, "제목", "내용");

        // then
        verify(emailNotificationSender).send("user@test.com", "제목", "내용");
        verify(telegramBotClient, never()).sendMessage(any(), any());
        verify(consoleNotificationSender, never()).send(any());
    }

    @Test
    @DisplayName("텔레그램만 활성화된 사용자에게는 텔레그램으로만 발송한다")
    void dispatch_telegramOnly() {
        // given
        Long userId = 1L;
        NotificationPreference preference = NotificationPreference.createDefault(userId);
        preference.linkTelegram("123456789");

        given(preferenceRepository.findByUserId(userId)).willReturn(Optional.of(preference));

        // when
        notificationDispatcher.dispatch(userId, "제목", "내용");

        // then
        verify(telegramBotClient).sendMessage("123456789", "내용");
        verify(emailNotificationSender, never()).send(any(), any(), any());
        verify(consoleNotificationSender, never()).send(any());
    }

    @Test
    @DisplayName("이메일 + 텔레그램 둘 다 활성화된 사용자에게는 양쪽 모두 발송한다")
    void dispatch_bothChannels() {
        // given
        Long userId = 1L;
        NotificationPreference preference = NotificationPreference.createDefault(userId);
        preference.enableEmail("user@test.com");
        preference.linkTelegram("123456789");

        given(preferenceRepository.findByUserId(userId)).willReturn(Optional.of(preference));

        // when
        notificationDispatcher.dispatch(userId, "제목", "내용");

        // then
        verify(emailNotificationSender).send("user@test.com", "제목", "내용");
        verify(telegramBotClient).sendMessage("123456789", "내용");
        verify(consoleNotificationSender, never()).send(any());
    }

    @Test
    @DisplayName("알림 설정이 없는 사용자에게는 콘솔 fallback")
    void dispatch_noPreference_consoleFallback() {
        // given
        Long userId = 1L;
        given(preferenceRepository.findByUserId(userId)).willReturn(Optional.empty());

        // when
        notificationDispatcher.dispatch(userId, "제목", "내용");

        // then
        verify(consoleNotificationSender).send("내용");
        verify(emailNotificationSender, never()).send(any(), any(), any());
        verify(telegramBotClient, never()).sendMessage(any(), any());
    }

    @Test
    @DisplayName("모든 채널이 비활성화된 사용자에게는 콘솔 fallback")
    void dispatch_allDisabled_consoleFallback() {
        // given
        Long userId = 1L;
        NotificationPreference preference = NotificationPreference.createDefault(userId);
        // 모든 채널 비활성화 상태 (기본값)

        given(preferenceRepository.findByUserId(userId)).willReturn(Optional.of(preference));

        // when
        notificationDispatcher.dispatch(userId, "제목", "내용");

        // then
        verify(consoleNotificationSender).send("내용");
        verify(emailNotificationSender, never()).send(any(), any(), any());
        verify(telegramBotClient, never()).sendMessage(any(), any());
    }
}
