package com.myplatform.backend.controller;

import com.myplatform.backend.dto.BoardDto;
import com.myplatform.backend.dto.BoardRequest;
import com.myplatform.backend.entity.BoardFile;
import com.myplatform.backend.service.BoardService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(name = "Board", description = "게시판 API")
@RestController
@RequestMapping("/api/board")
public class BoardController {

    private final BoardService boardService;

    public BoardController(BoardService boardService) {
        this.boardService = boardService;
    }

    @Operation(summary = "게시글 목록 조회", description = "전체 게시글 목록을 조회합니다.")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<BoardDto>>> getAllBoards(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<BoardDto> boards = boardService.getAllBoards(pageable);
        return ResponseEntity.ok(ApiResponse.success(boards));
    }

    @Operation(summary = "내 게시글 조회", description = "내가 작성한 게시글 목록을 조회합니다.")
    @GetMapping("/my")
    public ResponseEntity<ApiResponse<Page<BoardDto>>> getMyBoards(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        Pageable pageable = PageRequest.of(page, size);
        Page<BoardDto> boards = boardService.getMyBoards(username, pageable);
        return ResponseEntity.ok(ApiResponse.success(boards));
    }

    @Operation(summary = "게시글 검색", description = "제목 또는 내용으로 게시글을 검색합니다.")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<Page<BoardDto>>> searchBoards(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<BoardDto> boards = boardService.searchBoards(keyword, pageable);
        return ResponseEntity.ok(ApiResponse.success(boards));
    }

    @Operation(summary = "게시글 상세 조회", description = "게시글 상세 정보를 조회합니다.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BoardDto>> getBoard(@PathVariable Long id) {
        BoardDto board = boardService.getBoard(id);
        return ResponseEntity.ok(ApiResponse.success(board));
    }

    @Operation(summary = "게시글 작성", description = "새 게시글을 작성합니다.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<BoardDto>> createBoard(
            @RequestPart("board") BoardRequest request,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            Authentication authentication) {

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        String username = userDetails.getUsername();

        // 사용자 이름은 UserDetails에서 가져오기 (임시로 username 사용)
        BoardDto board = boardService.createBoard(request, username, username, files);
        return ResponseEntity.ok(ApiResponse.success("게시글이 작성되었습니다.", board));
    }

    @Operation(summary = "게시글 수정", description = "게시글을 수정합니다.")
    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<BoardDto>> updateBoard(
            @PathVariable Long id,
            @RequestPart("board") BoardRequest request,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            Authentication authentication) {

        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        BoardDto board = boardService.updateBoard(id, request, username, files);
        return ResponseEntity.ok(ApiResponse.success("게시글이 수정되었습니다.", board));
    }

    @Operation(summary = "게시글 삭제", description = "게시글을 삭제합니다.")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deleteBoard(
            @PathVariable Long id,
            Authentication authentication) {

        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        boardService.deleteBoard(id, username);
        return ResponseEntity.ok(ApiResponse.success("게시글이 삭제되었습니다.", null));
    }

    @Operation(summary = "첨부파일 삭제", description = "게시글의 첨부파일을 삭제합니다.")
    @DeleteMapping("/{boardId}/file/{fileId}")
    public ResponseEntity<ApiResponse<String>> deleteFile(
            @PathVariable Long boardId,
            @PathVariable Long fileId,
            Authentication authentication) {

        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        boardService.deleteFile(boardId, fileId, username);
        return ResponseEntity.ok(ApiResponse.success("파일이 삭제되었습니다.", null));
    }

    @Operation(summary = "파일 다운로드", description = "첨부파일을 다운로드합니다.")
    @GetMapping("/file/{fileId}")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long fileId) {
        BoardFile file = boardService.getFile(fileId);

        File physicalFile = new File(file.getFilePath());
        if (!physicalFile.exists()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(physicalFile);
        String encodedFilename = URLEncoder.encode(file.getOriginalFilename(), StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedFilename + "\"")
                .body(resource);
    }
}

