# Repository 스타일 가이드

## 목적
- JPA Repository를 단순 조회/저장 책임에 집중시킨다.

## 기본 규칙
- `JpaRepository` 기반으로 작성
- 메서드명은 조회 조건이 드러나게 작성
- 복잡한 비즈니스 판단은 Repository가 아니라 Service에서 수행

## 작성 원칙
- 단순 조건 조회는 derived query 우선
- 복잡한 조회만 필요한 경우에 한해 `@Query` 사용
- 반환 타입은 `Optional`, `List`, `Page` 등 의도를 드러내게 선택

## 금지 사항
- Repository에서 비즈니스 정책을 판단하지 않음
- 사용하지 않는 쿼리 메서드를 미리 만들지 않음
