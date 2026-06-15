# PBM External API Service - FastAPI + uvicorn
FROM python:3.12-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    libasound2 \
    libatk-bridge2.0-0 \
    libatk1.0-0 \
    libc6 \
    libcairo2 \
    libcups2 \
    libdbus-1-3 \
    libexpat1 \
    libfontconfig1 \
    libgbm1 \
    libgcc1 \
    libglib2.0-0 \
    libgtk-3-0 \
    libnspr4 \
    libnss3 \
    libpango-1.0-0 \
    libpangocairo-1.0-0 \
    libstdc++6 \
    libx11-6 \
    libx11-xcb1 \
    libxcb1 \
    libxcomposite1 \
    libxdamage1 \
    libxext6 \
    libxfixes3 \
    libxkbcommon0 \
    libxrandr2 \
    wget \
    xdg-utils \
    && rm -rf /var/lib/apt/lists/*

# 의존성 먼저 복사하여 캐시 활용
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
RUN python -m playwright install chromium

# 애플리케이션 코드 복사
COPY app/ ./app/

EXPOSE 8090

# NAVER_MOCK_ENABLED 환경변수로 모킹 모드 제어 (기본값: true)
# 실제 Naver API 호출 시 NAVER_MOCK_ENABLED=false 로 설정 후
# NAVER_CLIENT_ID, NAVER_CLIENT_SECRET 환경변수 필수
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
