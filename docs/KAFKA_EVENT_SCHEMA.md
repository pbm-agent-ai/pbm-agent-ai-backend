# Kafka 이벤트 스키마 초안

## 1. 서비스 간 발행/구독 흐름

- `auth-service` → `user-event` → `notification-service`
- `command-service` → `price-topic` → `price-service`
- `price-service` → `price-alert` → `notification-service`
- `price-service` → `payment-topic` → `payment-service`
- `payment-service` → `payment-result` → `notification-service`

## 2. 토픽별 용도 한 줄 정리

| 토픽 | 발행 서비스 | 소비 서비스 | 용도 |
|------|-------------|-------------|------|
| `user-event` | `auth-service` | `notification-service` | 회원가입/로그인 같은 사용자 인증 이벤트를 알림 서비스로 전달한다. |
| `price-topic` | `command-service` | `price-service` | 자연어 명령에서 추출한 가격 조회/모니터링 요청을 가격 서비스로 전달한다. |
| `price-alert` | `price-service` | `notification-service` | 가격 조건 충족 결과를 알림 서비스로 전달한다. |
| `payment-topic` | `price-service` | `payment-service` | 자동결제 실행 요청을 결제 서비스로 전달한다. |
| `payment-result` | `payment-service` | `notification-service` | 결제 성공/실패 결과를 알림 서비스로 전달한다. |

## 3. 공통 이벤트 구조

모든 Kafka 이벤트는 아래 공통 Envelope 구조를 따른다.

```json
{
  "eventId": "2c8f98fe-3f9e-4e97-92ea-0b8fa7bb1e6f",
  "eventType": "LOGIN_SUCCESS",
  "occurredAt": "2026-04-25T13:00:00Z",
  "producer": "auth-service",
  "payload": {
  }
}
```

### 공통 필드 규칙

| 필드 | 타입 | 설명 |
|------|------|------|
| `eventId` | `String` | 이벤트 고유 식별자(UUID 권장) |
| `eventType` | `String` | 이벤트 종류 (`LOGIN_SUCCESS`, `PRICE_ALERT` 등) |
| `occurredAt` | `String`(ISO-8601) | 이벤트가 실제 발생한 시각 |
| `producer` | `String` | 이벤트를 발행한 서비스명 |
| `payload` | `Object` | 토픽 목적에 맞는 실제 데이터 |

## 4. 토픽별 이벤트 타입

### `user-event`
- `SIGNUP_SUCCESS`
- `LOGIN_SUCCESS`
- `PASSWORD_CHANGED`

### `price-topic`
- `PRICE_CHECK_REQUEST`

### `price-alert`
- `PRICE_ALERT`

### `payment-topic`
- `PAYMENT_REQUESTED`

### `payment-result`
- `PAYMENT_COMPLETED`
- `PAYMENT_FAILED`

## 5. 토픽별 payload 최소 필드

### 5.1 `user-event`

인증 관련 후속 알림에 필요한 최소 정보만 담는다.

```json
{
  "eventId": "uuid",
  "eventType": "LOGIN_SUCCESS",
  "occurredAt": "2026-04-25T13:00:00Z",
  "producer": "auth-service",
  "payload": {
    "userId": 1,
    "email": "user@pbm.com",
    "nickname": "pbm-user"
  }
}
```

### 5.2 `price-topic`

가격 조회 요청을 시작하기 위한 최소 정보만 담는다.

```json
{
  "eventId": "uuid",
  "eventType": "PRICE_CHECK_REQUEST",
  "occurredAt": "2026-04-25T13:10:00Z",
  "producer": "command-service",
  "payload": {
    "userId": 1,
    "keyword": "아이폰 15",
    "targetPrice": 1200000
  }
}
```

### 5.3 `price-alert`

알림 발송 판단에 필요한 가격 충족 결과를 담는다.

```json
{
  "eventId": "uuid",
  "eventType": "PRICE_ALERT",
  "occurredAt": "2026-04-25T13:20:00Z",
  "producer": "price-service",
  "payload": {
    "userId": 1,
    "productName": "아이폰 15",
    "currentPrice": 1190000,
    "targetPrice": 1200000,
    "productUrl": "https://shopping.example/item/1"
  }
}
```

### 5.4 `payment-topic`

자동결제 서비스가 실행에 필요한 최소 결제 요청 정보를 받는다.

```json
{
  "eventId": "uuid",
  "eventType": "PAYMENT_REQUESTED",
  "occurredAt": "2026-04-25T13:25:00Z",
  "producer": "price-service",
  "payload": {
    "userId": 1,
    "productName": "아이폰 15",
    "productUrl": "https://shopping.example/item/1",
    "amount": 1190000,
    "currency": "KRW"
  }
}
```

### 5.5 `payment-result`

결제 결과 알림에 필요한 최소 결과 정보만 담는다.

```json
{
  "eventId": "uuid",
  "eventType": "PAYMENT_COMPLETED",
  "occurredAt": "2026-04-25T13:30:00Z",
  "producer": "payment-service",
  "payload": {
    "userId": 1,
    "paymentId": "pay-20260425-0001",
    "status": "SUCCESS",
    "transactionHash": "0xabc123",
    "message": "자동결제가 완료되었습니다."
  }
}
```

## 6. 설계 원칙

- payload에는 **소비 서비스가 바로 처리하는 데 필요한 값만** 담는다.
- 민감정보(비밀번호, API Key, 개인 비밀키)는 이벤트에 절대 담지 않는다.
- `occurredAt`은 ISO-8601 UTC 기준 문자열로 관리한다.
- `eventType`은 대문자 스네이크 케이스로 통일한다.
- 추후 공유 모듈이 생기면 공통 이벤트 Envelope를 별도 모듈로 분리한다.
