# Exception 스타일 가이드

## 목적
- 예외를 일관된 방식으로 정의하고 처리한다.

## 기본 규칙
- 예외는 `exception/` 패키지에 둔다.
- 전역 처리는 `GlobalExceptionHandler`에서 수행한다.
- 사용자 메시지와 로그 메시지를 구분해서 생각한다.

## 작성 원칙
- 예외 이름만 보고도 실패 원인이 추론되게 작성
- 외부 API 실패, 검증 실패, 리소스 없음 등 의미별로 분리
- 필요하면 원인 예외(cause)를 보존

## 금지 사항
- 모든 예외를 RuntimeException 하나로 뭉뚱그리지 않음
- Controller/Service 곳곳에서 중복 try-catch 남발하지 않음
