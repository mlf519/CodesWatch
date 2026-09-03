package com.codeswatch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "prompt_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    @Enumerated(EnumType.STRING)
    private ScanPhase phase;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(columnDefinition = "TEXT")
    private String analysisPrompt;

    /**
     * 全局配置专用：前缀提示词，拼接到分析提示词前面
     */
    @Column(columnDefinition = "TEXT")
    private String prefixPrompt;

    /**
     * 全局配置专用：后缀提示词，拼接到分析提示词后面
     */
    @Column(columnDefinition = "TEXT")
    private String suffixPrompt;

    @Column(columnDefinition = "TEXT")
    private String vulnerabilityTypes;

    @Column(columnDefinition = "TEXT")
    private String dangerousFunctionPatterns;

    /**
     * 漏洞类型专用提示词，JSON格式
     * 格式: {"SQL注入": "分析SQL注入的提示词...", "XSS": "分析XSS的提示词..."}
     */
    @Column(columnDefinition = "TEXT")
    private String vulnerabilityPrompts;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (enabled == null) {
            enabled = true;
        }
        if (displayOrder == null) {
            displayOrder = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum ScanPhase {
        GLOBAL_CONFIG,
        DEPENDENCY_SCAN,
        INTERFACE_SCAN,
        HIGH_RISK_OPERATION_SCAN
    }
}
