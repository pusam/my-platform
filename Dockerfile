# GitHub Actions 워크플로우에서 빌드된 결과물을 이미지화하는 설정

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 1. 필요한 패키지 설치 + KST timezone 고정 + 사용자 설정
# tzdata 추가 — alpine 기본엔 timezone 정의 없음 → -Duser.timezone 만으로는 ZoneId.of() 실패 가능
RUN mkdir -p /app/uploads && \
    addgroup -S spring && \
    adduser -S spring -G spring && \
    chown -R spring:spring /app && \
    apk add --no-cache curl libgcc tzdata && \
    cp /usr/share/zoneinfo/Asia/Seoul /etc/localtime && \
    echo "Asia/Seoul" > /etc/timezone

# 1-1. 개인키가 유출된 인증기관(eMudhra)의 뿌리 인증서를 자바 신뢰 목록에서 뺀다 — OpenJDK 권고(openjdk.org/emudhra, CVE-2026-114820).
#      Temurin 17.0.21+9 는 그 권고보다 먼저 나왔고, 신뢰 목록이 모질라 기반이라 별칭이 OpenJDK 안내와 다르다 → 소유자 이름으로 찾는다.
#      목록을 못 읽거나 지운 뒤에도 남아 있으면 빌드를 멈춘다. 고친 Temurin 에서 이미 빠져 있으면 지울 것 없이 통과한다.
#      백엔드가 부르는 바깥 주소(KIS·DART·네이버·Gemini·텔레그램 등) 중 이 인증기관을 쓰는 곳은 없다(2026-11-17 확인).
#      회귀 DockerfileTrustStoreTest.
RUN set -eu; \
    kt="keytool -J-Duser.language=en -J-Duser.country=US"; \
    before=$($kt -list -v -cacerts -storepass changeit </dev/null); \
    printf '%s\n' "$before" | awk '/^Alias name: /{a=substr($0,13)} tolower($0) ~ /^owner: .*(emudhra|emsign)/{print a}' > /tmp/distrust; \
    while IFS= read -r a; do $kt -delete -cacerts -storepass changeit -noprompt -alias "$a" </dev/null; done < /tmp/distrust; \
    after=$($kt -list -v -cacerts -storepass changeit </dev/null); \
    [ "$(printf '%s\n' "$after" | grep -c '^Alias name: ')" -gt 50 ] || { echo "자바 신뢰 목록을 읽지 못했다" >&2; exit 1; }; \
    if printf '%s\n' "$after" | grep -qiE '^owner: .*(emudhra|emsign)'; then echo "eMudhra 뿌리 인증서가 아직 신뢰 목록에 있다" >&2; exit 1; fi; \
    echo "자바 신뢰 목록에서 뺀 뿌리 인증서: $(wc -l < /tmp/distrust)개"; \
    rm -f /tmp/distrust

ENV TZ=Asia/Seoul

# 2. JAR 파일 복사
# GitHub Actions가 빌드한 'app.jar'를 이미지 안으로 복사합니다.
COPY app.jar app.jar

# 이미지가 담은 커밋 — 부팅 기록(server_boot_log.revision, V74)이 읽는다. deploy.yml 이 build-args 로 넣고, 없으면 빈 값(=모름).
# jar 뒤에 둔다(앞에 두면 커밋마다 위 계층 캐시가 깨진다).
ARG APP_REVISION=
ENV APP_REVISION=${APP_REVISION}

# 3. 권한 변경
RUN chown spring:spring app.jar

USER spring

EXPOSE 8080

# 4. 헬스체크 — 자체 경량 ping (Spring Boot 4.0 actuator/health access 이슈 우회)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/api/health || exit 1

# 5. 실행 — JVM timezone 명시 (시스템 TZ 누락 환경에서도 KST 강제)
ENTRYPOINT ["java", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Duser.timezone=Asia/Seoul", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", \
    "app.jar"]