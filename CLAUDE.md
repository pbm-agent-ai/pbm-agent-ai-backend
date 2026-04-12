# PBM Agent AI - Backend

## 프로젝트 개요
PBM 스마트컨트랙트 기반 AI 자동 결제 시스템 백엔드
Spring Boot MSA 구조로 개발

## 서비스 구조
- eureka-server (8761): 서비스 디스커버리
- gateway (8080): Spring Cloud Gateway, JWT 인증 필터
- auth-service (8081): 회원가입, 로그인, JWT 발급
- command-service (8082): 자연어 파싱 (GPT-4o)
- price-service (8083): 가격 모니터링, Claude API, 환율 API
- payment-service (8084): PBM 결제, Web3.js, 블록체인
- notification-service (8085): 텔레그램, 이메일 알림

## AI 모델 역할
- GPT-4o: 복잡한 DOM 분석, 결제 판단
- GPT-4o Mini: 자연어 파싱, 단순 DOM, 알림 메시지
- Claude API: 유튜버 자막 분석, 가격 추이 분석
- Gemini API: 이미지 상품 인식 (멀티모달)

## 기술 스택
- Java 17, Spring Boot 3.x
- Spring Cloud Gateway, Eureka, Resilience4j
- Apache Kafka
- PostgreSQL (서비스별 독립 DB), Redis
- Docker Compose

## 코딩 규칙
- 패키지: com.pbm.{서비스명}
- 모든 API Key는 환경변수로 주입 (하드코딩 금지)
- 외부 API 호출에 Resilience4j Circuit Breaker 적용
- 서비스 간 비동기 통신은 Kafka 사용

## 브랜치 전략
- main: 배포 브랜치
- develop: 개발 통합
- feature/{기능명}: 기능 개발
- fix/{버그명}: 버그 수정

## 커밋 컨벤션
feat: 새로운 기능
fix: 버그 수정
refactor: 리팩토링
docs: 문서 수정
test: 테스트 코드
chore: 빌드/설정 변경
