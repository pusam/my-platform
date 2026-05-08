# GitHub Actions 워크플로우에서 빌드된 결과물을 이미지화하는 설정

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 1. 필요한 패키지 설치 및 사용자 설정
RUN mkdir -p /app/uploads && \
    addgroup -S spring && \
    adduser -S spring -G spring && \
    chown -R spring:spring /app && \
    apk add --no-cache curl libgcc

# 2. JAR 파일 복사
# GitHub Actions가 빌드한 'app.jar'를 이미지 안으로 복사합니다.
COPY app.jar app.jar

# 3. 권한 변경
RUN chown spring:spring app.jar

USER spring

EXPOSE 8080

# 4. 헬스체크 — 자체 경량 ping (Spring Boot 4.0 actuator/health access 이슈 우회)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/api/health || exit 1

# 5. 실행
ENTRYPOINT ["java", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", \
    "app.jar"]