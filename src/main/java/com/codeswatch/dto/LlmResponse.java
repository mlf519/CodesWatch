package com.codeswatch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM响应DTO，包含思考过程和最终回答
 * 用于DeepSeek等支持思考模式的模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {
    /**
     * 思考过程（reasoning_content）
     */
    private String reasoningContent;
    
    /**
     * 最终回答内容（content）
     */
    private String content;
    
    /**
     * 是否有思考过程
     */
    public boolean hasReasoning() {
        return reasoningContent != null && !reasoningContent.isEmpty();
    }
}
