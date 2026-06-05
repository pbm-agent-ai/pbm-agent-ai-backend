package com.pbm.payment.controller;

import com.pbm.payment.domain.UserWallet;
import com.pbm.payment.dto.response.WalletProvisioningResponse;
import com.pbm.payment.service.BlockchainService;
import com.pbm.payment.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WalletController 웹 레이어 테스트.
 * MockMvc를 사용하여 지갑 생성 진행상태 API의 HTTP 응답 계약을 검증한다.
 *
 * 검증 대상:
 * - GET /api/v1/wallet/provisioning-status: 진행 중/완료/시작 전/실패 상태별 응답 형식
 */
@WebMvcTest(WalletController.class)
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WalletService walletService;

    @MockBean
    private BlockchainService blockchainService;

    // ──────────────── 지갑 생성 API 테스트 ────────────────

    @Test
    @DisplayName("POST /api/v1/wallet: 기존 지갑이 있으면 동기 응답 (data != null)")
    void createWallet_existingWallet_returnsWalletResponse() throws Exception {
        // given - 지갑이 이미 존재하는 경우
        UserWallet existingWallet = UserWallet.create(
                1L, "0xuserAddress", "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
                "0xwalletAddress", BigDecimal.valueOf(50000)
        );
        // 리플렉션으로 id/createdAt/updatedAt 설정 (UserWallet은 setter 없음)
        var idField = UserWallet.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(existingWallet, 100L);
        var createdAtField = UserWallet.class.getDeclaredField("createdAt");
        createdAtField.setAccessible(true);
        createdAtField.set(existingWallet, Instant.now());
        var updatedAtField = UserWallet.class.getDeclaredField("updatedAt");
        updatedAtField.setAccessible(true);
        updatedAtField.set(existingWallet, Instant.now());

        when(walletService.createWallet(1L, 50000L)).thenReturn(existingWallet);

        // when & then
        mockMvc.perform(post("/api/v1/wallet")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walletLimitKrw\": 50000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.data.walletAddress").value("0xwalletAddress"))
                .andExpect(jsonPath("$.data.walletLimit").value(50000))
                .andExpect(jsonPath("$.message").value("지갑이 이미 존재합니다."));

        // startAsyncProvisioning은 호출되지 않아야 함
        verify(walletService, never()).startAsyncProvisioning(anyLong(), anyLong());
    }

    @Test
    @DisplayName("POST /api/v1/wallet: 지갑이 없으면 비동기 시작 후 data=null 응답")
    void createWallet_noWallet_startsAsyncAndReturnsNullData() throws Exception {
        // given - 지갑이 없는 경우
        when(walletService.createWallet(1L, 50000L)).thenReturn(null);
        when(walletService.startAsyncProvisioning(1L, 50000L)).thenReturn(true);

        // when & then
        mockMvc.perform(post("/api/v1/wallet")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walletLimitKrw\": 50000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.message").value("지갑 생성을 시작했습니다."));

        verify(walletService).startAsyncProvisioning(1L, 50000L);
    }

    @Test
    @DisplayName("POST /api/v1/wallet: 이미 생성 중이면 data=null + 진행 중 메시지")
    void createWallet_alreadyProvisioning_returnsInProgressMessage() throws Exception {
        // given - 지갑이 없지만 이미 생성이 진행 중
        when(walletService.createWallet(1L, 50000L)).thenReturn(null);
        when(walletService.startAsyncProvisioning(1L, 50000L)).thenReturn(false);

        // when & then
        mockMvc.perform(post("/api/v1/wallet")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walletLimitKrw\": 50000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.message").value("지갑 생성이 이미 진행 중입니다."));

        verify(walletService).startAsyncProvisioning(1L, 50000L);
    }

    @Test
    @DisplayName("POST /api/v1/wallet: walletLimitKrw가 1 미만이면 400 에러")
    void createWallet_invalidLimit_throwsIllegalArgument() throws Exception {
        // when & then
        mockMvc.perform(post("/api/v1/wallet")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walletLimitKrw\": 0}"))
                .andExpect(status().isBadRequest());

        verify(walletService, never()).createWallet(anyLong(), anyLong());
        verify(walletService, never()).startAsyncProvisioning(anyLong(), anyLong());
    }

    // ──────────────── 지갑 생성 진행상태 조회 테스트 ────────────────

    @Test
    @DisplayName("GET /api/v1/wallet/provisioning-status: 진행 중 상태일 때 200 OK + ApiResponse 반환")
    void getProvisioningStatus_inProgress_returns200WithApiResponse() throws Exception {
        // given - 키쌍 생성까지 완료된 상태
        WalletProvisioningResponse response = new WalletProvisioningResponse(
                "KEYPAIR_CREATED", "키쌍 생성 완료", "사용자 키쌍 생성 완료",
                1, 7, Instant.now(), null
        );
        when(walletService.getProvisioningStatus(1L)).thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/v1/wallet/provisioning-status")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("지갑 생성 진행상태 조회 성공"))
                .andExpect(jsonPath("$.data.status").value("KEYPAIR_CREATED"))
                .andExpect(jsonPath("$.data.label").value("키쌍 생성 완료"))
                .andExpect(jsonPath("$.data.currentStep").value(1))
                .andExpect(jsonPath("$.data.totalSteps").value(7));
    }

    @Test
    @DisplayName("GET /api/v1/wallet/provisioning-status: 지갑이 이미 생성된 완료 상태")
    void getProvisioningStatus_completed_returnsSavedStatus() throws Exception {
        // given - 지갑이 이미 DB에 저장된 상태 (7/7 단계)
        WalletProvisioningResponse response = new WalletProvisioningResponse(
                "SAVED", "지갑 정보 저장 완료", "지갑이 이미 생성되어 있습니다.",
                6, 7, Instant.now(), null
        );
        when(walletService.getProvisioningStatus(1L)).thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/v1/wallet/provisioning-status")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SAVED"))
                .andExpect(jsonPath("$.data.label").value("지갑 정보 저장 완료"))
                .andExpect(jsonPath("$.data.currentStep").value(6))
                .andExpect(jsonPath("$.data.totalSteps").value(7));
    }

    @Test
    @DisplayName("GET /api/v1/wallet/provisioning-status: 생성 요청 전 NOT_STARTED 상태")
    void getProvisioningStatus_notStarted_returnsNotStartedStatus() throws Exception {
        // given - 아직 지갑 생성 요청이 없는 상태
        WalletProvisioningResponse response = new WalletProvisioningResponse(
                "NOT_STARTED", "대기 중", "아직 지갑 생성이 시작되지 않았습니다.",
                0, 7, Instant.now(), null
        );
        when(walletService.getProvisioningStatus(1L)).thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/v1/wallet/provisioning-status")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.currentStep").value(0))
                .andExpect(jsonPath("$.data.errorMessage").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/wallet/provisioning-status: 실패 상태에 errorMessage 포함")
    void getProvisioningStatus_failed_returnsErrorMessage() throws Exception {
        // given - 지갑 생성 중 예외가 발생한 상태
        WalletProvisioningResponse response = new WalletProvisioningResponse(
                "FAILED", "실패", "RPC connection refused",
                -1, 7, Instant.now(), "RPC connection refused"
        );
        when(walletService.getProvisioningStatus(1L)).thenReturn(response);

        // when & then - errorMessage 필드 검증
        mockMvc.perform(get("/api/v1/wallet/provisioning-status")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.currentStep").value(-1))
                .andExpect(jsonPath("$.data.errorMessage").value("RPC connection refused"));
    }
}
