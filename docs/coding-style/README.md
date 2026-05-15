# PBM Backend Coding Style

## 목적
- 코더가 레이어별 스타일을 빠르게 확인할 수 있도록 공통 뼈대를 제공한다.
- 상세 구현 규칙은 각 문서에서 관리한다.

## 적용 순서
1. `CLAUDE.md`
2. `docs/coding-style/*.md`
3. 기존 파일의 로컬 컨벤션

## 문서 목록
- `controller.md`: Controller 작성 규칙
- `service.md`: Service 작성 규칙
- `dto.md`: DTO 작성 규칙
- `repository.md`: Repository 작성 규칙
- `domain.md`: Domain(Entity) 작성 규칙
- `exception.md`: Exception 작성 규칙
- `client.md`: 외부 API Client 작성 규칙
- `test.md`: 테스트 작성 규칙

## 운영 원칙
- 문서 내용은 예시보다 규칙이 우선이다.
- 규칙 충돌 시 더 상위 문서를 따른다.
- 서비스별 예외 규칙이 필요하면 추후 `{service-name}/docs/` 하위에 별도 문서를 추가한다.
