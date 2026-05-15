# Command Service 자연어 파싱 체크리스트

> 날짜: 2026-05-04
> 브랜치: `feature/7-command-service-gpt-parser`
> 목표: GPT 기반 자연어 명령 파싱 + 누락 정보 판단 + 모달 보완 플로우 MVP 구현

---

## 1. 범위 확정

- [x] MVP에서 지원할 intent 범위 확정
- [x] intent enum 정의 (`AUTO_PURCHASE`, `PRICE_TRACK`, `PRICE_CHECK`)
- [x] MVP에서 지원할 상품 카테고리 범위 확정
- [x] 플랫폼 enum 범위 확정 (`NAVER`, `ALIEXPRESS`)
- [x] 플랫폼 미지정 허용 여부 확정
- [x] 카테고리별 절대 필수 필드 정의
- [x] 카테고리별 추가 확인(모호성) 필드 정의

### 1단계 확정 사항

- 지원 intent는 `AUTO_PURCHASE`, `PRICE_TRACK`, `PRICE_CHECK`만 허용
- 지원 플랫폼은 `NAVER`, `ALIEXPRESS`만 허용
- `COUPANG`은 현재 범위에서 제외
- 상품 카테고리는 `SHOES`, `ELECTRONICS`, `APPAREL`, `UNKNOWN`으로 1차 확정
- 플랫폼 미지정은 허용하지만, `AUTO_PURCHASE`에서는 추가 확인 대상(`ambiguousFields`)으로 처리

---

## 2. 파싱 API 스펙 설계

- [x] 파싱 전용 엔드포인트 경로 확정 (`POST /api/v1/commands/parse`)
- [x] Request DTO 정의
- [x] Response DTO 정의
- [x] `ApiResponse<T>` 래퍼 적용
- [ ] validation 실패 응답 형식 확정
- [ ] clarification 필요 시 응답 형식 확정

---

## 3. DTO / Enum 설계

- [x] `CommandParseRequest` record 생성
- [x] `CommandParseResponse` record 생성
- [x] `ParsedCommand` record 생성
- [x] `CommandIntent` enum 생성
- [x] `ProductCategory` enum 생성
- [x] `PlatformType` enum 생성
- [x] 필요한 경우 필드명 enum 또는 상수 정의
- [ ] 프론트 모달용 필드 메타데이터 구조 필요 여부 결정

---

## 4. 누락 정보 판단 규칙 설계

- [x] `missingRequiredFields` 기준 정의
- [x] `ambiguousFields` 기준 정의
- [x] 상품 카테고리별 required field 정책 정의
- [x] 상품 카테고리별 clarification field 정책 정의
- [x] 상품명이 너무 넓은 경우의 판정 규칙 정의
- [x] 색상/사이즈/모델/플랫폼의 필수 여부를 카테고리별로 정리

### 카테고리 초안

- [x] `SHOES`: `productName`, `maxPrice`, `size` 필수 여부 확정
- [x] `ELECTRONICS`: `productName`, `maxPrice`, `model` 필수 여부 확정
- [x] `APPAREL`: `productName`, `maxPrice`, `size` 필수 여부 확정

### 3단계 확정 규칙

- `missingRequiredFields`: 카테고리별 필수 필드가 null / blank / 0 이하인 경우
- `ambiguousFields`: 자동 결제 intent이거나 상품명이 너무 넓어 추가 확인이 필요한 경우
- 상품명이 너무 넓다고 보는 기준: 공백 기준 토큰 수가 2개 이하인 상품명
- `SHOES`
  - required: `productName`, `maxPrice`, `size`
  - auto purchase clarification: `color`, `platform`
  - broad product clarification: `model`
- `ELECTRONICS`
  - required: `productName`, `maxPrice`, `model`
  - auto purchase clarification: `color`, `platform`
- `APPAREL`
  - required: `productName`, `maxPrice`, `size`
  - auto purchase clarification: `color`, `platform`

---

## 5. GPT 프롬프트 설계

- [x] system prompt 작성
- [x] user prompt 템플릿 작성
- [x] JSON only 응답 강제
- [x] 허용 intent 목록 명시
- [x] 허용 category 목록 명시
- [x] 모르는 값은 `null` 반환하도록 명시
- [x] 추측 금지 규칙 명시
- [x] 가격/통화 정규화 규칙 명시
- [ ] 예시 입력/출력 few-shot 필요 여부 결정

### 5단계 확정 사항

- system prompt에 허용 intent/category/platform 목록을 고정값으로 명시
- 지원하지 않는 플랫폼(예: `COUPANG`)은 임의 매핑하지 않고 `null` 반환하도록 명시
- 응답은 JSON only, 최상위 키는 `intent`, `parsedCommand`, `confidence`만 허용
- 금액은 Integer로 정규화 (`20만원` → `200000`)
- 알 수 없는 값은 추측하지 않고 `null` 반환

---

## 6. GPT 연동 클라이언트 구현

- [x] GPT 호출 클라이언트 클래스 추가
- [x] API Key 환경변수 설계 (`OPENAI_API_KEY` 등)
- [x] base URL / model / timeout 설정 분리
- [x] 요청/응답 DTO 정의
- [x] JSON 파싱 실패 처리 추가
- [x] 응답 스키마 불일치 예외 처리 추가
- [x] 민감정보 로그 노출 방지 처리

### 6단계 확정 사항

- command-service는 OpenAI를 직접 호출하지 않고 external-api-service의 `POST /api/v1/openai/parse-command` 프록시를 호출
- OpenAI API Key와 실제 모델 호출 책임은 external-api-service가 소유
- command-service는 system/user prompt 생성 + 프록시 호출 + parsed_json 후처리만 담당
- refusal, finishReason, parsed_json null, malformed JSON을 각각 예외 처리
- `CommandParsingService`는 mock 규칙 대신 `OpenAiCommandClient` 프록시 결과를 사용하도록 변경

---

## 7. Service 계층 구현

- [x] `CommandParsingService` 추가
- [x] GPT 응답 파싱 로직 구현
- [ ] 카테고리별 규칙 조회 로직 구현
- [x] `missingRequiredFields` 계산 로직 구현
- [x] `ambiguousFields` 계산 로직 구현
- [x] `needsClarification` 계산 로직 구현
- [x] 최종 응답 조립 로직 구현

### 4단계 현재 상태

- 현재 `CommandParsingService`는 `OpenAiCommandClient`를 호출하여 GPT 구조화 응답을 사용함
- missing/ambiguous 계산은 여전히 백엔드 규칙 기반으로 후처리함

---

## 8. Resilience 적용

- [x] GPT API timeout 설정
- [x] Retry 적용
- [x] Circuit Breaker 적용
- [x] GPT 장애용 커스텀 예외 추가
- [x] `GlobalExceptionHandler` 응답 정책 추가
- [x] 장애 시 사용자 메시지 확정

### 8단계 확정 사항

- resilience 대상은 OpenAI 직접 호출이 아니라 `external-api-service` 프록시 호출
- `openAiProxyService` 인스턴스로 Circuit Breaker + Retry 적용
- HTTP timeout은 `RestClient` 요청 팩토리 timeout으로 유지
- 프록시 장애 시 `ExternalApiProxyException`으로 변환
- 컨트롤러 응답은 `GlobalExceptionHandler`에서 503 + `ApiResponse.error(...)`로 표준화

---

## 9. Controller 구현

- [x] `CommandParseController` 추가
- [x] `POST /api/v1/commands/parse` 구현
- [ ] 입력 validation 적용
- [ ] 성공/실패 응답 포맷 통일
- [ ] clarification 응답 예시 정리

---

## 10. 테스트 작성

- [x] Controller 테스트 작성
- [x] Service 테스트 작성
- [x] 규칙 기반 누락 판단 테스트 작성
- [x] GPT client mocking 테스트 작성
- [x] invalid JSON 응답 테스트 작성
- [x] timeout/retry/fallback 테스트 작성
- [x] `missingRequiredFields` 계산 테스트 작성
- [x] `ambiguousFields` 계산 테스트 작성

### 필수 테스트 케이스

- [ ] `나이키 조던 20만원 이하면 결제해줘` → `size` 누락 판단
- [ ] `아이폰 15 프로 256GB 140만원 이하` → electronics 파싱 검증
- [ ] `에어팟 프로 가격 알려줘` → `PRICE_CHECK` 분류 검증
- [ ] 모델명이 너무 넓은 상품명 → ambiguous 처리 검증
- [ ] GPT가 잘못된 JSON 반환 → 예외 처리 검증
- [ ] GPT API timeout → fallback/예외 처리 검증

---

## 11. 프론트 모달 연동 체크

- [ ] 모달에 표시할 필드 라벨 정의
- [ ] `missingRequiredFields` 표시 방식 정의
- [ ] `ambiguousFields` 표시 방식 정의
- [ ] 필드별 입력 타입 정의
- [ ] 보완 입력 후 재파싱 여부 결정
- [ ] 보완 입력 merge 방식 정의

---

## 12. Kafka / 후속 연동 분리

- [ ] clarification 필요 시 Kafka 발행하지 않기
- [ ] 충분한 정보가 있을 때만 후속 이벤트 생성하기
- [ ] 후속 event schema 초안 작성 여부 결정
- [ ] downstream 연동은 다음 이슈로 분리하기

---

## 13. 문서화

- [ ] 개발일지 작성
- [ ] 프롬프트 설계 변경 이력 정리
- [ ] 파싱 응답 예시 정리
- [ ] 카테고리별 required field 정책 정리
- [ ] 예시 명령어 모음 정리

---

## MVP 완료 기준

- [x] `POST /api/v1/commands/parse` 호출 가능
- [x] GPT가 자연어를 구조화 JSON으로 반환
- [x] 백엔드가 누락 필드와 모호 필드를 계산
- [x] 프론트 모달에 필요한 응답 데이터 제공 가능
- [x] 테스트 통과
- [x] API Key 하드코딩 없음
- [ ] timeout/retry/circuit breaker 적용 완료

---

## 이번 이슈에서 하지 않을 것

- [ ] 사용자별 대화 세션 저장
- [ ] 다단계 대화 상태 관리
- [ ] 실제 자동 결제 실행
- [ ] 상품 검색 결과 기반 고급 ambiguity 판정
- [ ] DB 영속화
- [ ] 복잡한 카테고리 taxonomy 확장

---

## 추천 구현 순서

- [x] intent/category 범위 확정
- [x] DTO/enum 설계
- [x] 규칙 기반 required field 정책 구현
- [x] 파싱 API 스펙 확정
- [x] GPT prompt 설계
- [x] GPT client 구현
- [x] parsing service 구현
- [x] missing/ambiguous 계산 구현
- [x] controller 구현
- [x] resilience 적용
- [x] 테스트 작성
- [ ] 문서 정리
