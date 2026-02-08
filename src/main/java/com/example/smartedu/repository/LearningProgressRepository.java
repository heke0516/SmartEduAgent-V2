package com.example.smartedu.repository;

import com.example.smartedu.entity.LearningProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface LearningProgressRepository extends JpaRepository<LearningProgress, Long> {
    Optional<LearningProgress> findByUserIdAndTaskId(Long userId, Long taskId);
}
