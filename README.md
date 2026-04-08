# 🦞 PBM Agent AI — Backend

> PBM Agent AI 자동 결제 시스템의 백엔드 레포지토리

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?style=flat&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-6DB33F?style=flat&logo=spring&logoColor=white)](https://spring.io/projects/spring-cloud)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-231F20?style=flat&logo=apachekafka)](https://kafka.apache.org)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=flat&logo=postgresql&logoColor=white)](https://www.postgresql.org)
[![Redis](https://img.shields.io/badge/Redis-FF4438?style=flat&logo=redis&logoColor=white)](https://redis.io)
[![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat&logo=docker&logoColor=white)](https://www.docker.com)

---

## 📌 프로젝트 소개

본 레포지토리는 **PBM Agent AI 자동 결제 시스템**의 백엔드 코드를 포함합니다.

Spring Cloud 기반 MSA 아키텍처로 구성되어 있으며, GPT-4o·Claude·Gemini 다중 AI 모델을 역할 분리하여 활용합니다. Apache Kafka를 통한 이벤트 기반 비동기 통신과 PBM 스마트컨트랙트 기반 자율 결제 기능을 제공합니다.

🔗 **Frontend 레포**: [pbm-agent-ai-frontend](https://github.com/pbm-agent-ai/pbm-agent-ai-frontend)

---

## 🏗️ 시스템 아키텍처

```
클라이언트
    ↓
Spring Cloud Gateway (8080) — JWT 인증 · 라우팅 · 로드밸런싱
    ↓
Eureka Server (8761) — 서비스 디스커버리
    ↓
┌─────────────────────────────────────────────┐
│              마이크로서비스                   │
│  Auth Service        (8081)                  │
│  Command Service     (8082) ⚡CB             │
│  Price Service       (8083) ⚡CB             │
│  Payment Service     (8084)                  │
│  Notification Service(8085)                  │
└─────────────────────────────────────────────┘
    ↓
Apache Kafka — 비동기 이벤트 통신
├── command-topic
├── price-alert-topic
├── payment-topic
└── notification-topic
    ↓
인프라
├── PostgreSQL (서비스별 독립 DB)
├── Redis (캐시 · 세션)
├── Prometheus + Grafana
└── PBM 블록체인 (Solidity · Web3.js)
```

---

## 🤖 AI 모델 역할 분리

| 모델 | 담당 기능 |
|------|-----------|
| **GPT-4o** | 복잡한 결제 DOM 분석, 결제 판단 |
| **GPT-4o Mini** | 자연어 명령 파싱, 단순 DOM, 알림 메시지 |
| **Claude API** | 유튜버 자막 분석, 장단점 추출, 가격 추이 분석 |
| **Gemini API** | 이미지 → 상품명 인식, 쇼핑몰 URL 탐색 |

---

## 🏗️ 프로젝트 구조

```
pbm-agent-ai-backend/
├── eureka-server/             # 서비스 디스커버리
├── gateway/                   # Spring Cloud Gateway
├── auth-service/              # 인증 서비스 (8081)
│   └── src/main/java/
│       ├── controller/
│       ├── service/
│       ├── repository/
│       └── security/          # JWT 설정
├── command-service/           # 명령 파싱 서비스 (8082)
│   └── src/main/java/
│       ├── controller/
│       ├── service/
│       └── ai/                # GPT-4o 연동
├── price-service/             # 가격 모니터링 서비스 (8083)
│   └── src/main/java/
│       ├── scheduler/         # 가격 수집 스케줄러
│       ├── api/               # 외부 API 연동
│       └── ai/                # Claude API 연동
├── payment-service/           # 결제 서비스 (8084)
│   └── src/main/java/
│       ├── controller/
│       ├── service/
│       └── blockchain/        # Web3.js 연동
├── notification-service/      # 알림 서비스 (8085)
│   └── src/main/java/
│       ├── telegram/
│       └── email/
├── blockchain/
│   └── contracts/             # Solidity 스마트컨트랙트
├── docker-compose.yml
└── .env.example
```

---

## 🛠️ 기술 스택

| 분류 | 기술 |
|------|------|
| 프레임워크 | Spring Boot 3.x, Spring Cloud |
| MSA | Eureka, Spring Cloud Gateway, Resilience4j |
| 메시지 큐 | Apache Kafka |
| 실시간 통신 | WebSocket (STOMP) |
| 데이터베이스 | PostgreSQL, Redis |
| AI 엔진 | GPT-4o, Claude API, Gemini API |
| 블록체인 | Solidity, Web3.js |
| 인프라 | Docker Compose, Prometheus, Grafana |
| 외부 API | 네이버쇼핑, AliExpress, Amadeus, 수출입은행, 소비자원 |

---

## 🚀 시작하기

### 사전 요구사항

```bash
Java 17+
Docker & Docker Compose
```

### 환경 변수 설정

```bash
cp .env.example .env
```

```env
# AI Services
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...
GEMINI_API_KEY=...

# Naver Shopping API
NAVER_CLIENT_ID=...
NAVER_CLIENT_SECRET=...

# AliExpress Affiliate API
ALIEXPRESS_APP_KEY=...
ALIEXPRESS_APP_SECRET=...

# Amadeus API
AMADEUS_CLIENT_ID=...
AMADEUS_CLIENT_SECRET=...

# 공공데이터 API
EXCHANGE_RATE_API_KEY=...
CONSUMER_PRICE_API_KEY=...

# Notification
TELEGRAM_BOT_TOKEN=...
GMAIL_APP_PASSWORD=...

# JWT
JWT_SECRET=...
JWT_EXPIRATION=1800000

# Database
POSTGRES_URL=jdbc:postgresql://localhost:5432/pbm_agent
POSTGRES_USERNAME=...
POSTGRES_PASSWORD=...

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
```

### 전체 서비스 실행

```bash
# Docker Compose로 인프라 실행
docker-compose up -d

# 각 서비스 개별 실행
cd auth-service && ./gradlew bootRun
cd command-service && ./gradlew bootRun
cd price-service && ./gradlew bootRun
cd payment-service && ./gradlew bootRun
cd notification-service && ./gradlew bootRun
```

### 서비스 포트

| 서비스 | 포트 |
|--------|------|
| Spring Cloud Gateway | 8080 |
| Eureka Dashboard | 8761 |
| Auth Service | 8081 |
| Command Service | 8082 |
| Price Service | 8083 |
| Payment Service | 8084 |
| Notification Service | 8085 |
| Grafana | 3001 |

---

## 🌿 브랜치 전략

```
main      → 배포 브랜치
develop   → 개발 통합 브랜치
feature/* → 기능 개발
fix/*     → 버그 수정
```

---

## 📋 커밋 컨벤션

```
feat:     새로운 기능 추가
fix:      버그 수정
refactor: 코드 리팩토링
docs:     문서 수정
test:     테스트 코드
chore:    빌드/설정 변경
```

---

## 👥 팀원

| 이름 | 역할 | GitHub |
|------|------|--------|
| 팀원 1 | Backend / AI 파이프라인 | @username |
| 팀원 2 | Backend 보조 / 블록체인 | @username |
```
