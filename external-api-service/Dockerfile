# PBM External API Service - FastAPI + uvicorn
FROM python:3.12-slim

WORKDIR /app

# 의존성 먼저 복사하여 캐시 활용
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 애플리케이션 코드 복사
COPY app/ ./app/

EXPOSE 8090

# NAVER_MOCK_ENABLED 환경변수로 모킹 모드 제어 (기본값: true)
# 실제 Naver API 호출 시 NAVER_MOCK_ENABLED=false 로 설정 후
# NAVER_CLIENT_ID, NAVER_CLIENT_SECRET 환경변수 필수
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]