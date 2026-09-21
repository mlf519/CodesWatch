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
public class FindingDTO {

    private Long id;
    private String title;
    private String description;
    private String codeSnippet;
    private String filePath;
    private String lineNumber;
    private String severity;
    private String findingType;
    private String exploitationPath;
    private String suggestion;
    private Boolean verified;
    private LocalDateTime createdAt;
    /** 所属扫描批次号（同一项目多次扫描时用于区分批次） */
    private Integer scanRound;
}