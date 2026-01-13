package com.myplatform.backend.service;

import com.myplatform.backend.dto.FileDto;
import com.myplatform.backend.dto.FolderContentDto;
import com.myplatform.backend.dto.FolderDto;
import com.myplatform.backend.entity.UserFile;
import com.myplatform.backend.entity.UserFolder;
import com.myplatform.backend.repository.UserFileRepository;
import com.myplatform.backend.repository.UserFolderRepository;
import com.myplatform.backend.repository.UserRepository;
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
import java.util.UUID;

@Service
@Transactional
public class FileManagementService {

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
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

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
                    .orElseThrow(() -> new RuntimeException("폴더를 찾을 수 없습니다."));

            if (!folder.getUserId().equals(user.getId())) {
                throw new RuntimeException("권한이 없습니다.");
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
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

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
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        // 폴더 검증
        if (folderId != null) {
            UserFolder folder = folderRepository.findById(folderId)
                    .orElseThrow(() -> new RuntimeException("폴더를 찾을 수 없습니다."));
            if (!folder.getUserId().equals(user.getId())) {
                throw new RuntimeException("권한이 없습니다.");
            }
        }

        // 파일 저장
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : "";
        String storedFilename = UUID.randomUUID().toString() + extension;

        Path userDir = Paths.get(uploadDir, user.getId().toString());
        Files.createDirectories(userDir);

        Path filePath = userDir.resolve(storedFilename);
        Files.copy(file.getInputStream(), filePath);

        // DB 저장
        UserFile userFile = new UserFile();
        userFile.setUserId(user.getId());
        userFile.setFolderId(folderId);
        userFile.setOriginalName(originalFilename);
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
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        UserFolder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new RuntimeException("폴더를 찾을 수 없습니다."));

        if (!folder.getUserId().equals(user.getId())) {
            throw new RuntimeException("권한이 없습니다.");
        }

        folderRepository.delete(folder);
    }

    public void deleteFile(String username, Long fileId) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        UserFile file = fileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다."));

        if (!file.getUserId().equals(user.getId())) {
            throw new RuntimeException("권한이 없습니다.");
        }

        // 물리적 파일 삭제
        try {
            Files.deleteIfExists(Paths.get(file.getFilePath()));
        } catch (IOException e) {
            // 로그만 남기고 계속 진행
        }

        fileRepository.delete(file);
    }

    @Transactional(readOnly = true)
    public void validateFileOwnership(String username, Long fileId) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        UserFile file = fileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다."));

        if (!file.getUserId().equals(user.getId())) {
            throw new RuntimeException("권한이 없습니다.");
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

