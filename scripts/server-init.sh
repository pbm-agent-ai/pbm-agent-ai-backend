#!/bin/bash
# KT Cloud 서버 최초 세팅 스크립트
# 실행: bash scripts/server-init.sh

set -e

echo "🚀 PBM Agent AI 서버 초기 세팅 시작"

# ─── Docker 설치 ───────────────────────────────────
echo "📦 Docker 설치 중..."
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
echo "✅ Docker 설치 완료"

# ─── 프로젝트 클론 ─────────────────────────────────
echo "📁 프로젝트 클론 중..."
git clone https://github.com/YOUR_GITHUB_USERNAME/pbm-agent-ai-backend.git ~/pbm-agent-ai-backend
cd ~/pbm-agent-ai-backend
echo "✅ 클론 완료"

# ─── .env 파일 생성 ────────────────────────────────
echo "⚙️  .env 파일 생성 중..."
cat > ~/.env.prod << 'EOF'
# DB
AUTH_DB_PASSWORD=
COMMAND_DB_PASSWORD=
PRICE_DB_PASSWORD=
PAYMENT_DB_PASSWORD=

# JWT
JWT_SECRET=

# API Keys
OPENAI_API_KEY=
EXCHANGE_RATE_API_KEY=
NAVER_CLIENT_ID=
NAVER_CLIENT_SECRET=
ALIEXPRESS_APP_KEY=
ALIEXPRESS_APP_SECRET=
GEMINI_API_KEY=

# Docker Hub
DOCKERHUB_USERNAME=
EOF

ln -sf ~/.env.prod ~/pbm-agent-ai-backend/.env
echo "✅ .env 파일 생성 완료 (nano ~/.env.prod 에서 값 채워주세요)"

# ─── 인프라만 먼저 실행 ────────────────────────────
echo "🐳 인프라 서비스 시작 중..."
docker compose -f docker-compose.prod.yml up -d postgres redis zookeeper kafka
echo "✅ 인프라 실행 완료"

echo ""
echo "======================================"
echo "✅ 초기 세팅 완료!"
echo "1. nano ~/.env.prod 에서 환경변수 값을 채워주세요"
echo "2. 그 다음 GitHub Actions에서 배포하거나"
echo "   직접 실행: docker compose -f docker-compose.prod.yml up -d"
echo "======================================"
