# Service 스타일 가이드

## 목적
- 비즈니스 로직을 읽기 쉽게 분리하고 서비스 계층 구조를 일관되게 유지한다.

## 기본 규칙
- `@Service` 사용
- 쓰기 작업은 `@Transactional` 범위를 명확히 둔다.
- 한국어 주석을 사용한다.
- 외부 시스템 연동, 정책 계산, 저장 흐름을 Service에서 조합한다.

## 클래스 내부 순서 규칙
Service 클래스 내부는 아래 순서를 기본으로 한다.

1. 상수(`static final`)
2. **내부 멤버 타입(`record`, `enum`, `static class`)**
3. 의존성 필드(`private final`)
4. 생성자
5. public 메서드
6. protected/package-private 메서드
7. private 헬퍼 메서드

## 내부 멤버 타입 규칙
- Service 안에서만 쓰는 보조 타입은 별도 파일로 빼지 말고 내부 멤버 타입으로 둘 수 있다.
- 이때 **내부 멤버 타입은 필드보다 먼저, 클래스 상단에 배치한다.**
- 이유:
  - 클래스 전체 구조를 초반에 바로 보여주기 쉽다.
  - 코드를 위에서부터 작성할 때 IDE에서 미완성 타입 때문에 빨간 줄이 길게 보이는 불편을 줄인다.
  - Service 하단에 보조 타입이 숨어 있어 찾기 어려운 문제를 줄인다.

## 예시
```java
public class SampleService {

    private record SampleContext(String value) {
    }

    private final SampleRepository sampleRepository;

    public void execute() {
    }

    private SampleContext buildContext() {
        return new SampleContext("test");
    }
}
```

## 메서드 작성 원칙
- public 메서드는 유스케이스 중심 이름으로 작성
- private 메서드는 계산/검증/변환 책임을 잘게 분리
- 하나의 public 메서드에 너무 많은 분기와 반복이 몰리면 private 메서드로 분리
- DB 저장과 외부 API 호출이 섞이면 흐름 주석을 추가

## 금지 사항
- Service가 Controller 응답 포맷까지 직접 책임지지 않음
- Service에서 불필요한 static 유틸 스타일 코드를 남발하지 않음
- Service 내부에 과도하게 긴 익명 클래스/람다를 두지 않음
