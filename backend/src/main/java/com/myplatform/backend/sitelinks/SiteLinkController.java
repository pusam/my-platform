package com.myplatform.backend.sitelinks;

import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 내 사이트 바로가기 API — 화면 {@code /sites}.
 *
 * <p>로그인이 필요하다({@code SecurityConfig} 의 {@code /api/**} 규칙 — 공개 목록에 넣지 말 것, {@code ApiAccessPolicyTest} 가 고정).
 * 본인 행만 읽고 고친다. 주식 화면이 아닌 운영 편의 화면의 데이터 — 추천·봇·시그널과 무관하다.
 */
@Tag(name = "내 사이트 바로가기", description = "계정별 사이트 주소 모음")
@RestController
@RequestMapping("/api/site-links")
@SecurityRequirement(name = "JWT Bearer")
public class SiteLinkController {

    private static final String NOT_FOUND = "바로가기를 찾을 수 없습니다.";

    private final SiteLinkService service;

    public SiteLinkController(SiteLinkService service) {
        this.service = service;
    }

    @Operation(summary = "내 바로가기 목록")
    @GetMapping
    public ResponseEntity<ApiResponse<List<SiteLinkDto>>> list(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success("바로가기 조회 성공", service.list(authentication.getName())));
    }

    @Operation(summary = "바로가기 추가")
    @PostMapping
    public ResponseEntity<ApiResponse<SiteLinkDto>> add(Authentication authentication, @RequestBody SiteLinkRequest request) {
        return ResponseEntity.ok(ApiResponse.success("추가했습니다.", service.add(authentication.getName(), request)));
    }

    @Operation(summary = "바로가기 수정")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SiteLinkDto>> update(Authentication authentication, @PathVariable Long id,
                                                           @RequestBody SiteLinkRequest request) {
        return service.update(authentication.getName(), id, request)
                .map(dto -> ResponseEntity.ok(ApiResponse.success("수정했습니다.", dto)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(NOT_FOUND)));
    }

    @Operation(summary = "바로가기 삭제")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> delete(Authentication authentication, @PathVariable Long id) {
        if (!service.delete(authentication.getName(), id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(NOT_FOUND));
        }
        return ResponseEntity.ok(ApiResponse.success("삭제했습니다."));
    }
}
