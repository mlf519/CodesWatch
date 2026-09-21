package com.codeswatch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskDTO {

    private Long id;
    private String taskName;
    private String taskDescription;
    private String taskType;
    private String status;
    private String llmRequest;
    private String llmResponse;
    private String thinkingProcess;
    private Boolean hasVulnerability;
    private String result;
    private String analysisResult;
    private Integer progress;
    /** 已完成任务数（叶子任务完成时为 1） */
    private Integer completedTaskCount;
    /** 任务总数（有子任务时为子任务数，叶子任务为 1） */
    private Integer totalTaskCount;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private List<TaskDTO> childTasks;
    private String projectName;
    private Long llmConfigId;
    private String llmConfigName;
    /** 扫描批次号（同一项目多次扫描时用于区分批次） */
    private Integer scanRound;
    private List<LogEntryDTO> logs;
    private List<ConversationEntryDTO> conversationHistory;
}