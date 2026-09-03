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
public class ScanProjectDTO {

    private Long id;
    private String projectName;
    private String projectDescription;
    private String status;
    private String projectCode;
    private Integer totalTasks;
    private Integer completedTasks;
    private Integer findingCount;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private List<TaskDTO> tasks;
    private List<FindingDTO> findings;
    private String codePath;
    private String scanSessionId;
}