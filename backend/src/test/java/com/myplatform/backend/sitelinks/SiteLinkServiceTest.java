package com.myplatform.backend.sitelinks;

import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 바로가기는 본인 행만 읽고 고친다 — 남의 행 id 를 넣어도 '없음'이고 그 행은 그대로다.
 * 소유권은 저장소 조회({@code findByIdAndUserId})가 지키므로 목(mock)이 아니라 실제 DB(H2)에서 두 계정의 행을 놓고 확인한다.
 * 운영 스키마(MariaDB) 대조는 {@code FlywayMigrationTest} 검증 26.
 */
@SpringBootTest(classes = SiteLinkServiceTest.JpaOnly.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitelinks;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
@Transactional
class SiteLinkServiceTest {

    /**
     * JPA 만 띄운다. {@code @TestConfiguration} 으로 바꾸지 말 것 — 그러면 Spring Boot 가 앱 전체({@code BackendApplication})를 같이 띄운다.
     * 테스트 클래스 안의 중첩 설정이라 앱 스캔에는 끼지 않는다({@code ExternalCrosscheckRepositoryTest} 와 같은 모양).
     */
    @Configuration
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class})
    @EntityScan(basePackageClasses = SiteLink.class)
    @EnableJpaRepositories(basePackageClasses = SiteLinkRepository.class)
    static class JpaOnly {}

    static final long ME = 1L;
    static final long OTHER = 2L;

    @Autowired
    SiteLinkRepository repository;

    SiteLinkService service;

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername("me")).thenReturn(Optional.of(user(ME, "me")));
        when(users.findByUsername("other")).thenReturn(Optional.of(user(OTHER, "other")));
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());
        service = new SiteLinkService(repository, users);
    }

    static User user(long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        return u;
    }

    private SiteLink stored(long userId, String name, String url, int sortOrder) {
        SiteLink l = new SiteLink();
        l.setUserId(userId);
        l.setName(name);
        l.setUrl(url);
        l.setSortOrder(sortOrder);
        return repository.saveAndFlush(l);
    }

    static SiteLinkRequest req(String name, String url) {
        return new SiteLinkRequest(name, "설명", url, true, 3);
    }

    @Test
    @DisplayName("목록은 내 것만, 순서 칸이 작은 것부터(같으면 먼저 넣은 것부터)")
    void listsOnlyMineInOrder() {
        stored(ME, "둘째", "https://b.example", 2);
        stored(OTHER, "남의 것", "https://secret.example", 0);
        stored(ME, "첫째", "https://a.example", 1);
        stored(ME, "둘째 동률", "https://c.example", 2);

        List<SiteLinkDto> mine = service.list("me");

        assertThat(mine).extracting(SiteLinkDto::name).containsExactly("첫째", "둘째", "둘째 동률");
        assertThat(service.list("other")).extracting(SiteLinkDto::name).containsExactly("남의 것");
    }

    @Test
    @DisplayName("추가하면 내 계정으로 저장되고 값은 규칙을 거친다")
    void addSavesUnderMyId() {
        SiteLinkDto out = service.add("me", new SiteLinkRequest("  주식  ", " ", " https://a.example/x ", null, null));

        SiteLink saved = repository.findById(out.id()).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(ME);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(out.name()).isEqualTo("주식");
        assertThat(out.description()).isNull();
        assertThat(out.url()).isEqualTo("https://a.example/x");
        assertThat(out.loginRequired()).as("안 보내면 false").isFalse();
        assertThat(out.sortOrder()).as("안 보내면 0").isZero();
        assertThat(service.list("other")).isEmpty();
    }

    @Test
    @DisplayName("스크립트 주소는 저장하지 않는다")
    void addRejectsScriptUrl() {
        assertThatThrownBy(() -> service.add("me", req("나쁜 링크", "javascript:alert(1)")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("계정당 상한을 넘으면 추가하지 않는다 — 남의 줄 수는 세지 않는다")
    void addStopsAtLimit() {
        for (int i = 0; i < SiteLinkRules.MAX_LINKS_PER_USER; i++) stored(OTHER, "남 " + i, "https://o.example/" + i, i);
        service.add("me", req("내 첫 줄", "https://a.example"));

        assertThatThrownBy(() -> service.add("other", req("하나 더", "https://o.example/more")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.countByUserId(OTHER)).isEqualTo(SiteLinkRules.MAX_LINKS_PER_USER);
    }

    @Test
    @DisplayName("남의 행은 수정되지 않는다 — '없음'으로 답하고 그 행은 그대로다")
    void updateOthersRowIsNotFound() {
        SiteLink theirs = stored(OTHER, "남의 것", "https://secret.example", 0);

        assertThat(service.update("me", theirs.getId(), req("바꿈", "https://evil.example"))).isEmpty();

        SiteLink after = repository.findById(theirs.getId()).orElseThrow();
        assertThat(after.getName()).isEqualTo("남의 것");
        assertThat(after.getUrl()).isEqualTo("https://secret.example");
        assertThat(after.getUserId()).isEqualTo(OTHER);
    }

    @Test
    @DisplayName("내 행은 수정된다 — 주인은 바뀌지 않는다")
    void updateMine() {
        SiteLink mine = stored(ME, "옛 이름", "https://a.example", 0);

        Optional<SiteLinkDto> out = service.update("me", mine.getId(), req("새 이름", "https://b.example"));

        assertThat(out).isPresent();
        assertThat(out.get().name()).isEqualTo("새 이름");
        assertThat(out.get().url()).isEqualTo("https://b.example");
        assertThat(out.get().loginRequired()).isTrue();
        assertThat(out.get().sortOrder()).isEqualTo(3);
        assertThat(repository.findById(mine.getId()).orElseThrow().getUserId()).isEqualTo(ME);
    }

    @Test
    @DisplayName("수정할 때도 스크립트 주소는 거절하고 옛 값이 남는다")
    void updateRejectsScriptUrl() {
        SiteLink mine = stored(ME, "주식", "https://a.example", 0);

        assertThatThrownBy(() -> service.update("me", mine.getId(), req("새 이름", "data:text/html,x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(mine.getName()).as("거절되면 이름도 안 바뀐다").isEqualTo("주식");
        assertThat(mine.getUrl()).isEqualTo("https://a.example");
    }

    @Test
    @DisplayName("남의 행은 지워지지 않는다")
    void deleteOthersRowDoesNothing() {
        SiteLink theirs = stored(OTHER, "남의 것", "https://secret.example", 0);

        assertThat(service.delete("me", theirs.getId())).isFalse();
        assertThat(repository.findById(theirs.getId())).isPresent();
    }

    @Test
    @DisplayName("내 행은 지워진다")
    void deleteMine() {
        SiteLink mine = stored(ME, "주식", "https://a.example", 0);

        assertThat(service.delete("me", mine.getId())).isTrue();
        assertThat(repository.findById(mine.getId())).isEmpty();
    }

    @Test
    @DisplayName("모르는 사용자는 아무것도 못 한다")
    void unknownUser() {
        stored(ME, "주식", "https://a.example", 0);

        assertThatThrownBy(() -> service.list("ghost")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.add("ghost", req("x", "https://x.example"))).isInstanceOf(IllegalArgumentException.class);
    }
}
