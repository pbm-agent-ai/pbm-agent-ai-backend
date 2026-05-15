# Command / Price API 명세서

## 문서 목적
- 현재 레포지토리에서 **실제로 구현되어 있는 API**를 기준으로 명세를 정리한다.
- 특히 이번 개발 범위였던 **자연어 명령 파싱 → 세션 조회 → 보완 입력 → 검색 후 후보 선택** 흐름을 상세히 설명한다.
- 구현되지 않은 API는 문서에 포함하지 않는다.

---

## 공통 사항

### Base URL 예시
- command-service: `http://localhost:8082`
- price-service: `http://localhost:8083`

### 공통 응답 형식

#### command-service
```json
{
  "success": true,
  "data": {},
  "message": "성공"
}
```

#### price-service
```json
{
  "success": true,
  "data": {},
  "message": "검색 성공"
}
```

### 인증 헤더
- 현재 문서에 포함된 API들은 **서비스 코드 자체에서 Authorization 헤더를 직접 검증하지 않는다.**
- 실제 운영에서는 Gateway / 인증 필터를 통해 보호될 수 있으므로, 배포 환경에서는 별도 인증 정책을 확인해야 한다.

예시 헤더:
```http
Content-Type: application/json
Authorization: Bearer {accessToken}
```

> 주의: 위 Authorization은 **운영 환경 예시**이며, 현재 각 서비스 컨트롤러 구현만 놓고 보면 필수 검증 로직은 없다.

---

## 전체 흐름 요약

### 1. 자연어 명령 시작
1. 프론트가 `POST /api/v1/commands/parse` 호출
2. command-service가 자연어를 파싱
3. 결과에 따라:
   - 1차 누락 조건이 있으면 `PRE_SEARCH_CLARIFICATION`
   - 1차 누락 조건이 없으면 `SEARCHING`
4. 모든 경우 응답에 `commandId`가 포함됨

### 2. 세션 상태 조회
1. 프론트가 `GET /api/v1/commands/{commandId}` polling
2. 상태 변화 확인:
   - `PRE_SEARCH_CLARIFICATION`
   - `SEARCHING`
   - `PRODUCT_SELECTION_REQUIRED`
   - `MONITORING_STARTED`

### 3. 보완 입력 제출
1. 프론트가 `POST /api/v1/commands/{commandId}/clarifications` 호출
2. pre-search면 재파싱 기반 보완 진행
3. post-search면 아래 두 경로 중 하나:
   - 자유 텍스트 / structured answers → 재파싱
   - `selectedProductId` → **fast-path** (재파싱 없이 후보 선택)

### 4. post-search 후보 선택
1. `GET /api/v1/commands/{commandId}` 응답의 `candidates` 목록을 프론트가 사용자에게 표시
2. 사용자가 하나를 선택하면 `selectedProductId`를 제출
3. command-service가 `product-selection-topic` 이벤트 발행
4. price-service가 선택 상품 기준으로 가격 비교 / 알림 / 결제 요청 수행

---

# 1. Command API

## 1-1. 자연어 명령 파싱 시작

### Endpoint
`POST /api/v1/commands/parse`

### 설명
- 자연어 명령을 파싱하고, **명령 세션을 생성**한다.
- 응답의 `commandId`는 이후 polling / clarification 제출에 사용된다.
- 1차 누락 조건이 없으면 내부적으로 price-service 검색 플로우가 시작될 수 있다.

### Request Header
```http
Content-Type: application/json
Authorization: Bearer {accessToken}
```

### Request Body
```json
{
  "userId": 1,
  "commandText": "QCY T13 PRO 블랙 알리익스프레스에서 5만원 이하면 결제해줘"
}
```

### Request 필드 설명
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| userId | Long | ✅ | 명령 요청 사용자 ID |
| commandText | String | ✅ | 사용자가 입력한 자연어 명령문 |

### 성공 응답 예시 - 1차 보완 불필요
```json
{
  "success": true,
  "data": {
    "intent": "AUTO_PURCHASE",
    "parsedCommand": {
      "productCategory": "ELECTRONICS",
      "productName": "QCY T13 PRO",
      "brand": "QCY",
      "line": null,
      "model": "T13 PRO",
      "color": "블랙",
      "size": null,
      "platform": "ALIEXPRESS",
      "maxPrice": 50000,
      "minPrice": null,
      "currency": "KRW"
    },
    "missingRequiredFields": [],
    "ambiguousFields": [],
    "needsClarification": false,
    "confidence": 0.98,
    "commandId": "2c98c44f-b892-4be7-ac1b-05a01a9e5d0d"
  },
  "message": "성공"
}
```

### 성공 응답 예시 - 1차 보완 필요
```json
{
  "success": true,
  "data": {
    "intent": "AUTO_PURCHASE",
    "parsedCommand": {
      "productCategory": "SHOES",
      "productName": "나이키 조던",
      "brand": "나이키",
      "line": null,
      "model": null,
      "color": null,
      "size": null,
      "platform": null,
      "maxPrice": 200000,
      "minPrice": null,
      "currency": "KRW"
    },
    "missingRequiredFields": ["size", "platform"],
    "ambiguousFields": [],
    "needsClarification": true,
    "confidence": 0.95,
    "commandId": "f6b5d00c-9ee4-485f-8eb1-ada13f0739ea"
  },
  "message": "성공"
}
```

### Response 필드 설명
| 필드 | 타입 | 설명 |
| --- | --- | --- |
| intent | String | 명령 의도 (`PRICE_CHECK`, `AUTO_PURCHASE` 등) |
| parsedCommand | Object | GPT가 추출한 구조화 결과 |
| missingRequiredFields | List<String> | 1차 검증 기준 누락 필드 목록 |
| ambiguousFields | List<String> | 모호해서 추가 확인이 필요한 필드 목록 |
| needsClarification | boolean | 모달 표시 필요 여부 |
| confidence | Double | 파싱 신뢰도 |
| commandId | String | UUID 형식 명령 세션 식별자 |

### ParsedCommand 필드 설명
| 필드 | 타입 | 설명 |
| --- | --- | --- |
| productCategory | String | 의미론적 카테고리 (`SHOES`, `ELECTRONICS` 등) |
| productName | String | 상품명 |
| brand | String | 브랜드 |
| line | String | 라인/시리즈 |
| model | String | 모델명 |
| color | String | 색상 |
| size | String | 사이즈 |
| platform | String | 플랫폼 (`NAVER`, `ALIEXPRESS`) |
| maxPrice | Integer | 최대 희망 가격 |
| minPrice | Integer | 최소 가격 |
| currency | String | 통화 코드 |

### 실패 응답
현재 `GlobalExceptionHandler` 기준:

#### 503 외부 AI 파싱 서비스 오류
```json
{
  "success": false,
  "data": null,
  "message": "외부 AI 파싱 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해주세요."
}
```

#### 500 서버 내부 오류
```json
{
  "success": false,
  "data": null,
  "message": "서버 내부 오류가 발생했습니다. 관리자에게 문의해주세요."
}
```

---

## 1-2. 명령 세션 조회 (Polling API)

### Endpoint
`GET /api/v1/commands/{commandId}`

### 설명
- 프론트가 `commandId` 기준으로 현재 명령 세션 상태를 조회하는 API이다.
- pre-search / post-search clarification 모달 표시 여부를 판단할 때 사용한다.
- post-search clarification 상태라면 후보 상품 목록도 함께 내려온다.

### Path Parameter
| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| commandId | String | UUID 형식 명령 세션 ID |

### 성공 응답 예시 - PRE_SEARCH_CLARIFICATION
```json
{
  "success": true,
  "data": {
    "commandId": "f6b5d00c-9ee4-485f-8eb1-ada13f0739ea",
    "userId": 1,
    "originalCommand": "나이키 조던 20만원 이하면 결제해줘",
    "status": "PRE_SEARCH_CLARIFICATION",
    "missingFields": ["size", "platform"],
    "clarificationMessage": "사이즈와 플랫폼을 알려주세요.",
    "categoryPath": null,
    "candidates": [],
    "targetPrice": null,
    "commandIntent": null,
    "createdAt": "2026-05-11T23:10:00",
    "updatedAt": "2026-05-11T23:10:00"
  },
  "message": "성공"
}
```

### 성공 응답 예시 - PRODUCT_SELECTION_REQUIRED
```json
{
  "success": true,
  "data": {
    "commandId": "2c98c44f-b892-4be7-ac1b-05a01a9e5d0d",
    "userId": 1,
    "originalCommand": "QCY T13 PRO 블랙 알리익스프레스에서 5만원 이하면 결제해줘",
    "status": "PRODUCT_SELECTION_REQUIRED",
    "missingFields": ["searchResultsCount"],
    "clarificationMessage": "AUTO_PURCHASE 의도이나 검색 결과가 4개로 명확하지 않습니다",
    "categoryPath": null,
    "candidates": [
      {
        "productId": "1005011904646394",
        "title": "QCY T13 Pro 어댑티브 ANC 무선 이어버드 13mm 드라이버 강력한 베이스 50dB 하이브리드 ANC 블루투스 6.0 이어폰 6개 마이크 ENC 47시간 재생 HT23",
        "lprice": "28300",
        "mallName": "QCY Official Store",
        "productUrl": "https://ko.aliexpress.com/item/1005011904646394.html?...",
        "currency": "KRW",
        "searchKeyword": "QCY T13 PRO 블랙"
      }
    ],
    "targetPrice": 50000,
    "commandIntent": "AUTO_PURCHASE",
    "createdAt": "2026-05-11T23:59:09.895905",
    "updatedAt": "2026-05-11T23:59:11.147928"
  },
  "message": "성공"
}
```

### 성공 응답 예시 - MONITORING_STARTED
```json
{
  "success": true,
  "data": {
    "commandId": "2c98c44f-b892-4be7-ac1b-05a01a9e5d0d",
    "userId": 1,
    "originalCommand": "QCY T13 PRO 블랙 알리익스프레스에서 5만원 이하면 결제해줘",
    "status": "MONITORING_STARTED",
    "missingFields": ["searchResultsCount"],
    "clarificationMessage": "AUTO_PURCHASE 의도이나 검색 결과가 4개로 명확하지 않습니다",
    "categoryPath": null,
    "candidates": [
      {
        "productId": "1005011904646394",
        "title": "QCY T13 Pro 어댑티브 ANC 무선 이어버드 ...",
        "lprice": "28300",
        "mallName": "QCY Official Store",
        "productUrl": "https://ko.aliexpress.com/item/1005011904646394.html?...",
        "currency": "KRW",
        "searchKeyword": "QCY T13 PRO 블랙"
      }
    ],
    "targetPrice": 50000,
    "commandIntent": "AUTO_PURCHASE",
    "createdAt": "2026-05-11T23:59:09.895905",
    "updatedAt": "2026-05-11T23:59:37.569427"
  },
  "message": "성공"
}
```

### Response 필드 설명
| 필드 | 타입 | 설명 |
| --- | --- | --- |
| commandId | String | UUID 형식 명령 세션 ID |
| userId | Long | 사용자 ID |
| originalCommand | String | 현재 세션이 기준으로 삼는 원본 명령문 |
| status | String | 세션 상태 |
| missingFields | List<String> | 현재 단계에서 부족한 필드 목록 |
| clarificationMessage | String | 사용자에게 보여줄 보완 메시지 |
| categoryPath | String | 검색 후 확정된 카테고리 경로 (현재 null 가능) |
| candidates | List<Object> | post-search 후보 상품 목록 |
| targetPrice | Integer | 원래 요청 목표 가격 |
| commandIntent | String | 사용자 의도 (`AUTO_PURCHASE`, `PRICE_CHECK`) |
| createdAt | LocalDateTime | 세션 생성 시각 |
| updatedAt | LocalDateTime | 세션 최종 변경 시각 |

### status 값 의미
| status | 의미 |
| --- | --- |
| PRE_SEARCH_CLARIFICATION | 1차 검증에서 누락 조건 발견, 검색 전 보완 필요 |
| SEARCHING | 1차 검증 통과, 현재 검색 / 검색 후 검증 진행 중 |
| PRODUCT_SELECTION_REQUIRED | 검색 후 후보 상품 선택 필요 |
| MONITORING_STARTED | 보완 완료 후 후속 처리 시작 |

### 실패 응답

#### 404 세션 없음
```json
{
  "success": false,
  "data": null,
  "message": "세션을 찾을 수 없습니다. commandId: non-existent-uuid"
}
```

#### 500 서버 내부 오류
```json
{
  "success": false,
  "data": null,
  "message": "서버 내부 오류가 발생했습니다. 관리자에게 문의해주세요."
}
```

---

## 1-3. 보완 입력 제출

### Endpoint
`POST /api/v1/commands/{commandId}/clarifications`

### 설명
- pre-search / post-search clarification 상태에서 사용자의 보완 입력을 제출한다.
- 현재 세 가지 입력 방식을 모두 지원한다.

### Path Parameter
| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| commandId | String | UUID 형식 명령 세션 ID |

### Request 방식 1 - 자유 텍스트 보완
```json
{
  "clarificationInput": "검은색 270 나이키 공식몰",
  "answers": null,
  "selectedProductId": null
}
```

### Request 방식 2 - 구조화 답변 보완
```json
{
  "clarificationInput": null,
  "answers": {
    "size": "270",
    "platform": "NAVER"
  },
  "selectedProductId": null
}
```

### Request 방식 3 - 후보 상품 선택 fast-path
```json
{
  "clarificationInput": null,
  "answers": null,
  "selectedProductId": "https://ko.aliexpress.com/item/1005011904646394.html?..."
}
```

### Request 필드 설명
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| clarificationInput | String | ❌ | 자유 텍스트 추가 입력 |
| answers | Map<String, String> | ❌ | 구조화된 답변 맵 |
| selectedProductId | String | ❌ | post-search 후보 선택용 productId |

> 최소 하나 이상 값을 보내는 것을 권장한다.

### 동작 방식

#### A. 일반 재파싱 경로
- `clarificationInput` 또는 `answers`가 오면
- `originalCommand + 보완 입력`을 합쳐 재파싱
- 결과에 따라:
  - 여전히 보완 필요 → 다시 `PRE_SEARCH_CLARIFICATION`
  - 보완 완료 → `SEARCHING` 후 후속 검색 재개

#### B. selectedProductId fast-path
- 세션 상태가 `PRODUCT_SELECTION_REQUIRED`
- `selectedProductId`가 세션의 후보 목록에 실제 존재하면
- **재파싱 없이** 바로 후보 선택 이벤트 발행
- 세션 상태를 `MONITORING_STARTED`로 전환

### 성공 응답 예시 - 일반 재파싱 경로
```json
{
  "success": true,
  "data": {
    "intent": "PRICE_CHECK",
    "parsedCommand": {
      "productCategory": "SHOES",
      "productName": "나이키 에어맥스",
      "brand": "나이키",
      "line": "에어맥스",
      "model": null,
      "color": "검은색",
      "size": "270",
      "platform": "NAVER",
      "maxPrice": 150000,
      "minPrice": null,
      "currency": "KRW"
    },
    "missingRequiredFields": [],
    "ambiguousFields": [],
    "needsClarification": false,
    "confidence": 0.94,
    "commandId": "test-uuid-5678"
  },
  "message": "성공"
}
```

### 성공 응답 예시 - selectedProductId fast-path
```json
{
  "success": true,
  "data": {
    "intent": "AUTO_PURCHASE",
    "parsedCommand": {
      "productCategory": null,
      "productName": "QCY T13 Pro 어댑티브 ANC 무선 이어버드 13mm 드라이버 강력한 베이스 50dB 하이브리드 ANC 블루투스 6.0 이어폰 6개 마이크 ENC 47시간 재생 HT23",
      "brand": null,
      "line": null,
      "model": null,
      "color": null,
      "size": null,
      "platform": null,
      "maxPrice": 50000,
      "minPrice": null,
      "currency": "KRW"
    },
    "missingRequiredFields": [],
    "ambiguousFields": [],
    "needsClarification": false,
    "confidence": 1.0,
    "commandId": "2c98c44f-b892-4be7-ac1b-05a01a9e5d0d"
  },
  "message": "성공"
}
```

### 실패 응답

#### 400 유효하지 않은 후보 선택
```json
{
  "success": false,
  "data": null,
  "message": "선택한 상품(commandId: ..., productId: ...)이 후보 목록에 존재하지 않습니다."
}
```

#### 404 세션 없음
```json
{
  "success": false,
  "data": null,
  "message": "세션을 찾을 수 없습니다. commandId: ..."
}
```

#### 500 서버 내부 오류
```json
{
  "success": false,
  "data": null,
  "message": "서버 내부 오류가 발생했습니다. 관리자에게 문의해주세요."
}
```

---

# 2. Price API

## 2-1. AliExpress 카테고리 수동 동기화 (관리자용)

### Endpoint
`POST /api/v1/admin/aliexpress/categories/sync`

### 설명
- external-api-service를 통해 AliExpress 전체 카테고리를 가져와 DB `category_nodes`에 동기화한다.
- 관리자/운영용 API이다.

### Request
- Body 없음

### 성공 응답
```json
{
  "success": true,
  "data": null,
  "message": "AliExpress 카테고리 동기화 성공"
}
```

### 실패 응답
price-service 전역 예외 핸들러가 별도로 붙어 있지 않은 현재 구조상, 일반적인 Spring 오류 응답 또는 서버 에러가 발생할 수 있다.

---

## 2-2. AliExpress 상품 검색

### Endpoint
`GET /api/v1/aliexpress/search`

### 설명
- AliExpress 상품 검색 결과를 공통 `SearchResponse` 형식으로 반환한다.
- 내부적으로 검색 결과 저장 로직이 함께 연결될 수 있다.

### Query Parameter
| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| keyword | String | ✅ | - | 검색 키워드 |
| pageNo | int | ❌ | 1 | 페이지 번호 |
| pageSize | int | ❌ | 10 | 페이지당 결과 수 |
| sort | String | ❌ | null | 정렬 기준 |
| targetCurrency | String | ❌ | KRW | 목표 통화 |
| targetLanguage | String | ❌ | KO | 응답 언어 |
| shipToCountry | String | ❌ | KR | 배송 국가 |
| trackingId | String | ❌ | null | 트래킹 ID |

### 호출 예시
```http
GET /api/v1/aliexpress/search?keyword=이어폰&pageNo=1&pageSize=1
```

### 성공 응답 예시
```json
{
  "success": true,
  "data": [
    {
      "title": "오리지널 마샬 미너 3 트루 무선 인이어 이어폰 무선 블루투스 5.1 하이파이 서브우퍼 음악 헤드폰 HK 버전",
      "lprice": "27550",
      "hprice": "32286",
      "mallName": "Shop1105357744 Store",
      "productUrl": "https://ko.aliexpress.com/item/1005012052863691.html?...",
      "currency": "KRW",
      "productId": "1005012052863691"
    }
  ],
  "message": "AliExpress 검색 성공"
}
```

---

## 2-3. 네이버 상품 검색

### Endpoint
`GET /api/v1/naver/search`

### 설명
- 네이버 상품 검색 결과를 공통 `SearchResponse` 형식으로 반환한다.

### Query Parameter
| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| keyword | String | ✅ | - | 검색 키워드 |
| display | int | ❌ | 10 | 검색 결과 개수 |

### 호출 예시
```http
GET /api/v1/naver/search?keyword=아이폰&display=10
```

### 성공 응답 예시
```json
{
  "success": true,
  "data": [
    {
      "title": "아이폰 15 프로 256GB",
      "lprice": "1450000",
      "hprice": "1590000",
      "mallName": "네이버 스토어",
      "productUrl": "https://shopping.naver.com/...",
      "currency": "KRW",
      "productId": "41234567890"
    }
  ],
  "message": "검색 성공"
}
```

---

# 3. 프론트 연동 포인트 요약

## polling 시작 조건
- `POST /api/v1/commands/parse` 응답에 `commandId`가 내려오고
- `needsClarification = false` 이거나
- 이후 비동기 처리를 추적해야 할 때

## polling 중단 조건
- `GET /api/v1/commands/{commandId}` 의 `status`가 아래 중 하나일 때
  - `PRE_SEARCH_CLARIFICATION`
  - `PRODUCT_SELECTION_REQUIRED`
  - `MONITORING_STARTED`

## pre-search 모달 표시 조건
- `parse` 응답의 `needsClarification = true`
또는
- `GET session` 결과의 `status = PRE_SEARCH_CLARIFICATION`

## post-search 모달 표시 조건
- `GET session` 결과의 `status = PRODUCT_SELECTION_REQUIRED`
- 이때 `candidates` 목록을 화면에 표시하고 사용자가 하나를 선택

## 후보 선택 제출
```json
{
  "selectedProductId": "{GET /commands/{commandId} 응답의 candidates[].productId 값}"
}
```

---

# 4. 현재 구현 기준 주의사항

1. `commandId`는 **Long이 아니라 String(UUID)** 이다.
2. 별도 `start` API는 없고, `POST /api/v1/commands/parse`가 시작 API 역할을 한다.
3. `GET /api/v1/commands/{commandId}` 응답의 `missingFields`는 **배열(List)** 이다. (`missingFieldsJson` 아님)
4. post-search 후보 선택은 이제 `selectedProductId` fast-path를 지원한다.
5. `Authorization` 헤더는 운영 환경에서 Gateway에 의해 요구될 수 있으나, 현재 서비스 컨트롤러 구현 자체에는 직접 인증 검증 로직이 없다.

---

# 5. 문서 버전 메모

- 작성일: 2026-05-12
- 기준: 현재 레포지토리 구현 상태
- 포함 범위:
  - command-service: parse / session GET / clarifications POST
  - price-service: AliExpress category sync / AliExpress search / Naver search
- 제외 범위:
  - 아직 구현되지 않은 별도 완료 API
  - 프론트 전용 상태 관리 코드
  - Gateway 인증 상세 정책
