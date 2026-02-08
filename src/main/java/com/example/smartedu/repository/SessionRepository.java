package com.example.smartedu.repository;
import com.example.smartedu.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SessionRepository extends JpaRepository<ChatSession, Long> {
    List<ChatSession> findAllByOrderByCreatedAtDesc();
}