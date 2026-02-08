package com.example.smartedu.repository;

import com.example.smartedu.entity.LearningTask;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningTaskRepository extends JpaRepository<LearningTask, Long> {
}
