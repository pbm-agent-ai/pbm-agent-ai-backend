# 2026-04-25 개발 일지
## Week 3 준비 - external-api-service 연동 시작

---

## 1. 작업 목적

Week 3 로드맵에 맞춰 외부 API 호출 구조를 정리했다.

기존에는 `price-service`가 네이버 쇼핑 API를 직접 호출하고 있었는데,
이를 `external-api-service(FastAPI)`가 전담하도록 구조를 변경하는 것이 목표였다.

### 변경 목표

- `price-service`의 외부 API 직접 호출 제거
- `external-api-service`가 네이버 쇼핑 API 호출 전담
- `price-service`는 `WebClient`로 `external-api-service`를 호출하도록 변경
- 이후 환율 API, 물가 API, Claude/Gemini API도 같은 방식으로 확장 가능한 구조 마련

---

## 2. 작업 전 상태

### 기존 구조

- `price-service`
  - `NaverShoppingController`
  - `NaverShoppingService`
  - `RestTemplate`로 네이버 Open API 직접 호출
  - `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`를 `price-service`가 직접 사용

### 문제점

- 로드맵에서 정한 `external-api-service 전담 구조`와 맞지 않음
- 외부 API 인증/호출/응답 파싱 로직이 `price-service`에 섞여 있었음
- 외부 API 종류가 늘어나면 `price-service`가 과도하게 비대해질 가능성이 있었음

---

## 3. 이번 작업 내용

### 3-1. external-api-service 신규 생성

FastAPI 기반 `external-api-service`를 새로 생성했다.

### 생성한 주요 파일

- `external-api-service/app/main.py`
- `external-api-service/app/routers/naver.py`
- `external-api-service/app/services/naver_service.py`
- `external-api-service/app/schemas/naver.py`
- `external-api-service/requirements.txt`
- `external-api-service/.env.example`
- `external-api-service/Dockerfile`
- `external-api-service/tests/test_naver_router.py`

### 구현한 기능

#### 1) 헬스체크 엔드포인트

- `GET /health`

#### 2) 네이버 쇼핑 검색 엔드포인트

- `GET /api/v1/naver/search`
- Query Parameter
  - `keyword`
  - `display`
  - `start`

#### 3) 응답 정규화

네이버 Open API 원본 응답을 내부 표준 형태로 변환하도록 구현했다.

응답 구조는 아래와 같다.

```json
{
  "total": 0,
  "start": 1,
  "display": 10,
  "items": [
    {
      "title": "",
      "lprice": "",
      "hprice": "",
      "mallName": "",
      "link": "",
      "productId": "",
      "image": "",
      "maker": "",
      "brand": "",
      "category1": "",
      "category2": "",
      "category3": "",
      "category4": ""
    }
  ]
}
```

---

### 3-2. price-service 연동 구조 변경

`price-service`가 직접 네이버 API를 호출하지 않고,
`external-api-service`를 호출하도록 변경했다.

### 변경한 주요 파일

- `price-service/build.gradle`
- `price-service/src/main/resources/application.yml`
- `price-service/src/main/java/com/pbm/price/config/WebClientConfig.java`
- `price-service/src/main/java/com/pbm/price/client/ExternalApiClient.java`
- `price-service/src/main/java/com/pbm/price/service/NaverShoppingService.java`
- `price-service/src/main/java/com/pbm/price/dto/response/NaverSearchResponse.java`
- `price-service/src/main/java/com/pbm/price/dto/response/NaverShoppingItem.java`

### 변경 내용

#### 1) WebClient 도입

- `spring-boot-starter-webflux` 추가
- `external-api-service` 호출용 `WebClient` Bean 생성

#### 2) 외부 서비스 주소 설정 추가

```yml
external-api-service:
  base-url: ${EXTERNAL_API_SERVICE_URL:http://localhost:8090}
```

#### 3) 외부 호출 전용 클라이언트 분리

- `ExternalApiClient` 클래스 추가
- `price-service` 내부에서 HTTP 호출 책임을 분리

#### 4) NaverShoppingService 리팩토링

기존:

- 네이버 API 직접 호출
- 인증 헤더 구성
- JSON 파싱

변경 후:

- `ExternalApiClient` 호출만 담당
- 외부 API 세부 구현 제거

---

## 4. 작업 중 발견한 문제와 수정 사항

### 4-1. API 경로 불일치

초기 구현 시 실제 호출 경로가 서로 달랐다.

#### 문제

- `external-api-service`: `/api/v1/naver/search`
- `price-service`: `/api/external/naver/search`

#### 수정

- `price-service` 호출 경로를 `/api/v1/naver/search`로 통일

---

### 4-2. Query Parameter 이름 불일치

#### 문제

- `external-api-service`: `keyword`
- `price-service`: `query`

#### 수정

- `price-service`에서도 `keyword`를 사용하도록 통일

---

### 4-3. 응답 형식 불일치

#### 문제

- `external-api-service`: 래퍼 응답 객체 반환
- `price-service`: `List<SearchResponse>`만 바로 기대

#### 수정

- `price-service`에 `NaverSearchResponse`, `NaverShoppingItem` DTO 추가
- `items`를 꺼내 기존 `SearchResponse`로 매핑하도록 수정

---

### 4-4. Gateway 경로 불일치

#### 문제

- `gateway`: `/api/price/**`
- `price-service`: `/api/v1/naver/**`, `/api/v1/aliexpress/**`

#### 수정

- Gateway 경로를 `/api/v1/naver/**`, `/api/v1/aliexpress/**`로 수정
- 버전 관리가 가능한 일관된 경로 형식으로 변경

수정 파일:

- `gateway/src/main/resources/application.yml`

---

### 4-5. Docker Compose 반영 필요

통합 테스트를 위해 `external-api-service`를 컨테이너로 띄울 수 있도록 구성했다.

#### 수정 파일

- `docker-compose.yml`
- `external-api-service/Dockerfile`

#### 추가 내용

- `external-api-service` 서비스 추가
- 포트 `8090`
- `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` 환경변수 전달

---

## 5. 테스트 및 검증 결과

### 5-1. external-api-service 테스트

파일:

- `external-api-service/tests/test_naver_router.py`

검증 항목:

- `/health` 정상 응답
- `/api/v1/naver/search` 정상 응답
- 기본 파라미터 처리
- 커스텀 `display`, `start` 처리
- `keyword` 누락 시 422
- 빈 결과 처리
- 응답 정규화 로직 확인

결과:

- `9 passed`

---

### 5-2. price-service 테스트

파일:

- `ExternalApiClientTest`
- `NaverShoppingServiceTest`

검증 항목:

- `external-api-service` 래퍼 응답 역직렬화
- `items -> SearchResponse` 매핑
- 빈 결과 처리
- 예외 전파 처리

결과:

- `:price-service:test` 성공
- `:price-service:build` 성공

---

### 5-3. 실제 HTTP 스모크 테스트

#### 1) external-api-service health check

- `GET http://localhost:8090/health`
- 정상 응답 확인

#### 2) external-api-service 네이버 검색

- `GET http://localhost:8090/api/v1/naver/search?keyword=아이폰&display=1`
- 실제 상품 1건 응답 확인

#### 3) price-service -> external-api-service 연동

- `GET http://localhost:8083/api/v1/naver/search?keyword=아이폰&display=1`
- `ApiResponse` 형태로 정상 응답 확인

### 결론

- `price-service -> external-api-service -> Naver Open API` 흐름이 실제로 정상 동작함을 확인했다.

---

## 6. 이번 작업의 결과

### 구조 변경 전

`price-service -> Naver Open API 직접 호출`

### 구조 변경 후

`price-service -> external-api-service -> Naver Open API`

### 얻은 효과

- 외부 API 호출 책임 분리
- `price-service` 단순화
- 향후 환율/물가/Claude/Gemini API 확장 기반 확보
- 로드맵의 Week 3 구조에 맞는 방향으로 정리 완료

---

## 7. 남은 작업

### 바로 다음 할 일

- `gateway -> price-service`까지 포함한 end-to-end 테스트
- `external-api-service`를 compose 운영 흐름에 맞게 정리
- `price-service` 비즈니스 로직 본격 구현
  - 스케줄러
  - DB 저장
  - Redis 캐싱
  - Circuit Breaker

### 이후 확장 예정

- 환율 API
- 물가 API
- YouTube Transcript API
- Claude API
- Gemini API

---

## 8. 한 줄 회고

이번 작업으로 `price-service`의 외부 API 직접 의존을 제거하고,
Week 3 로드맵의 핵심 구조인 `external-api-service 전담 방식`으로 전환하는 첫 단계를 완료했다.
