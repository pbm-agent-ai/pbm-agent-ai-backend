# Client 스타일 가이드

## 목적
- 외부 API 호출 코드를 일관되고 장애 대응 가능하게 작성한다.

## 기본 규칙
- 외부 호출 코드는 `client/` 패키지에 둔다.
- Resilience4j Circuit Breaker를 적용한다.
- 필요 시 Retry, timeout, fallback 전략을 함께 설계한다.
- 한국어 주석을 사용한다.

## 작성 원칙
- 외부 응답 DTO와 내부 공통 DTO를 구분한다.
- null/빈 응답 처리 기준을 명확히 둔다.
- fallback은 조용히 삼키지 말고 실패를 명확히 드러낸다.

## 금지 사항
- 외부 API key를 하드코딩하지 않음
- Service에서 직접 HTTP 호출하지 않고 Client로 분리한다.
