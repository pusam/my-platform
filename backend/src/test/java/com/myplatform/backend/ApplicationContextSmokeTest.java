package com.myplatform.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 애플리케이션 컨텍스트 기동 스모크 테스트.
 *
 * <p><b>왜</b>: 이 프로젝트엔 @SpringBootTest 가 0개라, 빈 와이어링/설정 오류로 <b>앱이 부팅 못 하는</b> 회귀를
 * 단위테스트(Mockito)로는 절대 못 잡았다(2026-06-29 봇 리더 선출 작업 후 배포 점검에서 드러난 맹점).
 * 이 테스트는 외부 인프라 없이(H2 + Flyway off + 더미 시크릿, application-test.yml) 전체 컨텍스트를
 * <b>eager 로 로드</b>해, 새 빈/생성자 변경이 컨텍스트를 깨면 CI 에서 즉시 실패하게 한다.
 *
 * <p>주의: 호스트 OOM/디스크 같은 인프라 다운은 이 테스트가 못 잡는다(그건 배포/모니터링 영역). 여기서 잡는 건
 * "코드가 앱 컨텍스트를 깨뜨렸는가"뿐이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ApplicationContextSmokeTest {

    @Test
    void contextLoads() {
        // 컨텍스트가 떠서 이 메서드까지 도달하면 성공. (빈 생성/와이어링/설정 오류 시 여기 못 옴)
    }
}
