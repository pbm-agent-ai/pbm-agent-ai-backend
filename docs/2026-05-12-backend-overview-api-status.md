# 전체 백엔드 개발 현황 및 API 명세서

## 문서 목적
- 이 문서는 **현재 레포지토리에서 실제로 구현된 백엔드 서비스/API/이벤트 흐름**을 기준으로 정리한다.
- `command-service`, `price-service`뿐 아니라 `auth-service`, `payment-service`, `notification-service`, `gateway`, `eureka-server`, `external-api-service`까지 포함한다.
- 기획만 존재하거나 문서에만 있는 내용이 아니라, **현재 코드에 존재하는 구현**만 적는다.

---

## 1. 전체 서비스 맵

| 서비스 | 포트 | 역할 | 현재 상태 |
| --- | --- | --- | --- |
| gateway | 8080 | JWT 검증 + 라우팅 | 구현됨 |
| auth-service | 8081 | 회원가입/로그인/JWT/리프레시 | 구현됨 |
| command-service | 8082 | 자연어 명령 파싱 + commandId 세션 관리 | 구현됨 |
| price-service | 8083 | 상품 검색/카테고리 동기화/가격 판단 | 구현됨 |
| payment-service | 8084 | 결제 요청 소비 + 결제 내역 조회 | 구현됨(실결제는 스텁) |
| notification-service | 8085 | 가격 알림 이벤트 소비 | 부분 구현 |
| external-api-service | 8090 | 네이버/알리/OpenAI 프록시(FastAPI) | 구현됨 |
| eureka-server | 8761 | 서비스 디스커버리 | 구현됨 |
| prometheus | 9091 | 모니터링 | compose 포함 |
| grafana | 3001 | 대시보드 | compose 포함 |

---

## 2. 공통 사항

### 2-1. 공통 응답 형식
대부분의 Spring 서비스는 아래 구조를 사용한다.

```json
{
  "success": true,
  "data": {},
  "message": "성공"
}
```

- `auth-service`, `command-service`: `record ApiResponse<T>`
- `price-service`, `payment-service`: class 기반 `ApiResponse<T>`
- 구조는 동일하게 `success / data / message`

### 2-2. 인증 경계 주의
- **Gateway 경유 시**: `/api/v1/auth/signup`, `/api/v1/auth/login`, `/api/v1/auth/refresh`를 제외한 대부분 경로는 JWT가 필요하다.
- **직접 서비스 포트 호출 시**:
  - `auth-service`는 자체 Spring Security가 있어 인증/인가를 직접 처리한다.
  - `command-service`, `price-service`, `payment-service`는 현재 코드상 별도 Spring Security가 없다.
  - 즉, 운영 기준 보안 경계는 사실상 **gateway**이다.

### 2-3. Gateway 공개 경로
- `POST /api/v1/auth/signup`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`

그 외 라우팅 경로는 `Authorization: Bearer {accessToken}` 필요.

---

## 3. Gateway

### 역할
- JWT 유효성 검증
- 인증 성공 시 다운스트림에 아래 헤더 전달
  - `X-User-Id`
  - `X-User-Role`
- 공개 경로가 아닌 경우 토큰 없으면 401 반환

### 라우팅 테이블

| 경로 | 대상 서비스 |
| --- | --- |
| `/api/v1/auth/**` | auth-service |
| `/api/v1/commands/**` | command-service |
| `/api/v1/naver/**` | price-service |
| `/api/v1/aliexpress/**` | price-service |
| `/api/v1/payments/**` | payment-service |
| `/api/v1/notification/**` | notification-service |

### 주의
- `notification-service`에는 현재 REST Controller가 없으므로, gateway에 라우트는 있어도 실제로 응답할 API는 없다.

---

## 4. auth-service

### 개요
- 회원가입, 로그인, 로그아웃, 토큰 재발급, 내 정보 조회, 비밀번호 변경 API가 구현되어 있다.
- JWT access/refresh를 발급한다.
- refresh token은 Redis에 저장한다.
- 로그인 성공 시 `user-event` Kafka 이벤트를 발행한다.
- Swagger 경로가 열려 있다.
  - `/swagger-ui.html`
  - `/v3/api-docs`

### Base URL
- direct: `http://localhost:8081`
- gateway: `http://localhost:8080`

### 인증 정책

| 경로 | 인증 필요 여부 |
| --- | --- |
| `POST /api/v1/auth/signup` | 불필요 |
| `POST /api/v1/auth/login` | 불필요 |
| `POST /api/v1/auth/refresh` | 불필요 |
| `POST /api/v1/auth/logout` | 필요 |
| `GET /api/v1/auth/me` | 필요 |
| `PUT /api/v1/auth/password` | 필요 |

### 응답 DTO

#### UserResponse
| 필드 | 타입 |
| --- | --- |
| id | Long |
| email | String |
| nickname | String |
| role | String |

#### TokenResponse
| 필드 | 타입 |
| --- | --- |
| accessToken | String |
| refreshToken | String |
| tokenType | String |
| expiresIn | long |

### API 목록

#### 4-1. 회원가입
`POST /api/v1/auth/signup`

Request Body:

```json
{
  "email": "user@example.com",
  "password": "password1234",
  "nickname": "coffee"
}
```

요청 규칙:
- `email`: 필수, 이메일 형식
- `password`: 필수, 8자 이상
- `nickname`: 필수, 2~20자

성공 응답: `201 Created`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "email": "user@example.com",
    "nickname": "coffee",
    "role": "USER"
  },
  "message": "성공"
}
```

실패 응답:
- `400`: DTO 검증 실패
- `409`: 이미 사용 중인 이메일

#### 4-2. 로그인
`POST /api/v1/auth/login`

Request Body:

```json
{
  "email": "user@example.com",
  "password": "password1234"
}
```

성공 시:
- 이메일/비밀번호 검증
- access token + refresh token 발급
- Redis에 `RT:{userId}` 키로 refresh token 저장
- Kafka `user-event` 토픽에 `LOGIN_SUCCESS` 이벤트 발행

성공 응답: `200 OK`

```json
{
  "success": true,
  "data": {
    "accessToken": "...",
    "refreshToken": "...",
    "tokenType": "Bearer",
    "expiresIn": 1800000
  },
  "message": "성공"
}
```

실패 응답:
- `400`: DTO 검증 실패
- `401`: 이메일 또는 비밀번호 불일치

#### 4-3. 로그아웃
`POST /api/v1/auth/logout`

Header:

```http
Authorization: Bearer {accessToken}
```

동작:
- 인증 사용자 식별
- Redis에서 `RT:{userId}` 삭제

성공 응답:

```json
{
  "success": true,
  "data": null,
  "message": "로그아웃되었습니다."
}
```

실패 응답:
- `401`: 인증 없음/토큰 무효

#### 4-4. 토큰 재발급
`POST /api/v1/auth/refresh`

Header:

```http
Refresh-Token: {refreshToken}
```

동작:
- refresh token 유효성 검사
- 토큰에서 userId 추출
- Redis 저장값과 완전 일치 여부 확인
- 새 access/refresh token 재발급
- Redis 값 갱신(rotate)

성공 응답:

```json
{
  "success": true,
  "data": {
    "accessToken": "...",
    "refreshToken": "...",
    "tokenType": "Bearer",
    "expiresIn": 1800000
  },
  "message": "성공"
}
```

실패 응답:
- `401`: 유효하지 않은 토큰
- `404`: 토큰의 userId에 해당하는 사용자 없음

#### 4-5. 내 정보 조회
`GET /api/v1/auth/me`

Header:

```http
Authorization: Bearer {accessToken}
```

성공 응답:

```json
{
  "success": true,
  "data": {
    "id": 1,
    "email": "user@example.com",
    "nickname": "coffee",
    "role": "USER"
  },
  "message": "성공"
}
```

실패 응답:
- `401`: 인증 없음/토큰 무효
- `404`: 사용자 없음

#### 4-6. 비밀번호 변경
`PUT /api/v1/auth/password`

Header:

```http
Authorization: Bearer {accessToken}
```

Request Body:

```json
{
  "currentPassword": "password1234",
  "newPassword": "newpassword5678"
}
```

요청 규칙:
- `currentPassword`: 필수
- `newPassword`: 필수, 8자 이상

성공 응답:

```json
{
  "success": true,
  "data": null,
  "message": "비밀번호가 변경되었습니다."
}
```

실패 응답:
- `400`: DTO 검증 실패 또는 현재 비밀번호 불일치
- `401`: 인증 없음/토큰 무효
- `404`: 사용자 없음

### auth-service 예외 응답 규칙

| 상태코드 | 조건 | 메시지 예시 |
| --- | --- | --- |
| 400 | DTO 검증 실패 | `비밀번호는 8자 이상이어야 합니다.` |
| 400 | 현재 비밀번호 불일치 | `현재 비밀번호가 일치하지 않습니다.` |
| 401 | 인증 실패 | `인증이 필요합니다.` |
| 401 | 로그인 실패 | `이메일 또는 비밀번호가 올바르지 않습니다.` |
| 401 | refresh token 오류 | `유효하지 않은 토큰입니다.` |
| 404 | 사용자 없음 | `사용자를 찾을 수 없습니다.` |
| 409 | 이메일 중복 | `이미 사용 중인 이메일입니다.` |
| 500 | 서버 오류 | `서버 오류가 발생했습니다.` |

---

## 5. command-service

### 개요
- 자연어 명령 파싱 시작 API
- `commandId` 기반 세션 조회 API
- clarification 제출 API
- pre-search / post-search clarification 상태 관리
- `selectedProductId` 기반 fast-path 지원

### Base URL
- direct: `http://localhost:8082`
- gateway: `http://localhost:8080`

### API 목록

| Method | Path | 설명 |
| --- | --- | --- |
| POST | `/api/v1/commands/parse` | 자연어 명령 파싱 시작 |
| GET | `/api/v1/commands/{commandId}` | 명령 세션 조회(polling) |
| POST | `/api/v1/commands/{commandId}/clarifications` | 보완 입력 제출 |

### 핵심 상태
- `PRE_SEARCH_CLARIFICATION`
- `SEARCHING`
- `PRODUCT_SELECTION_REQUIRED`
- `MONITORING_STARTED`

### 핵심 구현 포인트
- `POST /parse` 응답에 항상 `commandId` 포함
- pre-search 누락 시 세션 생성 후 clarification 대기
- post-search 후보 여러 개일 때 후보 목록을 세션에 저장
- 사용자가 `selectedProductId`를 보내면 재파싱 없이 `product-selection` 이벤트 발행

### 예외 응답 규칙

| 상태코드 | 조건 |
| --- | --- |
| 400 | 유효하지 않은 후보 선택 |
| 404 | command session 없음 |
| 503 | external-api-service AI 파싱 프록시 장애 |
| 500 | 기타 런타임 예외 |

### 상세 문서
- 세부 요청/응답 예시와 상태 흐름은 아래 문서 참고
- `docs/2026-05-12-command-price-api-spec.md`

---

## 6. price-service

### 개요
- 네이버/알리익스프레스 상품 검색 API 제공
- AliExpress 카테고리 수동 동기화 API 제공
- `price-topic` 소비 후 검색/가격판단 수행
- 상품 선택(product-selection-required) 필요 시 `product-selection-required` 발행
- 상품 선택 fast-path 소비
- 목표가 충족 시 `price-alert`, `payment-topic` 발행
- 스케줄러 기반 모니터링 수집 구현

### Base URL
- direct: `http://localhost:8083`
- gateway: `http://localhost:8080`

### REST API 목록

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/api/v1/naver/search` | 네이버 상품 검색 |
| GET | `/api/v1/aliexpress/search` | AliExpress 상품 검색 |
| POST | `/api/v1/admin/aliexpress/categories/sync` | AliExpress 카테고리 수동 동기화 |

### SearchResponse 필드

| 필드 | 타입 |
| --- | --- |
| title | String |
| lprice | String |
| hprice | String |
| mallName | String |
| link | String |
| currency | String |

### Query Parameter 요약

#### 네이버 검색
- `keyword` (필수)
- `display` (기본 10)

#### 알리 검색
- `keyword` (필수)
- `pageNo` (기본 1)
- `pageSize` (기본 10)
- `sort`
- `targetCurrency` (기본 KRW)
- `targetLanguage` (기본 KO)
- `shipToCountry` (기본 KR)
- `trackingId`

### 예외 응답 규칙

| 상태코드 | 조건 |
| --- | --- |
| 503 | external-api-service 호출 장애 |
| 500 | 기타 런타임 예외 |

### 상세 문서
- `docs/2026-05-12-command-price-api-spec.md`

---

## 7. payment-service

### 개요
- `payment-topic` Kafka 이벤트를 소비한다.
- 결제 엔티티를 생성하고 처리 후 상태를 저장한다.
- 사용자별 결제 목록/상세 조회 REST API를 제공한다.
- 처리 완료 후 `payment-result` 이벤트를 발행한다.
- 현재 실제 블록체인 결제기는 없고 `StubPaymentProcessor`가 항상 성공하는 개발용 구현이다.

### Base URL
- direct: `http://localhost:8084`
- gateway: `http://localhost:8080`

### REST API 목록

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/api/v1/payments?userId={userId}` | 사용자별 결제 목록 조회 |
| GET | `/api/v1/payments/{paymentId}` | 결제 상세 조회 |

### PaymentSummaryResponse

| 필드 | 타입 |
| --- | --- |
| paymentId | String |
| userId | Long |
| productName | String |
| amount | Integer |
| currency | String |
| status | PaymentStatus |
| createdAt | Instant |

### PaymentDetailResponse

| 필드 | 타입 |
| --- | --- |
| paymentId | String |
| userId | Long |
| productName | String |
| productUrl | String |
| amount | Integer |
| currency | String |
| status | PaymentStatus |
| transactionHash | String |
| failureReason | String |
| createdAt | Instant |
| updatedAt | Instant |

### 결제 상태
- `PENDING`
- `SUCCESS`
- `FAILED`

### API 예시

#### 7-1. 결제 목록 조회
`GET /api/v1/payments?userId=1`

성공 응답 예시:

```json
{
  "success": true,
  "data": [
    {
      "paymentId": "pay-a1b2c3d4",
      "userId": 1,
      "productName": "QCY T13 PRO",
      "amount": 28300,
      "currency": "KRW",
      "status": "SUCCESS",
      "createdAt": "2026-05-12T10:15:30Z"
    }
  ],
  "message": "결제 목록 조회 성공"
}
```

#### 7-2. 결제 상세 조회
`GET /api/v1/payments/pay-a1b2c3d4`

성공 응답 예시:

```json
{
  "success": true,
  "data": {
    "paymentId": "pay-a1b2c3d4",
    "userId": 1,
    "productName": "QCY T13 PRO",
    "productUrl": "https://ko.aliexpress.com/item/...",
    "amount": 28300,
    "currency": "KRW",
    "status": "SUCCESS",
    "transactionHash": "0xabc123...",
    "failureReason": null,
    "createdAt": "2026-05-12T10:15:30Z",
    "updatedAt": "2026-05-12T10:15:32Z"
  },
  "message": "결제 상세 조회 성공"
}
```

실패 응답:
- `paymentId` 없음 → 현재 컨트롤러에서 `success=false` 응답으로 변환

```json
{
  "success": false,
  "data": null,
  "message": "결제 건을 찾을 수 없습니다: paymentId=pay-xxxx"
}
```

### 현재 한계
- 실제 Web3/블록체인 결제 구현은 아직 없음
- 예외 처리 체계가 auth/command/price보다 단순함
- payment-result 발행은 구현됐지만, 현재 notification-service에는 해당 consumer가 없다

---

## 8. notification-service

### 개요
- 현재 **REST API는 없다.**
- `price-alert` Kafka 이벤트를 소비한다.
- 이벤트를 사람이 읽기 쉬운 문자열로 포맷한 뒤 `ConsoleNotificationSender`로 출력한다.

### 현재 구현 범위

| 항목 | 상태 |
| --- | --- |
| price-alert consumer | 구현됨 |
| 알림 메시지 포맷팅 | 구현됨 |
| 콘솔 발송 | 구현됨 |
| 텔레그램 발송 | 미구현 |
| 이메일 발송 | 미구현 |
| REST API | 없음 |
| user-event consumer | 없음 |
| payment-result consumer | 없음 |

### 주의
- `docs/KAFKA_EVENT_SCHEMA.md`에는 더 넓은 이벤트 연동 방향이 적혀 있을 수 있으나,
  **현재 코드 기준 실제 consumer는 `price-alert`만 존재**한다.

---

## 9. external-api-service (FastAPI)

### 개요
- Spring 서비스가 직접 외부 API를 호출하지 않고, 이 FastAPI 서비스를 통해 프록시 호출한다.
- 네이버 쇼핑, AliExpress, OpenAI 파싱 프록시가 구현되어 있다.
- mock mode 환경변수 지원

### Base URL
- `http://localhost:8090`

### API 목록

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/health` | 헬스체크 + mock mode 상태 |
| GET | `/api/v1/naver/search` | 네이버 쇼핑 검색 프록시 |
| GET | `/api/v1/aliexpress/categories` | 알리 카테고리 조회 프록시 |
| GET | `/api/v1/aliexpress/search` | 알리 상품 검색 프록시 |
| POST | `/api/v1/openai/parse-command` | 자연어 파싱 프록시 |

### 9-1. 헬스체크
`GET /health`

응답 예시:

```json
{
  "status": "ok",
  "service": "external-api-service",
  "mock_mode": {
    "naver": false,
    "aliexpress": false,
    "openai": false
  }
}
```

### 9-2. OpenAI 파싱 프록시
`POST /api/v1/openai/parse-command`

Request Body:

```json
{
  "system_prompt": "...",
  "user_prompt": "QCY T13 PRO 블랙 알리익스프레스에서 5만원 이하면 결제해줘"
}
```

Response 필드:

| 필드 | 타입 |
| --- | --- |
| parsed_json | object |
| finish_reason | string |
| confidence | float/null |
| refusal | string/null |

### 9-3. 네이버 검색 프록시
`GET /api/v1/naver/search?keyword=아이폰&display=10&start=1`

응답 구조:
- `total`
- `start`
- `display`
- `items[]`

`items[]` 주요 필드:
- `title`
- `lprice`
- `hprice`
- `mallName`
- `link`
- `productId`
- `image`
- `maker`
- `brand`
- `category1~4`

### 9-4. 알리 카테고리 프록시
`GET /api/v1/aliexpress/categories`

응답 구조:
- `total`
- `items[]`
  - `category_id`
  - `category_name`
  - `parent_category_id`

### 9-5. 알리 상품 검색 프록시
`GET /api/v1/aliexpress/search`

주요 쿼리 파라미터:
- `keyword`
- `page_no`
- `page_size`
- `sort`
- `target_currency`
- `target_language`
- `ship_to_country`
- `tracking_id`

응답 구조:
- `total`
- `page_no`
- `page_size`
- `items[]`

`items[]` 주요 필드:
- `product_id`
- `product_title`
- `product_detail_url`
- `product_main_image_url`
- `sale_price`
- `target_sale_price`
- `target_original_price`
- `target_app_sale_price`
- `target_app_original_price`
- `discount`
- `evaluate_rate`
- `commission_rate`
- `lastest_volume`
- `shop_name`
- `shop_url`
- `first_level_category_id`
- `first_level_category_name`
- `second_level_category_id`
- `second_level_category_name`

---

## 10. eureka-server

### 개요
- 서비스 디스커버리 전용 서버
- 비즈니스 API는 없다

### Base URL
- `http://localhost:8761`

### 현재 구현 범위
- Eureka server 부트스트랩
- 자기 자신 register/fetch 비활성화
- DB/JPA 없음

---

## 11. 현재 실제 Kafka 흐름

### 실제 구현 기준 producer/consumer 매핑

| 토픽 | Producer | Consumer | 현재 코드 상태 |
| --- | --- | --- | --- |
| `user-event` | auth-service | 없음 | **로그인 성공 발행만 구현** |
| `price-topic` | command-service | price-service | 구현됨 |
| `product-selection-required` | price-service | command-service | 구현됨 |
| `product-selection` | command-service | price-service | 구현됨 |
| `price-alert` | price-service | notification-service | 구현됨 |
| `payment-topic` | price-service | payment-service | 구현됨 |
| `payment-result` | payment-service | 없음 | 발행만 구현 |

### 핵심 흐름 1: 인증
1. 사용자가 로그인
2. auth-service가 JWT 발급
3. Redis에 refresh token 저장
4. auth-service가 `user-event`에 `LOGIN_SUCCESS` 발행
5. 현재 코드에는 이 이벤트를 소비하는 서비스가 없음

### 핵심 흐름 2: 명령 → 검색 → 보완 → 결제
1. command-service가 자연어를 파싱
2. 준비되면 `price-topic` 발행
3. price-service가 검색/가격판단 수행
4. 상품 선택 필요 시 `product-selection-required` 발행
5. command-service가 세션을 `PRODUCT_SELECTION_REQUIRED`로 업데이트
6. 사용자가 후보 선택 시 command-service가 `product-selection` 발행
7. price-service가 선택 상품을 기준으로 가격 비교
8. 목표가 충족되면 `price-alert`와 `payment-topic` 발행
9. notification-service는 콘솔 알림 출력
10. payment-service는 결제 처리 후 `payment-result` 발행
11. 현재 `payment-result`를 소비하는 구현은 없음

---

## 12. 현재 구현/미구현 경계

### 구현된 것
- auth-service 인증 API 전체
- command-service 세션 기반 명령 플로우
- price-service 검색 API + 카테고리 sync + post-search validation
- payment-service 결제 요청 소비 + 결제 조회 API
- notification-service price-alert 콘솔 알림
- gateway JWT 필터 + 라우팅
- external-api-service 프록시 API

### 아직 미완성/스텁인 것
- payment-service 실제 블록체인 결제
- notification-service 실제 텔레그램/이메일 발송
- notification-service의 `user-event`, `payment-result` 소비
- notification-service REST API

### 문서 읽을 때 주의할 점
- `ROADMAP`, `KAFKA_EVENT_SCHEMA`에는 목표 상태가 일부 포함될 수 있다.
- 이 문서는 **현재 코드 구현 기준**이다.

---

## 13. 관련 문서

- `docs/2026-05-12-command-price-api-spec.md`
- `docs/KAFKA_EVENT_SCHEMA.md`
- `docs/2026-05-04-command-service-natural-language-parsing-checklist.md`
- `docs/2026-04-27-backend-concepts.md`
- `docs/2026-04-27-price-service-db-persistence.md`
- `docs/2026-04-25-external-api-service-devlog.md`
- `docs/AliExpress_Integration.md`

---

## 14. 문서 기준 버전

- 작성일: 2026-05-12
- 기준: 현재 레포지토리 소스코드
- 포함 범위:
  - gateway
  - auth-service
  - command-service
  - price-service
  - payment-service
  - notification-service
  - external-api-service
  - eureka-server
- 제외 범위:
  - 프론트엔드 구현 세부
  - 미구현 예정 기능의 상세 설계
