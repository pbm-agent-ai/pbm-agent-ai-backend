package com.pbm.command.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.dto.request.PriceCheckRequest;
import com.pbm.command.dto.response.PriceCheckResponse;
import com.pbm.command.service.PriceRequestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CommandController 단위 테스트.
 *
 * 역할: 가격 확인 요청 API가 service 호출 결과를 공통 응답 형식으로 반환하는지 검증한다.
 * 동작: PriceRequestService를 Mock으로 대체하고 MockMvc로 HTTP 요청/응답을 확인한다.
 * 연관: CommandController, PriceRequestService.
 */
@WebMvcTest(CommandController.class)
class CommandControllerTest {

    @SpringBootApplication
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PriceRequestService priceRequestService;

    @Test
    @DisplayName("가격 확인 요청 - 공통 응답 형식으로 발행 성공 응답 반환")
    void priceCheck_returnsSuccessResponse() throws Exception {
        // given
        PriceCheckRequest request = new PriceCheckRequest(1L, "에어팟 프로", 300000);
        PriceCheckResponse response = new PriceCheckResponse(
                "evt-001",
                "price-topic",
                "price-topic 발행 성공"
        );

        when(priceRequestService.publishPriceCheckRequest(any(PriceCheckRequest.class)))
                .thenReturn(response);

        // when & then
        mockMvc.perform(post("/api/commands/price-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.eventId").value("evt-001"))
                .andExpect(jsonPath("$.data.topic").value("price-topic"))
                .andExpect(jsonPath("$.data.message").value("price-topic 발행 성공"))
                .andExpect(jsonPath("$.message").value("성공"));
    }
}
