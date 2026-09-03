package com.codeswatch.controller;

import com.codeswatch.dto.TaskDTO;
import com.codeswatch.entity.ScanTask;
import com.codeswatch.repository.ScanTaskRepository;
import com.codeswatch.service.ScanEngineService;
import com.codeswatch.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@Slf4j
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final ScanEngineService scanEngineService;
    private final ScanTaskRepository taskRepository;

    @GetMapping
    public ResponseEntity<List<TaskDTO>> getAllTasks() {
        return ResponseEntity.ok(taskService.getAllTasks());
    }

    @GetMapping("/active")
    public ResponseEntity<List<TaskDTO>> getActiveTasks() {
        return ResponseEntity.ok(taskService.getActiveTasks());
    }

    @GetMapping("/completed")
    public ResponseEntity<List<TaskDTO>> getCompletedTasks() {
        return ResponseEntity.ok(taskService.getCompletedTasks());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskDTO> getTask(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.getTask(id));
    }

    @GetMapping("/{id}/children")
    public ResponseEntity<List<TaskDTO>> getChildTasks(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.getChildTasks(id));
    }

    @PostMapping
    public SseEmitter createTask(@RequestBody Map<String, Long> request) {
        Long projectId = request.get("projectId");
        Long llmConfigId = request.get("llmConfigId");
        
        if (projectId == null) {
            throw new RuntimeException("项目ID不能为空");
        }
        
        SseEmitter emitter = new SseEmitter(300000L);
        scanEngineService.startScan(projectId, emitter, llmConfigId);
        return emitter;
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<TaskDTO> pauseTask(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.pauseTask(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<TaskDTO> cancelTask(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.cancelTask(id));
    }

    @PostMapping("/{id}/pause-children")
    public ResponseEntity<TaskDTO> pauseTaskWithChildren(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.pauseTaskWithChildren(id));
    }

    @PostMapping("/{id}/resume-children")
    public ResponseEntity<TaskDTO> resumeTaskWithChildren(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.resumeTaskWithChildren(id));
    }

    @PostMapping("/{id}/restart")
    public ResponseEntity<TaskDTO> restartTask(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.restartTask(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteTask(@PathVariable Long id) {
        taskService.deleteTask(id);
        return ResponseEntity.ok(Map.of("message", "任务删除成功"));
    }

    @GetMapping("/{id}/stream")
    public SseEmitter getTaskStream(@PathVariable Long id) {
        ScanTask task = taskRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("任务不存在"));
        Long projectId = task.getProject().getId();
        return taskService.getTaskEmitter(projectId);
    }
}