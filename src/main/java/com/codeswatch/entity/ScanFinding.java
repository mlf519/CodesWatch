package com.codeswatch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "scan_finding")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private ScanProject project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private ScanTask task;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String codeSnippet;

    @Column(length = 100)
    private String filePath;

    @Column(length = 20)
    private String lineNumber;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private FindingSeverity severity;

    @Column(length = 100)
    @Enumerated(EnumType.STRING)
    private FindingType findingType;

    @Column(columnDefinition = "TEXT")
    private String exploitationPath;

    @Column(columnDefinition = "TEXT")
    private String suggestion;

    @Column(nullable = false)
    @Builder.Default
    private Boolean verified = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public enum FindingSeverity {
        CRITICAL, HIGH, MEDIUM, LOW, INFO
    }

    public enum FindingType {
        SQL_INJECTION, XSS, COMMAND_INJECTION, PATH_TRAVERSAL, SSRF, FILE_UPLOAD, DESERIALIZATION,
        INSECURE_DEPENDENCY, AUTH_BYPASS, CSRF, OPEN_REDIRECT, INFORMATION_DISCLOSURE, RACE_CONDITION
    }
}