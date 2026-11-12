package com.myplatform.backend.sitelinks;

import com.myplatform.backend.repository.UserRepository;
import com.myplatform.core.exception.ErrorMessages;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 내 사이트 바로가기 — 본인 행만 읽고 고친다.
 *
 * <p>모든 메서드가 로그인한 사용자 이름에서 계정 id 를 구해 그 id 로만 조회한다({@code findByIdAndUserId}).
 * 남의 행 id 를 넣으면 '없음'으로 답한다 — 그 id 가 있는지조차 알려 주지 않는다.
 */
@Service
public class SiteLinkService {

    private final SiteLinkRepository repository;
    private final UserRepository userRepository;

    public SiteLinkService(SiteLinkRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    private Long userIdOf(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException(ErrorMessages.USER_NOT_FOUND))
                .getId();
    }

    @Transactional(readOnly = true)
    public List<SiteLinkDto> list(String username) {
        return repository.findByUserIdOrderBySortOrderAscIdAsc(userIdOf(username)).stream().map(SiteLinkDto::of).toList();
    }

    @Transactional
    public SiteLinkDto add(String username, SiteLinkRequest request) {
        Long userId = userIdOf(username);
        SiteLink link = new SiteLink();
        link.setUserId(userId);
        apply(link, request);
        if (repository.countByUserId(userId) >= SiteLinkRules.MAX_LINKS_PER_USER) {
            throw new IllegalArgumentException("바로가기는 " + SiteLinkRules.MAX_LINKS_PER_USER + "개까지 넣을 수 있습니다.");
        }
        return SiteLinkDto.of(repository.save(link));
    }

    /** 본인 행이 아니거나 없으면 비어 있다. */
    @Transactional
    public Optional<SiteLinkDto> update(String username, Long id, SiteLinkRequest request) {
        return repository.findByIdAndUserId(id, userIdOf(username)).map(link -> {
            apply(link, request);
            return SiteLinkDto.of(repository.save(link));
        });
    }

    /** 지웠으면 true. 본인 행이 아니거나 없으면 false. */
    @Transactional
    public boolean delete(String username, Long id) {
        Optional<SiteLink> link = repository.findByIdAndUserId(id, userIdOf(username));
        link.ifPresent(repository::delete);
        return link.isPresent();
    }

    private static void apply(SiteLink link, SiteLinkRequest request) {
        if (request == null) throw new IllegalArgumentException("내용이 비어 있습니다.");
        // 전부 검사한 뒤에 넣는다 — 중간에 거절되면 행에 반쯤 바뀐 값이 남지 않게.
        String name = SiteLinkRules.name(request.name());
        String description = SiteLinkRules.description(request.description());
        String url = SiteLinkRules.url(request.url());
        link.setName(name);
        link.setDescription(description);
        link.setUrl(url);
        link.setLoginRequired(Boolean.TRUE.equals(request.loginRequired()));
        link.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
    }
}
