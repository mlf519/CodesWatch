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
public class ConversationEntryDTO {

    private Long taskId;
    private String taskName;
    private String taskType;
    private String thinkingProcess;
    private String llmRequest;
    private String llmResponse;
    private LocalDateTime completedAt;
}
