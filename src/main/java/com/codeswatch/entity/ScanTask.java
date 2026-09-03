package com.codeswatch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "scan_task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private ScanProject project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private ScanTask parentTask;

    @Column(name = "parent_id", insertable = false, updatable = false)
    private Long parentTaskId;

    @OneToMany(mappedBy = "parentTask", cascade = {CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REMOVE}, orphanRemoval = true)
    @Builder.Default
    private List<ScanTask> childTasks = new ArrayList<>();

    @OneToMany(mappedBy = "task", cascade = {CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REMOVE}, orphanRemoval = true)
    @Builder.Default
    private List<ScanLog> logs = new ArrayList<>();

    @OneToMany(mappedBy = "task", cascade = {CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REMOVE}, orphanRemoval = true)
    @Builder.Default
    private List<ScanFinding> findings = new ArrayList<>();

    @Column(nullable = false, length = 500)
    private String taskName;

    @Column(columnDefinition = "TEXT")
    private String taskDescription;

    @Column(nullable = false, length = 50, columnDefinition = "VARCHAR(50)")
    @Enumerated(EnumType.STRING)
    private TaskType taskType;

    @Column(nullable = false, length = 50, columnDefinition = "VARCHAR(50)")
    @Enumerated(EnumType.STRING)
    private TaskStatus status;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String targetCode;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String llmRequest;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String llmResponse;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String thinkingProcess;

    @Column(name = "has_vulnerability")
    private Boolean hasVulnerability;

    @Column(length = 50)
    private String result;

    @Column(columnDefinition = "TEXT")
    private String analysisResult;

    @Column(nullable = false)
    private Integer progress = 0;

    @Column(name = "llm_config_id")
    private Long llmConfigId;

    @Column(name = "llm_config_name", length = 255)
    private String llmConfigName;

    @Column(name = "prompt_config_id")
    private Long promptConfigId;

    @Column(name = "file_path", length = 500)
    private String filePath;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = TaskStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum TaskType {
        DEPENDENCY_SCAN, INTERFACE_SCAN, HIGH_RISK_OPERATION_SCAN, 
        SUB_DEPENDENCY_SCAN, SUB_INTERFACE_SCAN, SUB_HIGH_RISK_SCAN,
        SUB_INTERFACE_VULNERABILITY_SCAN
    }
    
    public static final String[] VULNERABILITY_TYPES = {
        "SQL_INJECTION", "XSS", "COMMAND_INJECTION", "PATH_TRAVERSAL", 
        "SSRF", "FILE_UPLOAD", "DESERIALIZATION", "AUTH_BYPASS", 
        "CSRF", "OPEN_REDIRECT", "INFORMATION_DISCLOSURE", "RACE_CONDITION"
    };

    public enum TaskStatus {
        PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
    }
}