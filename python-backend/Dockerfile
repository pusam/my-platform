FROM python:3.12-slim

WORKDIR /app

# 시스템 패키지
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 의존성 설치
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 앱 복사
COPY app/ app/

# 테스트 포함 — `docker compose run --rm python-backend pytest` 로 지표 순수함수 검증(CI/재실행).
# uvicorn 운영엔 무영향(테스트 파일은 import 안 됨).
COPY tests/ tests/
COPY pytest.ini .

# 환경변수
ENV TZ=Asia/Seoul
ENV PYTHONPATH=/app

EXPOSE 8000

# 헬스체크
HEALTHCHECK --interval=30s --timeout=10s --retries=3 --start-period=15s \
    CMD curl -f http://localhost:8000/api/v2/health || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2"]
