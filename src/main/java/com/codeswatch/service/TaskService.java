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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TaskService {

    private final ScanTaskRepository taskRepository;
    private final ScanProjectRepository projectRepository;
    private final ScanLogRepository logRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    @Lazy
    private ScanEngineService scanEngineService;

    private final Map<Long, TaskRuntimeState> runningTasks = new ConcurrentHashMap<>();
    private final Map<Long, SseEmitter> taskEmitters = new ConcurrentHashMap<>();

    /** 后台更新项目状态的线程：项目行被扫描事务占用时轮询重试，避免阻塞接口响应 */
    private final ExecutorService projectStatusExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "project-status-updater");
        t.setDaemon(true);
        return t;
    });

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

    /**
     * 若项目尚无运行时状态则注册一个（重启单个任务时使用）。
     * 已有状态说明该项目正在扫描，此时不覆盖，避免丢失其暂停/取消标志。
     *
     * @return true 表示本次新建，调用方结束后需调用 {@link #unregisterTask(Long)} 释放
     */
    public boolean registerTaskIfAbsent(Long projectId, SseEmitter emitter) {
        if (runningTasks.containsKey(projectId)) {
            if (emitter != null) {
                taskEmitters.put(projectId, emitter);
            }
            return false;
        }
        registerTask(projectId, emitter);
        return true;
    }

    public boolean isPaused(Long projectId) {
        TaskRuntimeState state = runningTasks.get(projectId);
        return state != null && state.paused;
    }

    public boolean isCancelled(Long projectId) {
        TaskRuntimeState state = runningTasks.get(projectId);
        return state != null && state.cancelled;
    }

    /** 标记任务开始执行（进入持有任务行锁的区间） */
    public void markExecuting(Long projectId, Long taskId) {
        TaskRuntimeState state = runningTasks.get(projectId);
        if (state != null && taskId != null) {
            state.executingTaskIds.add(taskId);
        }
    }

    /** 标记任务执行结束（行锁已释放） */
    public void unmarkExecuting(Long projectId, Long taskId) {
        TaskRuntimeState state = runningTasks.get(projectId);
        if (state != null && taskId != null) {
            state.executingTaskIds.remove(taskId);
        }
    }

    /** 获取当前正在执行（持有行锁）的任务 id 集合 */
    public Set<Long> getExecutingTaskIds(Long projectId) {
        TaskRuntimeState state = runningTasks.get(projectId);
        return state != null ? Set.copyOf(state.executingTaskIds) : Set.of();
    }

    /**
     * 标记一个执行线程开始运行，返回本次执行的令牌。
     * 结束后必须把该令牌原样传给 {@link #endExecution(Object)}，
     * 以保证增减作用于同一个运行时状态对象（项目可能在此期间被重新注册）。
     */
    public Object beginExecution(Long projectId) {
        TaskRuntimeState state = runningTasks.get(projectId);
        if (state != null) {
            state.activeExecutions.incrementAndGet();
        }
        return state;
    }

    /** 标记执行线程结束（token 为 {@link #beginExecution(Long)} 的返回值） */
    public void endExecution(Object token) {
        if (token instanceof TaskRuntimeState state) {
            state.activeExecutions.decrementAndGet();
        }
    }

    /** 当前正在执行该项目的执行线程数 */
    public int getActiveExecutions(Long projectId) {
        TaskRuntimeState state = runningTasks.get(projectId);
        return state != null ? state.activeExecutions.get() : 0;
    }

    /** 请求中止当前执行：唤醒暂停中的执行线程，使其尽快退出并释放行锁 */
    public void requestAbort(Long projectId) {
        TaskRuntimeState state = runningTasks.get(projectId);
        if (state != null) {
            synchronized (state) {
                state.cancelled = true;
                state.notifyAll();
            }
        }
    }

    /** 清除暂停/取消标志（重启的新执行开始前调用，避免被项目级暂停/取消阻塞） */
    public void clearPauseCancelFlags(Long projectId) {
        TaskRuntimeState state = runningTasks.get(projectId);
        if (state != null) {
            synchronized (state) {
                state.paused = false;
                state.cancelled = false;
                state.notifyAll();
            }
        }
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
        Long projectId = task.getProject() != null ? task.getProject().getId() : null;
        TaskRuntimeState state = projectId != null ? runningTasks.get(projectId) : null;
        if (state != null) {
            synchronized (state) {
                state.paused = true;
                state.notifyAll();
            }
        }

        // 正在执行的任务行由执行线程持有行锁，跳过不等待（原实现会阻塞约50秒后超时）
        updateSubtreeStatusSkippingLocked(id, projectId, ScanTask.TaskStatus.PENDING, null,
                List.of(ScanTask.TaskStatus.RUNNING), false);

        if (projectId != null) {
            // 项目行可能被正在执行的扫描事务占用，改为后台重试更新，避免阻塞本次响应
            updateProjectStatusAsync(projectId, ScanProject.ProjectStatus.PAUSED, null);
            sendEvent(projectId, "paused", "任务已暂停");
        }
        refreshTask(task);
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
        Long projectId = task.getProject() != null ? task.getProject().getId() : null;

        // 先置内存取消标志：唤醒执行线程并使其不再开启下一个子任务，
        // 尽快释放其持有的任务行锁，避免下方 DB 更新被长时间阻塞
        TaskRuntimeState state = projectId != null ? runningTasks.get(projectId) : null;
        if (state != null) {
            synchronized (state) {
                state.cancelled = true;
                state.paused = false;
                state.notifyAll();
            }
        }

        LocalDateTime now = LocalDateTime.now();

        // 递归取消任务及其所有子任务和孙任务（含 PAUSED 状态），与暂停/继续的级联行为保持一致。
        // 正在执行的任务行由执行线程持有行锁，这里直接跳过不等待，由执行线程在结束后自行置为 CANCELLED
        updateSubtreeStatusSkippingLocked(id, projectId, ScanTask.TaskStatus.CANCELLED, now,
                List.of(ScanTask.TaskStatus.PENDING,
                        ScanTask.TaskStatus.RUNNING,
                        ScanTask.TaskStatus.PAUSED), true);

        if (projectId != null) {
            // 项目行可能被正在执行的扫描事务占用，改为后台重试更新，避免阻塞本次响应
            updateProjectStatusAsync(projectId, ScanProject.ProjectStatus.CANCELLED, null);
            sendEvent(projectId, "cancelled", "任务及其子任务已取消");
        }

        // 上面的批量更新绕过了持久化上下文，刷新后返回最新状态
        refreshTask(task);
        return convertToDTO(task, false);
    }

    /**
     * 递归更新任务及其子任务的状态。
     * 正在执行的任务（{@link #getExecutingTaskIds}）其行由执行事务持有排他锁，
     * 且该事务横跨整个大模型调用过程、状态变更尚未提交，因此无法通过状态字段识别。
     * 这里按内存中记录的"执行中任务 id"精确排除这些行，只批量更新其余行，从而立即返回；
     * 被排除的行由执行线程在结束后自行收尾。
     */
    private void updateSubtreeStatusSkippingLocked(Long rootId, Long projectId, ScanTask.TaskStatus target,
                                                   LocalDateTime completedAt,
                                                   List<ScanTask.TaskStatus> fromStatuses,
                                                   boolean descendChildren) {
        List<Long> ids = collectSubtreeIds(rootId, descendChildren);
        if (ids.isEmpty()) {
            return;
        }
        Set<Long> executing = getExecutingTaskIds(projectId);
        List<Long> updatable = executing.isEmpty()
                ? ids
                : ids.stream().filter(id -> !executing.contains(id)).toList();
        if (updatable.isEmpty()) {
            log.info("任务状态更新跳过: rootId={}, target={}, 全部处于执行中", rootId, target);
            return;
        }
        int updated = taskRepository.updateStatusByIdsAndStatusIn(updatable, target, completedAt, fromStatuses);
        if (!executing.isEmpty()) {
            log.info("任务状态更新完成(执行中的任务跳过): rootId={}, target={}, 已更新={}, 跳过执行中={}",
                    rootId, target, updated, ids.size() - updatable.size());
        }
    }

    /** 收集任务及其所有子孙任务的 id（普通读取，不加锁） */
    private List<Long> collectSubtreeIds(Long rootId, boolean descendChildren) {
        List<Long> ids = new ArrayList<>();
        Deque<Long> pending = new ArrayDeque<>();
        pending.add(rootId);
        while (!pending.isEmpty()) {
            Long currentId = pending.poll();
            ids.add(currentId);
            if (descendChildren) {
                pending.addAll(taskRepository.findChildIdsByParentId(currentId));
            }
        }
        return ids;
    }

    /** 批量更新绕过持久化上下文，重新读取任务以返回最新状态 */
    private void refreshTask(ScanTask task) {
        try {
            entityManager.refresh(task);
        } catch (Exception e) {
            log.warn("刷新任务状态失败: taskId={}, {}", task.getId(), e.getMessage());
        }
    }

    /**
     * 更新项目状态。
     * 项目行会被正在执行的扫描事务占用（scan_task / scan_log 写入时会对该外键父行加共享锁），
     * 直接更新会等待到 innodb_lock_wait_timeout（约50秒），导致取消/暂停等接口长时间无响应甚至报错。
     * 因此改为后台线程用短事务轮询：行空闲时立即更新，超时则放弃（项目最终状态由扫描线程同步）。
     *
     * @param allowedFrom 仅当项目当前状态属于该集合时才更新；传 null 表示不限制
     */
    private void updateProjectStatusAsync(Long projectId, ScanProject.ProjectStatus target,
                                          List<ScanProject.ProjectStatus> allowedFrom) {
        if (projectId == null) {
            return;
        }
        projectStatusExecutor.submit(() -> {
            // 最多重试2分钟：正在执行的大模型调用最长可能持续数分钟，期间项目行一直被占用。
            // 每次尝试都是独立短事务；若行仍被占用则事务等待至 innodb_lock_wait_timeout 后抛异常，
            // 捕获后继续重试（不放弃），待扫描事务结束、锁释放后即可更新成功。
            for (int attempt = 0; attempt < 120; attempt++) {
                try {
                    Boolean updated = transactionTemplate.execute(status -> projectRepository.findById(projectId)
                            .map(p -> {
                                if (allowedFrom != null && !allowedFrom.contains(p.getStatus())) {
                                    return Boolean.TRUE;
                                }
                                p.setStatus(target);
                                projectRepository.saveAndFlush(p);
                                return Boolean.TRUE;
                            }).orElse(Boolean.TRUE));
                    if (Boolean.TRUE.equals(updated)) {
                        log.info("项目状态已更新: projectId={}, status={}", projectId, target);
                        return;
                    }
                } catch (Exception e) {
                    log.debug("项目状态暂不可更新(行被扫描事务占用), 稍后重试: projectId={}, {}", projectId,
                            e.getMessage());
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            log.warn("项目行被扫描事务长时间占用，放弃更新项目状态: projectId={}, status={}", projectId, target);
        });
    }

    @Transactional
    public void deleteTask(Long id) {
        ScanTask task = taskRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("任务不存在"));
        
        if (task.getStatus() == ScanTask.TaskStatus.RUNNING) {
            throw new RuntimeException("运行中的任务无法删除，请先取消任务");
        }
        
        // 项目被删除后任务已解绑（project_id 为空），此处仅用于日志，需容忍为空
        Long projectId = task.getProject() != null ? task.getProject().getId() : null;
        
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

        Long projectId = task.getProject() != null ? task.getProject().getId() : null;

        // 先置内存暂停标志：让执行线程在完成当前子任务后不再开启下一个子任务，
        // 从而尽快释放其持有的任务行锁，避免下方 DB 更新被长时间阻塞
        TaskRuntimeState state = projectId != null ? runningTasks.get(projectId) : null;
        if (state != null) {
            synchronized (state) {
                state.paused = true;
                state.notifyAll();
            }
        }

        // 暂停父任务及其所有子任务（递归）。正在执行的任务行由执行线程持有行锁，
        // 这里直接跳过不等待，由执行线程在完成当前任务后停止后续任务
        updateSubtreeStatusSkippingLocked(id, projectId, ScanTask.TaskStatus.PAUSED, null,
                List.of(ScanTask.TaskStatus.RUNNING, ScanTask.TaskStatus.PENDING), true);

        // 同步项目状态为暂停，保证项目管理处与任务管理处状态一致
        if (projectId != null) {
            // 项目行可能被正在执行的扫描事务占用，改为后台重试更新，避免阻塞本次响应
            updateProjectStatusAsync(projectId, ScanProject.ProjectStatus.PAUSED,
                    List.of(ScanProject.ProjectStatus.SCANNING, ScanProject.ProjectStatus.PENDING));
            sendEvent(projectId, "paused", "任务及其子任务已暂停");
        }
        refreshTask(task);
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

        Long projectId = task.getProject() != null ? task.getProject().getId() : null;

        // 继续父任务及其所有子任务（递归）。正在执行的任务行由执行线程持有行锁，
        // 这里直接跳过不等待，避免恢复操作被长时间阻塞
        updateSubtreeStatusSkippingLocked(id, projectId, ScanTask.TaskStatus.RUNNING, null,
                List.of(ScanTask.TaskStatus.PAUSED), true);

        TaskRuntimeState state = projectId != null ? runningTasks.get(projectId) : null;
        if (state != null) {
            synchronized (state) {
                state.paused = false;
                state.notifyAll();
            }
        }

        // 同步项目状态为扫描中，保证项目管理处与任务管理处状态一致
        if (projectId != null) {
            // 项目行可能被正在执行的扫描事务占用，改为后台重试更新，避免阻塞本次响应
            updateProjectStatusAsync(projectId, ScanProject.ProjectStatus.SCANNING,
                    List.of(ScanProject.ProjectStatus.PAUSED));
            sendEvent(projectId, "resumed", "任务及其子任务已恢复");
        }
        refreshTask(task);
        return convertToDTO(task, false);
    }

    /**
     * 重启任务（重置状态为PENDING，清除执行结果，记录日志，并异步重新执行）
     */
    @Transactional
    public TaskDTO restartTask(Long id) {
        ScanTask task = taskRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("任务不存在"));
        
        // 重置任务状态（清除思考过程、对话记录、执行结果）
        resetTaskFields(task);
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

        // 清除项目的暂停/取消内存标志，避免重启的任务被项目级暂停阻塞而永远等待
        // （例如项目整体"全部暂停"后重启单个任务，checkPauseCancel 会因 paused=true 一直阻塞）
        // 注意：若仍有旧执行线程在运行（如刚取消但线程尚未退出），直接清除标志会唤醒它继续执行，
        // 与新启动的重启线程并发更新 scan_project 造成死锁；因此这里只请求其中止，
        // 等待其退出、清除标志并补正被覆盖的任务状态，统一由异步执行线程在后台完成。
        if (getActiveExecutions(projectId) > 0) {
            requestAbort(projectId);
        }

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
     * 把任务重置为待执行并清空执行结果（思考过程、对话记录、结果等）
     */
    private void resetTaskFields(ScanTask task) {
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
    }

    /**
     * 重新把任务及其子任务置为 PENDING 并清空执行结果。
     * 用于旧执行线程在退出前把任务置为 CANCELLED/FAILED、覆盖了重启时的 PENDING 的情况。
     */
    @Transactional
    public void resetTaskSubtreeForRestart(Long rootId) {
        ScanTask root = taskRepository.findById(rootId).orElse(null);
        if (root == null) {
            return;
        }
        resetTaskFields(root);
        taskRepository.saveAndFlush(root);
        restartChildTasks(rootId);
        log.info("重启任务状态被旧执行覆盖，已重新置为 PENDING: taskId={}", rootId);
    }

    /**
     * 递归重启子任务（清除数据 + 记录重启日志，不触发执行）
     */
    private void restartChildTasks(Long parentId) {
        List<ScanTask> children = taskRepository.findChildrenWithProject(parentId);
        for (ScanTask child : children) {
            resetTaskFields(child);
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
    public int[] countLeafStats(Long taskId) {
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
        String projectName = "已删除项目";
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
        /** 正在执行（持有任务行锁）的任务 id，状态更新时需跳过，避免等待行锁 */
        final Set<Long> executingTaskIds = ConcurrentHashMap.newKeySet();
        /** 正在执行该项目的执行线程数：重启时据此等待旧执行退出，避免并发更新项目行 */
        final AtomicInteger activeExecutions = new AtomicInteger();
    }
}