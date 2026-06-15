package com.pbm.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.notification.domain.PendingOptionSelectionEntity;
import com.pbm.notification.dto.PendingOptionSelection;
import com.pbm.notification.dto.event.OptionSelectionRequestEvent;
import com.pbm.notification.repository.PendingOptionSelectionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 텔레그램 옵션 선택 대기 요청을 인메모리로 관리하는 저장소.
 *
 * 역할: 텔레그램으로 옵션 목록을 전송한 뒤 사용자 응답을 기다리는 동안
 *       chatId → PendingOptionSelection 매핑을 유지한다.
 * 동작: ConcurrentHashMap 기반, 30초마다 만료 항목을 자동 정리한다.
 * 연관: OptionSelectionRequestConsumer, TelegramOptionSelectionHandler.
 */
@Slf4j
@Component
public class PendingOptionStore {
    private static final TypeReference<List<OptionSelectionRequestEvent.OptionGroup>> OPTION_GROUPS_TYPE =
            new TypeReference<>() {};

    private final PendingOptionSelectionRepository repository;
    private final ObjectMapper objectMapper;

    public PendingOptionStore(
            PendingOptionSelectionRepository repository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 대기 요청을 저장한다. */
    public void save(String chatId, PendingOptionSelection pending) {
        try {
            String optionGroupsJson = objectMapper.writeValueAsString(pending.optionGroups());
            repository.save(new PendingOptionSelectionEntity(
                    chatId,
                    pending.runId(),
                    pending.userId(),
                    optionGroupsJson,
                    pending.expiresAt()
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("옵션 선택 대기 저장 직렬화 실패", e);
        }

        log.info("옵션 선택 대기 저장 - chatId: {}, runId: {}, 만료: {}",
                chatId, pending.runId(), pending.expiresAt());
    }

    /** chatId로 대기 요청을 조회한다. 만료되었으면 null 반환. */
    public PendingOptionSelection findByChatId(String chatId) {
        PendingOptionSelectionEntity entity = repository.findById(chatId).orElse(null);

        if (entity != null && Instant.now().isAfter(entity.getExpiresAt())) {
            repository.deleteById(chatId);
            return null;
        }

        if (entity == null) {
            return null;
        }

        try {
            List<OptionSelectionRequestEvent.OptionGroup> optionGroups =
                    objectMapper.readValue(entity.getOptionGroupsJson(), OPTION_GROUPS_TYPE);
            return new PendingOptionSelection(
                    entity.getRunId(),
                    entity.getUserId(),
                    optionGroups,
                    entity.getExpiresAt()
            );
        } catch (JsonProcessingException e) {
            log.error("옵션 선택 대기 조회 역직렬화 실패 - chatId: {}, runId: {}",
                    chatId, entity.getRunId(), e);
            repository.deleteById(chatId);
            return null;
        }
    }

    /** 대기 요청을 제거한다. */
    public void remove(String chatId) {
        repository.deleteById(chatId);
    }

    /** 30초마다 만료된 대기 요청을 정리한다. */
    @Scheduled(fixedRate = 30000)
    public void cleanExpired() {
        Instant now = Instant.now();
        List<PendingOptionSelectionEntity> expiredEntries = repository.findAllByExpiresAtBefore(now);
        for (PendingOptionSelectionEntity entry : expiredEntries) {
            log.info("옵션 선택 대기 만료 제거 - chatId: {}, runId: {}",
                    entry.getChatId(), entry.getRunId());
            repository.deleteById(entry.getChatId());
        }
    }
}
