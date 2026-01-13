package com.myplatform.backend.repository;
import com.myplatform.backend.entity.UserFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
@Repository
public interface UserFileRepository extends JpaRepository<UserFile, Long> {
    List<UserFile> findByUserIdAndFolderIdIsNull(Long userId);
    List<UserFile> findByUserIdAndFolderId(Long userId, Long folderId);
    List<UserFile> findByUserId(Long userId);
    List<UserFile> findByUserIdAndUploadDate(Long userId, LocalDate uploadDate);
    @Query("SELECT f FROM UserFile f WHERE f.userId = :userId AND f.fileType LIKE 'image/%'")
    List<UserFile> findImagesByUserId(@Param("userId") Long userId);
    @Query("SELECT SUM(f.fileSize) FROM UserFile f WHERE f.userId = :userId")
    Long getTotalFileSizeByUserId(@Param("userId") Long userId);
}