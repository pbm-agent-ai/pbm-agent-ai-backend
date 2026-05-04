# Price Service DB 영속화 개발일지

> 날짜: 2026-04-27
> 브랜치: `feature/6-price-history-db`
> 담당: opencode (에이전틱 코딩)

---

## 작업 개요

네이버 쇼핑·알리익스프레스 검색 결과를 DB에 저장하고, **공유 수집 모델**(A/B가 같은 상품을 모니터링해도 API는 1번만 호출) 기반의 가격 이력 영속화 아키텍처를 구축했다.

---

## 아키텍처 원칙

| 원칙 | 설명 |
|------|------|
| API 제공처 → 서버 호출 | 네이버, 알리익스프레스 등 API가 있는 플랫폼은 서버에서 직접 호출 |
| 스크래핑 필요처 → Extension | 쿠팡, 네이버 항공권 등은 사용자 PC의 Chrome Extension에서 수집 |
| 공유 수집 | 같은 키워드·플랫폼 조합은 MonitorTarget 1개로 묶어 한 번만 API 호출 |

## ERD 구조 (공유 수집 모델)

```
┌──────────────────┐       ┌──────────────────┐       ┌──────────────────┐
│  monitor_targets │       │     products      │       │   price_history   │
├──────────────────┤       ├──────────────────┤       ├──────────────────┤
│ id (PK)          │◄──┐   │ id (PK)          │◄──┐   │ id (PK)          │
│ source_type      │   │   │ monitor_target_id│   │   │ product_id (FK)  │
│ normalized_keyword│   │   │ source_type      │   │   │ current_price    │
│ fetch_interval   │   └───│ external_product_id│   │   │ original_price   │
│ last_fetched_at  │       │ title            │       │ currency         │
│ next_fetch_at    │       │ product_url      │       │ checked_at       │
│ created_at       │       │ image_url        │       └──────────────────┘
│ updated_at       │       │ mall_name        │
└──────────────────┘       │ last_seen_at     │
                           │ created_at       │
                           │ updated_at       │
                           └──────────────────┘
```

- **monitor_targets**: 플랫폼 + 정규화 키워드 기준 공유 수집 대상 (`source_type + normalized_keyword` unique)
- **products**: 외부 API가 반환한 개별 상품 (`source_type + external_product_id` unique, upsert)
- **price_history**: 조회 시점마다 가격 스냅샷을 누적 저장

---

## 완료한 작업

### 1. ERD 재설계

- `monitor_targets` 중심 공유 수집 구조로 vuerd JSON 파일 수정
- `price_history`를 `condition_id` → `product_id` 기준으로 변경
- `products` 테이블 추가, `conditions`에 `monitor_target_id` 추가
- 고아 컬럼·잘못된 관계선·오타 정리 완료

### 2. DB/JPA 활성화

- `application.yml`에서 `DataSourceAutoConfiguration`, `HibernateJpaAutoConfiguration` 제외 설정 제거
- PostgreSQL datasource/JPA 설정 추가 (`ddl-auto: update`)
- `docker-compose.yml`의 price-service에 `PRICE_DB_URL` 환경변수 추가
- `init-db.sql`에 `CREATE DATABASE price_db;` 이미 포함

### 3. 엔티티 구현

| 클래스 | 설명 |
|--------|------|
| `SourceType.java` | 플랫폼 구분 enum (`NAVER`, `ALIEXPRESS`) |
| `CurrencyType.java` | 통화 enum (`KRW`, `USD`) |
| `MonitorTarget.java` | 공유 수집 대상 (`source_type + normalized_keyword` unique) |
| `Product.java` | 개별 상품 (`source_type + external_product_id` unique, upsert) |
| `PriceHistory.java` | 가격 스냅샷 누적 (product_id 기준) |

### 4. 리포지토리 구현

| 인터페이스 | 주요 메서드 |
|------------|------------|
| `MonitorTargetRepository` | `findBySourceTypeAndNormalizedKeyword` |
| `ProductRepository` | `findBySourceTypeAndExternalProductId` |
| `PriceHistoryRepository` | 기본 CRUD |

### 5. 저장 서비스 구현 (`ProductPersistenceService`)

- 네이버 검색 결과 → `saveNaverSearchResults()`
- 알리 검색 결과 → `saveAliExpressSearchResults()`
- 공통 흐름: MonitorTarget 찾기/생성 → Product upsert → PriceHistory 누적
- 키워드 정규화 (공백 제거, 소문자 변환)
- 가격 파싱 실패 시 PriceHistory 저장 생략 (경고 로그)

### 6. 검색 서비스 연결

- `NaverShoppingService`: 원본 `NaverShoppingItem` 수신 → `ProductPersistenceService.saveNaverSearchResults()` 호출 → `SearchResponse` 변환 반환
- `AliExpressShoppingService`: 원본 `AliExpressShoppingItem` 수신 → `ProductPersistenceService.saveAliExpressSearchResults()` 호출 → `SearchResponse` 변환 반환

### 7. ExternalApiClient 확장

- `searchNaverProductItems()`: 원본 DTO 목록 반환 (DB 저장용 필드 포함)
- `searchAliExpressProductItems()`: 원본 DTO 목록 반환 (DB 저장용 필드 포함)
- 기존 `searchNaverProducts()`, `searchAliExpressProducts()`는 `SearchResponse` 반환 (하위 호환 유지)

### 8. 테스트 구현

| 테스트 클래스 | 설명 |
|---------------|------|
| `ProductPersistenceServiceTest` | DataJpaTest + H2, 저장·upsert·PriceHistory 누적 검증 |
| `NaverShoppingServiceTest` | Mock 기반 네이버 검색 + 저장 호출 검증 |
| `AliExpressShoppingServiceTest` | Mock 기반 알리 검색 + 저장 호출 검증 |
| `ExternalApiClientTest` | WebClient Mock 설정 + 원본 DTO 반환 검증 |

### 9. 빌드/테스트 통과

```
./gradlew :price-service:test ✅
./gradlew :price-service:bootJar ✅
```

---

## 발견한 이슈 & 해결

| 이슈 | 해결 |
|------|------|
| `columnDefinition = "TIMESTAMPTZ"` H2 미인식 | `columnDefinition` 속성 제거로 PostgreSQL + H2 모두 호환 |
| DataSource/JPA exclude로 DB 비활성화 | application.yml에서 exclusion 제거, datasource/jpa 설정 추가 |
| `SearchResponse`에 productId, image 필드 없음 | 원본 DTO 반환 메서드 별도 추가 |

---

## 파일 변경 목록

### 신규 파일
```
price-service/src/main/java/com/pbm/price/domain/
  ├── SourceType.java
  ├── CurrencyType.java
  ├── MonitorTarget.java
  ├── Product.java
  └── PriceHistory.java

price-service/src/main/java/com/pbm/price/repository/
  ├── MonitorTargetRepository.java
  ├── ProductRepository.java
  └── PriceHistoryRepository.java

price-service/src/main/java/com/pbm/price/service/ProductPersistenceService.java

price-service/src/test/resources/application.yml

price-service/src/test/java/com/pbm/price/service/ProductPersistenceServiceTest.java
```

### 수정 파일
```
price-service/src/main/resources/application.yml          # DB/JPA 설정 활성화
price-service/src/main/java/com/pbm/price/service/NaverShoppingService.java      # 저장 서비스 연결
price-service/src/main/java/com/pbm/price/service/AliExpressShoppingService.java # 저장 서비스 연결
price-service/src/main/java/com/pbm/price/client/ExternalApiClient.java         # 원본 DTO 메서드 추가

price-service/src/test/java/com/pbm/price/service/NaverShoppingServiceTest.java
price-service/src/test/java/com/pbm/price/service/AliExpressShoppingServiceTest.java
price-service/src/test/java/com/pbm/price/client/ExternalApiClientTest.java

docker-compose.yml      # PRICE_DB_URL 추가
.env.example            # PRICE_DB_URL 주석 추가
```

### ERD
```
PBM_Agent_AI.vuerd.json  # monitor_targets, products, price_history 구조 반영
```

---

## 다음 단계 (미완료)

| 우선순위 | 항목 | 설명 |
|----------|------|------|
| 🔴 High | 백그라운드 스케줄러 | 1분 주기 폴링 + 10분 수집 주기 공유 수집 `@Scheduled` 구현 |
| 🔴 High | 로컬 PostgreSQL 실행 검증 | Docker Compose로 실제 DB 저장 동작 확인 |
| 🟡 Medium | Flyway 마이그레이션 | `ddl-auto: update` → 스키마 버전 관리 |
| 🟡 Medium | 카테고리 조회 API 연결 | external-api-service의 카테고리 API를 price-service에서 활용 |
| 🟡 Medium | 사용자별 모니터링 등록 | 조건(conditions) 테이블 + 사용자별 알림 threshold |
| 🟢 Low | 쿠팡/네이버 항공권 | Chrome Extension 클라이언트 수집 API |