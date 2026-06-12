package com.pbm.price.service;

import com.pbm.price.domain.BrowserSearchTask;
import com.pbm.price.domain.BrowserSearchTaskStatus;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.event.PriceRequestEvent;
import com.pbm.price.dto.event.ProductCandidateDto;
import com.pbm.price.dto.event.ProductSelectionRequiredEvent;
import com.pbm.price.dto.event.ProductSelectionRequiredEventPayload;
import com.pbm.price.dto.request.BrowserSearchResultReportRequest;
import com.pbm.price.dto.response.BrowserSearchActiveTaskResponse;
import com.pbm.price.publisher.ProductSelectionRequiredEventPublisher;
import com.pbm.price.repository.BrowserSearchTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 브라우저 검색 태스크 서비스.
 */
@Slf4j
@Service
@Transactional
public class BrowserSearchTaskService {

    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final long REDISPATCH_TIMEOUT_SECONDS = 120L;
    /** 브라우저 검색 태스크 최대 디스패치 횟수 — 초과 시 자동 FAILED 처리 */
    private static final int MAX_DISPATCH_COUNT = 3;

    private final BrowserSearchTaskRepository browserSearchTaskRepository;
    private final ProductSelectionRequiredEventPublisher productSelectionRequiredEventPublisher;

    public BrowserSearchTaskService(
            BrowserSearchTaskRepository browserSearchTaskRepository,
            ProductSelectionRequiredEventPublisher productSelectionRequiredEventPublisher
    ) {
        this.browserSearchTaskRepository = browserSearchTaskRepository;
        this.productSelectionRequiredEventPublisher = productSelectionRequiredEventPublisher;
    }

    public void enqueueAliExpressSearchTask(PriceRequestEvent event) {
        String keyword = event.payload().keyword();
        if (keyword == null || keyword.isBlank()) {
            log.warn("AliExpress 브라우저 검색 태스크 생성을 건너뜀 - keyword 비어 있음, commandId: {}",
                    event.payload().commandId());
            publishSelectionRequired(
                    event.payload().commandId(),
                    event.payload().targetPrice(),
                    event.payload().intent(),
                    List.of(),
                    "검색어를 추출하지 못했습니다. 상품명을 더 구체적으로 입력해주세요.",
                    keyword
            );
            return;
        }

        String trimmedKeyword = keyword.trim();
        String searchUrl = buildAliExpressSearchUrl(trimmedKeyword);

        BrowserSearchTask task = browserSearchTaskRepository.findByCommandId(event.payload().commandId())
                .map(existing -> {
                    existing.resetForRetry();
                    return existing;
                })
                .orElseGet(() -> BrowserSearchTask.create(
                        event.payload().userId(),
                        event.payload().commandId(),
                        Platform.ALIEXPRESS,
                        trimmedKeyword,
                        searchUrl,
                        event.payload().targetPrice(),
                        event.payload().intent(),
                        DEFAULT_MAX_RESULTS
                ));

        browserSearchTaskRepository.save(task);
        log.info("AliExpress 브라우저 검색 태스크 생성 - taskId: {}, commandId: {}, keyword: {}",
                task.getId(), task.getCommandId(), task.getKeyword());
    }

    /**
     * 활성 브라우저 검색 태스크 조회.
     *
     * PENDING 태스크와 디스패치 후 일정 시간 경과한 stale DISPATCHED 태스크를 반환한다.
     * 디스패치 횟수가 MAX_DISPATCH_COUNT를 초과한 태스크는 자동으로 FAILED 처리한다.
     */
    public List<BrowserSearchActiveTaskResponse> getActiveTasks(Long userId) {
        Instant redispatchThreshold = Instant.now().minusSeconds(REDISPATCH_TIMEOUT_SECONDS);

        List<BrowserSearchTask> pendingTasks = browserSearchTaskRepository
                .findAllByUserIdAndStatus(userId, BrowserSearchTaskStatus.PENDING);
        List<BrowserSearchTask> staleDispatchedTasks = browserSearchTaskRepository
                .findAllByUserIdAndStatusAndLastDispatchedAtBefore(
                        userId,
                        BrowserSearchTaskStatus.DISPATCHED,
                        redispatchThreshold
                );

        Map<Long, BrowserSearchTask> deduped = new LinkedHashMap<>();
        pendingTasks.forEach(task -> deduped.put(task.getId(), task));
        staleDispatchedTasks.forEach(task -> deduped.put(task.getId(), task));

        // 디스패치 횟수 초과 태스크는 FAILED 처리 후 제외
        List<BrowserSearchTask> eligible = new java.util.ArrayList<>();
        for (BrowserSearchTask task : deduped.values()) {
            int count = task.getDispatchCount() != null ? task.getDispatchCount() : 0;
            if (count >= MAX_DISPATCH_COUNT) {
                task.fail("디스패치 횟수 초과 (" + count + "/" + MAX_DISPATCH_COUNT + ")");
                log.warn("브라우저 검색 태스크 디스패치 횟수 초과 → FAILED - taskId={}, commandId={}, count={}",
                        task.getId(), task.getCommandId(), count);
            } else {
                eligible.add(task);
            }
        }

        return eligible.stream()
                .map(task -> new BrowserSearchActiveTaskResponse(
                        task.getId(),
                        task.getCommandId(),
                        task.getPlatform().name(),
                        task.getKeyword(),
                        task.getSearchUrl(),
                        task.getMaxResults()
                ))
                .toList();
    }

    public void markTasksDispatched(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        browserSearchTaskRepository.findAllById(taskIds).forEach(task -> task.redispatch(now));
    }

    /**
     * 익스텐션이 브라우저 검색 실패를 보고한다.
     * 태스크를 FAILED로 마킹하여 더 이상 재디스패치하지 않는다.
     */
    public void reportFailure(Long taskId, String reason) {
        BrowserSearchTask task = browserSearchTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "브라우저 검색 태스크를 찾을 수 없습니다. taskId=" + taskId));

        if (task.getStatus() == BrowserSearchTaskStatus.COMPLETED) {
            log.info("이미 완료된 브라우저 검색 태스크 실패 보고를 무시합니다. taskId={}", taskId);
            return;
        }

        task.fail(reason);
        log.warn("브라우저 검색 태스크 실패 보고 처리 완료 - taskId={}, commandId={}, reason={}",
                taskId, task.getCommandId(), reason);
    }

    public void processSearchResultReport(BrowserSearchResultReportRequest request) {
        BrowserSearchTask task = browserSearchTaskRepository.findById(request.taskId())
                .orElseThrow(() -> new IllegalArgumentException("브라우저 검색 태스크를 찾을 수 없습니다. taskId=" + request.taskId()));

        if (task.getStatus() == BrowserSearchTaskStatus.COMPLETED) {
            log.info("이미 완료된 브라우저 검색 태스크 보고를 무시합니다. taskId: {}", task.getId());
            return;
        }

        List<ProductCandidateDto> normalizedCandidates = normalizeCandidates(task, request.candidates());

        if (normalizedCandidates.isEmpty()) {
            publishSelectionRequired(
                    task.getCommandId(),
                    task.getTargetPrice(),
                    task.getIntent(),
                    List.of(),
                    "검색 결과가 없습니다. 검색어를 바꿔 다시 시도해주세요.",
                    task.getKeyword()
            );
        } else {
            publishSelectionRequired(
                    task.getCommandId(),
                    task.getTargetPrice(),
                    task.getIntent(),
                    normalizedCandidates,
                    "검색 결과를 확인하고 상품을 선택해주세요.",
                    task.getKeyword()
            );
        }

        task.complete(Instant.now());
        log.info("브라우저 검색 결과 반영 완료 - taskId: {}, commandId: {}, candidateCount: {}",
                task.getId(), task.getCommandId(), normalizedCandidates.size());
    }

    private List<ProductCandidateDto> normalizeCandidates(BrowserSearchTask task, List<ProductCandidateDto> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        Map<String, ProductCandidateDto> deduped = new LinkedHashMap<>();
        for (ProductCandidateDto candidate : candidates) {
            if (candidate == null
                    || candidate.productId() == null || candidate.productId().isBlank()
                    || candidate.productUrl() == null || candidate.productUrl().isBlank()
                    || candidate.title() == null || candidate.title().isBlank()) {
                continue;
            }

            String key = candidate.productId().trim();
            deduped.put(key, new ProductCandidateDto(
                    candidate.productId().trim(),
                    candidate.title().trim(),
                    candidate.lprice(),
                    candidate.mallName() != null && !candidate.mallName().isBlank() ? candidate.mallName().trim() : "AliExpress",
                    candidate.productUrl().trim(),
                    candidate.imageUrl(),
                    candidate.currency() != null && !candidate.currency().isBlank() ? candidate.currency().trim().toUpperCase() : "KRW",
                    Platform.ALIEXPRESS.name(),
                    candidate.searchKeyword() != null && !candidate.searchKeyword().isBlank() ? candidate.searchKeyword().trim() : task.getKeyword()
            ));
        }

        return deduped.values().stream().limit(30).toList();
    }

    private void publishSelectionRequired(
            String commandId,
            Integer targetPrice,
            String intent,
            List<ProductCandidateDto> candidates,
            String message,
            String searchKeyword
    ) {
        ProductSelectionRequiredEvent event = new ProductSelectionRequiredEvent(
                UUID.randomUUID().toString(),
                "PRODUCT_SELECTION_REQUIRED",
                Instant.now(),
                "price-service",
                new ProductSelectionRequiredEventPayload(
                        commandId,
                        null,
                        List.of(),
                        message,
                        candidates,
                        targetPrice,
                        intent,
                        searchKeyword
                )
        );
        productSelectionRequiredEventPublisher.publish(event);
    }

    private String buildAliExpressSearchUrl(String keyword) {
        return "https://ko.aliexpress.com/w/wholesale-" + java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8)
                + ".html?spm=a2g0o.home.search.0";
    }
}
