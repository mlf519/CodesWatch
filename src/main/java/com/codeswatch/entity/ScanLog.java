package com.codeswatch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "scan_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private ScanProject project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private ScanTask task;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private LogType logType;

    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public enum LogType {
        THOUGHT, ACTION, OBSERVATION, INFO, WARNING, ERROR, TASK_START, TASK_COMPLETE, FINDING,
        LLM_REQUEST, LLM_RESPONSE, LLM_THINKING, FILE_CONTEXT
    }
}