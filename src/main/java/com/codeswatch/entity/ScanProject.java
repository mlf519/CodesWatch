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
@Table(name = "scan_project")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String projectName;

    @Column(columnDefinition = "TEXT")
    private String projectDescription;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private ProjectStatus status;

    @Column(columnDefinition = "LONGTEXT")
    private String projectCode;

    @Column(columnDefinition = "LONGTEXT")
    private String reportContent;

    @Column(nullable = false)
    @Builder.Default
    private Integer totalTasks = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer completedTasks = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer findingCount = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "code_path", length = 500)
    private String codePath;

    @Column(name = "scan_session_id", length = 100)
    private String scanSessionId;

    @Column(name = "last_llm_config_id")
    private Long lastLlmConfigId;

    /** 扫描次数（每发起一次扫描 +1，用于区分多次扫描的任务批次） */
    @Column(name = "scan_count", nullable = false)
    @Builder.Default
    private Integer scanCount = 0;

    // 任务与项目解绑：删除项目不同步删除任务，因此此处不含 REMOVE 级联与 orphanRemoval
    @OneToMany(mappedBy = "project", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @Builder.Default
    private List<ScanTask> tasks = new ArrayList<>();

    @OneToMany(mappedBy = "project", cascade = {CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REMOVE}, orphanRemoval = true)
    @Builder.Default
    private List<ScanLog> logs = new ArrayList<>();

    @OneToMany(mappedBy = "project", cascade = {CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REMOVE}, orphanRemoval = true)
    @Builder.Default
    private List<ScanFinding> findings = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = ProjectStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum ProjectStatus {
        PENDING, SCANNING, PAUSED, COMPLETED, FAILED, CANCELLED
    }
}
