package com.myplatform.backend.service;

import com.myplatform.backend.dto.RiskAnalysisDto.NewsItem;
import com.myplatform.backend.service.NaverSearchService.Route;
import com.myplatform.backend.service.NaverSearchService.SearchFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 네이버 검색 — URL 인코딩 회귀 + NAVER API HUB 이전(2026-11-19).
 *
 * <p>버그(2026-07-01): 한글 쿼리가 <b>이중 인코딩</b>(% → %25)돼 네이버가 무관 뉴스를 반환
 * → 종목명 필터 전부 탈락 → 재료 100% NONE. 이중 인코딩의 최종 원인은 RestTemplate.exchange 가
 * <b>String</b> URL 을 URI 템플릿으로 보고 재인코딩한 것 → buildSearchUrl 은 <b>URI</b> 를 반환해
 * RestTemplate 재인코딩을 차단하고, 그 URI 자체는 UTF-8 단일 인코딩이어야 한다.
 *
 * <p>HUB 이전: 개발자센터 키는 2027-06-30 부터 검색 API 에서 막힌다. HUB 키가 있으면 HUB 주소·HUB 헤더로만,
 * 없으면 개발자센터 주소·헤더로만 부른다(한 요청에 두 벌 금지). 호출 실패는 '뉴스 0건'이 아니라 예외다.
 * 가짜 서버는 {@link MockRestServiceServer}(서비스의 RestTemplate 에 붙인다 — 밖으로 나가지 않는다).
 */
class NaverSearchServiceTest {

    private static final String SAMSUNG_ENCODED = "%EC%82%BC%EC%84%B1%EC%A0%84%EC%9E%90";   // 삼성전자 UTF-8
    private static final String HUB_ID = "hub-id-AAAA1111";
    private static final String HUB_SECRET = "hub-secret-BBBB2222";
    private static final String LEGACY_ID = "legacy-id-CCCC3333";
    private static final String LEGACY_SECRET = "legacy-secret-DDDD4444";

    private RestTemplate rest;
    private MockRestServiceServer server;

    private NaverSearchService service(String legacyId, String legacySecret, String hubId, String hubSecret, String hubUrl) {
        rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        return new NaverSearchService(rest, legacyId, legacySecret, hubId, hubSecret, hubUrl);
    }

    /** 검색 응답 — 종목명이 제목에 든 오늘 기사 n 건(필터를 통과한다). */
    private static String newsBody(int n) {
        String pubDate = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
                .format(ZonedDateTime.now(ZoneId.of("Asia/Seoul")));
        StringBuilder items = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            if (i > 1) items.append(',');
            items.append("{\"title\":\"<b>삼성전자</b> 기사 ").append(i).append("\",")
                 .append("\"originallink\":\"https://example.com/").append(i).append("\",")
                 .append("\"link\":\"https://n.news.naver.com/").append(i).append("\",")
                 .append("\"description\":\"요약\",\"pubDate\":\"").append(pubDate).append("\"}");
        }
        return "{\"lastBuildDate\":\"" + pubDate + "\",\"total\":" + n + ",\"start\":1,\"display\":" + n
                + ",\"items\":[" + items + "]}";
    }

    @Test
    @DisplayName("검색 URI — 한글 쿼리 단일 인코딩(삼성전자=%EC%82%BC…), 이중 인코딩(%25) 없음")
    void buildSearchUrl_singleEncodesKorean() {
        URI url = NaverSearchService.buildSearchUrl("삼성전자");
        String s = url.toString();

        // 반환 타입이 URI 여야 RestTemplate 이 재인코딩하지 않는다(회귀 방지의 핵심)
        assertThat(url).isInstanceOf(URI.class);
        // '삼' = UTF-8 EC 82 BC → 단일 인코딩 흔적
        assertThat(s).contains("query=%EC%82%BC");
        // 이중 인코딩(%25)이 있으면 네이버가 한글을 못 알아듣는다 — 절대 없어야 함
        assertThat(s).doesNotContain("%25");
        // 고정 파라미터 유지
        assertThat(s).contains("sort=date");
        assertThat(s).contains("display=30");
    }

    @Test
    @DisplayName("검색 URI — 다른 한글 종목명도 %25 없이 단일 인코딩")
    void buildSearchUrl_noDoubleEncodingForOtherNames() {
        assertThat(NaverSearchService.buildSearchUrl("효성중공업").toString()).doesNotContain("%25");
        assertThat(NaverSearchService.buildSearchUrl("대한광통신").toString()).doesNotContain("%25");
        assertThat(NaverSearchService.buildSearchUrl(Route.HUB, NaverSearchService.DEFAULT_HUB_NEWS_URL, "효성중공업")
                .toString()).doesNotContain("%25");
    }

    @Test
    @DisplayName("HUB 키가 있으면 — HUB 주소(문서 기본값)·HUB 헤더 두 개로만 부르고 개발자센터 헤더는 보내지 않는다")
    void hubRoute_hubUrlAndHeaders_noLegacyHeaders() {
        // 두 벌이 다 있어도 HUB 하나만 — 한 요청에 두 키를 싣지 않는다
        NaverSearchService svc = service(LEGACY_ID, LEGACY_SECRET, HUB_ID, HUB_SECRET,
                "https://naverapihub.apigw.ntruss.com/search/v1/news");
        server.expect(once(), requestTo("https://naverapihub.apigw.ntruss.com/search/v1/news?query=" + SAMSUNG_ENCODED
                        + "&display=30&start=1&sort=date&format=json"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-NCP-APIGW-API-KEY-ID", HUB_ID))
                .andExpect(header("X-NCP-APIGW-API-KEY", HUB_SECRET))
                .andExpect(headerDoesNotExist("X-Naver-Client-Id"))
                .andExpect(headerDoesNotExist("X-Naver-Client-Secret"))
                .andRespond(withSuccess(newsBody(3), MediaType.APPLICATION_JSON));

        assertThat(svc.route()).isEqualTo(Route.HUB);
        List<NewsItem> news = svc.searchStockNews("삼성전자");

        server.verify();
        assertThat(news).hasSize(3);
        assertThat(news.get(0).getTitle()).isEqualTo("삼성전자 기사 1");   // <b> 태그 정리는 종전 그대로
    }

    @Test
    @DisplayName("HUB 주소는 설정값을 따른다(naver.hub.news-url)")
    void hubRoute_usesConfiguredUrl() {
        NaverSearchService svc = service("", "", HUB_ID, HUB_SECRET, "https://hub.example.test/search/v1/news");
        server.expect(once(), requestTo("https://hub.example.test/search/v1/news?query=" + SAMSUNG_ENCODED
                        + "&display=30&start=1&sort=date&format=json"))
                .andExpect(header("X-NCP-APIGW-API-KEY-ID", HUB_ID))
                .andRespond(withSuccess(newsBody(1), MediaType.APPLICATION_JSON));

        assertThat(svc.searchStockNews("삼성전자")).hasSize(1);
        server.verify();
    }

    @Test
    @DisplayName("HUB 키가 없으면 — 종전 개발자센터 주소·헤더 그대로, HUB 헤더는 보내지 않는다")
    void legacyRoute_onlyLegacyKeys() {
        NaverSearchService svc = service(LEGACY_ID, LEGACY_SECRET, "", "", null);
        server.expect(once(), requestTo("https://openapi.naver.com/v1/search/news.json?query=" + SAMSUNG_ENCODED
                        + "&display=30&start=1&sort=date"))
                .andExpect(header("X-Naver-Client-Id", LEGACY_ID))
                .andExpect(header("X-Naver-Client-Secret", LEGACY_SECRET))
                .andExpect(headerDoesNotExist("X-NCP-APIGW-API-KEY-ID"))
                .andExpect(headerDoesNotExist("X-NCP-APIGW-API-KEY"))
                .andRespond(withSuccess(newsBody(2), MediaType.APPLICATION_JSON));

        assertThat(svc.route()).isEqualTo(Route.LEGACY);
        assertThat(svc.searchStockNews("삼성전자")).hasSize(2);
        server.verify();
    }

    @Test
    @DisplayName("HUB 키가 한 쪽만 있으면 HUB 를 쓰지 않는다 — 개발자센터로(있을 때), 둘 다 없으면 NONE·미가용")
    void route_partialHubKeys_andNone() {
        assertThat(service(LEGACY_ID, LEGACY_SECRET, HUB_ID, "", null).route()).isEqualTo(Route.LEGACY);
        assertThat(service(LEGACY_ID, LEGACY_SECRET, "  ", HUB_SECRET, null).route()).isEqualTo(Route.LEGACY);
        NaverSearchService none = service("", null, HUB_ID, null, null);
        assertThat(none.route()).isEqualTo(Route.NONE);
        assertThat(none.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("키 거절(401)은 '뉴스 0건'이 아니다 — SearchFailedException(경로·상태), 실패 누계 +1, 메시지에 키 값 없음")
    void refusedKey_throwsInsteadOfEmpty() {
        NaverSearchService svc = service("", "", HUB_ID, HUB_SECRET, null);
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(NaverSearchService.DEFAULT_HUB_NEWS_URL)))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        // 응답이 키를 되돌려 주더라도 메시지엔 실리지 않아야 한다
                        .body("{\"error\":{\"errorCode\":\"200\",\"message\":\"Authentication Failed " + HUB_ID + "\"}}"));
        long before = NaverSearchService.failureCount();

        assertThatThrownBy(() -> svc.searchStockNews("삼성전자"))
                .isInstanceOfSatisfying(SearchFailedException.class, e -> {
                    assertThat(e.getRoute()).isEqualTo(Route.HUB);
                    assertThat(e.getHttpStatus()).isEqualTo(401);
                    assertThat(e.getMessage()).contains("HTTP 401").contains("인증 실패")
                            .doesNotContain(HUB_ID).doesNotContain(HUB_SECRET);
                });
        assertThat(NaverSearchService.failureCount()).isEqualTo(before + 1);
        assertThat(NaverSearchService.lastFailureSummary()).contains("HUB").contains("401").doesNotContain(HUB_ID);
        server.verify();
    }

    @Test
    @DisplayName("한도 초과(429)·서버 오류(500)도 예외 — HUB 실패를 개발자센터로 다시 부르지 않는다")
    void quotaAndServerError_throw_noFallbackToLegacy() {
        NaverSearchService svc = service(LEGACY_ID, LEGACY_SECRET, HUB_ID, HUB_SECRET, null);
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(NaverSearchService.DEFAULT_HUB_NEWS_URL)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(NaverSearchService.DEFAULT_HUB_NEWS_URL)))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> svc.searchStockNews("삼성전자"))
                .isInstanceOf(SearchFailedException.class).hasMessageContaining("HTTP 429").hasMessageContaining("한도");
        assertThatThrownBy(() -> svc.searchStockNews("삼성전자"))
                .isInstanceOf(SearchFailedException.class).hasMessageContaining("HTTP 500");
        server.verify();   // 개발자센터 주소로 가는 세 번째 요청이 있었다면 여기서 실패한다
    }

    @Test
    @DisplayName("200 이라도 items 배열이 없으면 실패(게이트웨이 오류 모양) — 빈 items 는 진짜 0건")
    void bodyShape_missingItemsFails_emptyItemsIsZero() {
        NaverSearchService svc = service("", "", HUB_ID, HUB_SECRET, null);
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(NaverSearchService.DEFAULT_HUB_NEWS_URL)))
                .andRespond(withSuccess("{\"error\":{\"message\":\"unexpected\"}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(NaverSearchService.DEFAULT_HUB_NEWS_URL)))
                .andRespond(withSuccess("{\"total\":0,\"start\":1,\"display\":0,\"items\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> svc.searchStockNews("삼성전자"))
                .isInstanceOf(SearchFailedException.class).hasMessageContaining("items");
        assertThat(svc.searchStockNews("삼성전자")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("진단 호출 — 경로·HTTP 상태·기사 수만, 키 값 없음, 실패 누계엔 넣지 않는다")
    void probeReport_routeStatusCount_noSecrets() {
        NaverSearchService svc = service(LEGACY_ID, LEGACY_SECRET, HUB_ID, HUB_SECRET, null);
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(NaverSearchService.DEFAULT_HUB_NEWS_URL)))
                .andExpect(header("X-NCP-APIGW-API-KEY-ID", HUB_ID))
                .andRespond(withSuccess(newsBody(4), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(NaverSearchService.DEFAULT_HUB_NEWS_URL)))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"error\":\"" + HUB_SECRET + "\"}"));
        long before = NaverSearchService.failureCount();

        Map<String, Object> ok = svc.probeReport("삼성전자");
        Map<String, Object> refused = svc.probeReport(null);

        assertThat(ok).containsEntry("route", "HUB").containsEntry("httpStatus", 200).containsEntry("itemCount", 4)
                .containsEntry("ok", true).containsEntry("hubKeysConfigured", true).containsEntry("legacyKeysConfigured", true)
                .containsEntry("endpoint", NaverSearchService.DEFAULT_HUB_NEWS_URL).containsEntry("legacyCutoff", "2027-06-30");
        assertThat(refused).containsEntry("route", "HUB").containsEntry("httpStatus", 401).containsEntry("ok", false)
                .containsEntry("query", "삼성전자");
        for (Map<String, Object> r : List.of(ok, refused)) {
            assertThat(String.valueOf(r)).doesNotContain(HUB_ID).doesNotContain(HUB_SECRET)
                    .doesNotContain(LEGACY_ID).doesNotContain(LEGACY_SECRET);
        }
        assertThat(NaverSearchService.failureCount()).isEqualTo(before);   // 진단은 배치 판정과 섞지 않는다
        server.verify();
    }

    @Test
    @DisplayName("진단 호출 — 키가 없으면 부르지 않고 NONE 을 말한다")
    void probeReport_noKeys() {
        NaverSearchService svc = service("", "", "", "", null);
        Map<String, Object> r = svc.probeReport("삼성전자");
        assertThat(r).containsEntry("route", "NONE").containsEntry("ok", false).containsEntry("hubKeysConfigured", false);
        server.verify();   // 요청 없음
    }
}
