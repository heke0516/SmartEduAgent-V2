package com.example.smartedu.repository;

import com.example.smartedu.entity.LearningChapter;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LearningChapterRepository extends JpaRepository<LearningChapter, Long> {
    List<LearningChapter> findByTaskIdOrderByChapterOrderAsc(Long taskId);
}
