package com.pbm.notification.service;

import com.pbm.notification.domain.NotificationPreference;
import com.pbm.notification.dto.request.NotificationPreferenceRequest;
import com.pbm.notification.dto.response.NotificationPreferenceResponse;
import com.pbm.notification.repository.NotificationPreferenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * NotificationPreferenceService 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceTest {

    @Mock
    private NotificationPreferenceRepository preferenceRepository;

    @InjectMocks
    private NotificationPreferenceService preferenceService;

    @Test
    @DisplayName("알림 설정 조회 - 기존 설정이 있으면 그대로 반환한다")
    void getPreference_existingPreference_returnsIt() {
        // given
        Long userId = 1L;
        NotificationPreference preference = NotificationPreference.createDefault(userId);
        preference.enableEmail("test@test.com");

        given(preferenceRepository.findByUserId(userId)).willReturn(Optional.of(preference));

        // when
        NotificationPreferenceResponse response = preferenceService.getPreference(userId);

        // then
        assertThat(response.email()).isEqualTo("test@test.com");
        assertThat(response.emailEnabled()).isTrue();
        assertThat(response.telegramLinked()).isFalse();
        assertThat(response.telegramEnabled()).isFalse();
    }

    @Test
    @DisplayName("알림 설정 조회 - 설정이 없으면 기본값으로 생성한다")
    void getPreference_noPreference_createsDefault() {
        // given
        Long userId = 1L;
        given(preferenceRepository.findByUserId(userId)).willReturn(Optional.empty());
        given(preferenceRepository.save(any())).willAnswer(i -> i.getArgument(0));

        // when
        NotificationPreferenceResponse response = preferenceService.getPreference(userId);

        // then
        assertThat(response.emailEnabled()).isFalse();
        assertThat(response.telegramEnabled()).isFalse();
        assertThat(response.telegramLinked()).isFalse();
        verify(preferenceRepository).save(any(NotificationPreference.class));
    }

    @Test
    @DisplayName("알림 설정 수정 - 이메일 활성화")
    void updatePreference_enableEmail() {
        // given
        Long userId = 1L;
        NotificationPreference preference = NotificationPreference.createDefault(userId);
        given(preferenceRepository.findByUserId(userId)).willReturn(Optional.of(preference));
        given(preferenceRepository.save(any())).willAnswer(i -> i.getArgument(0));

        NotificationPreferenceRequest request = new NotificationPreferenceRequest(
                "user@example.com", true, false
        );

        // when
        NotificationPreferenceResponse response = preferenceService.updatePreference(userId, request);

        // then
        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.emailEnabled()).isTrue();
    }

    @Test
    @DisplayName("알림 설정 수정 - 텔레그램 활성화 (chatId 연동 후)")
    void updatePreference_enableTelegram_afterLinked() {
        // given
        Long userId = 1L;
        NotificationPreference preference = NotificationPreference.createDefault(userId);
        preference.linkTelegram("123456789");  // 이미 연동됨

        given(preferenceRepository.findByUserId(userId)).willReturn(Optional.of(preference));
        given(preferenceRepository.save(any())).willAnswer(i -> i.getArgument(0));

        NotificationPreferenceRequest request = new NotificationPreferenceRequest(
                null, false, true
        );

        // when
        NotificationPreferenceResponse response = preferenceService.updatePreference(userId, request);

        // then
        assertThat(response.telegramEnabled()).isTrue();
        assertThat(response.telegramLinked()).isTrue();
    }

    @Test
    @DisplayName("알림 설정 수정 - chatId 미연동 상태에서 텔레그램 활성화 시도하면 비활성화 유지")
    void updatePreference_enableTelegram_withoutLink_staysDisabled() {
        // given
        Long userId = 1L;
        NotificationPreference preference = NotificationPreference.createDefault(userId);
        // chatId 미연동 상태

        given(preferenceRepository.findByUserId(userId)).willReturn(Optional.of(preference));
        given(preferenceRepository.save(any())).willAnswer(i -> i.getArgument(0));

        NotificationPreferenceRequest request = new NotificationPreferenceRequest(
                null, false, true  // 텔레그램 활성화 시도
        );

        // when
        NotificationPreferenceResponse response = preferenceService.updatePreference(userId, request);

        // then
        assertThat(response.telegramEnabled()).isFalse(); // 연동 안 됐으므로 비활성화 유지
    }

    @Test
    @DisplayName("텔레그램 연동 - chatId가 저장된다")
    void linkTelegram_savesChatId() {
        // given
        Long userId = 1L;
        NotificationPreference preference = NotificationPreference.createDefault(userId);
        given(preferenceRepository.findByUserId(userId)).willReturn(Optional.of(preference));
        given(preferenceRepository.save(any())).willAnswer(i -> i.getArgument(0));

        // when
        preferenceService.linkTelegram(userId, "987654321");

        // then
        ArgumentCaptor<NotificationPreference> captor = ArgumentCaptor.forClass(NotificationPreference.class);
        verify(preferenceRepository).save(captor.capture());

        NotificationPreference saved = captor.getValue();
        assertThat(saved.getTelegramChatId()).isEqualTo("987654321");
        assertThat(saved.isTelegramEnabled()).isTrue();
    }

    @Test
    @DisplayName("텔레그램 연동 해제 - chatId가 null로 변경된다")
    void unlinkTelegram_removesChatId() {
        // given
        Long userId = 1L;
        NotificationPreference preference = NotificationPreference.createDefault(userId);
        preference.linkTelegram("123456");

        given(preferenceRepository.findByUserId(userId)).willReturn(Optional.of(preference));
        given(preferenceRepository.save(any())).willAnswer(i -> i.getArgument(0));

        // when
        preferenceService.unlinkTelegram(userId);

        // then
        ArgumentCaptor<NotificationPreference> captor = ArgumentCaptor.forClass(NotificationPreference.class);
        verify(preferenceRepository).save(captor.capture());

        NotificationPreference saved = captor.getValue();
        assertThat(saved.getTelegramChatId()).isNull();
        assertThat(saved.isTelegramEnabled()).isFalse();
    }
}
