package com.codeswatch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptConfigDTO {

    private Long id;
    private String phase;
    private String name;
    private String systemPrompt;
    private String analysisPrompt;
    private String prefixPrompt;
    private String suffixPrompt;
    private String vulnerabilityTypes;
    private String vulnerabilityPrompts;
    private String dangerousFunctionPatterns;
    private Boolean enabled;
    private Integer displayOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
