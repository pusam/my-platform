# [수정] 빌드 과정(FROM gradle...) 삭제함!
# 이미 윈도우에서 만들어진 JAR를 실행만 하는 이미지입니다.

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 1. 업로드 폴더 생성 및 권한 설정
RUN mkdir -p /app/uploads && \
    addgroup -S spring && \
    adduser -S spring -G spring && \
    chown -R spring:spring /app

# 2. 헬스체크용 curl 설치
RUN apk add --no-cache curl

# [수정] 빌더(builder)에서 복사하는 게 아니라,
# 윈도우에서 보내준 app.jar를 바로 복사합니다.
COPY app.jar app.jar

# 3. 권한 변경
RUN chown spring:spring app.jar

USER spring

EXPOSE 8080

# 4. 헬스체크 (기존과 동일)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# 5. 실행 (기존과 동일)
ENTRYPOINT ["java", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", \
    "app.jar"]