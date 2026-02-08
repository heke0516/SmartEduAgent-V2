package com.example.smartedu.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long sessionId;
    
    @Column(length = 5000)
    private String content;
    
    private String role; // USER, AI
    private LocalDateTime timestamp = LocalDateTime.now();
}