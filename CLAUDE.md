# PBM Agent AI - Backend

## 프로젝트 개요
PBM 스마트컨트랙트 기반 AI 자동 결제 시스템 백엔드
Spring Boot MSA 구조로 개발
에이전틱 코딩 주제 (oh-my-opencode로 개발)

## 절대 규칙 (반드시 지켜야 함)
- API Key, 비밀번호 절대 하드코딩 금지
- 모든 설정값은 application.yml + 환경변수로 주입
- 한국어 주석 사용
- 테스트 코드 항상 함께 작성
- 공통 응답은 반드시 ApiResponse<T> 래퍼 사용
- DTO는 record 클래스로 작성

## 코드 스타일 문서
- 코드 작성 시 반드시 아래 문서를 참고
- 공통/강제 규칙은 이 CLAUDE.md를 우선 적용
- 레이어별 상세 스타일은 docs/coding-style/ 하위 문서를 적용
- 규칙 우선순위: CLAUDE.md > docs/coding-style/*.md > 기존 파일 로컬 스타일

- Controller: `docs/coding-style/controller.md`
- Service: `docs/coding-style/service.md`
- DTO: `docs/coding-style/dto.md`
- Repository: `docs/coding-style/repository.md`
- Domain: `docs/coding-style/domain.md`
- Exception: `docs/coding-style/exception.md`
- Client: `docs/coding-style/client.md`
- Test: `docs/coding-style/test.md`

## 서비스 구조
- eureka-server (8761): 서비스 디스커버리
- gateway (8080): Spring Cloud Gateway, JWT 인증 필터
- auth-service (8081): 회원가입, 로그인, JWT 발급
- command-service (8082): 자연어 파싱 (GPT-4o)
- price-service (8083): 가격 모니터링, Claude API, 환율 API
- payment-service (8084): PBM 결제, Web3.js, 블록체인
- notification-service (8085): 텔레그램, 이메일 알림

## 패키지 구조 (각 서비스 동일하게 적용)
com.pbm.{서비스명}
├── controller/     # REST API 컨트롤러
├── service/        # 비즈니스 로직
├── repository/     # JPA 레포지토리
├── domain/         # 엔티티 클래스
├── dto/
│   ├── request/    # 요청 DTO
│   └── response/   # 응답 DTO
├── common/         # ApiResponse 등 공통 클래스
├── config/         # 설정 클래스
├── exception/      # 커스텀 예외, GlobalExceptionHandler
└── client/         # 외부 API 클라이언트

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
- Controller: @RestController, @RequestMapping
- Service: @Service, @Transactional
- DTO: record 클래스 사용 (불변 객체)
- 예외: GlobalExceptionHandler로 일괄 처리
- 응답: ApiResponse<T> 공통 응답 래퍼 반드시 사용
- 외부 API 호출: Resilience4j Circuit Breaker 필수 적용
- 서비스 간 비동기 통신: Kafka 사용

## API 응답 형식
{
  "success": true,
  "data": {},
  "message": "성공"
}

## 현재 개발 단계
1. 개발환경 세팅 완료 ✅
2. Docker Compose 인프라 실행 완료 ✅
3. 진행 중: Auth Service 개발

## 개발 우선순위
1. auth-service (JWT 로그인/회원가입)
2. gateway (라우팅 + JWT 필터)
3. eureka-server (서비스 디스커버리)
4. command-service (자연어 파싱)
5. price-service (가격 모니터링)
6. payment-service (PBM 결제)
7. notification-service (알림)

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
