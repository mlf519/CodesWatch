package com.codeswatch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "llm_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 配置名称：用户自定义，用于区分同一厂商的多个配置
     */
    @Column(length = 100)
    private String configName;

    /**
     * 厂商类型（openai/qwen 等）：同一厂商可存在多条配置
     */
    @Column(nullable = false, length = 50)
    private String providerName;

    @Column(length = 255)
    private String baseUrl;

    @Column(length = 500)
    private String apiKey;

    @Column(length = 100)
    private String model;

    /**
     * 是否支持思考模式（DeepSeek-R1 等推理模型）：决定请求体构造与响应解析方式
     */
    @Column(name = "thinking_enabled", nullable = false)
    @Builder.Default
    private Boolean thinkingEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}