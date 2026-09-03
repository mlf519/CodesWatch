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
public class LogEntryDTO {

    private Long id;
    private String logType;
    private String message;
    private LocalDateTime createdAt;
    private Long taskId;
    private String taskName;
}