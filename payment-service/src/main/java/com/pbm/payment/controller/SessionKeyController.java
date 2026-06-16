package com.pbm.payment.controller;

import com.pbm.payment.common.ApiResponse;
import com.pbm.payment.dto.response.SessionKeyProgressEvent;
import com.pbm.payment.service.SessionKeyProgressService;
import com.pbm.payment.service.SessionKeyService;
import com.pbm.payment.service.SessionKeyService.SessionKeyRegistrationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 세션키 등록 REST API 컨트롤러.
 * <p>
 * 역할: price-service에서 즉시 충전 조건이 충족되었을 때 동기적으로 세션키를 등록할 수 있는
 *       REST API를 제공한다. 기존 Kafka 이벤트 기반(SessionKeyRegistrationConsumer) 비동기 등록과 달리,
 *       클라이언트가 응답을 받을 때까지 블록체인 등록이 완료됨을 보장한다.
 * <p>
 * 사용 시나리오:
 *   price-service가 AUTO_PURCHASE 모니터링 조건 생성 시, 즉시 충전 조건이 충족되면
 *   이 API를 호출하여 세션키를 바로 등록하고, 충전이 필요하면 Kafka 이벤트로 비동기 등록한다.
 * <p>
 * 인증: 모든 요청은 게이트웨이에서 X-User-Id 헤더로 사용자 ID가 주입된다 (JWT 기반).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/session-keys")
@RequiredArgsConstructor
public class SessionKeyController {

    private final SessionKeyService sessionKeyService;
    private final SessionKeyProgressService sessionKeyProgressService;

    /**
     * 세션키 등록 진행 상태를 폴링으로 조회한다.
     * DONE/FAILED 상태는 조회 후 자동 제거된다.
     *
     * @param userId JWT에서 추출된 사용자 ID
     * @return 현재 진행 상태 (없으면 null)
     */
    @GetMapping("/registration/progress")
    public ResponseEntity<ApiResponse<SessionKeyProgressEvent>> getRegistrationProgress(
            @RequestHeader("X-User-Id") Long userId
    ) {
        SessionKeyProgressEvent event = sessionKeyProgressService.poll(userId);
        if (event == null) {
            return ResponseEntity.ok(ApiResponse.success(null, "진행 중인 세션키 등록 없음"));
        }
        return ResponseEntity.ok(ApiResponse.success(event, event.message()));
    }

    /**
     * 세션키를 동기적으로 등록한다.
     * <p>
     * 요청 본문에 AI 에이전트 정보, 세션키 한도, 유효 기간, 플랫폼을 전달하면,
     * 블록체인에 세션키를 등록하고 DB에 저장한 후 등록 결과를 반환한다.
     * <p>
     * 처리 단계:
     *   1. 사용자 지갑 조회 및 한도 검증
     *   2. AI 에이전트 ETH 지원 (부족분만)
     *   3. 사용자 EOA ETH 지원 (부족분만)
     *   4. addSessionKey() 블록체인 호출
     *   5. DB 세션키 저장
     *   6. 가스비 수수료 차감
     *
     * @param userId  JWT에서 추출된 사용자 ID (게이트웨이가 헤더로 주입)
     * @param request 세션키 등록 요청 (subscriptionId, aiAgentAddress, aiAgentPrivateKey, limitKrw, validSeconds, platform)
     * @return 세션키 등록 결과 (세션키 ID, 트랜잭션 해시, 지갑 주소, AI 에이전트 주소)
     */
    @PostMapping
    public ResponseEntity<ApiResponse<SessionKeyRegistrationResult>> registerSessionKey(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody SessionKeyRegistrationRequest request
    ) {
        log.info("세션키 등록 API 호출 - userId: {}, subscriptionId: {}, aiAgent: {}",
                userId, request.subscriptionId(), request.aiAgentAddress());

        SessionKeyRegistrationResult result = sessionKeyService.registerSessionKey(
                userId,
                request.subscriptionId(),
                request.aiAgentAddress(),
                request.aiAgentPrivateKey(),
                request.limitKrw(),
                request.validSeconds(),
                request.platform()
        );

        log.info("세션키 등록 API 완료 - userId: {}, sessionKeyId: {}, txHash: {}",
                userId, result.sessionKeyId(), result.transactionHash());

        return ResponseEntity.ok(ApiResponse.success(result, "세션키 등록 완료"));
    }

    /**
     * 세션키 등록 요청 DTO.
     * <p>
     * price-service가 REST API를 호출할 때 전달하는 요청 본문 구조체.
     *
     * @param subscriptionId    모니터링 구독 ID (price-service의 monitoring_subscriptions.id)
     * @param aiAgentAddress    AI 에이전트 이더리움 주소 (0x...)
     * @param aiAgentPrivateKey AI 에이전트 개인키 (64자리 hex 문자열)
     * @param limitKrw          세션키 한도 (KRW 기준, 지갑 한도 이하여야 함)
     * @param validSeconds      세션키 유효 기간 (초 단위)
     * @param platform          플랫폼 구분 (NAVER, COUPANG, ALIEXPRESS 등)
     */
    public record SessionKeyRegistrationRequest(
            Long subscriptionId,
            String aiAgentAddress,
            String aiAgentPrivateKey,
            long limitKrw,
            long validSeconds,
            String platform
    ) {}
}
