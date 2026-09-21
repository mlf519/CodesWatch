package com.codeswatch.service;

import com.codeswatch.dto.*;
import com.codeswatch.entity.ScanFinding;
import com.codeswatch.entity.ScanProject;
import com.codeswatch.entity.ScanTask;
import com.codeswatch.repository.ScanFindingRepository;
import com.codeswatch.repository.ScanProjectRepository;
import com.codeswatch.repository.ScanTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectService {

    private final ScanProjectRepository projectRepository;
    private final ScanTaskRepository taskRepository;
    private final ScanFindingRepository findingRepository;
    private final TaskService taskService;

    @Transactional
    public ScanProjectDTO updateProject(Long id, CreateProjectRequest request) {
        ScanProject project = projectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        // 仅更新基本信息，源码通过 ZIP 上传接口替换
        project.setProjectName(request.getProjectName());
        project.setProjectDescription(request.getProjectDescription());

        project = projectRepository.save(project);
        return convertToDTO(project);
    }

    @Transactional(readOnly = true)
    public ScanProjectDTO getProject(Long id) {
        ScanProject project = projectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        return convertToDTO(project);
    }

    @Transactional(readOnly = true)
    public List<ScanProjectDTO> getAllProjects() {
        return projectRepository.findAllOrderByCreatedAtDesc().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ScanProjectDTO> searchProjects(String name) {
        return projectRepository.findByProjectNameContaining(name).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteProject(Long id) {
        ScanProject project = projectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        // 任务与项目解绑：删除项目不删除任务记录（任务保留在任务管理处，项目列显示"已删除项目"）
        taskRepository.detachProject(id);
        // 清空内存关联，避免 JPA 集合级联干扰（无 REMOVE 级联，不会删除任务）
        project.getTasks().clear();
        // 删除项目（日志、漏洞级联删除；任务已解绑保留）
        projectRepository.delete(project);
        log.info("删除项目 {}（{}），已保留其历史任务记录", id, project.getProjectName());
    }

    @Transactional(readOnly = true)
    public List<FindingDTO> getProjectFindings(Long projectId) {
        return findingRepository.findByProjectIdOrderBySeverityDesc(projectId).stream()
                .map(this::convertFindingToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DashboardDTO getDashboardStats() {
        long totalProjects = projectRepository.count();
        long completedProjects = projectRepository.countCompletedProjects();
        long totalFindings = projectRepository.sumTotalFindings() != null ? projectRepository.sumTotalFindings() : 0;
        long criticalFindings = projectRepository.countCriticalFindings() != null ? projectRepository.countCriticalFindings() : 0;
        long highFindings = projectRepository.countHighFindings() != null ? projectRepository.countHighFindings() : 0;
        long scanningProjects = projectRepository.findByStatus(ScanProject.ProjectStatus.SCANNING).size();

        return DashboardDTO.builder()
                .totalProjects(totalProjects)
                .completedProjects(completedProjects)
                .totalFindings(totalFindings)
                .criticalFindings(criticalFindings)
                .highFindings(highFindings)
                .scanningProjects(scanningProjects)
                .build();
    }

    public ScanProjectDTO convertToDTO(ScanProject project) {
        List<TaskDTO> tasks = taskRepository.findByProjectId(project.getId()).stream()
                .filter(t -> t.getParentTask() == null)
                .map(t -> convertTaskToDTO(t, false))
                .collect(Collectors.toList());

        List<FindingDTO> findings = findingRepository.findByProjectId(project.getId()).stream()
                .map(this::convertFindingToDTO)
                .collect(Collectors.toList());

        return ScanProjectDTO.builder()
                .id(project.getId())
                .projectName(project.getProjectName())
                .projectDescription(project.getProjectDescription())
                .status(project.getStatus().name())
                .projectCode(project.getProjectCode())
                .totalTasks(project.getTotalTasks())
                .completedTasks(project.getCompletedTasks())
                .findingCount(project.getFindingCount())
                .createdAt(project.getCreatedAt())
                .startedAt(project.getStartedAt())
                .completedAt(project.getCompletedAt())
                .tasks(tasks)
                .findings(findings)
                .codePath(project.getCodePath())
                .scanSessionId(project.getScanSessionId())
                .build();
    }

    private TaskDTO convertTaskToDTO(ScanTask task, boolean loadChildren) {
        List<TaskDTO> childTasks = null;
        if (loadChildren) {
            childTasks = taskRepository.findByParentTaskId(task.getId()).stream()
                    .map(t -> convertTaskToDTO(t, false))
                    .collect(Collectors.toList());
        }

        String projectName = "已删除项目";
        if (task.getProject() != null) {
            projectName = task.getProject().getProjectName();
        }

        // 与任务管理页口径一致：递归统计叶子任务（实际执行扫描的任务），已完成 = COMPLETED 的叶子任务数
        int totalTaskCount;
        int completedTaskCount;
        int[] leafStats = taskService.countLeafStats(task.getId());
        if (leafStats != null) {
            totalTaskCount = leafStats[0];
            completedTaskCount = leafStats[1];
        } else {
            totalTaskCount = 1;
            completedTaskCount = task.getStatus() == ScanTask.TaskStatus.COMPLETED ? 1 : 0;
        }

        return TaskDTO.builder()
                .id(task.getId())
                .taskName(task.getTaskName())
                .taskDescription(task.getTaskDescription())
                .taskType(task.getTaskType().name())
                .status(task.getStatus().name())
                .thinkingProcess(task.getThinkingProcess())
                .progress(task.getProgress())
                .completedTaskCount(completedTaskCount)
                .totalTaskCount(totalTaskCount)
                .createdAt(task.getCreatedAt())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .childTasks(childTasks)
                .projectName(projectName)
                .llmConfigId(task.getLlmConfigId())
                .llmConfigName(task.getLlmConfigName())
                .scanRound(task.getScanRound())
                .build();
    }

    private FindingDTO convertFindingToDTO(ScanFinding finding) {
        return FindingDTO.builder()
                .id(finding.getId())
                .title(finding.getTitle())
                .description(finding.getDescription())
                .codeSnippet(finding.getCodeSnippet())
                .filePath(finding.getFilePath())
                .lineNumber(finding.getLineNumber())
                .severity(finding.getSeverity().name())
                .findingType(finding.getFindingType().name())
                .exploitationPath(finding.getExploitationPath())
                .suggestion(finding.getSuggestion())
                .verified(finding.getVerified())
                .createdAt(finding.getCreatedAt())
                .scanRound(finding.getTask() != null ? finding.getTask().getScanRound() : null)
                .build();
    }
}