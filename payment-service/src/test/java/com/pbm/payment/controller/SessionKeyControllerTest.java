package com.pbm.payment.controller;

import com.pbm.payment.service.SessionKeyProgressService;
import com.pbm.payment.service.SessionKeyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SessionKeyController 웹 레이어 테스트.
 *
 * 검증 대상:
 * - SSE 스트림 구독 엔드포인트: 200 OK + async 시작
 */
@WebMvcTest(SessionKeyController.class)
class SessionKeyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SessionKeyService sessionKeyService;

    @MockBean
    private SessionKeyProgressService sessionKeyProgressService;

    @Test
    @DisplayName("세션키 등록 진행 SSE 구독 시 200 OK를 반환한다")
    void 세션키_SSE_구독_성공() throws Exception {
        when(sessionKeyProgressService.subscribe(1L))
                .thenReturn(new SseEmitter(30_000L));

        mockMvc.perform(get("/api/v1/session-keys/registration/stream")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }
}
