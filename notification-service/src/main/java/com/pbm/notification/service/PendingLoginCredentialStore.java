package com.pbm.notification.service;

import com.pbm.notification.domain.PendingLoginCredentialEntity;
import com.pbm.notification.dto.PendingLoginCredentialRequest;
import com.pbm.notification.repository.PendingLoginCredentialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 텔레그램 로그인 자격증명 대기 요청 저장소.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingLoginCredentialStore {

    private final PendingLoginCredentialRepository repository;

    public void save(String chatId, PendingLoginCredentialRequest pending) {
        repository.save(new PendingLoginCredentialEntity(
                chatId,
                pending.runId(),
                pending.userId(),
                pending.expiresAt()
        ));
        log.info("로그인 자격증명 대기 저장 - chatId: {}, runId: {}, 만료: {}",
                chatId, pending.runId(), pending.expiresAt());
    }

    public PendingLoginCredentialRequest findByChatId(String chatId) {
        PendingLoginCredentialEntity entity = repository.findById(chatId).orElse(null);

        if (entity != null && Instant.now().isAfter(entity.getExpiresAt())) {
            repository.deleteById(chatId);
            return null;
        }

        if (entity == null) {
            return null;
        }

        return new PendingLoginCredentialRequest(
                entity.getRunId(),
                entity.getUserId(),
                entity.getExpiresAt()
        );
    }

    public void remove(String chatId) {
        repository.deleteById(chatId);
    }

    @Scheduled(fixedRate = 30000)
    public void cleanExpired() {
        Instant now = Instant.now();
        List<PendingLoginCredentialEntity> expiredEntries = repository.findAllByExpiresAtBefore(now);
        for (PendingLoginCredentialEntity entry : expiredEntries) {
            log.info("로그인 자격증명 대기 만료 제거 - chatId: {}, runId: {}",
                    entry.getChatId(), entry.getRunId());
            repository.deleteById(entry.getChatId());
        }
    }
}
