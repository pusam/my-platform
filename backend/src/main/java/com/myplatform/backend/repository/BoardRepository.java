package com.myplatform.backend.repository;

import com.myplatform.backend.entity.Board;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface BoardRepository extends JpaRepository<Board, Long> {

    Page<Board> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Board> findByAuthorOrderByCreatedAtDesc(String author, Pageable pageable);

    @Query("SELECT b FROM Board b WHERE b.title LIKE CONCAT('%', :keyword, '%') ESCAPE '\\' OR b.content LIKE CONCAT('%', :keyword, '%') ESCAPE '\\' ORDER BY b.createdAt DESC")
    Page<Board> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * Board + files 함께 fetch — getBoard/deleteBoard 의 LAZY N+1 방지
     */
    @EntityGraph(attributePaths = "files")
    Optional<Board> findWithFilesById(Long id);

    // 관리자 통계용
    Long countByAuthor(String author);
    Long countByCreatedAtAfter(LocalDateTime dateTime);
}

