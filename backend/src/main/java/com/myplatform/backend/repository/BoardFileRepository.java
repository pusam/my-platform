package com.myplatform.backend.repository;

import com.myplatform.backend.entity.BoardFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BoardFileRepository extends JpaRepository<BoardFile, Long> {

    List<BoardFile> findByBoardId(Long boardId);

    void deleteByBoardId(Long boardId);
}

