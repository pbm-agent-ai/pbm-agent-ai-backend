# 백엔드 개념 정리 - Price Service DB 영속화 편

> 날짜: 2026-04-27
> 오늘 작업하면서 등장한 핵심 개념을 입문자 관점에서 정리

---

## 1. Upsert 패턴 (find-or-create)

### 개념

**Upsert** = **Up**date + In**sert**. 이미 있으면 갱신하고, 없으면 새로 만드는 패턴.

### 왜 필요한가

네이버에서 "갤럭시s24"를 검색할 때마다 같은 상품이 계속 나타난다. 매번 INSERT하면 동일 상품이 수십 개씩 쌓인다. 그래서:

1. `source_type + external_product_id`로 기존 상품을 찾고
2. 있으면 → 메타데이터(제목, URL, 이미지 등) 갱신
3. 없으면 → 새 상품 INSERT

### 코드에서의 구현

```java
Product product = productRepository
    .findBySourceTypeAndExternalProductId(sourceType, externalProductId)
    .orElseGet(() -> Product.create(...));  // 없으면 새로 생성

product.updateSnapshot(...);  // 있으면 갱신
productRepository.save(product);  // JPA가 알아서 INSERT 또는 UPDATE 판별
```

### 핵심 포인트

- DB에 **unique 제약조건**(`uk_products_source_external_id`)이 걸려 있어야 같은 상품이 중복 저장되지 않는다
- JPA의 `save()`는 엔티티에 **PK(id)가 없으면 INSERT, 있으면 UPDATE**를 실행한다
- `findBy...`로 먼저 조회하는 이유: 기존 엔티티를 그대로 갱신하면 JPA가 더티체킹(dirty checking)으로 변경된 필드만 UPDATE한다

---

## 2. 공유 수집 모델 (Shared Collection Model)

### 개념

A유저가 "갤럭시s24"를 모니터링하고, B유저도 "갤럭시s24"를 모니터링한다면, **API 호출은 1번만** 하고 결과를 공유한다.

### 구조

```
MonitorTarget (공유)        ← "갤럭시s24" + NAVER 조합은 1개뿐
   ├── Product A             ← 네이버에 나타난 개별 상품들
   │    └── PriceHistory     ← 가격 스냅샷 누적
   ├── Product B
   │    └── PriceHistory
   └── Product C
        └── PriceHistory
```

### 왜 이렇게 하는가

| 방식 | API 호출 | 문제 |
|------|---------|------|
| 각 유저마다 개별 수집 | 100명 = 100번 호출 | API 한도 초과, 비용 증가 |
| 공유 수집 | 100명 = 1번 호출 | API 효율 최적화 |

### 키워드 정규화

"아이폰 15"와 "아이폰15"를 같은 수집 대상으로 묶기 위해 공백 제거 + 소문자 변환:

```java
private String normalizeKeyword(String keyword) {
    return keyword.trim()
            .replaceAll("\\s+", "")    // 공백 제거
            .toLowerCase(Locale.ROOT);  // 소문자 변환
}
```

---

## 3. JPA 엔티티 설계 핵심

### @ManyToOne과 FetchType.LAZY

```java
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "monitor_target_id", nullable = false)
private MonitorTarget monitorTarget;
```

- **@ManyToOne**: N개의 Product가 1개의 MonitorTarget에 속한다 (DB에서는 외래키)
- **FetchType.LAZY**: Product를 조회할 때 MonitorTarget을 **즉시 조인하지 않고** 필요할 때만 가져온다
  - EAGER(즉시 로딩)면 Product 조회할 때마다 MonitorTarget도 같이 SELECT → 성능 저하
  - LAZY(지연 로딩)면 MonitorTarget에 접근하는 순간에 SELECT → 효율적
- **optional = false**: MonitorTarget 없는 Product는 존재할 수 없다는 의미 (DB NOT NULL 제약)

### Unique 제약조건

```java
@Table(
    name = "products",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_products_source_external_id",
        columnNames = {"source_type", "external_product_id"}
    )
)
```

- 같은 플랫폼에서 같은 외부 ID의 상품은 1개만 존재해야 한다
- DB 레벨에서 중복을 방지하는 안전장치
- 이름(`uk_products_source_external_id`)은 의미를 알아보기 쉽게 지정

### @PrePersist / @PreUpdate

```java
@PrePersist
void prePersist() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
}

@PreUpdate
void preUpdate() {
    this.updatedAt = Instant.now();
}
```

- **@PrePersist**: JPA가 INSERT를 실행하기 **직전**에 자동 호출 → createdAt, updatedAt 초기화
- **@PreUpdate**: JPA가 UPDATE를 실행하기 **직전**에 자동 호출 → updatedAt 갱신
- 개발자가 매번 `entity.setCreatedAt(Instant.now())`를 안 해도 된다

---

## 4. DTO 레이어 분리 (원본 DTO vs 공개 DTO)

### 개념

외부 API가 반환하는 **모든 필드**를 담은 원본 DTO와, 프론트에 **필요한 필드만** 담은 공개 DTO를 분리한다.

### 구조

```
NaverShoppingItem (원본 DTO)     →  DB 저장용 (productId, image 등 포함)
        ↓ 맵핑
SearchResponse (공개 DTO)       →  API 응답용 (title, lprice, mallName, link만)
```

### 왜 분리하는가

| 원본 DTO | 공개 DTO |
|----------|---------|
| DB 저장에 필요한 모든 필드 | 프론트에 보여줄 최소 필드 |
| 외부 API 구조에 종속 | 프론트 요구사항에 맞춰 자유롭게 변경 가능 |
| NaverShoppingItem, AliExpressShoppingItem | SearchResponse (공통 포맷) |

### 실제 코드 흐름

```java
// 서비스 내부
List<NaverShoppingItem> items = externalApiClient.searchNaverProductItems(keyword, display);  // 원본 DTO
productPersistenceService.saveNaverSearchResults(keyword, items);  // DB 저장은 원본 DTO로

return items.stream()
    .map(item -> new SearchResponse(item.title(), item.lprice(), ...))  // 프론트엔드엔 공개 DTO만
    .toList();
```

---

## 5. WebClient를 통한 MSA 간 통신

### 개념

price-service가 네이버 API를 직접 호출하지 않고, **external-api-service(FastAPI)** 를 거쳐서 호출한다.

### 왜 중간 서비스를 두는가

```
[price-service] → [external-api-service] → [네이버 API]
[price-service] → [external-api-service] → [알리 API]
```

| 이점 | 설명 |
|------|------|
| API 키 관리 분리 | 네이버/알리 API 키는 external-api-service에만 저장 |
| 모킹 | 테스트 시 external-api-service에서 모킹 응답 가능 |
| 재시도/캐싱 | external-api-service에서 재시도, 캐싱 담당 가능 |
| 언어 혼합 | Spring Boot + FastAPI 혼합 아키텍처 가능 |

### WebClient vs RestTemplate

- **RestTemplate**: 동기(블로킹), Spring 공식 지원 중단 예정
- **WebClient**: 비동기(논블로킹), Spring WebFlux 제공, 현재 권장

```java
NaverSearchResponse response = externalApiWebClient.get()
    .uri(uriBuilder -> uriBuilder
        .path("/api/v1/naver/search")
        .queryParam("keyword", keyword)
        .queryParam("display", display)
        .build())
    .retrieve()
    .bodyToMono(NaverSearchResponse.class)
    .block();  // 현재는 블로킹으로 사용, 추후 비동기 전환 가능
```

---

## 6. @Scheduled 백그라운드 스케줄러

### 개념

Spring Boot 애플리케이션 내부에서 **주기적으로 자동 실행**되는 작업을 만드는 방법.

### 구동 원리

```
애플리케이션 시작
  → @EnableScheduling 감지
    → Spring이 스레드풀(TaskScheduler) 생성
      → @Scheduled가 붙은 메서드를 주기적으로 스레드풀에서 실행
```

### fixedDelay vs cron

| 방식 | 동작 | 장단점 |
|------|------|--------|
| `fixedDelay = 60000` | 이전 실행 **종료 후** 1분 대기 후 재실행 | API 응답 지연되어도 안전 |
| `cron = "0 */10 * * * *"` | 매 10분마다 정각에 실행 | 이전 실행이 안 끝나도 재실행 시도 → 위험 |

> **참고**: `fixedDelay`는 스케줄러의 DB 폴링 주기(1분)이며, 개별 모니터링 대상의 수집 주기(기본 10분)는 `MonitorTarget.fetchInterval`로 별도 관리된다.

### 우리 구조에서의 역할

```
@Scheduled(fixedDelay = 60000)  // 1분마다 DB 폴링
collectDueTargets()
  → MonitorTargetRepository.findByNextFetchAtBefore(now)  // 수집 예정 대상 조회 (10분 주기)
  → 네이버/알리 API 호출 (기존 서비스 재사용)
  → 결과 자동 저장 (ProductPersistenceService)
```

- 모니터링 대상이 없으면 API 호출 안 함 (조회만 하고 리턴)
- 한 대상 실패해도 전체 배치는 계속 진행 (try-catch 격리)

---

## 7. Repository 쿼리 메서드 네이밍 규칙

### 개념

Spring Data JPA는 **메서드 이름**만으로 자동으로 SQL을 생성해준다.

### 규칙

```
findBy + 필드명 + 조건
```

| 메서드 이름 | 생성되는 SQL 조건 |
|-------------|------------------|
| `findBySourceTypeAndNormalizedKeyword` | `WHERE source_type = ? AND normalized_keyword = ?` |
| `findBySourceTypeAndExternalProductId` | `WHERE source_type = ? AND external_product_id = ?` |
| `findByNextFetchAtBefore` | `WHERE next_fetch_at < ?` |

### And / Or / Before / After / Like 등

- `And`: 두 조건을 모두 만족
- `Before`: 지정한 값보다 작은 경우 (시간 비교에 자주 사용)
- `After`: 지정한 값보다 큰 경우

이 규칙만 알면 직접 SQL이나 `@Query`를 안 써도 80%의 조회는 해결된다.

---

## 한 줄 요약

| 개념 | 한 줄 |
|------|-------|
| Upsert | 있으면 갱신, 없으면 삽입 (중복 방지) |
| 공유 수집 | 같은 키워드는 API 1번만 호출 |
| FetchType.LAZY | 연관 엔티티를 필요할 때만 조회 (성능 최적화) |
| DTO 분리 | DB 저장용(원본)과 API 응답용(공개)을 구분 |
| WebClient | MSA에서 다른 서비스를 호출하는 비동기 HTTP 클라이언트 |
| @Scheduled | Spring이 주기적으로 자동 실행해주는 백그라운드 작업 |
| Repository 네이밍 | 메서드 이름으로 SQL을 자동 생성하는 Spring Data JPA 규칙 |