package com.codeswatch.service;

import com.codeswatch.dto.ConversationEntryDTO;
import com.codeswatch.dto.LogEntryDTO;
import com.codeswatch.dto.TaskDTO;
import com.codeswatch.entity.ScanLog;
import com.codeswatch.entity.ScanProject;
import com.codeswatch.entity.ScanTask;
import com.codeswatch.repository.ScanLogRepository;
import com.codeswatch.repository.ScanProjectRepository;
import com.codeswatch.repository.ScanTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TaskService {

    private final ScanTaskRepository taskRepository;
    private final ScanProjectRepository projectRepository;
    private final ScanLogRepository logRepository;

    @Autowired
    @Lazy
    private ScanEngineService scanEngineService;

    private final Map<Long, TaskRuntimeState> runningTasks = new ConcurrentHashMap<>();
    private final Map<Long, SseEmitter> taskEmitters = new ConcurrentHashMap<>();

    public void registerTask(Long projectId, SseEmitter emitter) {
        runningTasks.put(projectId, new TaskRuntimeState());
        if (emitter != null) {
            taskEmitters.put(projectId, emitter);
            emitter.onCompletion(() -> taskEmitters.remove(projectId));
            emitter.onTimeout(() -> taskEmitters.remove(projectId));
            emitter.onError(e -> taskEmitters.remove(projectId));
        }
        log.info("注册任务: projectId={}", projectId);
    }

    public void unregisterTask(Long projectId) {
        runningTasks.remove(projectId);
        taskEmitters.remove(projectId);
        log.info("注销任务: projectId={}", projectId);
    }

    public boolean isPaused(Long projectId) {
        TaskRuntimeState state = runningTasks.get(projectId);
        return state != null && state.paused;
    }

    public boolean isCancelled(Long projectId) {
        TaskRuntimeState state = runningTasks.get(projectId);
        return state != null && state.cancelled;
    }

    public void checkPauseCancel(Long projectId) {
        TaskRuntimeState state = runningTasks.get(projectId);
        if (state != null) {
            synchronized (state) {
                while (state.paused && !state.cancelled) {
                    try {
                        state.wait(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (state.cancelled) {
                    throw new RuntimeException("任务已取消");
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public List<TaskDTO> getAllTasks() {
        return taskRepository.findAll().stream()
                .filter(t -> t.getParentTask() == null)
                .map(t -> convertToDTO(t, false))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TaskDTO> getActiveTasks() {
        return taskRepository.findAll().stream()
                .filter(t -> t.getParentTask() == null)
                .filter(t -> t.getStatus() == ScanTask.TaskStatus.RUNNING
                        || t.getStatus() == ScanTask.TaskStatus.PENDING)
                .map(t -> convertToDTO(t, false))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TaskDTO> getCompletedTasks() {
        return taskRepository.findAll().stream()
                .filter(t -> t.getParentTask() == null)
                .filter(t -> t.getStatus() == ScanTask.TaskStatus.COMPLETED
                        || t.getStatus() == ScanTask.TaskStatus.FAILED
                        || t.getStatus() == ScanTask.TaskStatus.CANCELLED)
                .map(t -> convertToDTO(t, false))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TaskDTO getTask(Long id) {
        ScanTask task = taskRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("任务不存在"));
        return convertToDTO(task, true);
    }

    @Transactional(readOnly = true)
    public List<TaskDTO> getChildTasks(Long parentId) {
        // 使用 JOIN FETCH 确保加载 project 关联
        List<ScanTask> children = taskRepository.findChildrenWithProject(parentId);
        return children.stream()
                .map(t -> convertToDTO(t, false))
                .collect(Collectors.toList());
    }

    @Transactional
    public TaskDTO pauseTask(Long id) {
        ScanTask task = taskRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("任务不存在"));
        if (task.getStatus() != ScanTask.TaskStatus.RUNNING) {
            throw new RuntimeException("只能暂停运行中的任务");
        }
        task.setStatus(ScanTask.TaskStatus.PENDING);
        taskRepository.saveAndFlush(task);

        Long projectId = task.getProject().getId();
        TaskRuntimeState state = runningTasks.get(projectId);
        if (state != null) {
            synchronized (state) {
                state.paused = true;
                state.notifyAll();
            }
        }

        projectRepository.findById(projectId).ifPresent(p -> {
            p.setStatus(ScanProject.ProjectStatus.PAUSED);
            projectRepository.saveAndFlush(p);
        });

        sendEvent(projectId, "paused", "任务已暂停");
        return convertToDTO(task, false);
    }

    @Transactional
    public TaskDTO cancelTask(Long id) {
        ScanTask task = taskRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("任务不存在"));
        if (task.getStatus() == ScanTask.TaskStatus.COMPLETED
                || task.getStatus() == ScanTask.TaskStatus.FAILED
                || task.getStatus() == ScanTask.TaskStatus.CANCELLED) {
            throw new RuntimeException("任务已结束，无法取消");
        }
        LocalDateTime now = LocalDateTime.now();
        task.setStatus(ScanTask.TaskStatus.CANCELLED);
        task.setCompletedAt(now);
        taskRepository.saveAndFlush(task);

        // 递归取消所有子任务和孙任务（含 PAUSED 状态），与暂停/继续的级联行为保持一致
        cancelChildTasks(id, now);

        Long projectId = task.getProject().getId();
        TaskRuntimeState state = runningTasks.get(projectId);
        if (state != null) {
            synchronized (state) {
                state.cancelled = true;
                state.paused = false;
                state.notifyAll();
            }
        }

        projectRepository.findById(projectId).ifPresent(p -> {
            p.setStatus(ScanProject.ProjectStatus.CANCELLED);
            projectRepository.saveAndFlush(p);
        });

        sendEvent(projectId, "cancelled", "任务及其子任务已取消");
        return convertToDTO(task, false);
    }

    /**
     * 递归取消子任务：PENDING/RUNNING/PAUSED 状态全部置为 CANCELLED
     */
    private void cancelChildTasks(Long parentId, LocalDateTime now) {
        List<ScanTask> children = taskRepository.findChildrenWithProject(parentId);
        for (ScanTask child : children) {
            if (child.getStatus() == ScanTask.TaskStatus.PENDING
                    || child.getStatus() == ScanTask.TaskStatus.RUNNING
                    || child.getStatus() == ScanTask.TaskStatus.PAUSED) {
                child.setStatus(ScanTask.TaskStatus.CANCELLED);
                child.setCompletedAt(now);
                taskRepository.saveAndFlush(child);
            }
            cancelChildTasks(child.getId(), now);
        }
    }

    @Transactional
    public void deleteTask(Long id) {
        ScanTask task = taskRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("任务不存在"));
        
        if (task.getStatus() == ScanTask.TaskStatus.RUNNING) {
            throw new RuntimeException("运行中的任务无法删除，请先取消任务");
        }
        
        Long projectId = task.getProject().getId();
        
        // 收集所有要删除的任务ID（包括自身和所有子任务）
        Set<Long> idsToDelete = new HashSet<>();
        collectTaskIdsToDelete(id, idsToDelete);
        
        // 批量删除所有相关任务
        List<ScanTask> tasksToDelete = taskRepository.findAllById(idsToDelete);
        taskRepository.deleteAll(tasksToDelete);
        
        log.info("删除任务树: rootTaskId={}, projectId={}, deletedCount={}", id, projectId, idsToDelete.size());
    }
    
    /**
     * 递归收集任务ID及其所有子任务ID
     */
    private void collectTaskIdsToDelete(Long taskId, Set<Long> idsToDelete) {
        idsToDelete.add(taskId);
        List<Long> childIds = taskRepository.findChildIdsByParentId(taskId);
        for (Long childId : childIds) {
            if (!idsToDelete.contains(childId)) {
                collectTaskIdsToDelete(childId, idsToDelete);
            }
        }
    }

    @Transactional
    public void cancelTaskByProjectId(Long projectId) {
        TaskRuntimeState state = runningTasks.get(projectId);
        if (state != null) {
            synchronized (state) {
                state.cancelled = true;
                state.paused = false;
                state.notifyAll();
            }
        }

        // 批量更新所有RUNNING和PENDING状态的任务为CANCELLED
        LocalDateTime now = LocalDateTime.now();
        int updatedCount = taskRepository.updateStatusByProjectIdAndStatusIn(
                projectId, 
                ScanTask.TaskStatus.CANCELLED, 
                now,
                List.of(ScanTask.TaskStatus.RUNNING, ScanTask.TaskStatus.PENDING));

        projectRepository.findById(projectId).ifPresent(p -> {
            p.setStatus(ScanProject.ProjectStatus.CANCELLED);
            p.setCompletedAt(now);
            projectRepository.saveAndFlush(p);
        });

        sendEvent(projectId, "cancelled", "扫描已终止");
        log.info("终止扫描: projectId={}, 更新任务数={}", projectId, updatedCount);
    }

    /**
     * 批量暂停父任务及其所有子任务
     */
    @Transactional
    public TaskDTO pauseTaskWithChildren(Long id) {
        ScanTask task = taskRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("任务不存在"));
        
        if (task.getStatus() != ScanTask.TaskStatus.RUNNING && task.getStatus() != ScanTask.TaskStatus.PENDING) {
            throw new RuntimeException("只能暂停运行中或待执行的任务");
        }

        // 暂停父任务（改为 PAUSED 状态）
        task.setStatus(ScanTask.TaskStatus.PAUSED);
        taskRepository.saveAndFlush(task);
        
        // 暂停所有子任务（递归）
        pauseChildTasks(id);

        Long projectId = task.getProject().getId();
        TaskRuntimeState state = runningTasks.get(projectId);
        if (state != null) {
            synchronized (state) {
                state.paused = true;
                state.notifyAll();
            }
        }

        sendEvent(projectId, "paused", "任务及其子任务已暂停");
        return convertToDTO(task, false);
    }

    /**
     * 批量继续父任务及其所有子任务
     */
    @Transactional
    public TaskDTO resumeTaskWithChildren(Long id) {
        ScanTask task = taskRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("任务不存在"));
        
        if (task.getStatus() != ScanTask.TaskStatus.PAUSED) {
            throw new RuntimeException("只能恢复已暂停的任务");
        }

        // 继续父任务
        task.setStatus(ScanTask.TaskStatus.RUNNING);
        taskRepository.saveAndFlush(task);
        
        // 继续所有子任务（递归）
        resumeChildTasks(id);

        Long projectId = task.getProject().getId();
        TaskRuntimeState state = runningTasks.get(projectId);
        if (state != null) {
            synchronized (state) {
                state.paused = false;
                state.notifyAll();
            }
        }

        sendEvent(projectId, "resumed", "任务及其子任务已恢复");
        return convertToDTO(task, false);
    }

    /**
     * 递归暂停子任务
     */
    private void pauseChildTasks(Long parentId) {
        List<ScanTask> children = taskRepository.findChildrenWithProject(parentId);
        for (ScanTask child : children) {
            if (child.getStatus() == ScanTask.TaskStatus.RUNNING || child.getStatus() == ScanTask.TaskStatus.PENDING) {
                child.setStatus(ScanTask.TaskStatus.PAUSED);
                taskRepository.saveAndFlush(child);
            }
            pauseChildTasks(child.getId());
        }
    }

    /**
     * 递归继续子任务
     */
    private void resumeChildTasks(Long parentId) {
        List<ScanTask> children = taskRepository.findChildrenWithProject(parentId);
        for (ScanTask child : children) {
            if (child.getStatus() == ScanTask.TaskStatus.PAUSED) {
                child.setStatus(ScanTask.TaskStatus.RUNNING);
                taskRepository.saveAndFlush(child);
            }
            resumeChildTasks(child.getId());
        }
    }

    /**
     * 重启任务（重置状态为PENDING，清除执行结果，记录日志，并异步重新执行）
     */
    @Transactional
    public TaskDTO restartTask(Long id) {
        ScanTask task = taskRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("任务不存在"));
        
        // 重置任务状态（清除思考过程、对话记录、执行结果）
        task.setStatus(ScanTask.TaskStatus.PENDING);
        task.setProgress(0);
        task.setStartedAt(null);
        task.setCompletedAt(null);
        task.setLlmRequest(null);
        task.setLlmResponse(null);
        task.setThinkingProcess(null);
        task.setAnalysisResult(null);
        task.setHasVulnerability(null);
        task.setResult(null);
        taskRepository.saveAndFlush(task);
        
        // 删除该任务关联的旧 ScanLog
        logRepository.deleteByTaskId(id);
        logRepository.flush();

        // 记录重启事件到执行日志（TASK_START 类型，确保能穿透前端过滤）
        ScanProject project = task.getProject();
        if (project != null) {
            ScanLog restartLog = ScanLog.builder()
                    .project(project)
                    .task(task)
                    .logType(ScanLog.LogType.TASK_START)
                    .message(String.format("【任务重启】%s 已重置，准备重新执行", task.getTaskName()))
                    .build();
            logRepository.saveAndFlush(restartLog);
        }

        // 同时重置所有子任务（子任务暂不触发执行，等父任务汇总时处理）
        restartChildTasks(id);
        
        // 发送事件通知
        Long projectId = task.getProject().getId();
        sendEvent(projectId, "task_restarted", "任务已重启");

        // 事务提交后异步执行（避免在事务未提交时读不到新状态）
        Long taskIdToExecute = id;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        scanEngineService.restartAndExecuteTask(taskIdToExecute);
                    } catch (Exception e) {
                        log.error("事务提交后异步重启执行任务失败: taskId={}", taskIdToExecute, e);
                    }
                }
            });
        } else {
            // 无事务直接触发
            scanEngineService.restartAndExecuteTask(taskIdToExecute);
        }
        
        return convertToDTO(task, false);
    }

    /**
     * 递归重启子任务（清除数据 + 记录重启日志，不触发执行）
     */
    private void restartChildTasks(Long parentId) {
        List<ScanTask> children = taskRepository.findChildrenWithProject(parentId);
        for (ScanTask child : children) {
            child.setStatus(ScanTask.TaskStatus.PENDING);
            child.setProgress(0);
            child.setStartedAt(null);
            child.setCompletedAt(null);
            child.setLlmRequest(null);
            child.setLlmResponse(null);
            child.setThinkingProcess(null);
            child.setAnalysisResult(null);
            child.setHasVulnerability(null);
            child.setResult(null);
            taskRepository.saveAndFlush(child);

            // 删除子任务关联的旧 ScanLog
            logRepository.deleteByTaskId(child.getId());
            logRepository.flush();

            // 记录子任务重启日志
            ScanProject childProject = child.getProject();
            if (childProject != null) {
                ScanLog childRestartLog = ScanLog.builder()
                        .project(childProject)
                        .task(child)
                        .logType(ScanLog.LogType.TASK_START)
                        .message(String.format("【任务重启】%s 已重置，准备重新执行", child.getTaskName()))
                        .build();
                logRepository.saveAndFlush(childRestartLog);
            }

            restartChildTasks(child.getId());
        }
    }

    public SseEmitter getTaskEmitter(Long projectId) {
        SseEmitter emitter = taskEmitters.get(projectId);
        if (emitter != null) {
            return emitter;
        }
        emitter = new SseEmitter(900000L);
        taskEmitters.put(projectId, emitter);
        emitter.onCompletion(() -> taskEmitters.remove(projectId));
        emitter.onTimeout(() -> taskEmitters.remove(projectId));
        emitter.onError(e -> taskEmitters.remove(projectId));
        return emitter;
    }

    private void sendEvent(Long projectId, String eventName, String data) {
        SseEmitter emitter = taskEmitters.get(projectId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (Exception e) {
                log.warn("发送SSE事件失败: projectId={}", projectId, e);
            }
        }
    }

    /**
     * 递归统计指定任务下所有叶子任务（无子任务的最深层任务）的数量
     * 返回 [叶子总数, 已完成叶子数]；无子任务时返回 null（调用方按单任务处理）
     */
    private int[] countLeafStats(Long taskId) {
        List<ScanTask> children = taskRepository.findByParentTaskId(taskId);
        if (children == null || children.isEmpty()) {
            return null;
        }
        int total = 0;
        int completed = 0;
        for (ScanTask child : children) {
            int[] sub = countLeafStats(child.getId());
            if (sub == null) {
                // 该子任务本身是叶子任务
                total += 1;
                completed += child.getStatus() == ScanTask.TaskStatus.COMPLETED ? 1 : 0;
            } else {
                total += sub[0];
                completed += sub[1];
            }
        }
        return new int[]{total, completed};
    }

    private TaskDTO convertToDTO(ScanTask task, boolean loadChildren) {
        String projectName = null;
        if (task.getProject() != null) {
            projectName = task.getProject().getProjectName();
        }

        List<TaskDTO> childTasks = null;
        List<LogEntryDTO> logs;
        List<ConversationEntryDTO> conversationHistory = null;
        
        if (loadChildren) {
            // 加载子任务（不递归加载子任务的子任务日志，避免重复）
            childTasks = task.getChildTasks().stream()
                    .map(t -> convertToDTO(t, false))
                    .collect(Collectors.toList());
            
            // 加载当前任务 + 所有子任务的日志合集
            logs = loadLogsWithChildren(task);
            
            // 加载当前任务 + 所有子任务的对话记录合集
            conversationHistory = loadConversationHistoryWithChildren(task);
        } else {
            // 只加载当前任务的日志
            logs = loadTaskLogs(task.getId());
            
            // 只加载当前任务的对话记录
            conversationHistory = loadTaskConversationHistory(task);
        }

        // 统计已完成/总任务数：递归统计所有叶子任务（无子任务的最深层任务，即实际执行扫描的任务）
        // 总数 = 所有叶子任务数（含孙任务），已完成 = 状态为 COMPLETED 的叶子任务数（已取消/失败不计入成功）
        int totalTaskCount;
        int completedTaskCount;
        int[] leafStats = countLeafStats(task.getId());
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
                .llmRequest(task.getLlmRequest())
                .llmResponse(task.getLlmResponse())
                .thinkingProcess(task.getThinkingProcess())
                .hasVulnerability(task.getHasVulnerability())
                .result(task.getResult())
                .analysisResult(task.getAnalysisResult())
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
                .logs(logs)
                .conversationHistory(conversationHistory)
                .build();
    }
    
    /**
     * 加载单个任务的日志
     */
    private List<LogEntryDTO> loadTaskLogs(Long taskId) {
        return logRepository.findByTaskIdOrderByCreatedAtAsc(taskId).stream()
                .map(scanLog -> LogEntryDTO.builder()
                        .id(scanLog.getId())
                        .logType(scanLog.getLogType().name())
                        .message(scanLog.getMessage())
                        .createdAt(scanLog.getCreatedAt())
                        .taskId(scanLog.getTask().getId())
                        .taskName(scanLog.getTask().getTaskName())
                        .build())
                .collect(Collectors.toList());
    }
    
    /**
     * 加载单个任务的对话记录（思考过程 + LLM请求/响应）
     */
    private List<ConversationEntryDTO> loadTaskConversationHistory(ScanTask task) {
        List<ConversationEntryDTO> history = new ArrayList<>();
        
        // 只有当任务有LLM请求或响应时才添加
        if (task.getLlmRequest() != null || task.getLlmResponse() != null || task.getThinkingProcess() != null) {
            history.add(ConversationEntryDTO.builder()
                    .taskId(task.getId())
                    .taskName(task.getTaskName())
                    .taskType(task.getTaskType().name())
                    .thinkingProcess(task.getThinkingProcess())
                    .llmRequest(task.getLlmRequest())
                    .llmResponse(task.getLlmResponse())
                    .completedAt(task.getCompletedAt())
                    .build());
        }
        
        return history;
    }
    
    /**
     * 加载任务及其所有子任务的日志合集
     */
    private List<LogEntryDTO> loadLogsWithChildren(ScanTask task) {
        List<LogEntryDTO> allLogs = new ArrayList<>();
        
        // 添加当前任务的日志
        allLogs.addAll(loadTaskLogs(task.getId()));
        
        // 递归添加子任务的日志
        for (ScanTask child : task.getChildTasks()) {
            allLogs.addAll(loadLogsWithChildren(child));
        }
        
        // 按时间排序
        allLogs.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
        
        return allLogs;
    }
    
    /**
     * 加载任务及其所有子任务的对话记录合集
     */
    private List<ConversationEntryDTO> loadConversationHistoryWithChildren(ScanTask task) {
        List<ConversationEntryDTO> allHistory = new ArrayList<>();
        
        // 添加当前任务的对话记录
        allHistory.addAll(loadTaskConversationHistory(task));
        
        // 递归添加子任务的对话记录
        for (ScanTask child : task.getChildTasks()) {
            allHistory.addAll(loadConversationHistoryWithChildren(child));
        }
        
        // 按完成时间排序
        allHistory.sort((a, b) -> {
            if (a.getCompletedAt() == null && b.getCompletedAt() == null) return 0;
            if (a.getCompletedAt() == null) return 1;
            if (b.getCompletedAt() == null) return -1;
            return a.getCompletedAt().compareTo(b.getCompletedAt());
        });
        
        return allHistory;
    }

    private static class TaskRuntimeState {
        volatile boolean paused = false;
        volatile boolean cancelled = false;
    }
}