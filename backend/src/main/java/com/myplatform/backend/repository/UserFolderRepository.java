package com.myplatform.backend.repository;
import com.myplatform.backend.entity.UserFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
@Repository
public interface UserFolderRepository extends JpaRepository<UserFolder, Long> {
    List<UserFolder> findByUserIdAndParentIdIsNull(Long userId);
    List<UserFolder> findByUserIdAndParentId(Long userId, Long parentId);
    Optional<UserFolder> findByUserIdAndParentIdAndName(Long userId, Long parentId, String name);
    List<UserFolder> findByUserId(Long userId);
}