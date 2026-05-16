# 후보 상품 대표 이미지 응답 추가 변경 정리

## 문서 목적

- 후보 상품 30개 조회 응답에 대표 이미지 URL(`imageUrl`)을 포함하도록 변경한 내용을 정리한다.
- 사용자가 후보 상품을 볼 때 아래 정보를 함께 표시할 수 있도록 한 변경이다.
  - 대표 상품 이미지
  - 상품 링크
  - 가격
  - 상품명
- **테스트 파일은 제외**하고 실제 구현 파일만 정리한다.

---

## 1. 변경 목표

기존 후보 상품 응답에는 아래 정보가 주로 포함되어 있었다.

- `productId`
- `title`
- `lprice`
- `mallName`
- `productUrl`
- `currency`
- `platform`
- `searchKeyword`

이번 변경으로 여기에 아래 필드를 추가했다.

- `imageUrl`

즉 프론트에서는 후보 상품 하나를 아래처럼 바로 렌더링할 수 있다.

```json
{
  "productId": "55019284710",
  "title": "AULA F108 스카이블랙, 저소음 바다축",
  "lprice": "82000",
  "mallName": "네이버",
  "productUrl": "https://search.shopping.naver.com/catalog/55019284710",
  "imageUrl": "https://shopping-phinf.pstatic.net/main_5501928/55019284710.20250529104853.jpg",
  "currency": "KRW",
  "platform": "NAVER",
  "searchKeyword": "무선 키보드 black"
}
```

---

## 2. 수정한 파일 정리

## 2-1. price-service

### `price-service/src/main/java/com/pbm/price/dto/response/SearchResponse.java`
**수정**

#### 핵심 변경
- 공통 검색 결과 DTO에 `imageUrl` 필드 추가
- 네이버/알리익스프레스 검색 결과를 공통 형식으로 전달할 때 대표 이미지 URL도 함께 보존하도록 변경

```java
public record SearchResponse(
        String title,
        String lprice,
        String hprice,
        String mallName,
        String productUrl,
        String imageUrl,
        String currency,
        String productId
) {
}
```

---

### `price-service/src/main/java/com/pbm/price/dto/event/ProductCandidateDto.java`
**수정**

#### 핵심 변경
- price-service 내부 후보 DTO에 `imageUrl` 필드 추가
- 이후 Kafka 이벤트로 command-service에 넘길 후보 데이터에도 이미지가 유지되도록 변경

```java
public record ProductCandidateDto(
        String productId,
        String title,
        String lprice,
        String mallName,
        String productUrl,
        String imageUrl,
        String currency,
        String platform,
        String searchKeyword
) {
}
```

---

### `price-service/src/main/java/com/pbm/price/client/ExternalApiClient.java`
**수정**

#### 핵심 변경
- 네이버 검색 결과를 `SearchResponse`로 바꿀 때 `item.image()`를 함께 매핑
- AliExpress 검색 결과를 `SearchResponse`로 바꿀 때 `item.product_main_image_url()`을 함께 매핑

```java
.map(item -> new SearchResponse(
        item.title(),
        item.lprice(),
        item.hprice(),
        item.mallName(),
        item.link(),
        item.image(),
        "KRW",
        item.productId()
))
```

```java
return new SearchResponse(
        item.product_title(),
        lprice,
        item.target_original_price(),
        item.shop_name(),
        item.product_detail_url(),
        item.product_main_image_url(),
        currency,
        item.product_id()
);
```

#### 의미
- 외부 API에서 이미 전달되고 있던 대표 이미지 URL을 더 이상 버리지 않고 내부 공통 DTO에 실어 보낼 수 있게 함

---

### `price-service/src/main/java/com/pbm/price/service/NaverShoppingService.java`
**수정**

#### 핵심 변경
- 네이버 원본 상품 DTO를 공통 `SearchResponse`로 매핑할 때 `image()`를 포함

```java
return items.stream()
        .map(item -> new SearchResponse(
                item.title(),
                item.lprice(),
                item.hprice(),
                item.mallName(),
                item.link(),
                item.image(),
                "KRW",
                item.productId()
        ))
        .toList();
```

---

### `price-service/src/main/java/com/pbm/price/service/AliExpressShoppingService.java`
**수정**

#### 핵심 변경
- AliExpress 상품을 `SearchResponse`로 만들 때 대표 이미지 URL 추가

```java
return new SearchResponse(
        item.product_title(),
        lprice,
        item.target_original_price(),
        item.shop_name(),
        item.product_detail_url(),
        item.product_main_image_url(),
        currency,
        item.product_id()
);
```

---

### `price-service/src/main/java/com/pbm/price/service/NaverProductUrlService.java`
**수정**

#### 핵심 변경
- 네이버 URL fallback 경로에서도 이미지가 유지되도록 `item.image()`를 함께 매핑

```java
return new SearchResponse(
        item.title(),
        item.lprice(),
        item.hprice(),
        item.mallName(),
        item.link(),
        item.image(),
        "KRW",
        item.productId()
);
```

#### 의미
- 일반 검색 경로뿐 아니라 URL 직접 입력 fallback 경로에서도 대표 이미지가 빠지지 않음

---

### `price-service/src/main/java/com/pbm/price/service/AliExpressProductUrlService.java`
**수정**

#### 핵심 변경
- AliExpress URL fallback 경로에서도 `product_main_image_url()`를 함께 매핑

```java
return new SearchResponse(
        product.product_title(),
        lprice,
        hprice,
        product.shop_name(),
        product.product_detail_url(),
        product.product_main_image_url(),
        currency == null ? "KRW" : currency.toUpperCase(Locale.ROOT),
        product.product_id()
);
```

---

### `price-service/src/main/java/com/pbm/price/consumer/PriceTopicConsumer.java`
**수정**

#### 핵심 변경
- `SearchResponse`를 후보 DTO로 변환할 때 `imageUrl`도 같이 복사
- 이 시점에 후보 최대 30개가 command-service로 넘어가므로, 대표 이미지도 이 단계에서 함께 저장되도록 처리

```java
.map(r -> new ProductCandidateDto(
        r.productId() != null && !r.productId().isBlank()
                ? r.productId()
                : (r.productUrl() != null ? r.productUrl() : UUID.randomUUID().toString()),
        r.title(),
        r.lprice(),
        r.mallName(),
        r.productUrl(),
        r.imageUrl(),
        r.currency(),
        platform,
        searchKeyword
))
```

#### 의미
- 후보 30개 저장 구조에 이미지 정보도 같이 포함되므로, page 0/1/2 어디를 조회하든 대표 이미지가 함께 내려감

---

### `price-service/src/main/java/com/pbm/price/consumer/ProductSelectionConsumer.java`
**수정**

#### 핵심 변경
- 사용자가 상품 선택 후 검증/모니터링 단계로 넘어갈 때도 선택 상품의 `imageUrl`이 유지되도록 반영

```java
ProductCandidateDto refreshedCandidate = new ProductCandidateDto(
        snapshot.productId(),
        snapshot.title(),
        snapshot.currentPrice().toPlainString(),
        selectedProduct.mallName(),
        snapshot.productUrl(),
        selectedProduct.imageUrl(),
        snapshot.currency().name(),
        selectedProduct.platform(),
        selectedProduct.searchKeyword()
);
```

#### 의미
- 선택 후 검증 결과 응답에서도 이미지 정보를 계속 사용할 수 있음

---

## 2-2. command-service

### `command-service/src/main/java/com/pbm/command/dto/event/ProductCandidateDto.java`
**수정**

#### 핵심 변경
- command-service가 Kafka로 전달받는 후보 DTO에도 `imageUrl` 필드 추가

```java
public record ProductCandidateDto(
        String productId,
        String title,
        String lprice,
        String mallName,
        String productUrl,
        String imageUrl,
        String currency,
        String platform,
        String searchKeyword
) {
}
```

---

### `command-service/src/main/java/com/pbm/command/dto/response/ProductCandidateResponse.java`
**수정**

#### 핵심 변경
- 최종 API 응답용 후보 DTO에 `imageUrl` 필드 추가
- 프론트가 polling API 응답에서 바로 대표 이미지를 사용할 수 있도록 변경

```java
public record ProductCandidateResponse(
        String productId,
        String title,
        String lprice,
        String mallName,
        String productUrl,
        String imageUrl,
        String currency,
        String platform,
        String searchKeyword
) {
}
```

---

### `command-service/src/main/java/com/pbm/command/consumer/PriceValidationResultConsumer.java`
**수정**

#### 핵심 변경
- price-service에서 보내준 검증 결과 후보를 `ProductCandidateResponse`로 바꿀 때 `imageUrl`도 함께 복사

```java
.map(product -> new ProductCandidateResponse(
        product.productId(),
        product.title(),
        product.lprice(),
        product.mallName(),
        product.productUrl(),
        product.imageUrl(),
        product.currency(),
        product.platform(),
        product.searchKeyword()
))
```

#### 의미
- 단순 후보 목록 조회뿐 아니라, 이후 선택 검증 결과 응답에서도 이미지가 유지됨

---

## 3. 변경 흐름 요약

이번 변경으로 대표 이미지 URL은 아래 흐름을 따라 전달된다.

1. external-api-service 응답에서 상품 이미지 확보
2. `SearchResponse.imageUrl`에 저장
3. `PriceTopicConsumer`에서 `ProductCandidateDto.imageUrl`로 전달
4. Kafka 이벤트를 통해 command-service로 전달
5. command-service 세션 후보 JSON에 저장
6. `GET /api/v1/commands/{commandId}?page=0&size=10` 응답에서 `ProductCandidateResponse.imageUrl`로 반환

즉 후보 30개 페이지네이션 구조 안에서 `imageUrl`도 함께 저장/조회된다.

---

## 4. 실제 사용 관점 정리

이제 프론트에서는 후보 상품 카드 하나를 아래 방식으로 구성할 수 있다.

- 이미지: `imageUrl`
- 상품명: `title`
- 가격: `lprice`
- 링크: `productUrl`

예시:

```tsx
<a href={candidate.productUrl} target="_blank" rel="noreferrer">
  <img src={candidate.imageUrl} alt={candidate.title} />
  <div>{candidate.title}</div>
  <div>{candidate.lprice}원</div>
</a>
```

---

## 5. 참고

- 이번 문서는 **테스트 파일 제외** 기준으로 작성했다.
- URL fallback 경로와 일반 검색 경로 모두에서 대표 이미지가 유지되도록 반영했다.
- 후보 30개 pagination 구조에도 동일하게 적용되므로 `page=0`, `page=1`, `page=2` 모두 이미지 표시 가능하다.
