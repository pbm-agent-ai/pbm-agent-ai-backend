# PBM Agent AI - 8주차 개발 로드맵

> 2인 개발 / Spring Boot 입문 + AI 에이전트 병행 / 블록체인 구현
> 시작일: 2026년 4월 18일
> 목표일: 2026년 6월 13일

---

## 현재 진행률

| 서비스 | 진행률 | 상태 |
|--------|--------|------|
| auth-service | ~95% | 테스트 코드, Eureka 연동 미완료 |
| price-service | ~30% | 네이버 쇼핑 API만 구현 |
| gateway | 0% | |
| eureka-server | 0% | |
| command-service | 0% | |
| payment-service | 0% | |
| notification-service | 0% | |
| blockchain | 0% | |
| chrome-extension | 0% | |
| frontend (팀원) | ~20% | 피그마 메이커 화면 구성 완료, React 적용 중 |

---

## Week 1 (4/18 - 4/24): Spring Boot 기초 + Auth 완성

### 학습 목표
- [x] Spring Boot 프로젝트 구조 이해 (controller/service/repository)
- [x] Spring Security + JWT 필터 체인 흐름 이해
- [x] JPA Entity 생명주기 이해
- [x] DTO record 클래스 패턴 익히기

### 개발 작업
- [x] auth-service 테스트 코드 작성
- [x] auth-service Eureka 클라이언트 등록
- [x] 비밀번호 변경 API 추가
- [x] eureka-server 완성
- [x] docker-compose에 Eureka 컨테이너 추가
- [x] auth-service Swagger 적용 (팀원 API 연동 참고용)

### AI 프롬프트 예시
```text
"auth-service의 SecurityConfig.java를 분석해서
JWT 필터가 어떤 순서로 동작하는지 설명해줘"
```

### 팀원 작업
- [x] 피그마 화면 기반 React 프로젝트 구조 정리
- [x] 로그인 / 회원가입 페이지 구현 (이슈 기반 개발)
- [x] 사이드바 레이아웃 구현

---

## Week 2 (4/25 - 5/1): Gateway + Kafka 기초

### 학습 목표
- [ ] Spring Cloud Gateway 라우팅 개념
- [ ] Kafka producer/consumer 구조
- [ ] `@KafkaListener` 동작 원리
- [ ] Kafka 메시지 스키마 설계 원칙

### 개발 작업
- [ ] gateway 완성 (JWT 검증 필터 포함)
- [ ] docker-compose에 Kafka 토픽 설정
- [ ] 전 서비스 Kafka 공통 설정
- [ ] auth-service → Kafka 로그인 이벤트 발행
- [ ] **Kafka 메시지 스키마 설계 확정** (서비스 간 이벤트 구조 정의)
- [ ] **AWS EC2 배포 + CI/CD 구축** (gateway 완성 후 진행)
- [ ] AWS EC2 인스턴스 생성
- [ ] docker-compose로 인프라 + 서비스 배포
- [ ] GitHub Actions CI/CD 구성
- [ ] Swagger 외부 접근 가능하도록 설정 (팀원 프론트 연동용)

### AI 프롬프트 예시
```text
"Spring Cloud Gateway에서 JWT 검증 필터를 작성해줘.
인증 실패 시 401 응답을 반환하고
/api/auth/** 경로는 필터를 건너뛰게 해줘"
```

### 팀원 작업
- [ ] 대시보드 메인 화면 구현
- [ ] 조건 관리 페이지 구현

---

## Week 3 (5/2 - 5/8): Price Service + AI API 연동

### 학습 목표
- [ ] RestTemplate vs WebClient
- [ ] Resilience4j Circuit Breaker 패턴
- [ ] 외부 API 연동 시 예외 처리

### 개발 작업
- [ ] AliExpress Affiliate API 연동
- [ ] 한국수출입은행 환율 API 연동 (Redis 캐싱)
- [ ] 한국소비자원 물가정보 API 연동 (Redis 캐싱)
- [ ] 가격 스케줄러 (cron, 조건별 주기적 가격 수집)
- [ ] **Claude API 연동** — YouTube 자막 수집 → 장단점 분석 → 추천 생성 파이프라인
- [ ] **Gemini API 연동** — 이미지 업로드 → 상품명 인식 → 쇼핑몰 검색
- [ ] Resilience4j Circuit Breaker 적용 (외부 API 장애 대응)
- [ ] price-service Swagger 적용

### AI 프롬프트 예시
```text
"youtube-transcript-api로 자막을 수집하고
Claude API로 상품 장단점을 추출하는 파이프라인을
Spring Boot에서 구현해줘"
```

### 팀원 작업
- [ ] 가격 히스토리 차트 구현 (recharts)
- [ ] 이미지 검색 페이지 구현
- [ ] 추천 메뉴 페이지 구현

---

## Week 4 (5/9 - 5/15): Command Service + Chrome Extension 기초

### 학습 목표
- [ ] GPT API 프롬프트 엔지니어링
- [ ] Kafka 메시지 발행/소비 실습
- [ ] Chrome Extension manifest v3 구조

### 개발 작업
- [ ] GPT-4o Mini 자연어 파싱 (누락 조건 감지)
- [ ] 누락 조건 답변 수집 후 모니터링 등록 완성
- [ ] 명령 이력 저장 API
- [ ] command-service → Kafka price-topic 발행
- [ ] **Chrome Extension 기본 구조 완성**
  - [ ] manifest.json 설정
  - [ ] content.js (DOM 접근)
  - [ ] background.js (서버 통신)
  - [ ] popup.html (Extension UI)
- [ ] command-service Swagger 적용

### AI 프롬프트 예시
```text
"네이버 쇼핑 상품 페이지에서
상품명과 가격을 파싱하는 content.js 코드를 작성해줘.
파싱 결과를 background.js를 통해 서버로 전송해줘"
```

### 팀원 작업
- [ ] 결제 내역 페이지 구현
- [ ] 알림 설정 페이지 구현
- [ ] auth-service API 연동 (로그인/회원가입)

---

## Week 5 (5/16 - 5/22): Blockchain 핵심 개발 + Extension DOM 파싱

### 학습 목표
- [ ] Solidity 기초 (contract, function, modifier)
- [ ] 스마트컨트랙트 보안 패턴 (reentrancy, overflow)
- [ ] Web3j Java 연동
- [ ] 테스트넷 (Sepolia) 배포 절차

### 개발 작업
- [ ] PBM 스마트컨트랙트 설계 및 완성 (Solidity)
  - [ ] 결제 함수
  - [ ] 환불 함수
  - [ ] 이벤트 로그
- [ ] Hardhat 테스트 환경 구성
- [ ] Hardhat 단위 테스트
- [ ] Web3j 연동 설정
- [ ] **Chrome Extension DOM 파싱 고도화**
  - [ ] 네이버 쇼핑 DOM 파싱
  - [ ] 쿠팡 DOM 파싱
  - [ ] 알리익스프레스 DOM 파싱
  - [ ] 파싱 결과 서버 전송 (/internal/result)

### AI 프롬프트 예시
```text
"ERC-20 기반 PBMT 토큰 스마트컨트랙트를 작성해줘.
결제 시 수수료 1%를 컨트랙트 소유자에게 전송하고
결제 이벤트 로그를 기록해줘"
```

### 팀원 작업
- [ ] conditions-service API 연동 (조건 목록/등록/수정)
- [ ] price-service API 연동 (가격 히스토리 차트)
- [ ] WebSocket 연결 설정 (STOMP)

---

## Week 6 (5/23 - 5/29): Payment Service + 블록체인 연동 + WebSocket

### 학습 목표
- [ ] Web3j로 스마트컨트랙트 호출
- [ ] 트랜잭션 서명/전송
- [ ] 가스비 최적화
- [ ] WebSocket (STOMP) 실시간 통신

### 개발 작업
- [ ] 결제 요청/처리/조회 API
- [ ] Kafka payment-topic consumer
- [ ] Web3j ↔ 스마트컨트랙트 연동
- [ ] 테스트넷 배포 및 테스트
- [ ] **WebSocket (STOMP) 구현**
  - [ ] /topic/price-alert (가격 조건 충족 알림)
  - [ ] /topic/payment-done (자동결제 완료 알림)
  - [ ] /topic/payment-failed (결제 실패 알림)
  - [ ] /topic/monitoring-update (실시간 가격 업데이트)
- [ ] payment-service Swagger 적용

### AI 프롬프트 예시
```text
"Web3j를 사용해서 PBMT 스마트컨트랙트의
결제 함수를 호출하는 PaymentService 코드를 작성해줘.
트랜잭션 해시를 DB에 저장하고 실패 시 재시도 로직을 추가해줘"
```

### 팀원 작업
- [ ] payment-service API 연동 (결제 내역)
- [ ] WebSocket 실시간 알림 수신 구현
- [ ] 대시보드 실시간 모니터링 카드 업데이트

---

## Week 7 (5/30 - 6/5): Notification + Chrome Extension 자동결제 + 통합 테스트

### 학습 목표
- [ ] 텔레그램 Bot API
- [ ] E2E 테스트 작성법

### 개발 작업
- [ ] 텔레그램 알림 구현
- [ ] 이메일 알림 구현 (SMTP)
- [ ] Kafka notification-topic consumer
- [ ] notification-service Swagger 적용
- [ ] **Chrome Extension 자동결제 연동**
  - [ ] 결제 페이지 DOM 파싱 (결제 버튼 탐색)
  - [ ] GPT-4o DOM 분석 요청
  - [ ] 자동결제 실행 → /internal/result 전송
- [ ] 전체 플로우 E2E 테스트
  - [ ] 자연어 명령 → 조건 등록 → 가격 수집 → 조건 충족 → 자동결제 → 알림
- [ ] Resilience4j 장애 복구 테스트
- [ ] 버그 수정

### AI 프롬프트 예시
```text
"쿠팡 결제 페이지에서 구매하기 버튼을 탐색하고
클릭하는 content.js 코드를 작성해줘.
실행 결과를 background.js를 통해 /internal/result로 전송해줘"
```

### 팀원 작업
- [ ] notification-service API 연동 (알림 목록/읽음 처리)
- [ ] 알림 설정 API 연동
- [ ] 전체 화면 버그 수정
- [ ] Chrome Extension 팝업 UI 연동

---

## Week 8 (6/6 - 6/13): 배포 + 문서화

### 학습 목표
- [ ] Docker Compose 프로덕션 설정
- [ ] CI/CD 파이프라인
- [ ] Prometheus + Grafana

### 개발 작업
- [ ] GitHub Actions CI/CD 구성
- [ ] 전체 서비스 Swagger 최종 검토
- [ ] Prometheus + Grafana 모니터링 대시보드
- [ ] README 최종 업데이트
- [ ] 최종 배포 (서버 환경 구성)
- [ ] Chrome Extension 패키징

### 팀원 작업
- [ ] 프론트엔드 최종 배포 (Vercel 또는 Netlify)
- [ ] 크로스 브라우저 테스트
- [ ] UI 최종 버그 수정

---

## 주간 시간 배분 (권장)

| 요일 | 내용 | 시간 |
|------|------|------|
| 월-화 | AI로 기능 개발 | 4-6시간 |
| 수 | 생성된 코드 분석/이해 | 2-3시간 |
| 목-금 | 직접 수정/테스트 | 3-4시간 |
| 주말 | 학습 자료 + 복습 | 3-4시간 |

---

## 학습 사이클

1. AI에게 기능 요청 + 설명 요청
2. 생성된 코드 읽기
3. 직접 작은 수정 해보기 (변수명 변경, 로그 추가 등)
4. 테스트 실행해보기
5. 이해 안 되는 부분 다시 질문

---

## Swagger 적용 원칙

```
각 서비스 개발 완료 직후 바로 Swagger 적용
→ auth-service: Week 1
→ price-service: Week 3
→ command-service: Week 4
→ payment-service: Week 6
→ notification-service: Week 7

이유
→ 팀원이 API 문서 보면서 프론트 개발 가능
→ API 변경 시 즉시 반영 가능
```

---

## Kafka 토픽 구조

| 토픽 | 발행 서비스 | 소비 서비스 | 설명 |
|------|-------------|-------------|------|
| user-event | auth-service | notification-service | 로그인/회원가입 이벤트 |
| price-topic | command-service | price-service | 가격 수집 요청 |
| price-alert | price-service | notification-service | 가격 조건 충족 이벤트 |
| payment-topic | price-service | payment-service | 자동결제 실행 요청 |
| payment-result | payment-service | notification-service | 결제 완료/실패 이벤트 |

---

## 리스크

| 리스크 | 대응 |
|--------|------|
| 블록체인 구현 + 8주 타이트 | 테스트넷 MVP를 1차 목표로, 메인넷은 이후 |
| Chrome Extension 자동결제 구현 난이도 | Week 4부터 점진적으로 구현, GPT-4o DOM 분석 활용 |
| AI API 비용 | 개발 중 mock/stub 활용, 실제 연동은 Week 3 이후 |
| 팀원 프론트-백엔드 연동 타이밍 | Swagger 조기 적용으로 API 문서 공유, Week 4-5부터 연동 |