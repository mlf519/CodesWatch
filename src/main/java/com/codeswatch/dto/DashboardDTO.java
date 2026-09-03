package com.codeswatch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardDTO {

    private Long totalProjects;
    private Long completedProjects;
    private Long totalFindings;
    private Long criticalFindings;
    private Long highFindings;
    private Long scanningProjects;
}