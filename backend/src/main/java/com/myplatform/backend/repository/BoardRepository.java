package com.myplatform.backend.repository;

import com.myplatform.backend.entity.Board;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface BoardRepository extends JpaRepository<Board, Long> {

    Page<Board> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Board> findByAuthorOrderByCreatedAtDesc(String author, Pageable pageable);

    @Query("SELECT b FROM Board b WHERE b.title LIKE %:keyword% OR b.content LIKE %:keyword% ORDER BY b.createdAt DESC")
    Page<Board> searchByKeyword(String keyword, Pageable pageable);

    // 관리자 통계용
    Long countByAuthor(String author);
    Long countByCreatedAtAfter(LocalDateTime dateTime);
    Long countByUserId(Long userId);
}

