package com.myplatform.backend.controller;

import com.myplatform.backend.dto.FileDto;
import com.myplatform.backend.dto.FolderContentDto;
import com.myplatform.backend.dto.FolderDto;
import com.myplatform.backend.entity.UserFile;
import com.myplatform.backend.repository.UserFileRepository;
import com.myplatform.backend.service.FileManagementService;
import com.myplatform.core.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;

@Tag(name = "파일 관리", description = "사용자별 파일/폴더 관리 API")
@RestController
@RequestMapping("/api/files")
@SecurityRequirement(name = "JWT Bearer")
public class FileManagementController {

    private final FileManagementService fileManagementService;
    private final UserFileRepository userFileRepository;

    public FileManagementController(FileManagementService fileManagementService,
                                   UserFileRepository userFileRepository) {
        this.fileManagementService = fileManagementService;
        this.userFileRepository = userFileRepository;
    }

    @Operation(summary = "폴더 내용 조회", description = "지정된 폴더의 하위 폴더와 파일 목록을 조회합니다.")
    @GetMapping("/folders")
    public ResponseEntity<ApiResponse<FolderContentDto>> getFolderContent(
            Authentication authentication,
            @Parameter(description = "폴더 ID (null이면 루트)")
            @RequestParam(required = false) Long folderId) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        FolderContentDto content = fileManagementService.getFolderContent(username, folderId);
        return ResponseEntity.ok(ApiResponse.success("폴더 내용 조회 성공", content));
    }

    @Operation(summary = "폴더 생성", description = "새로운 폴더를 생성합니다.")
    @PostMapping("/folders")
    public ResponseEntity<ApiResponse<FolderDto>> createFolder(
            Authentication authentication,
            @Parameter(description = "부모 폴더 ID (null이면 루트)")
            @RequestParam(required = false) Long parentId,
            @Parameter(description = "폴더명")
            @RequestParam String name) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        FolderDto folder = fileManagementService.createFolder(username, parentId, name);
        return ResponseEntity.ok(ApiResponse.success("폴더가 생성되었습니다.", folder));
    }

    @Operation(summary = "파일 업로드", description = "파일을 업로드합니다.")
    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<FileDto>> uploadFile(
            Authentication authentication,
            @Parameter(description = "폴더 ID (null이면 루트)")
            @RequestParam(required = false) Long folderId,
            @Parameter(description = "업로드할 파일")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "업로드 날짜 (YYYY-MM-DD)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate uploadDate) {
        try {
            String username = ((UserDetails) authentication.getPrincipal()).getUsername();
            FileDto uploadedFile = fileManagementService.uploadFile(username, folderId, file, uploadDate);
            return ResponseEntity.ok(ApiResponse.success("파일이 업로드되었습니다.", uploadedFile));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.fail("파일 업로드 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "폴더 삭제", description = "폴더와 하위 내용을 삭제합니다.")
    @DeleteMapping("/folders/{folderId}")
    public ResponseEntity<ApiResponse<String>> deleteFolder(
            Authentication authentication,
            @PathVariable Long folderId) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        fileManagementService.deleteFolder(username, folderId);
        return ResponseEntity.ok(ApiResponse.success("폴더가 삭제되었습니다.", null));
    }

    @Operation(summary = "파일 삭제", description = "파일을 삭제합니다.")
    @DeleteMapping("/{fileId}")
    public ResponseEntity<ApiResponse<String>> deleteFile(
            Authentication authentication,
            @PathVariable Long fileId) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        fileManagementService.deleteFile(username, fileId);
        return ResponseEntity.ok(ApiResponse.success("파일이 삭제되었습니다.", null));
    }

    @Operation(summary = "파일 다운로드", description = "파일을 다운로드합니다.")
    @GetMapping("/download/{fileId}")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long fileId) {
        try {
            UserFile file = userFileRepository.findById(fileId)
                    .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다."));

            Path filePath = Paths.get(file.getFilePath());
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists() || !resource.isReadable()) {
                throw new RuntimeException("파일을 읽을 수 없습니다.");
            }

            String encodedFileName = URLEncoder.encode(file.getOriginalName(), StandardCharsets.UTF_8)
                    .replace("+", "%20");

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(file.getFileType() != null ? file.getFileType() : "application/octet-stream"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                    .body(resource);
        } catch (Exception e) {
            throw new RuntimeException("파일 다운로드 실패: " + e.getMessage());
        }
    }
}

