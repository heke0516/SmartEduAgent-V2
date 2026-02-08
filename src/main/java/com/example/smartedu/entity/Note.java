package com.example.smartedu.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
public class Note {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String title;
    
    @Column(length = 10000)
    private String content;
    
    private String fileName;
    private LocalDateTime createdAt = LocalDateTime.now();
}
