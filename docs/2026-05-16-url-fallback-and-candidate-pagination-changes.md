# URL 기반 검색 및 후보 30개 페이지네이션 변경 정리

## 문서 목적

- URL 직접 입력 기반 상품 검색 fallback 변경사항 정리
- 후보 상품 30개 저장 + `page`/`size` 기반 조회 변경사항 정리
- **테스트 파일은 제외**하고 실제 구현 파일만 정리

---

## 1. command-service

### `command-service/src/main/java/com/pbm/command/controller/CommandSessionController.java`
**수정**

#### URL 기반 검색 관련
- `POST /api/v1/commands/{commandId}/product-links` 엔드포인트 추가
- 사용자가 직접 입력한 상품 URL 목록을 받아 URL fallback 검색을 시작하도록 연결

```java
@PostMapping("/{commandId}/product-links")
public ResponseEntity<ApiResponse<CommandSessionResponse>> submitProductUrls(
        @PathVariable String commandId,
        @RequestBody ProductUrlSubmitRequest request
) {
    CommandSessionResponse response = commandExecutionService.handleProductUrlSubmission(commandId, request);
    return ResponseEntity.ok(ApiResponse.success(response));
}
```

#### 후보 30개 페이지네이션 관련
- `GET /api/v1/commands/{commandId}` 조회 API에 `page`, `size` 쿼리 파라미터 추가
- 기본값은 `page=0`, `size=10`

```java
@GetMapping("/{commandId}")
public ResponseEntity<ApiResponse<CommandSessionResponse>> getCommandSession(
        @PathVariable String commandId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size
) {
    CommandSessionResponse response = commandSessionService.getByCommandId(commandId, page, size);
    return ResponseEntity.ok(ApiResponse.success(response));
}
```

---

### `command-service/src/main/java/com/pbm/command/dto/request/ProductUrlSubmitRequest.java`
**추가**

#### URL 기반 검색 관련
- URL 직접 제출용 요청 DTO 추가
- URL 공백 제거, 빈 값 제거, 중복 제거
- 최소 1개, 최대 5개까지 허용

```java
public record ProductUrlSubmitRequest(
        List<String> productUrls
) {
    public ProductUrlSubmitRequest {
        if (productUrls == null || productUrls.isEmpty()) {
            throw new IllegalArgumentException("productUrls는 최소 1개 이상이어야 합니다.");
        }

        productUrls = productUrls.stream()
                .map(url -> url == null ? null : url.trim())
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .toList();

        if (productUrls.isEmpty()) {
            throw new IllegalArgumentException("productUrls는 최소 1개 이상이어야 합니다.");
        }

        if (productUrls.size() > 5) {
            throw new IllegalArgumentException("productUrls는 최대 5개까지 입력할 수 있습니다.");
        }
    }
}
```

---

### `command-service/src/main/java/com/pbm/command/service/CommandExecutionService.java`
**수정**

#### URL 기반 검색 관련
- `handleProductUrlSubmission()` 메서드 추가
- 현재 세션이 `PRODUCT_SELECTION_REQUIRED` 상태인지 검증
- 기존 후보 목록에서 `platform`, `searchKeyword`를 추출
- 세션을 `SEARCHING`으로 바꾸고 `price-topic`으로 URL 기반 재검색 요청 발행

```java
@Transactional
public CommandSessionResponse handleProductUrlSubmission(
        String commandId,
        ProductUrlSubmitRequest request
) {
    CommandSession session = commandSessionService.getSessionEntityByCommandId(commandId);

    if (session.getStatus() != CommandSessionStatus.PRODUCT_SELECTION_REQUIRED) {
        throw new InvalidProductSelectionException(...);
    }

    List<ProductCandidateDto> candidates = parseCandidatesFromSession(session);
    String platform = resolvePlatform(candidates);
    String searchKeyword = resolveSearchKeyword(session, candidates);

    commandSessionService.updateToSearching(commandId);

    priceRequestService.publishProductUrlRequest(
            session.getUserId(),
            session.getCommandIntent(),
            session.getTargetPrice(),
            commandId,
            request.productUrls(),
            searchKeyword,
            platform
    );

    return commandSessionService.getByCommandId(commandId);
}
```

#### 핵심 수정 포인트
- `updateToSearching()` 호출 전에 `platform`, `searchKeyword`를 먼저 확정하도록 변경
- 이유: `toSearching()` 과정에서 `candidatesJson`이 비워질 수 있어서, 그 뒤에 후보 정보를 읽으면 platform/searchKeyword가 꼬일 수 있음

```java
String platform = resolvePlatform(candidates);
String searchKeyword = resolveSearchKeyword(session, candidates);
commandSessionService.updateToSearching(commandId);
```

---

### `command-service/src/main/java/com/pbm/command/service/PriceRequestService.java`
**수정**

#### URL 기반 검색 관련
- `publishProductUrlRequest()` 메서드 추가
- 기존 명령 세션의 `commandId`, `intent`, `targetPrice`를 유지한 채 URL 목록 기반 price-topic 이벤트 발행
- `searchKeyword`를 원본 자연어 명령이 아니라 **1차 검색 때 실제 사용했던 키워드**로 전달

```java
public PriceCheckResponse publishProductUrlRequest(
        Long userId,
        String intent,
        Integer targetPrice,
        String commandId,
        List<String> productUrls,
        String searchKeyword,
        String platform
) {
    PriceRequestEvent event = new PriceRequestEvent(
            eventId,
            "PRICE_CHECK_REQUEST",
            Instant.now(),
            "command-service",
            new PriceRequestEventPayload(
                    userId,
                    searchKeyword,
                    targetPrice,
                    platform,
                    "KRW",
                    commandId,
                    intent,
                    null,
                    null,
                    searchKeyword,
                    productUrls
            )
    );

    kafkaTemplate.send(priceTopic, String.valueOf(userId), event);
    ...
}
```

---

### `command-service/src/main/java/com/pbm/command/dto/event/PriceRequestEventPayload.java`
**수정**

#### URL 기반 검색 관련
- URL fallback용 `productUrls` 필드 추가
- 1차 검색 키워드를 보존하기 위한 `searchKeyword` 필드와 함께 전달

```java
public record PriceRequestEventPayload(
        Long userId,
        String keyword,
        Integer targetPrice,
        String platform,
        String currency,
        String commandId,
        String intent,
        ParsedCommandSnapshot parsedCommandSnapshot,
        String productUrl,
        String searchKeyword,
        List<String> productUrls
) {
    public PriceRequestEventPayload(... 기존 10개 필드 ...) {
        this(..., null);
    }
}
```

---

### `command-service/src/main/java/com/pbm/command/service/CommandSessionService.java`
**수정**

#### 후보 30개 페이지네이션 관련
- `getByCommandId(commandId, page, size)` 오버로드 추가
- 기존 `getByCommandId(commandId)`는 `(0, 10)` 기본값으로 위임

```java
@Transactional(readOnly = true)
public CommandSessionResponse getByCommandId(String commandId) {
    return getByCommandId(commandId, 0, 10);
}

@Transactional(readOnly = true)
public CommandSessionResponse getByCommandId(String commandId, int page, int size) {
    CommandSession session = commandSessionRepository.findByCommandId(commandId)
            .orElseThrow(() -> new CommandSessionNotFoundException(...));

    return CommandSessionResponse.from(session, page, size);
}
```

---

### `command-service/src/main/java/com/pbm/command/dto/response/CommandSessionResponse.java`
**수정**

#### 후보 30개 페이지네이션 관련
- `from(session, page, size)` 오버로드 추가
- 세션에 저장된 전체 후보 목록을 page/size 기준으로 slice해서 응답하도록 변경

```java
public static CommandSessionResponse from(CommandSession session, int page, int size) {
    List<ProductCandidateResponse> allCandidates = parseCandidates(session.getCandidatesJson());
    List<ProductCandidateResponse> pagedCandidates = sliceCandidates(allCandidates, page, size);

    return new CommandSessionResponse(
            ...,
            pagedCandidates,
            ...
    );
}
```

- 안전한 slice 로직 추가

```java
private static List<ProductCandidateResponse> sliceCandidates(List<ProductCandidateResponse> candidates, int page, int size) {
    if (candidates == null || candidates.isEmpty()) {
        return List.of();
    }

    int safePage = Math.max(page, 0);
    int safeSize = size <= 0 ? 10 : size;
    int fromIndex = safePage * safeSize;
    if (fromIndex >= candidates.size()) {
        return List.of();
    }

    int toIndex = Math.min(fromIndex + safeSize, candidates.size());
    return candidates.subList(fromIndex, toIndex);
}
```

---

## 2. price-service

### `price-service/src/main/java/com/pbm/price/consumer/PriceTopicConsumer.java`
**수정**

이 파일은 이번 작업에서 **URL fallback**과 **후보 30개 저장** 두 기능 모두의 핵심 처리 지점이다.

#### URL 기반 검색 관련
- `productUrls`가 있는 경우 일반 검색 대신 URL 전용 서비스로 분기
- NAVER는 `NaverProductUrlService`, ALIEXPRESS는 `AliExpressProductUrlService` 사용

```java
if (hasDirectNaverUrls(event)) {
    return naverProductUrlService.resolveProductsByUrls(
            event.payload().searchKeyword(),
            event.payload().productUrls()
    );
}

if (hasDirectAliExpressUrls(event)) {
    return aliExpressProductUrlService.resolveProductsByUrls(
            event.payload().productUrls(),
            "KRW",
            "KO",
            "KR"
    );
}
```

#### 후보 30개 페이지네이션 관련
- NAVER 일반 검색 호출 개수를 `10 -> 30`으로 변경

```java
case NAVER:
    return naverShoppingService.searchProducts(keyword, 30);
```

- 후보 DTO 저장 상한도 `30개`로 변경

```java
return results.stream()
        .limit(30)
        .map(r -> new ProductCandidateDto(...))
        .toList();
```

#### 핵심 수정 포인트
- command-service에서 page/size slice를 하더라도, price-service가 실제로 10개만 가져오면 page 1/page 2는 비게 됨
- 그래서 **NAVER 검색 요청 개수 자체를 30으로 늘린 것**이 실제 동작상 중요함

---

### `price-service/src/main/java/com/pbm/price/service/NaverProductUrlService.java`
**추가**

#### URL 기반 검색 관련
- 네이버 URL fallback 전용 서비스 추가
- 네이버 catalog URL에서 `productId`를 추출
- `searchKeyword`로 네이버 검색 결과를 다시 가져온 뒤, 같은 `productId`를 가진 상품만 후보로 복원
- 최대 200건(100 + 100)까지 조회 가능하게 구성

```java
private static final Pattern CATALOG_PRODUCT_ID_PATTERN = Pattern.compile("/catalog/(\\d+)");
```

```java
public List<SearchResponse> resolveProductsByUrls(String searchKeyword, List<String> productUrls) {
    if (searchKeyword == null || searchKeyword.isBlank() || productUrls == null || productUrls.isEmpty()) {
        return List.of();
    }

    List<NaverShoppingItem> items = new ArrayList<>();
    items.addAll(externalApiClient.searchNaverProductItems(searchKeyword, 100, 1));
    if (items.size() >= 100) {
        items.addAll(externalApiClient.searchNaverProductItems(searchKeyword, 100, 101));
    }
    ...
}
```

```java
private String extractProductId(String productUrl) {
    Matcher matcher = CATALOG_PRODUCT_ID_PATTERN.matcher(productUrl);
    if (matcher.find()) {
        return matcher.group(1);
    }
    return null;
}
```

#### 핵심 수정 포인트
- 원본 자연어 명령이 아니라 **기존 1차 후보의 `searchKeyword`를 재사용**해야 안정적으로 재매칭 가능
- 단순 링크 문자열 비교가 아니라 **catalog productId 기준 매칭**으로 처리

---

### `price-service/src/main/java/com/pbm/price/service/AliExpressProductUrlService.java`
**추가**

#### URL 기반 검색 관련
- AliExpress URL fallback 전용 서비스 추가
- URL에서 productId를 추출한 뒤 official detail API로 단건 조회
- detail API에서 실제로 복원 가능한 상품만 후보로 반환

```java
private static final Pattern PRODUCT_ID_PATTERN = Pattern.compile("(?:/item/|productId=)(\\d{10,})");
```

```java
public List<SearchResponse> resolveProductsByUrls(
        List<String> productUrls,
        String targetCurrency,
        String targetLanguage,
        String shipToCountry
) {
    for (String productUrl : productUrls) {
        String productId = extractProductId(productUrl);
        if (productId == null) {
            continue;
        }

        AliexpressProductDetailResponse response = externalApiClient.getAliExpressProductDetail(
                productId,
                targetCurrency,
                targetLanguage,
                shipToCountry
        );
        AliExpressShoppingItem product = response.product();
        if (product == null) {
            continue;
        }

        resolvedProducts.put(product.product_id(), mapToSearchResponse(product, targetCurrency));
    }
    ...
}
```

#### 핵심 수정 포인트
- 웹 페이지 URL이 있다고 해서 무조건 후보가 되는 구조가 아님
- **공식 detail API에서 살아 있는 상품만 후보로 인정**

---

### `price-service/src/main/java/com/pbm/price/dto/event/PriceRequestEventPayload.java`
**수정**

#### URL 기반 검색 관련
- command-service에서 보낸 `productUrls`, `searchKeyword`를 받을 수 있도록 payload 구조 확장

```java
public record PriceRequestEventPayload(
        Long userId,
        String keyword,
        Integer targetPrice,
        String platform,
        String currency,
        String commandId,
        String intent,
        ParsedCommandSnapshot parsedCommandSnapshot,
        String productUrl,
        String searchKeyword,
        List<String> productUrls
) {
    public PriceRequestEventPayload(... 기존 10개 필드 ...) {
        this(..., null);
    }
}
```

---

## 3. 변경 파일 요약

### URL 기반 검색 핵심 파일
- `command-service/src/main/java/com/pbm/command/controller/CommandSessionController.java`
- `command-service/src/main/java/com/pbm/command/dto/request/ProductUrlSubmitRequest.java`
- `command-service/src/main/java/com/pbm/command/service/CommandExecutionService.java`
- `command-service/src/main/java/com/pbm/command/service/PriceRequestService.java`
- `command-service/src/main/java/com/pbm/command/dto/event/PriceRequestEventPayload.java`
- `price-service/src/main/java/com/pbm/price/consumer/PriceTopicConsumer.java`
- `price-service/src/main/java/com/pbm/price/service/NaverProductUrlService.java`
- `price-service/src/main/java/com/pbm/price/service/AliExpressProductUrlService.java`
- `price-service/src/main/java/com/pbm/price/dto/event/PriceRequestEventPayload.java`

### 후보 30개 저장 + 페이지네이션 핵심 파일
- `command-service/src/main/java/com/pbm/command/controller/CommandSessionController.java`
- `command-service/src/main/java/com/pbm/command/service/CommandSessionService.java`
- `command-service/src/main/java/com/pbm/command/dto/response/CommandSessionResponse.java`
- `price-service/src/main/java/com/pbm/price/consumer/PriceTopicConsumer.java`

---

## 4. 참고 사항

- 이 문서는 **테스트 파일을 제외한 구현 파일만** 정리했다.
- 이번 작업의 핵심은 아래 두 가지다.
  1. 1차 검색 결과가 마음에 들지 않을 때 **URL 직접 입력 fallback** 가능하게 만들기
  2. 후보를 **최대 30개 저장**하고, `GET /api/v1/commands/{commandId}?page=0&size=10` 형식으로 10개씩 나눠 조회 가능하게 만들기
