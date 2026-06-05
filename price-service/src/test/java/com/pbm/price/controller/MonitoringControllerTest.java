package com.pbm.price.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.request.MonitoringSubscriptionUpdateRequest;
import com.pbm.price.exception.SubscriptionAccessDeniedException;
import com.pbm.price.exception.SubscriptionNotFoundException;
import com.pbm.price.service.MonitoringSubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MonitoringController 웹 레이어 테스트.
 *
 * 역할: GET /api/v1/monitoring/subscriptions, PATCH /api/v1/monitoring/subscriptions/{id}
 *       엔드포인트의 HTTP 응답 형식을 검증한다.
 * 동작: MockMvc로 X-User-Id 헤더 포함 요청을 보내 ApiResponse JSON 형식과 상태코드를 확인한다.
 * 연관: MonitoringController, MonitoringSubscriptionService, MonitoringSubscriptionResponse.
 */
@WebMvcTest(MonitoringController.class)
class MonitoringControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MonitoringSubscriptionService monitoringSubscriptionService;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    // ======================== GET /subscriptions ========================

    @Test
    @DisplayName("GET /api/v1/monitoring/subscriptions: ACTIVE 구독 목록을 200 OK로 반환한다")
    void getActiveSubscriptions_returnsActiveList() throws Exception {
        Long userId = 1L;
        MonitoringSubscription subscription = createTestSubscription(userId, "AUTO_PURCHASE");

        when(monitoringSubscriptionService.findActiveByUserId(userId))
                .thenReturn(List.of(subscription));

        mockMvc.perform(get("/api/v1/monitoring/subscriptions")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("모니터링 목록 조회 성공 (총 1건)"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].platform").value("NAVER"))
                .andExpect(jsonPath("$.data[0].snapshotTitle").value("나이키 에어맥스 270"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].currency").value("KRW"));
    }

    @Test
    @DisplayName("GET /api/v1/monitoring/subscriptions: 구독이 없으면 빈 배열을 반환한다")
    void getActiveSubscriptions_returnsEmptyList_whenNoSubscriptions() throws Exception {
        when(monitoringSubscriptionService.findActiveByUserId(99L))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/monitoring/subscriptions")
                        .header("X-User-Id", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("모니터링 목록 조회 성공 (총 0건)"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/monitoring/subscriptions: X-User-Id 헤더가 없으면 400을 반환한다")
    void getActiveSubscriptions_returnsBadRequest_whenUserIdHeaderMissing() throws Exception {
        mockMvc.perform(get("/api/v1/monitoring/subscriptions"))
                .andExpect(status().isBadRequest());
    }

    // ======================== PATCH /subscriptions/{id} ========================

    @Test
    @DisplayName("PATCH /subscriptions/{id}: intent 수정 요청 시 200 OK와 수정된 구독 반환")
    void updateSubscription_updatesIntent_returns200() throws Exception {
        Long userId = 1L;
        Long subscriptionId = 10L;
        MonitoringSubscription updated = createTestSubscription(userId, "PRICE_TRACK");

        MonitoringSubscriptionUpdateRequest request =
                new MonitoringSubscriptionUpdateRequest("PRICE_TRACK", null, null);

        when(monitoringSubscriptionService.updateSubscription(eq(userId), eq(subscriptionId), any()))
                .thenReturn(updated);

        mockMvc.perform(patch("/api/v1/monitoring/subscriptions/{id}", subscriptionId)
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("모니터링 조건 수정 성공"))
                .andExpect(jsonPath("$.data.intent").value("PRICE_TRACK"));
    }

    @Test
    @DisplayName("PATCH /subscriptions/{id}: targetPrice 수정 요청 시 200 OK 반환")
    void updateSubscription_updatesTargetPrice_returns200() throws Exception {
        Long userId = 1L;
        Long subscriptionId = 10L;
        MonitoringSubscription updated = createTestSubscription(userId, "AUTO_PURCHASE");

        MonitoringSubscriptionUpdateRequest request =
                new MonitoringSubscriptionUpdateRequest(null, new BigDecimal("180000"), null);

        when(monitoringSubscriptionService.updateSubscription(eq(userId), eq(subscriptionId), any()))
                .thenReturn(updated);

        mockMvc.perform(patch("/api/v1/monitoring/subscriptions/{id}", subscriptionId)
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("모니터링 조건 수정 성공"));
    }

    @Test
    @DisplayName("PATCH /subscriptions/{id}: 구독을 찾을 수 없으면 404를 반환한다")
    void updateSubscription_returns404_whenSubscriptionNotFound() throws Exception {
        Long subscriptionId = 999L;
        MonitoringSubscriptionUpdateRequest request =
                new MonitoringSubscriptionUpdateRequest("PRICE_TRACK", null, null);

        when(monitoringSubscriptionService.updateSubscription(eq(1L), eq(subscriptionId), any()))
                .thenThrow(new SubscriptionNotFoundException(subscriptionId));

        mockMvc.perform(patch("/api/v1/monitoring/subscriptions/{id}", subscriptionId)
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("PATCH /subscriptions/{id}: 다른 사용자의 구독이면 403을 반환한다")
    void updateSubscription_returns403_whenAccessDenied() throws Exception {
        Long subscriptionId = 10L;
        MonitoringSubscriptionUpdateRequest request =
                new MonitoringSubscriptionUpdateRequest("PRICE_TRACK", null, null);

        when(monitoringSubscriptionService.updateSubscription(eq(2L), eq(subscriptionId), any()))
                .thenThrow(new SubscriptionAccessDeniedException(subscriptionId, 2L));

        mockMvc.perform(patch("/api/v1/monitoring/subscriptions/{id}", subscriptionId)
                        .header("X-User-Id", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("PATCH /subscriptions/{id}: 수정 항목이 모두 null이면 400을 반환한다")
    void updateSubscription_returns400_whenAllFieldsNull() throws Exception {
        Long subscriptionId = 10L;
        MonitoringSubscriptionUpdateRequest request =
                new MonitoringSubscriptionUpdateRequest(null, null, null);

        when(monitoringSubscriptionService.updateSubscription(eq(1L), eq(subscriptionId), any()))
                .thenThrow(new IllegalArgumentException("수정할 항목이 없습니다."));

        mockMvc.perform(patch("/api/v1/monitoring/subscriptions/{id}", subscriptionId)
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ======================== 헬퍼 ========================

    private MonitoringSubscription createTestSubscription(Long userId, String intent) {
        MonitoringSubscription sub = MonitoringSubscription.create(
                userId,
                UUID.randomUUID().toString(),
                Platform.NAVER,
                "naver-product-123",
                "https://smartstore.naver.com/nike/products/123",
                "나이키 에어맥스 270",
                new BigDecimal("189000"),
                null,
                "나이키 에어맥스 270",
                new BigDecimal("200000"),
                CurrencyType.KRW,
                intent,
                MonitoringSubscriptionStatus.ACTIVE,
                0,
                10,
                Instant.now().plus(7, ChronoUnit.DAYS)
        );
        sub.markChecked(Instant.now());
        return sub;
    }
}
