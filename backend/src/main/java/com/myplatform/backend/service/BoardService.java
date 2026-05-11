package com.myplatform.backend.service;

import com.myplatform.backend.dto.BoardDto;
import com.myplatform.backend.dto.BoardRequest;
import com.myplatform.backend.entity.Board;
import com.myplatform.backend.entity.BoardFile;
import com.myplatform.backend.repository.BoardRepository;
import com.myplatform.backend.repository.BoardFileRepository;
import com.myplatform.core.exception.ErrorMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class BoardService {

    private static final Logger log = LoggerFactory.getLogger(BoardService.class);

    private final BoardRepository boardRepository;
    private final BoardFileRepository boardFileRepository;

    @Value("${file.upload.dir:uploads}")
    private String uploadDir;

    public BoardService(BoardRepository boardRepository, BoardFileRepository boardFileRepository) {
        this.boardRepository = boardRepository;
        this.boardFileRepository = boardFileRepository;
    }

    @Transactional(readOnly = true)
    public Page<BoardDto> getAllBoards(Pageable pageable) {
        return boardRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(this::convertToDto);
    }

    @Transactional(readOnly = true)
    public Page<BoardDto> getMyBoards(String username, Pageable pageable) {
        return boardRepository.findByAuthorOrderByCreatedAtDesc(username, pageable)
                .map(this::convertToDto);
    }

    @Transactional(readOnly = true)
    public Page<BoardDto> searchBoards(String keyword, Pageable pageable) {
        String safeKeyword = escapeLikeKeyword(keyword);
        return boardRepository.searchByKeyword(safeKeyword, pageable)
                .map(this::convertToDto);
    }

    private String escapeLikeKeyword(String keyword) {
        if (keyword == null) return "";
        return keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    @Transactional
    public BoardDto getBoard(Long id) {
        // @EntityGraph 로 files 함께 fetch — convertToDto가 board.getFiles() 접근 시 N+1 방지
        Board board = boardRepository.findWithFilesById(id)
                .orElseThrow(() -> new RuntimeException(ErrorMessages.BOARD_NOT_FOUND));

        board.incrementViews();
        boardRepository.save(board);

        return convertToDto(board);
    }

    public BoardDto createBoard(BoardRequest request, String username, String name, List<MultipartFile> files) {
        Board board = new Board();
        board.setTitle(request.getTitle());
        board.setContent(request.getContent());
        board.setAuthor(username);
        board.setAuthorName(name);

        Board savedBoard = boardRepository.save(board);

        if (files != null && !files.isEmpty()) {
            for (MultipartFile file : files) {
                if (!file.isEmpty()) {
                    try {
                        BoardFile boardFile = saveFile(file, savedBoard);
                        savedBoard.addFile(boardFile);
                    } catch (IOException e) {
                        throw new RuntimeException("파일 업로드 중 오류가 발생했습니다.", e);
                    }
                }
            }
        }

        return convertToDto(savedBoard);
    }

    public BoardDto updateBoard(Long id, BoardRequest request, String username, List<MultipartFile> files) {
        Board board = boardRepository.findById(id)
                .orElseThrow(() -> new RuntimeException(ErrorMessages.BOARD_NOT_FOUND));

        if (!board.getAuthor().equals(username)) {
            throw new RuntimeException("수정 권한이 없습니다.");
        }

        board.setTitle(request.getTitle());
        board.setContent(request.getContent());

        if (files != null && !files.isEmpty()) {
            for (MultipartFile file : files) {
                if (!file.isEmpty()) {
                    try {
                        BoardFile boardFile = saveFile(file, board);
                        board.addFile(boardFile);
                    } catch (IOException e) {
                        throw new RuntimeException("파일 업로드 중 오류가 발생했습니다.", e);
                    }
                }
            }
        }

        Board updatedBoard = boardRepository.save(board);
        return convertToDto(updatedBoard);
    }

    public void deleteBoard(Long id, String username) {
        // files도 함께 fetch — 삭제 직전 물리 파일 정리 시 LAZY N+1 방지
        Board board = boardRepository.findWithFilesById(id)
                .orElseThrow(() -> new RuntimeException(ErrorMessages.BOARD_NOT_FOUND));

        if (!board.getAuthor().equals(username)) {
            throw new RuntimeException("삭제 권한이 없습니다.");
        }

        // 파일 삭제
        List<BoardFile> files = board.getFiles();
        for (BoardFile file : files) {
            deletePhysicalFile(file.getFilePath());
        }

        boardRepository.delete(board);
    }

    public void deleteFile(Long boardId, Long fileId, String username) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new RuntimeException(ErrorMessages.BOARD_NOT_FOUND));

        if (!board.getAuthor().equals(username)) {
            throw new RuntimeException("삭제 권한이 없습니다.");
        }

        BoardFile file = boardFileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException(ErrorMessages.FILE_NOT_FOUND));

        deletePhysicalFile(file.getFilePath());
        boardFileRepository.delete(file);
    }

    private BoardFile saveFile(MultipartFile file, Board board) throws IOException {
        // 보안 검증 — 실행 파일/서버 코드/XSS 위험 형식 차단.
        com.myplatform.backend.util.FileUploadValidator.validate(file);

        // 업로드 디렉토리 생성
        String dateDir = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Path uploadPath = Paths.get(uploadDir, dateDir);

        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        // 고유한 파일명 생성
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String storedFilename = UUID.randomUUID() + extension;

        // 파일 저장
        Path filePath = uploadPath.resolve(storedFilename);
        file.transferTo(filePath.toFile());

        // BoardFile 엔티티 생성
        BoardFile boardFile = new BoardFile();
        boardFile.setBoard(board);
        boardFile.setOriginalFilename(originalFilename);
        boardFile.setStoredFilename(storedFilename);
        boardFile.setFilePath(filePath.toString());
        boardFile.setFileSize(file.getSize());
        boardFile.setContentType(file.getContentType());

        return boardFileRepository.save(boardFile);
    }

    private void deletePhysicalFile(String filePath) {
        try {
            File file = new File(filePath);
            if (file.exists()) {
                boolean deleted = file.delete();
                if (!deleted) {
                    log.warn("파일 삭제 실패: {}", filePath);
                }
            }
        } catch (Exception e) {
            // 파일 삭제 실패는 로그만 남기고 계속 진행
            log.warn("파일 삭제 오류: {} ({})", filePath, e.getMessage());
        }
    }

    private BoardDto convertToDto(Board board) {
        BoardDto dto = new BoardDto();
        dto.setId(board.getId());
        dto.setTitle(board.getTitle());
        dto.setContent(board.getContent());
        dto.setAuthor(board.getAuthor());
        dto.setAuthorName(board.getAuthorName());
        dto.setViews(board.getViews());
        dto.setCreatedAt(board.getCreatedAt());
        dto.setUpdatedAt(board.getUpdatedAt());

        List<BoardDto.FileDto> fileDtos = board.getFiles().stream()
                .map(file -> new BoardDto.FileDto(
                        file.getId(),
                        file.getOriginalFilename(),
                        file.getFileSize(),
                        file.getContentType()
                ))
                .collect(Collectors.toList());
        dto.setFiles(fileDtos);

        return dto;
    }

    @Transactional(readOnly = true)
    public BoardFile getFile(Long fileId) {
        return boardFileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException(ErrorMessages.FILE_NOT_FOUND));
    }
}

