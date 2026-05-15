# Controller 스타일 가이드

## 목적
- REST API 진입점을 일관된 방식으로 작성한다.

## 기본 규칙
- `@RestController`, `@RequestMapping` 사용
- 응답은 반드시 `ApiResponse<T>`로 감싼다.
- 요청/응답 본문은 DTO를 사용한다.
- 비즈니스 로직은 Controller에 두지 않고 Service로 위임한다.
- 한국어 주석을 사용한다.

## 권장 구조
1. 클래스 주석
2. 어노테이션
3. 의존성 주입 필드 또는 생성자
4. 엔드포인트 메서드

## 메서드 작성 원칙
- HTTP 메서드와 경로가 드러나게 작성
- 파라미터 검증은 Bean Validation을 우선 사용
- 성공 메시지는 API 목적에 맞게 명확히 작성
- 예외 처리는 GlobalExceptionHandler에 위임

## 금지 사항
- Entity를 직접 request/response로 노출하지 않음
- Controller에서 DB 접근하지 않음
- 긴 조건문/계산 로직을 직접 작성하지 않음
