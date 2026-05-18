# Browser Agent / Backend 연동 작업 진행 요약

- **날짜**: 2026-05-16
- **작성자**: 개발팀
- **관련 문서**: `pbm-agent-ai-extension/docs/browser-agent-contract.md` (extension 기준 단일 계약 문서)

---

## 목적

Chrome Extension 기반 브라우저 제어형 구매 에이전트를 pbm-agent-ai 시스템에 연동하기 위한 backend 구조를 단계적으로 구축한다.
현재 auth-service / gateway / command-service를 중심으로 Phase 0~3 일부까지 진행되었으며, 이 문서는 해당 작업의 변경 내역을 팀원이 빠르게 파악할 수 있도록 정리한다.

---

## 작업 범위

| 서비스 | 작업 내용 |
|---|---|
| auth-service | pairing token 발급 기능 추가 |
| gateway | role 분기 및 헤더 주입, 라우팅 보강 |
| command-service | BrowserDevice 등록/heartbeat, AgentRun FSM, Phase 3 보강 |

---

## extension 연관 문서 (요약)

extension repo의 `docs/browser-agent-contract.md`를 단일 기준(single source of truth) 문서로 정리하였다.
이번 작업에서 아래 항목이 문서에 반영되었다.

- pull model 방식 및 웹앱 승인 흐름
- 3토큰 모델 (pairingToken / deviceToken / agentToken)
- nodeId / snapshot 계약
- recover / start 흐름
- 첫 `/steps` 요청 규칙
- `/runs/{runId}/start` 계약 추가

---

## 파일별 수정/추가 내역

### 1. auth-service

**추가 파일**

- `auth-service/src/main/java/com/pbm/auth/dto/response/PairingTokenResponse.java`

**수정 파일**

- `auth-service/src/main/java/com/pbm/auth/config/JwtUtil.java`
- `auth-service/src/main/java/com/pbm/auth/controller/AuthController.java`
- `auth-service/src/main/java/com/pbm/auth/service/AuthService.java`
- `auth-service/src/main/resources/application.yml`
- `auth-service/src/test/java/com/pbm/auth/config/JwtUtilTest.java`
- `auth-service/src/test/java/com/pbm/auth/controller/AuthControllerTest.java`
- `auth-service/src/test/java/com/pbm/auth/integration/AuthIntegrationTest.java`

**요약**

- `POST /api/v1/auth/pairing-token` API 추가
- `jwt.pairing-expiration` 설정값 추가
- pairing token 발급 로직 및 관련 테스트 반영

---

### 2. gateway

**수정 파일**

- `gateway/src/main/java/com/pbm/gateway/config/JwtUtil.java`
- `gateway/src/main/java/com/pbm/gateway/filter/JwtAuthenticationFilter.java`
- `gateway/src/main/resources/application.yml`
- `gateway/src/test/java/com/pbm/gateway/filter/JwtAuthenticationFilterTest.java`

**요약**

- JWT role 기반 분기 처리: `USER` / `DEVICE` / `AGENT` 구분
- 다운스트림 서비스로 전달되는 헤더 주입: `X-User-Id`, `X-User-Role`, `X-Device-Id`, `X-Run-Id`
- `/api/v1/devices/register` 공개 경로(인증 불필요) 처리
- `/api/v1/runs/**` 라우팅 추가

---

### 3. command-service — BrowserDevice / 토큰 구조

**추가 파일**

- `command-service/src/main/java/com/pbm/command/config/BrowserAgentTokenUtil.java`
- `command-service/src/main/java/com/pbm/command/controller/BrowserDeviceController.java`
- `command-service/src/main/java/com/pbm/command/domain/BrowserDevice.java`
- `command-service/src/main/java/com/pbm/command/domain/BrowserDeviceStatus.java`
- `command-service/src/main/java/com/pbm/command/dto/request/BrowserDeviceRegisterRequest.java`
- `command-service/src/main/java/com/pbm/command/dto/response/BrowserDeviceRegisterResponse.java`
- `command-service/src/main/java/com/pbm/command/dto/response/BrowserHeartbeatResponse.java`
- `command-service/src/main/java/com/pbm/command/dto/response/AssignedRunResponse.java`
- `command-service/src/main/java/com/pbm/command/exception/BrowserDeviceNotFoundException.java`
- `command-service/src/main/java/com/pbm/command/exception/InvalidBrowserAgentTokenException.java`
- `command-service/src/main/java/com/pbm/command/repository/BrowserDeviceRepository.java`
- `command-service/src/main/java/com/pbm/command/service/BrowserDeviceService.java`
- `command-service/src/test/java/com/pbm/command/controller/BrowserDeviceControllerTest.java`
- `command-service/src/test/java/com/pbm/command/service/BrowserDeviceServiceTest.java`

**수정 파일**

- `command-service/src/main/java/com/pbm/command/exception/GlobalExceptionHandler.java`
- `command-service/src/main/resources/application.yml`
- `command-service/build.gradle`
- `docker-compose.yml`

**요약**

- pairing token 기반 디바이스 등록 흐름 구현
- `deviceToken` / `agentToken` 발급 유틸(`BrowserAgentTokenUtil`) 추가
- heartbeat 처리 및 online TTL 관리
- `BrowserDeviceNotFoundException`, `InvalidBrowserAgentTokenException` 예외 추가 및 `GlobalExceptionHandler` 반영

---

### 4. command-service — AgentRun FSM

**추가 파일**

- `command-service/src/main/java/com/pbm/command/controller/AgentRunController.java`
- `command-service/src/main/java/com/pbm/command/domain/AgentRun.java`
- `command-service/src/main/java/com/pbm/command/domain/AgentRunStatus.java`
- `command-service/src/main/java/com/pbm/command/dto/request/AgentRunAbortRequest.java`
- `command-service/src/main/java/com/pbm/command/dto/request/AgentRunApproveRequest.java`
- `command-service/src/main/java/com/pbm/command/dto/response/AgentRunCreatedResponse.java`
- `command-service/src/main/java/com/pbm/command/dto/response/AgentRunResponse.java`
- `command-service/src/main/java/com/pbm/command/exception/AgentRunAccessDeniedException.java`
- `command-service/src/main/java/com/pbm/command/exception/AgentRunConflictException.java`
- `command-service/src/main/java/com/pbm/command/exception/AgentRunNotFoundException.java`
- `command-service/src/main/java/com/pbm/command/repository/AgentRunRepository.java`
- `command-service/src/main/java/com/pbm/command/service/AgentRunService.java`
- `command-service/src/test/java/com/pbm/command/controller/AgentRunControllerTest.java`
- `command-service/src/test/java/com/pbm/command/domain/AgentRunTest.java`
- `command-service/src/test/java/com/pbm/command/service/AgentRunServiceTest.java`

**요약**

- `AgentRunStatus` 상태 전이 및 active run 중복 제약 구현
- create / pending / get / recover / approve / abort API 구현
- recover 시 assigned device 소유권 검증, approve 시 run owner 검증

---

### 5. command-service — Phase 3 보강

**반영 파일**

- `command-service/src/main/java/com/pbm/command/service/BrowserDeviceService.java`
- `command-service/src/main/java/com/pbm/command/service/AgentRunService.java`
- `command-service/src/main/java/com/pbm/command/controller/AgentRunController.java`
- `command-service/src/main/java/com/pbm/command/domain/AgentRun.java`
- `command-service/src/main/java/com/pbm/command/repository/AgentRunRepository.java`
- `command-service/src/main/java/com/pbm/command/repository/BrowserDeviceRepository.java`
- `command-service/src/main/java/com/pbm/command/dto/response/AssignedRunResponse.java`
- `command-service/src/test/java/com/pbm/command/controller/AgentRunControllerTest.java`
- `command-service/src/test/java/com/pbm/command/domain/AgentRunTest.java`
- `command-service/src/test/java/com/pbm/command/service/AgentRunServiceTest.java`
- `command-service/src/test/java/com/pbm/command/service/BrowserDeviceServiceTest.java`

**요약**

- run 생성 시 최신 온라인 디바이스가 존재하면 즉시 `ASSIGNED` 상태로 전환
- heartbeat 응답에 `assignedRun` 포함하도록 변경
- `GET /api/v1/runs/pending` 응답을 `AssignedRunResponse`로 통일
- `ASSIGNED` 상태에서 `agentToken` 반복 재발급 문제를 캐시 기반 재사용으로 보완
- `POST /api/v1/runs/{runId}/start` 추가로 `ASSIGNED → RUNNING` 상태 전환 지원
- abort 인증 guard, complete 상태 guard 보완

---

## 추가된 주요 API

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/v1/auth/pairing-token` | pairing token 발급 |
| POST | `/api/v1/devices/register` | 디바이스 등록 (deviceToken 발급) |
| POST | `/api/v1/devices/{deviceId}/heartbeat` | heartbeat 및 assignedRun 수신 |
| POST | `/api/v1/commands/{commandId}/runs` | AgentRun 생성 |
| GET | `/api/v1/runs/pending` | ASSIGNED 상태 run 조회 |
| GET | `/api/v1/runs/{runId}` | run 상태 조회 |
| POST | `/api/v1/runs/{runId}/recover` | run recover (ASSIGNED 재진입) |
| POST | `/api/v1/runs/{runId}/start` | ASSIGNED → RUNNING 전환 |
| POST | `/api/v1/runs/{runId}/approve` | 웹앱 사용자 결제 승인 |
| POST | `/api/v1/runs/{runId}/abort` | run 중단 |

---

## 테스트 결과

- `./gradlew :auth-service:test` 통과
- `./gradlew :gateway:test` 통과
- `./gradlew :command-service:test` 통과 (Phase 3 보강 후 포함)

---

## 다음 단계

- gateway에서 `/runs/{runId}/start` 경로 보호 규칙 점검 (AGENT role 전용 guard 확인)
- extension에서 heartbeat로 `assignedRun` 수신 후 `/start` 호출 흐름 구현
- stale/offline 디바이스 정리 스케줄러 구현
- `/steps` planner / action loop 구현
