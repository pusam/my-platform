package com.myplatform.backend.service;

import com.myplatform.backend.dto.FileDto;
import com.myplatform.backend.dto.FolderContentDto;
import com.myplatform.backend.dto.FolderDto;
import com.myplatform.backend.entity.UserFile;
import com.myplatform.backend.entity.UserFolder;
import com.myplatform.backend.repository.UserFileRepository;
import com.myplatform.backend.repository.UserFolderRepository;
import com.myplatform.backend.repository.UserRepository;
import com.myplatform.core.exception.ErrorMessages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@Transactional
public class FileManagementService {

    // 차단할 위험 파일 확장자
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            ".exe", ".bat", ".cmd", ".com", ".msi", ".scr", ".pif",
            ".vbs", ".vbe", ".js", ".jse", ".wsf", ".wsh", ".ps1",
            ".sh", ".bash", ".csh", ".ksh",
            ".jsp", ".jspx", ".asp", ".aspx", ".php", ".cgi", ".pl",
            ".py", ".rb", ".jar", ".war", ".class",
            ".dll", ".sys", ".drv", ".reg",
            ".hta", ".inf", ".lnk"
    );

    private final UserFolderRepository folderRepository;
    private final UserFileRepository fileRepository;
    private final UserRepository userRepository;

    @Value("${file.upload.dir:uploads}")
    private String uploadDir;

    public FileManagementService(UserFolderRepository folderRepository,
                                UserFileRepository fileRepository,
                                UserRepository userRepository) {
        this.folderRepository = folderRepository;
        this.fileRepository = fileRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public FolderContentDto getFolderContent(String username, Long folderId) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException(ErrorMessages.USER_NOT_FOUND));

        FolderContentDto content = new FolderContentDto();

        if (folderId == null) {
            // 루트 폴더
            content.setFolders(folderRepository.findByUserIdAndParentIdIsNull(user.getId())
                    .stream().map(this::convertFolderToDto).toList());
            content.setFiles(fileRepository.findByUserIdAndFolderIdIsNull(user.getId())
                    .stream().map(this::convertFileToDto).toList());
            content.setBreadcrumbs(new ArrayList<>());
        } else {
            // 특정 폴더
            UserFolder folder = folderRepository.findById(folderId)
                    .orElseThrow(() -> new RuntimeException(ErrorMessages.FOLDER_NOT_FOUND));

            if (!folder.getUserId().equals(user.getId())) {
                throw new RuntimeException(ErrorMessages.FORBIDDEN);
            }

            content.setCurrentFolder(convertFolderToDto(folder));
            content.setFolders(folderRepository.findByUserIdAndParentId(user.getId(), folderId)
                    .stream().map(this::convertFolderToDto).toList());
            content.setFiles(fileRepository.findByUserIdAndFolderId(user.getId(), folderId)
                    .stream().map(this::convertFileToDto).toList());
            content.setBreadcrumbs(buildBreadcrumbs(folder));
        }

        return content;
    }

    public FolderDto createFolder(String username, Long parentId, String folderName) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException(ErrorMessages.USER_NOT_FOUND));

        // 중복 체크
        var existing = folderRepository.findByUserIdAndParentIdAndName(user.getId(), parentId, folderName);
        if (existing.isPresent()) {
            throw new RuntimeException("같은 이름의 폴더가 이미 존재합니다.");
        }

        UserFolder folder = new UserFolder();
        folder.setUserId(user.getId());
        folder.setParentId(parentId);
        folder.setName(folderName);

        // 경로 생성
        if (parentId == null) {
            folder.setPath("/" + folderName);
        } else {
            UserFolder parent = folderRepository.findById(parentId)
                    .orElseThrow(() -> new RuntimeException("상위 폴더를 찾을 수 없습니다."));
            folder.setPath(parent.getPath() + "/" + folderName);
        }

        UserFolder saved = folderRepository.save(folder);
        return convertFolderToDto(saved);
    }

    public FileDto uploadFile(String username, Long folderId, MultipartFile file, LocalDate uploadDate) throws IOException {
        // 보안 검증 — 실행 파일/서버 코드/XSS 위험 형식 차단 (+ 경로 traversal 도 함께 검사).
        com.myplatform.backend.util.FileUploadValidator.validate(file);

        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException(ErrorMessages.USER_NOT_FOUND));

        // 폴더 검증
        if (folderId != null) {
            UserFolder folder = folderRepository.findById(folderId)
                    .orElseThrow(() -> new RuntimeException(ErrorMessages.FOLDER_NOT_FOUND));
            if (!folder.getUserId().equals(user.getId())) {
                throw new RuntimeException(ErrorMessages.FORBIDDEN);
            }
        }

        // 파일명 검증
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new RuntimeException("파일 이름이 없습니다.");
        }
        // 경로 순회 방지
        if (originalFilename.contains("..") || originalFilename.contains("/") || originalFilename.contains("\\")) {
            throw new RuntimeException("잘못된 파일 이름입니다.");
        }

        String extension = originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase()
                : "";

        // 위험 파일 확장자 차단
        if (BLOCKED_EXTENSIONS.contains(extension)) {
            throw new RuntimeException("보안상 업로드할 수 없는 파일 형식입니다: " + extension);
        }

        Path userDir = Paths.get(uploadDir, user.getId().toString());
        Files.createDirectories(userDir);

        // 동일 파일명 존재 시 덮어쓰기
        var existing = folderId != null
                ? fileRepository.findByUserIdAndFolderIdAndOriginalName(user.getId(), folderId, originalFilename)
                : fileRepository.findByUserIdAndFolderIdIsNullAndOriginalName(user.getId(), originalFilename);

        UserFile userFile;
        if (existing.isPresent()) {
            // 기존 파일 물리적 삭제 후 덮어쓰기
            userFile = existing.get();
            try {
                Files.deleteIfExists(Paths.get(userFile.getFilePath()));
            } catch (IOException e) {
                log.warn("기존 파일 삭제 실패: {}", e.getMessage());
            }
        } else {
            userFile = new UserFile();
            userFile.setUserId(user.getId());
            userFile.setFolderId(folderId);
            userFile.setOriginalName(originalFilename);
        }

        String storedFilename = UUID.randomUUID().toString() + extension;
        Path filePath = userDir.resolve(storedFilename).normalize();
        // 경로 순회 최종 방어
        if (!filePath.startsWith(userDir.normalize())) {
            throw new RuntimeException("잘못된 파일 경로입니다.");
        }
        Files.copy(file.getInputStream(), filePath);

        userFile.setStoredName(storedFilename);
        userFile.setFilePath(filePath.toString());
        userFile.setFileSize(file.getSize());
        userFile.setFileType(file.getContentType());
        userFile.setFileExtension(extension);
        userFile.setUploadDate(uploadDate != null ? uploadDate : LocalDate.now());

        UserFile saved = fileRepository.save(userFile);
        return convertFileToDto(saved);
    }

    public void deleteFolder(String username, Long folderId) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException(ErrorMessages.USER_NOT_FOUND));

        UserFolder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException(ErrorMessages.FOLDER_NOT_FOUND));

        if (!folder.getUserId().equals(user.getId())) {
            throw new RuntimeException(ErrorMessages.FORBIDDEN);
        }

        folderRepository.delete(folder);
    }

    public void deleteFile(String username, Long fileId) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException(ErrorMessages.USER_NOT_FOUND));

        UserFile file = fileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException(ErrorMessages.FILE_NOT_FOUND));

        if (!file.getUserId().equals(user.getId())) {
            throw new RuntimeException(ErrorMessages.FORBIDDEN);
        }

        // 물리적 파일 삭제
        try {
            Files.deleteIfExists(Paths.get(file.getFilePath()));
        } catch (IOException e) {
            log.warn("파일 삭제 실패 (fileId={}, path={}): {}", fileId, file.getFilePath(), e.getMessage());
        }

        fileRepository.delete(file);
    }

    @Transactional(readOnly = true)
    public void validateFileOwnership(String username, Long fileId) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException(ErrorMessages.USER_NOT_FOUND));

        UserFile file = fileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException(ErrorMessages.FILE_NOT_FOUND));

        if (!file.getUserId().equals(user.getId())) {
            throw new RuntimeException(ErrorMessages.FORBIDDEN);
        }
    }

    private FolderDto convertFolderToDto(UserFolder folder) {
        FolderDto dto = new FolderDto();
        dto.setId(folder.getId());
        dto.setName(folder.getName());
        dto.setParentId(folder.getParentId());
        dto.setPath(folder.getPath());
        dto.setCreatedAt(folder.getCreatedAt());
        return dto;
    }

    private FileDto convertFileToDto(UserFile file) {
        FileDto dto = new FileDto();
        dto.setId(file.getId());
        dto.setOriginalName(file.getOriginalName());
        dto.setFileSize(file.getFileSize());
        dto.setFileType(file.getFileType());
        dto.setFileExtension(file.getFileExtension());
        dto.setFolderId(file.getFolderId());
        dto.setThumbnailPath(file.getThumbnailPath());
        dto.setDescription(file.getDescription());
        dto.setUploadDate(file.getUploadDate());
        dto.setCreatedAt(file.getCreatedAt());
        dto.setDownloadUrl("/api/files/download/" + file.getId());
        return dto;
    }

    private List<FolderDto> buildBreadcrumbs(UserFolder folder) {
        List<FolderDto> breadcrumbs = new ArrayList<>();
        UserFolder current = folder;

        while (current != null) {
            breadcrumbs.add(0, convertFolderToDto(current));
            if (current.getParentId() == null) {
                break;
            }
            current = folderRepository.findById(current.getParentId()).orElse(null);
        }

        return breadcrumbs;
    }
}

