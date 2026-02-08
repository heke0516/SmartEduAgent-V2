package com.example.smartedu.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
public class LearningProgress {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private Long taskId;
    private Long currentChapterId;
    private int currentChapterOrder;
    private boolean completed;
    private LocalDateTime lastUpdated = LocalDateTime.now();
    
    @ManyToOne
    @JoinColumn(name = "taskId", insertable = false, updatable = false)
    private LearningTask task;
    
    @ManyToOne
    @JoinColumn(name = "currentChapterId", insertable = false, updatable = false)
    private LearningChapter currentChapter;
}
