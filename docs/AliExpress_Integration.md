# AliExpress Affiliate API (IOP) 연동 문서

## 1. 연동 개요

### Affiliate API 선택 이유

AliExpress는 **IOP(Open Platform) Affiliate API**를 통해 제휴 마케팅 데이터를 제공한다. 일반 상품 API와 달리 Affiliate API는 다음 장점이 있다:

- **가격 변환 내장**: `target_currency=KRW` 설정 시 원화 환산 가격(`target_sale_price` 등)을 직접 반환 → 별도 환율 계산 불필요
- **커미션 정보 제공**: `commission_rate` 필드로 제휴 수익률 즉시 확인 가능
- **트래킹 지원**: `tracking_id` 파라미터로 제휴 링크 추적 가능
- **다국어/다통화**: `target_language=KO`, `ship_to_country=KR` 등 한국 시장 타겟팅 지원

### 시스템 내 역할

PBM 시스템에서 AliExpress 연동은 **가격 비교·모니터링** 데이터 소스로 사용된다. 사용자가 등록한 관심 상품의 최저가를 추적하고, 결제 서비스(payment-service)에 가격 정보를 제공하는 핵심 역할을 수행한다.

---

## 2. 인증 및 서명 방식

### IOP 기반 HMAC-SHA256 서명

AliExpress Affiliate API는 **HMAC-SHA256** 서명 방식을 사용한다. 모든 API 요청에 서명(sign) 파라미터가 필수이며, 서명 생성 규칙은 다음과 같다.

#### 필수 파라미터

| 파라미터 | 설명 | 예시 |
|---|---|---|
| `app_key` | IOP에서 발급받은 앱 키 | `510000` |
| `app_secret` | 서명에 사용되는 비밀키 (전송하지 않음) | `hL4m...` |
| `method` | 호출할 API 메서드명 | `aliexpress.affiliate.product.query` |
| `timestamp` | 요청 타임스탬프 | `2026-04-27 14:30:00` |
| `sign_method` | 서명 알고리즘 | `hmac-sha256` |
| `format` | 응답 포맷 | `json` |
| `v` | API 버전 | `2.0` |

#### 서명 생성 규칙

```
1. sign 파라미터를 제외한 모든 파라미터를 key 오름차순 정렬
2. 정렬된 key+value 를 순서대로 이어붙임 → sign_source 문자열 생성
3. HMAC-SHA256(app_secret, sign_source) 해시 생성
4. 해시 결과를 대문자 HEX 문자열로 변환 → sign 값
```

**코드 구현** (`external-api-service/app/services/aliexpress_service.py`):

```python
sign_source = "".join(f"{key}{params[key]}" for key in sorted(params))
sign = hmac.new(
    app_secret.encode("utf-8"),
    sign_source.encode("utf-8"),
    hashlib.sha256,
).hexdigest().upper()
```

#### 타임스탬프 규칙

- **반드시 GMT+8 기준**이어야 한다
- 포맷: `yyyy-MM-dd HH:mm:ss`
- 서버와 5분 이상 차이 나면 에러 응답

```python
timestamp = datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S")
```

---

## 3. API 호출 아키텍처

### 3계층 구조

```
┌─────────────────┐     ┌──────────────────────┐     ┌─────────────────────┐
│  price-service   │────▶│  external-api-service │────▶│  AliExpress API     │
│   (Java/Spring)  │     │    (Python/FastAPI)   │     │  (api-sg.aliexpress)│
│   port: 8083     │     │    port: 8090         │     │  /sync              │
└─────────────────┘     └──────────────────────┘     └─────────────────────┘
```

### 호출 흐름

1. **price-service** (Java) → `ExternalApiClient`가 `WebClient`로 `external-api-service` 호출
2. **external-api-service** (Python/FastAPI) → 서명 생성 후 AliExpress API 호출, 응답 정규화
3. **price-service** → 정규화된 응답을 `SearchResponse` 공통 DTO로 매핑하여 반환

### price-service → external-api-service

```
GET http://localhost:8090/api/v1/aliexpress/search?keyword=이어폰&page_no=1&page_size=10
```

`WebClientConfig`에서 `external-api-service.base-url`을 환경변수로 주입:

```yaml
# application.yml
external-api-service:
  base-url: ${EXTERNAL_API_SERVICE_URL:http://localhost:8090}
```

### external-api-service → AliExpress

```
POST https://api-sg.aliexpress.com/sync
Content-Type: application/x-www-form-urlencoded;charset=utf-8

app_key=...&method=aliexpress.affiliate.product.query&timestamp=...&sign=...
```

---

## 4. 주요 API 및 파라미터

### 4.1 카테고리 조회

| 항목 | 값 |
|---|---|
| **method** | `aliexpress.affiliate.category.get` |
| **엔드포인트** | `POST https://api-sg.aliexpress.com/sync` |
| **필수 파라미터** | `app_key`, `method`, `timestamp`, `sign_method`, `sign`, `format`, `v` |

**응답 경로**:
```
aliexpress_affiliate_category_get_response
  → resp_result
    → result
      → categories
        → category[]    ← 카테고리 배열
```

### 4.2 상품 검색

| 항목 | 값 |
|---|---|
| **method** | `aliexpress.affiliate.product.query` |
| **엔드포인트** | `POST https://api-sg.aliexpress.com/sync` |
| **필수 파라미터** | `app_key`, `method`, `timestamp`, `sign_method`, `sign`, `format`, `v`, `keywords` |

**검색 파라미터**:

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `keywords` | string | ✅ | 검색 키워드 |
| `page_no` | int | ❌ | 페이지 번호 (기본 1) |
| `page_size` | int | ❌ | 페이지당 결과 수 (최대 50) |
| `sort` | string | ❌ | 정렬 기준 (`SALE_PRICE_ASC`, `SALE_PRICE_DESC`, `LAST_VOLUME_ASC`, `LAST_VOLUME_DESC`) |
| `target_currency` | string | ❌ | 타겟 통화 (기본 `KRW`) |
| `target_language` | string | ❌ | 타겟 언어 (기본 `KO`) |
| `ship_to_country` | string | ❌ | 배송 국가 (기본 `KR`) |
| `tracking_id` | string | ❌ | 제휴 트래킹 ID |

> **주의**: `keyword` 파라미터는 API 전송 시 `keywords`로 매핑된다. (external-api-service 라우터는 `keyword`로 받아 내부적으로 `keywords`로 변환)

**응답 경로**:
```
aliexpress_affiliate_product_query_response
  → resp_result
    → result
      → products
        → product[]    ← 상품 배열
```

---

## 5. 응답 데이터 정규화 규칙

### external-api-service 정규화

AliExpress API 원본 응답은 중첩 구조와 다양한 필드명을 사용하므로, external-api-service에서 **정규화된 스키마**로 변환 후 반환한다.

#### 카테고리 정규화 (`_normalize_categories`)

| 원본 필드 | 정규화 필드 | 변환 |
|---|---|---|
| `category_id` | `category_id` | `str()` 변환 |
| `category_name` | `category_name` | 기본값 `""` |
| `parent_category_id` | `parent_category_id` | `str()` 변환, 기본값 `""` |

#### 상품 정규화 (`_normalize_products`)

| 원본 필드 | 정규화 필드 | 변환 |
|---|---|---|
| `product_id` | `product_id` | `str()` 변환 |
| `product_title` | `product_title` | 기본값 `""` |
| `product_detail_url` | `product_detail_url` | 기본값 `""` |
| `product_main_image_url` | `product_main_image_url` | 기본값 `""` |
| `sale_price` | `sale_price` | 원화폐 판매가 (USD 등) |
| `target_sale_price` | `target_sale_price` | KRW 변환 판매가 |
| `target_original_price` | `target_original_price` | KRW 변환 원가 |
| `target_app_sale_price` | `target_app_sale_price` | KRW 변환 앱 판매가 |
| `target_app_original_price` | `target_app_original_price` | KRW 변환 앱 원가 |
| `discount` | `discount` | 할인율 |
| `evaluate_rate` | `evaluate_rate` | 평점 |
| `commission_rate` | `commission_rate` | 커미션 비율 |
| `lastest_volume` | `lastest_volume` | 최근 판매량 |
| `shop_name` | `shop_name` | 상점명 |
| `shop_url` | `shop_url` | 상점 URL |

> 모든 필드는 `.get()`으로 안전하게 접근하며, 누락 시 빈 문자열(`""`) 기본값을 사용한다.

### price-service 매핑 (`SearchResponse`)

price-service는 external-api-service의 정규화된 응답을 받아 **공통 `SearchResponse`** 형식으로 2차 매핑한다:

| 정규화 필드 | SearchResponse 필드 | 매핑 규칙 |
|---|---|---|
| `product_title` | `title` | 직접 매핑 |
| `target_sale_price` | `lprice` | **우선 사용**, 없으면 `sale_price` 대체 |
| `target_original_price` | `hprice` | 직접 매핑 |
| `shop_name` | `mallName` | 직접 매핑 |
| `product_detail_url` | `link` | 직접 매핑 |

**핵심**: `target_sale_price → lprice` 매핑은 KRW 환산가를 최저가로 사용하기 위함이다. `target_sale_price`가 없는 경우(USD 원본 데이터) `sale_price`를 폴백으로 사용한다.

```java
// ExternalApiClient.mapAliExpressItemToSearchResponse()
String lprice = (item.target_sale_price() != null && !item.target_sale_price().isBlank())
        ? item.target_sale_price()
        : item.sale_price();
```

---

## 6. 모킹(Mock) 모드 환경변수 설정

### 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `ALIEXPRESS_MOCK_ENABLED` | `false` | `true` 설정 시 실제 API 호출 없이 고정 데이터 반환 |
| `ALIEXPRESS_APP_KEY` | (없음) | IOP 앱 키 (모킹 모드에서는 불필요) |
| `ALIEXPRESS_APP_SECRET` | (없음) | IOP 앱 시크릿 (모킹 모드에서는 불필요) |
| `ALIEXPRESS_BASE_URL` | `https://api-sg.aliexpress.com/sync` | API 엔드포인트 (오버라이드 가능) |

### 모킹 모드 동작

- 기본값은 `false`이며, **실제 AliExpress API를 호출**한다
- `ALIEXPRESS_MOCK_ENABLED=true` 설정 시 **모든 AliExpress API 호출이 고정 데이터로 대체**됨 (선택적 사용)
- 서명 생성, HTTP 요청 과정 생략
- `page_no`, `page_size` 파라미터에 따라 슬라이싱 적용 (페이징 검증 가능)
- `total`은 항상 전체 모킹 데이터 개수(3개) 반환
- CI/CD 및 로컬 개발 환경에서 API 자격증명 없이도 전체 플로우 테스트 가능 (필요 시에만 활성화)

### 헬스체크에서 모킹 상태 확인

```
GET /health → { "mock_mode": { "aliexpress": true, "naver": false } }
```

---

## 7. 개발 중 주의사항

### 7.1 엔드포인트: `/sync` vs `/rest`

AliExpress IOP는 두 가지 엔드포인트를 제공한다:

| 엔드포인트 | 설명 | 본 프로젝트 사용 |
|---|---|---|
| `https://api-sg.aliexpress.com/sync` | **동기 호출** (응답 대기) | ✅ 사용 중 |
| `https://api-sg.aliexpress.com/rest` | 비동기 호출 (세션 ID로 결과 조회) | ❌ 미사용 |

> **혼동 주의**: `/rest` 엔드포인트는 RESTful 의미가 아니라 **비동기 TOP 스타일** 호출이다. 본 프로젝트는 즉시 응답을 받을 수 있는 `/sync`를 사용한다.

### 7.2 서명 방식의 헷갈리기 쉬운 부분

#### ❌ 자주 하는 실수

1. **`sign`을 서명 대상에 포함**: 서명 생성 시 `sign` 파라미터를 포함하면 안 된다. `sign`을 **제외한** 파라미터만 정렬하여 이어붙여야 한다.

2. **타임스탬프 타임존 오류**: `UTC`나 `KST(UTC+9)`가 아닌 **GMT+8**이어야 한다. AliExpress 서버는 중국 시간(UTC+8) 기준이다.

3. **HEX 대소문자**: 서명 결과는 **대문자 HEX**여야 한다. `.hexdigest().upper()` 필수.

4. **파라미터 정렬 순서**: `key`의 **알파벳 오름차순**으로 정렬한다. `app_key` → `format` → `method` → `timestamp` → `v` 순.

5. **Content-Type**: 요청은 `application/x-www-form-urlencoded;charset=utf-8`로 전송해야 한다. `application/json`이 아니다.

6. **`keyword` vs `keywords`**: 라우터 파라미터는 `keyword`이지만, AliExpress API 전송 시에는 `keywords`로 매핑된다.

#### ✅ 올바른 서명 생성 순서

```python
# 1. 공통 파라미터 + 추가 파라미터 구성 (sign 제외)
params = {
    "app_key": app_key,
    "method": "aliexpress.affiliate.product.query",
    "timestamp": "2026-04-27 14:30:00",  # GMT+8
    "sign_method": "hmac-sha256",
    "format": "json",
    "v": "2.0",
    "keywords": "이어폰",
    "page_no": "1",
    ...
}

# 2. key 오름차순 정렬 후 key+value 이어붙이기
sign_source = "".join(f"{key}{params[key]}" for key in sorted(params))
# 결과: "app_key510000formatjsonkeywords이어폰methodaliexpress.affiliate.product.query..."

# 3. HMAC-SHA256 서명 후 대문자 HEX
sign = hmac.new(app_secret.encode(), sign_source.encode(), hashlib.sha256).hexdigest().upper()

# 4. sign 파라미터 추가
params["sign"] = sign
```

### 7.3 에러 응답 처리

AliExpress API는 다양한 에러 응답 형식을 사용하므로, 여러 위치에서 에러를 탐색해야 한다:

```python
# 최상위 레벨
payload.get("error_code") or payload.get("code")
payload.get("error_message") or payload.get("msg") or payload.get("sub_msg")

# 중첩 error_response
payload.get("error_response", {}).get("code")
payload.get("error_response", {}).get("msg")
```

### 7.4 응답 중첩 구조 파싱

API 응답은 두 가지 형태로 올 수 있어 모두 처리해야 한다:

```python
# 형태 1: 메서드명 키로 래핑
{"aliexpress_affiliate_product_query_response": {"resp_result": {"result": ...}}}

# 형태 2: 직접 resp_result 키
{"resp_result": {"result": ...}}
```

코드에서는 두 형태를 모두 처리하도록 구현되어 있다:

```python
resp_result = data.get("aliexpress_affiliate_product_query_response", data.get("resp_result", {}))
if isinstance(resp_result, dict) and "resp_result" in resp_result:
    resp_result = resp_result["resp_result"]
```