package com.codeswatch.service;

import com.codeswatch.dto.FindingDTO;
import com.codeswatch.dto.LlmResponse;
import com.codeswatch.dto.LogEntryDTO;
import com.codeswatch.entity.*;
import com.codeswatch.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ScanEngineService {

    private final ScanProjectRepository projectRepository;
    private final ScanTaskRepository taskRepository;
    private final ScanFindingRepository findingRepository;
    private final ScanLogRepository logRepository;
    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final TaskService taskService;
    private final FileService fileService;
    private final PromptConfigService promptConfigService;
    private final PromptConfigRepository promptConfigRepository;

    @Autowired
    @Lazy
    private ScanEngineService self;

    private final ExecutorService scanExecutor = Executors.newFixedThreadPool(3);

    /**
     * 启动恢复：应用重启会导致扫描线程丢失，
     * 将遗留的 RUNNING/PAUSED 任务和 SCANNING 项目标记为失败，避免状态永远卡住
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedScans() {
        try {
            List<ScanTask> interruptedTasks = taskRepository.findByStatusIn(
                    List.of(ScanTask.TaskStatus.RUNNING, ScanTask.TaskStatus.PAUSED));
            for (ScanTask task : interruptedTasks) {
                // 阶段父任务只有 已完成/已取消 两种终态，中断按已取消处理；其余任务标记为失败
                task.setStatus(isPhaseTaskType(task.getTaskType())
                        ? ScanTask.TaskStatus.CANCELLED : ScanTask.TaskStatus.FAILED);
                task.setCompletedAt(LocalDateTime.now());
            }
            if (!interruptedTasks.isEmpty()) {
                taskRepository.saveAll(interruptedTasks);
                log.warn("启动恢复：{} 个中断的扫描任务已标记为失败", interruptedTasks.size());
            }

            List<ScanProject> scanningProjects = projectRepository.findByStatus(ScanProject.ProjectStatus.SCANNING);
            for (ScanProject project : scanningProjects) {
                project.setStatus(ScanProject.ProjectStatus.FAILED);
                project.setCompletedAt(LocalDateTime.now());
                // 遗留的 PENDING 任务转为 CANCELLED，使其可以重启恢复
                taskRepository.updateStatusByProjectIdAndStatusIn(project.getId(),
                        ScanTask.TaskStatus.CANCELLED, LocalDateTime.now(),
                        List.of(ScanTask.TaskStatus.PENDING));
                // 修正容器任务状态：孙任务已全部完成但容器仍卡在等待/运行
                aggregateInterfaceSubTasks(project.getId());
            }
            if (!scanningProjects.isEmpty()) {
                projectRepository.saveAll(scanningProjects);
                log.warn("启动恢复：{} 个扫描中的项目已标记为失败", scanningProjects.size());
            }
        } catch (Exception e) {
            log.error("启动恢复失败", e);
        }
    }

    // Maven依赖相关模式
    private static final Pattern POM_FILE_PATTERN = Pattern.compile(
            "<groupId>.*?</groupId>\\s*<artifactId>.*?</artifactId>\\s*<version>.*?</version>",
            Pattern.DOTALL);

    private static final Pattern DEPENDENCY_PATTERN = Pattern.compile(
            "<dependency>\\s*<groupId>(.*?)</groupId>\\s*<artifactId>(.*?)</artifactId>\\s*(?:<version>(.*?)</version>)?",
            Pattern.DOTALL);

    // Spring MVC接口注解模式 - 匹配注解名称
    // 只匹配注解关键字部分，后面单独处理参数
    private static final Pattern HTTP_MAPPING_ANNOTATION_PATTERN = Pattern.compile(
            "@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)" +
            "(?=\\s*(?:\\(|@|$|\\s))", Pattern.DOTALL);

    // 提取注解中路径的内部模式
    // 匹配: "/path", "/{id}", value="/path", path="/path"
    // 使用[\s\S]来支持包含特殊字符的路径
    private static final Pattern PATH_EXTRACT_PATTERN = Pattern.compile(
            "(?:path|value)\\s*=\\s*\"([^\"]*)\"|\"([^\"]*)\"", Pattern.DOTALL);

    // 匹配数组格式: {"/path1", "/path2"} 或 value={"/path1", "/path2"}
    // 匹配包含引号字符串的数组，不匹配单一路径变量如 "/{id}"
    private static final Pattern ARRAY_PATH_BRACE_PATTERN = Pattern.compile(
            "\\{([^}]*)\\}", Pattern.DOTALL);
    
    // 匹配数组中每个引号字符串: "path"
    private static final Pattern ARRAY_STRING_PATTERN = Pattern.compile(
            "\"([^\"]*)\"");

    // 危险函数模式
    private static final List<Pattern> DANGEROUS_FUNCTION_PATTERNS = List.of(
            Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec\\s*\\("),
            Pattern.compile("ProcessBuilder\\s*\\("),
            Pattern.compile("System\\.exit\\s*\\("),
            Pattern.compile("exec\\s*\\([^)]*\\)"),
            Pattern.compile("eval\\s*\\("),
            Pattern.compile("ScriptEngine.*eval"),
            Pattern.compile("Class\\.forName"),
            Pattern.compile("Method\\.invoke"),
            Pattern.compile("setAccessible\\s*\\(\\s*true\\s*\\)"),
            Pattern.compile("ObjectInputStream|readObject"),
            Pattern.compile("XMLDecoder"),
            Pattern.compile("getRuntime\\(\\)\\.exec"),
            Pattern.compile("Process\\.exec"),
            Pattern.compile("execSQL|executeUpdate"),
            Pattern.compile("Statement.*execute"),
            Pattern.compile("DriverManager\\.getConnection"),
            Pattern.compile("URL\\s*\\("),
            Pattern.compile("HttpURLConnection|openConnection"),
            Pattern.compile("RestTemplate|WebClient"),
            Pattern.compile("FileOutputStream|Files\\.write"),
            Pattern.compile("FileInputStream|Files\\.readAllBytes"),
            Pattern.compile("Paths\\.get|Path\\.of"),
            Pattern.compile("new\\s+File\\s*\\("),
            Pattern.compile("ZipInputStream|ZipFile"),
            Pattern.compile("getRealPath|getParameter"),
            Pattern.compile("sendRedirect|forward"),
            Pattern.compile("setHeader|addHeader"),
            Pattern.compile("Cookie\\s*\\("),
            Pattern.compile("JSESSIONID|SESSION")
    );

    private String getProjectCode(ScanProject project) {
        String cached = project.getProjectCode();
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        
        if (project.getCodePath() != null && !project.getCodePath().isEmpty()) {
            try {
                String code = fileService.readCodeFiles(project.getCodePath());
                if (code != null && !code.isEmpty()) {
                    return code;
                }
            } catch (Exception e) {
                log.warn("从文件读取代码失败，回退到数据库: {}", e.getMessage());
            }
        }
        
        return cached;
    }

    private String extractJsonForParse(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).trim();
            }
        }
        int jsonStart = text.indexOf('{');
        int jsonEnd = text.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            text = text.substring(jsonStart, jsonEnd + 1);
        }
        return text;
    }

    @FunctionalInterface
    public interface LogCallback {
        void onLog(LogEntryDTO log);
    }

    @Transactional
    public ScanProject createProject(String projectName, String description, String code) {
        ScanProject project = ScanProject.builder()
                .projectName(projectName)
                .projectDescription(description)
                .projectCode(code)
                .status(ScanProject.ProjectStatus.PENDING)
                .totalTasks(0)
                .completedTasks(0)
                .findingCount(0)
                .tasks(new ArrayList<>())
                .build();

        saveLog(project, null, ScanLog.LogType.INFO, "项目创建成功: " + projectName);
        return projectRepository.save(project);
    }

    @Transactional
    public void startScan(Long projectId, SseEmitter emitter, Long llmConfigId) {
        ScanProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        // 检查是否需要清除历史数据（选择相同大模型且之前已完成扫描时）
        boolean needClearHistory = project.getLastLlmConfigId() != null
                && project.getLastLlmConfigId().equals(llmConfigId)
                && project.getStatus() == ScanProject.ProjectStatus.COMPLETED;

        if (needClearHistory) {
            log.info("检测到选择相同大模型({})，清除项目{}的历史扫描数据", llmConfigId, projectId);
            clearProjectScanData(projectId);
            project = projectRepository.findById(projectId).orElseThrow();
        }

        if (project.getStatus() != ScanProject.ProjectStatus.PENDING
                && project.getStatus() != ScanProject.ProjectStatus.PAUSED
                && project.getStatus() != ScanProject.ProjectStatus.FAILED
                && project.getStatus() != ScanProject.ProjectStatus.CANCELLED) {
            throw new RuntimeException("项目已在扫描中或已完成");
        }

        String projectCode = getProjectCode(project);
        if (projectCode == null || projectCode.isEmpty()) {
            throw new RuntimeException("项目无代码内容，无法进行扫描。请上传代码或填写项目代码。");
        }

        if (project.getScanSessionId() == null) {
            project.setScanSessionId(UUID.randomUUID().toString());
        }
        project.setLastLlmConfigId(llmConfigId);
        project.setStatus(ScanProject.ProjectStatus.SCANNING);
        project.setStartedAt(LocalDateTime.now());
        project.setTotalTasks(0);
        project.setCompletedTasks(0);
        project.setFindingCount(0);
        project.setReportContent(null);
        project.getTasks().clear();
        project.getLogs().clear();
        project.getFindings().clear();
        project = projectRepository.saveAndFlush(project);

        taskService.registerTask(projectId, emitter);

        saveLog(project, null, ScanLog.LogType.TASK_START, "开始扫描项目: " + project.getProjectName());

        String codeForScan = projectCode;
        Long scanProjectId = project.getId();

        CompletableFuture<Void> scanFuture = CompletableFuture.runAsync(() -> {
            try {
                executeScanPhases(scanProjectId, emitter, llmConfigId, codeForScan);
            } catch (Exception e) {
                log.error("扫描执行失败", e);
                handleScanFailure(scanProjectId, e);
            } finally {
                taskService.unregisterTask(scanProjectId);
            }
        }, scanExecutor);

        scanFuture.exceptionally(e -> {
            log.error("扫描任务异常", e);
            handleScanFailure(scanProjectId, e instanceof Exception ? (Exception) e : new RuntimeException(e));
            taskService.unregisterTask(scanProjectId);
            return null;
        });
    }

    /**
     * 清除项目的扫描数据（任务、漏洞、日志、报告）
     * 注意：不删除项目本身，也不删除任务管理处的独立任务记录
     */
    private void clearProjectScanData(Long projectId) {
        log.info("开始清除项目{}的扫描数据...", projectId);

        // 1. 清除扫描日志
        logRepository.deleteByProjectId(projectId);
        log.info("已清除扫描日志");

        // 2. 清除漏洞详情
        findingRepository.deleteByProjectId(projectId);
        log.info("已清除漏洞详情");

        // 3. 清除扫描任务
        taskRepository.deleteByProjectId(projectId);
        log.info("已清除扫描任务");

        // 4. 重置项目的关联集合（确保JPA同步）
        ScanProject project = projectRepository.findById(projectId).orElseThrow();
        project.getTasks().clear();
        project.getLogs().clear();
        project.getFindings().clear();
        project.setReportContent(null);
        project.setTotalTasks(0);
        project.setCompletedTasks(0);
        project.setFindingCount(0);
        project.setStatus(ScanProject.ProjectStatus.PENDING);
        project.setStartedAt(null);
        project.setCompletedAt(null);
        projectRepository.saveAndFlush(project);

        log.info("项目{}的扫描数据清除完成", projectId);
    }

    private void handleScanFailure(Long projectId, Exception e) {
        try {
            ScanProject project = projectRepository.findById(projectId).orElse(null);
            if (project != null) {
                if (e.getMessage() != null && e.getMessage().contains("任务已取消")) {
                    project.setStatus(ScanProject.ProjectStatus.CANCELLED);
                } else {
                    project.setStatus(ScanProject.ProjectStatus.FAILED);
                }
                project.setCompletedAt(LocalDateTime.now());
                projectRepository.saveAndFlush(project);
            }
        } catch (Exception ex) {
            log.error("更新项目状态失败", ex);
        }

        // 将所有RUNNING状态的任务也标记为FAILED
        try {
            List<ScanTask> runningTasks = taskRepository.findByProjectIdAndStatusIn(projectId,
                    List.of(ScanTask.TaskStatus.RUNNING));
            for (ScanTask task : runningTasks) {
                task.setStatus(ScanTask.TaskStatus.FAILED);
                task.setCompletedAt(LocalDateTime.now());
                task.setProgress(100);
                try {
                    taskRepository.saveAndFlush(task);
                } catch (Exception ex) {
                    log.warn("更新任务{}状态失败: {}", task.getTaskName(), ex.getMessage());
                }
            }
            if (!runningTasks.isEmpty()) {
                log.info("已将{}个RUNNING任务标记为FAILED", runningTasks.size());
            }
        } catch (Exception ex) {
            log.error("批量更新任务状态失败", ex);
        }
    }

    private void executeScanPhases(Long projectId, SseEmitter emitter, Long llmConfigId, String projectCode) {
        try {
            taskService.checkPauseCancel(projectId);
            boolean hasFailure = false;

            String llmConfigName = getLlmConfigName(llmConfigId);
            ScanProject project = projectRepository.findById(projectId).orElseThrow();

            // ==================== 阶段一：创建所有任务 ====================
            sendHeartbeat(emitter, "阶段一：创建扫描任务...");
            saveLog(projectId, null, ScanLog.LogType.INFO, "开始创建所有扫描任务");

            // 1.1 依赖扫描阶段（未配置提示词则不创建相关任务）
            if (promptConfigService.getPromptConfigEntityByPhase("DEPENDENCY_SCAN") == null) {
                saveLog(projectId, null, ScanLog.LogType.WARNING,
                        "未配置[依赖扫描]阶段提示词，跳过创建依赖扫描任务");
                sendHeartbeat(emitter, "未配置[依赖扫描]阶段提示词，跳过依赖扫描");
            } else {
                ScanTask dependencyTask = self.createPhaseTask(projectId, "依赖扫描",
                        "扫描项目的所有依赖是否存在安全漏洞",
                        ScanTask.TaskType.DEPENDENCY_SCAN, llmConfigId, llmConfigName);

                List<String> pomFiles = findPomFiles(projectCode);
                if (!pomFiles.isEmpty()) {
                    for (String pomContent : pomFiles) {
                        String extractedDeps = extractDependenciesFromPom(pomContent);
                        if (extractedDeps != null && !extractedDeps.isEmpty()) {
                            self.createSmartSubTask(dependencyTask,
                                    "依赖分析 - Maven依赖检查",
                                    "分析Maven依赖的安全性",
                                    ScanTask.TaskType.SUB_DEPENDENCY_SCAN,
                                    extractedDeps,
                                    "pom.xml");  // 绑定pom.xml文件
                        }
                    }
                }
            }

            // 1.2 接口扫描阶段（未配置提示词则不创建相关任务）
            if (promptConfigService.getPromptConfigEntityByPhase("INTERFACE_SCAN") == null) {
                saveLog(projectId, null, ScanLog.LogType.WARNING,
                        "未配置[接口扫描]阶段提示词，跳过创建接口扫描任务");
                sendHeartbeat(emitter, "未配置[接口扫描]阶段提示词，跳过接口扫描");
            } else {
                ScanTask interfaceTask = self.createPhaseTask(projectId, "接口扫描",
                        "扫描所有互联网入口，识别代码中是否存在安全漏洞",
                        ScanTask.TaskType.INTERFACE_SCAN, llmConfigId, llmConfigName);

                log.info("开始接口扫描，项目代码长度: {} 字符", projectCode.length());
                saveLog(projectId, null, ScanLog.LogType.INFO, "开始接口扫描...");

                List<EndpointInfo> endpoints = findInterfaceAnnotations(projectCode);
                log.info("接口扫描完成，找到 {} 个接口", endpoints.size());
                saveLog(projectId, null, ScanLog.LogType.INFO, "接口扫描完成，找到 " + endpoints.size() + " 个接口");

                if (!endpoints.isEmpty()) {
                    for (EndpointInfo endpoint : endpoints) {
                        // 只存储接口描述信息，不存储代码内容
                        String endpointDesc = String.format("接口: %s %s | 类: %s | 方法: %s | 文件: %s | 行号: %d",
                                endpoint.getMethod(), endpoint.getPath(),
                                endpoint.getClassName(), endpoint.getMethodName(),
                                endpoint.getFilePath(), endpoint.getLineNumber());
                        ScanTask subTask = self.createSmartSubTask(interfaceTask,
                                "接口分析 - " + endpoint.getMethod() + " " + endpoint.getPath(),
                                "分析API接口的安全性: " + endpoint.getMethod() + " " + endpoint.getPath(),
                                ScanTask.TaskType.SUB_INTERFACE_SCAN,
                                endpointDesc,
                                endpoint.getFilePath());

                        // 为每个接口创建漏洞类型孙任务（仅创建，不执行）
                        self.createVulnerabilityGrandchildTasks(subTask, projectCode);
                    }
                } else {
                    log.warn("接口扫描未找到任何接口，可能是正则表达式未匹配到注解");
                    saveLog(projectId, null, ScanLog.LogType.WARNING, "接口扫描未找到任何接口");
                }
            }

            // 1.3 高危操作扫描阶段（未配置提示词则不创建相关任务）
            if (promptConfigService.getPromptConfigEntityByPhase("HIGH_RISK_OPERATION_SCAN") == null) {
                saveLog(projectId, null, ScanLog.LogType.WARNING,
                        "未配置[高危操作扫描]阶段提示词，跳过创建高危操作扫描任务");
                sendHeartbeat(emitter, "未配置[高危操作扫描]阶段提示词，跳过高危操作扫描");
            } else {
                ScanTask highRiskTask = self.createPhaseTask(projectId, "高危操作扫描",
                        "扫描代码中的高危操作方法，分析是否能从互联网接口进行利用",
                        ScanTask.TaskType.HIGH_RISK_OPERATION_SCAN, llmConfigId, llmConfigName);

                // 按启用的高危扫描配置创建子任务和孙任务
                self.createHighRiskSubTasksByConfig(highRiskTask, projectCode);
            }

            saveLog(projectId, null, ScanLog.LogType.INFO, "任务创建完成，开始执行扫描...");
            sendHeartbeat(emitter, "阶段一完成：任务树已创建，开始执行扫描...");

            // 发送任务创建完成事件，通知前端刷新任务树
            try {
                emitter.send(SseEmitter.event()
                        .name("tasks_created")
                        .data("任务创建完成"));
            } catch (Exception e) {
                log.debug("发送任务创建事件失败: {}", e.getMessage());
            }

            // ==================== 阶段二：执行所有任务 ====================
            hasFailure = executeAllTasks(projectId, emitter, llmConfigId, projectCode);

            project = projectRepository.findById(projectId).orElseThrow();
            // 扫描已被终止：保持 CANCELLED 状态，不再覆盖为完成/失败
            if (project.getStatus() == ScanProject.ProjectStatus.CANCELLED
                    || taskService.isCancelled(projectId)) {
                saveLog(project, null, ScanLog.LogType.WARNING, "扫描已终止，项目保持取消状态");
                emitter.send(SseEmitter.event().name("complete").data("扫描已终止"));
                emitter.complete();
                return;
            }
            if (hasFailure) {
                project.setStatus(ScanProject.ProjectStatus.FAILED);
                saveLog(project, null, ScanLog.LogType.ERROR, "部分扫描任务失败，项目标记为失败");
            } else {
                project.setStatus(ScanProject.ProjectStatus.COMPLETED);
                saveLog(project, null, ScanLog.LogType.TASK_COMPLETE, "项目扫描完成");
            }
            project.setCompletedAt(LocalDateTime.now());
            project.setFindingCount(Math.toIntExact(findingRepository.countByProjectId(projectId)));
            projectRepository.saveAndFlush(project);

            String report = generateReport(project);
            project.setReportContent(report);
            projectRepository.saveAndFlush(project);

            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(hasFailure ? "扫描部分失败" : "扫描完成"));
            emitter.complete();
        } catch (Exception e) {
            log.error("扫描阶段执行失败", e);
            handleScanFailure(projectId, e);
        }
    }

    private void sendHeartbeat(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data(message));
        } catch (Exception e) {
            log.debug("发送心跳事件失败: {}", e.getMessage());
        }
    }

    private String getLlmConfigName(Long llmConfigId) {
        if (llmConfigId == null) {
            return "默认模型";
        }
        return llmService.getLlmConfigName(llmConfigId);
    }

    @Transactional
    public ScanTask createPhaseTask(Long projectId, String name, String description,
                                     ScanTask.TaskType type, Long llmConfigId, String llmConfigName) {
        ScanProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        ScanTask task = ScanTask.builder()
                .project(project)
                .taskName(name)
                .taskDescription(description)
                .taskType(type)
                .status(ScanTask.TaskStatus.PENDING)
                .progress(0)
                .llmConfigId(llmConfigId)
                .llmConfigName(llmConfigName)
                .build();

        task = taskRepository.saveAndFlush(task);

        project.setTotalTasks(project.getTotalTasks() + 1);
        project.getTasks().add(task);
        projectRepository.saveAndFlush(project);

        return task;
    }

    @Transactional
    public ScanTask createSmartSubTask(ScanTask parentTask, String name, String description,
                                        ScanTask.TaskType type, String targetCode) {
        return createSmartSubTask(parentTask, name, description, type, targetCode, null);
    }

    /**
     * 创建智能子任务（带文件路径）
     */
    public ScanTask createSmartSubTask(ScanTask parentTask, String name, String description,
                                        ScanTask.TaskType type, String targetCode, String filePath) {
        ScanProject project = projectRepository.findById(parentTask.getProject().getId())
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        ScanTask subTask = ScanTask.builder()
                .project(project)
                .parentTask(parentTask)
                .taskName(name)
                .taskDescription(description)
                .taskType(type)
                .targetCode(targetCode)
                .filePath(filePath)
                .status(ScanTask.TaskStatus.PENDING)
                .progress(0)
                .llmConfigId(parentTask.getLlmConfigId())
                .llmConfigName(parentTask.getLlmConfigName())
                .build();

        subTask = taskRepository.saveAndFlush(subTask);

        project.setTotalTasks(project.getTotalTasks() + 1);
        projectRepository.saveAndFlush(project);

        return subTask;
    }

    private void executeSubTaskWithProtocol(Long projectId, ScanTask subTask, SseEmitter emitter, Long llmConfigId, String projectCode) {
        subTask.setStatus(ScanTask.TaskStatus.RUNNING);
        subTask.setStartedAt(LocalDateTime.now());
        subTask.setProgress(20);
        taskRepository.saveAndFlush(subTask);

        ScanProject project = projectRepository.findById(projectId).orElseThrow();
        
        // 获取绑定的文件路径
        String filePath = subTask.getFilePath();
        String displayFilePath = filePath != null ? filePath : "未绑定文件";
        
        saveLog(project, subTask, ScanLog.LogType.TASK_START,
                String.format("开始子任务: %s | 绑定文件: %s", subTask.getTaskName(), displayFilePath));

        log.info("执行子任务: taskId={}, taskName={}, taskType={}, filePath={}, projectCodeLength={}",
                subTask.getId(), subTask.getTaskName(), subTask.getTaskType(), 
                displayFilePath, projectCode != null ? projectCode.length() : 0);

        try {
            taskService.checkPauseCancel(projectId);

            // 统一根据filePath动态提取文件内容
            String targetCode;
            String existingTargetCode = subTask.getTargetCode();
            boolean hasExistingCode = existingTargetCode != null && !existingTargetCode.isEmpty()
                    && existingTargetCode.length() > 50;
            
            if (filePath != null && !filePath.isEmpty()) {
                // 尝试从项目代码中提取绑定文件的内容
                targetCode = extractFileContentFromProject(projectCode, filePath);
                if (targetCode == null || targetCode.isEmpty()) {
                    // 提取失败，回退策略:
                    // 1. 如果targetCode已有丰富内容(如解析好的pom依赖)，直接使用
                    // 2. 否则使用全量项目代码
                    if (hasExistingCode && subTask.getTaskType() == ScanTask.TaskType.SUB_DEPENDENCY_SCAN) {
                        targetCode = existingTargetCode;
                        safeSaveLog(project, subTask, ScanLog.LogType.INFO,
                                String.format("文件 %s 提取失败，使用已解析的依赖内容: %d 字符", displayFilePath, targetCode.length()));
                    } else {
                        targetCode = projectCode;
                        if (hasExistingCode) {
                            // 对于有内容的任务，将原有内容作为附加信息
                            targetCode = targetCode + "\n\n【预解析信息】\n" + existingTargetCode;
                        }
                        safeSaveLog(project, subTask, ScanLog.LogType.WARNING,
                                String.format("未能从文件 %s 提取代码，使用全量项目代码", displayFilePath));
                    }
                } else {
                    safeSaveLog(project, subTask, ScanLog.LogType.INFO,
                            String.format("从绑定文件 %s 提取代码内容: %d 字符", displayFilePath, targetCode.length()));
                }
            } else {
                // 没有绑定文件
                if (hasExistingCode) {
                    targetCode = existingTargetCode;
                    safeSaveLog(project, subTask, ScanLog.LogType.INFO,
                            String.format("使用已有的targetCode: %d 字符", targetCode.length()));
                } else {
                    targetCode = projectCode;
                    safeSaveLog(project, subTask, ScanLog.LogType.INFO,
                            "未绑定文件，使用全量项目代码");
                }
            }

            // 获取当前阶段的PromptConfig
            String phase = getPhaseFromTaskType(subTask.getTaskType());
            PromptConfig promptConfig;
            
            // 如果任务关联了特定的提示词配置，使用该配置
            if (subTask.getPromptConfigId() != null) {
                promptConfig = promptConfigRepository.findById(subTask.getPromptConfigId())
                        .orElseGet(() -> {
                            log.warn("任务 [{}] 关联的提示词配置 [{}] 不存在，使用默认配置", 
                                    subTask.getTaskName(), subTask.getPromptConfigId());
                            return promptConfigService.getPromptConfigEntityByPhase(phase);
                        });
            } else {
                promptConfig = promptConfigService.getPromptConfigEntityByPhase(phase);
            }

            // 构建并记录LLM请求内容
            String llmPrompt;
            String taskTypeDesc;
            String interfaceInfo = null;
            
            if (subTask.getTaskType() == ScanTask.TaskType.SUB_INTERFACE_VULNERABILITY_SCAN) {
                taskTypeDesc = subTask.getTaskDescription();
                
                // 构建接口信息：从父任务获取接口信息
                ScanTask parentTask = null;
                try {
                    if (subTask.getParentTaskId() != null) {
                        parentTask = taskRepository.findById(subTask.getParentTaskId()).orElse(null);
                    }
                } catch (Exception ex) {
                    log.warn("加载父任务失败: {}", ex.getMessage());
                }
                if (parentTask != null) {
                    String parentDesc = parentTask.getTaskDescription();
                    String parentName = parentTask.getTaskName();
                    // 从父任务提取接口信息（格式如 "接口: GET /api/users | 类: xxx | 方法: xxx | 文件: xxx.java | 行号: 12"）
                    if (parentDesc != null && parentDesc.contains("接口:")) {
                        interfaceInfo = parentDesc;
                    } else {
                        interfaceInfo = parentName;
                    }
                } else {
                    interfaceInfo = subTask.getTaskName();
                }
                
                llmPrompt = llmService.buildPromptForVulnerability(
                        truncateCodeForLog(targetCode), taskTypeDesc, phase, promptConfig, interfaceInfo);
            } else {
                taskTypeDesc = subTask.getTaskType().name();
                llmPrompt = buildPromptForTask(targetCode, taskTypeDesc, promptConfig);
            }
            
            // 保存完整LLM请求到任务实体（用于前端完整显示），使用简洁格式
            subTask.setLlmRequest("大模型请求1\n" + llmPrompt);
            
            // 记录详细的LLM请求日志
            saveLog(project, subTask, ScanLog.LogType.LLM_REQUEST,
                    String.format("大模型请求1 | 任务: %s | 类型: %s | 代码长度: %d字符",
                            subTask.getTaskName(), taskTypeDesc, targetCode.length()));
            saveLog(project, subTask, ScanLog.LogType.LLM_REQUEST,
                    "大模型请求1\n" + llmPrompt);

            // 记录调用的模型信息
            String modelName = getLlmConfigName(llmConfigId);
            boolean isThinkingModel = llmConfigId != null && llmService.isThinkingModel(llmConfigId);
            saveLog(project, subTask, ScanLog.LogType.LLM_REQUEST,
                    String.format("模型: %s | 模式: %s",
                            modelName, isThinkingModel ? "思考模式" : "多轮对话模式"));

            // 构建系统提示词和用户问题
            String systemPrompt = buildSystemPrompt(promptConfig, subTask);
            String userPrompt = buildUserPrompt(targetCode, subTask, phase, promptConfig, interfaceInfo);
            
            // 使用多轮对话模式进行分析
            String analysisResult;
            long startTime = System.currentTimeMillis();
            
            // 先进行初始分析（根据是否支持思考模式选择不同方法）
            String initialResponse;
            StringBuilder initialReasoning = new StringBuilder();
            
            if (isThinkingModel) {
                // 使用思考模式
                LlmResponse llmResponse;
                if (subTask.getTaskType() == ScanTask.TaskType.SUB_INTERFACE_VULNERABILITY_SCAN) {
                    llmResponse = llmService.analyzeCodeForVulnerabilityWithThinking(
                            targetCode, subTask.getTaskDescription(), phase, llmConfigId, promptConfig, interfaceInfo);
                } else {
                    llmResponse = llmService.analyzeCodeWithThinking(
                            targetCode, subTask.getTaskType().name(), llmConfigId, promptConfig);
                }
                
                initialResponse = llmResponse.getContent();
                
                // 保存初始思考过程
                if (llmResponse.hasReasoning()) {
                    initialReasoning.append(llmResponse.getReasoningContent());
                    saveLog(project, subTask, ScanLog.LogType.LLM_THINKING,
                            String.format("思考过程1 | 长度: %d字符", llmResponse.getReasoningContent().length()));
                    saveLog(project, subTask, ScanLog.LogType.LLM_THINKING,
                            "思考过程1\n" + llmResponse.getReasoningContent());
                }
            } else {
                // 非思考模式
                if (subTask.getTaskType() == ScanTask.TaskType.SUB_INTERFACE_VULNERABILITY_SCAN) {
                    initialResponse = llmService.analyzeCodeForVulnerabilityWithPrompt(
                            targetCode, subTask.getTaskDescription(), phase, llmConfigId, promptConfig, interfaceInfo);
                } else {
                    initialResponse = llmService.analyzeCodeWithPromptAndTimeout(
                            targetCode, subTask.getTaskType().name(), llmConfigId, promptConfig);
                }
            }
            
            // 使用多轮对话模式处理响应
            analysisResult = processLlmResponseWithProtocolMultiTurn(
                    projectId, subTask, initialResponse, 
                    emitter, llmConfigId, projectCode, targetCode,
                    systemPrompt, userPrompt, initialReasoning);
            
            long duration = System.currentTimeMillis() - startTime;

            // 记录完成日志（实际的内容已在processLlmResponseWithProtocolMultiTurn中按轮次保存）
            saveLog(project, subTask, ScanLog.LogType.LLM_RESPONSE,
                    String.format("分析完成 | 耗时: %dms | 响应长度: %d字符",
                            duration,
                            analysisResult != null ? analysisResult.length() : 0));
            
            // 确保思考过程已保存（如果多轮对话未设置，则使用初始思考过程）
            if ((subTask.getThinkingProcess() == null || subTask.getThinkingProcess().isEmpty()) 
                    && initialReasoning.length() > 0) {
                String thinkingContent = "思考过程1\n" + initialReasoning.toString();
                saveLog(project, subTask, ScanLog.LogType.LLM_THINKING, thinkingContent);
                subTask.setThinkingProcess(thinkingContent);
            }
            
            // 确保响应已保存（如果多轮对话未设置，则使用分析结果）
            if (subTask.getLlmResponse() == null || subTask.getLlmResponse().isEmpty()) {
                if (analysisResult != null) {
                    String responseContent = "大模型回复1\n" + analysisResult;
                    saveLog(project, subTask, ScanLog.LogType.LLM_RESPONSE, responseContent);
                    subTask.setLlmResponse(responseContent);
                } else {
                    subTask.setLlmResponse("");
                }
            }
            
            // 尝试解析思考过程
            if (analysisResult != null) {
                tryExtractAndLogThinking(project, subTask, analysisResult);
            }
            
            subTask.setProgress(70);
            taskRepository.saveAndFlush(subTask);

            parseFindings(project, subTask, analysisResult);

            subTask.setStatus(ScanTask.TaskStatus.COMPLETED);
            subTask.setCompletedAt(LocalDateTime.now());
            subTask.setProgress(100);
            taskRepository.saveAndFlush(subTask);

            project = projectRepository.findById(projectId).orElseThrow();
            project.setCompletedTasks(project.getCompletedTasks() + 1);
            project.setFindingCount(Math.toIntExact(findingRepository.countByProjectId(projectId)));
            projectRepository.saveAndFlush(project);

            saveLog(project, subTask, ScanLog.LogType.TASK_COMPLETE,
                    "完成子任务: " + subTask.getTaskName());

        } catch (Exception e) {
            log.error("执行子任务失败", e);
            String errorMsg = e.getMessage();
            if (errorMsg == null) errorMsg = e.getClass().getSimpleName();

            if (errorMsg.contains("任务已取消")) {
                subTask.setStatus(ScanTask.TaskStatus.CANCELLED);
            } else {
                subTask.setStatus(ScanTask.TaskStatus.FAILED);
                try {
                    saveLog(project, subTask, ScanLog.LogType.ERROR,
                            "子任务失败: " + errorMsg);
                } catch (Exception logEx) {
                    log.warn("保存错误日志失败: {}", logEx.getMessage());
                }
            }
            subTask.setCompletedAt(LocalDateTime.now());
            try {
                taskRepository.saveAndFlush(subTask);
            } catch (Exception saveEx) {
                log.error("保存任务状态失败", saveEx);
            }
        }
    }

    /**
     * 构建通用任务的提示词（用于日志预览）
     */
    private String buildPromptForTask(String code, String taskType, PromptConfig config) {
        StringBuilder prompt = new StringBuilder();
        if (config != null && config.getSystemPrompt() != null && !config.getSystemPrompt().isEmpty()) {
            prompt.append(config.getSystemPrompt()).append("\n\n");
        }
        prompt.append("任务类型: ").append(taskType).append("\n\n");
        prompt.append("代码内容:\n").append(truncateCodeForLog(code)).append("\n\n");
        if (config != null && config.getAnalysisPrompt() != null && !config.getAnalysisPrompt().isEmpty()) {
            prompt.append(config.getAnalysisPrompt());
        }
        return prompt.toString();
    }

    /**
     * 截断代码用于日志记录
     */
    private String truncateCodeForLog(String code) {
        if (code == null) return "";
        int maxLength = 500000;
        if (code.length() <= maxLength) return code;
        // 在最后一个换行处截断，保持代码完整性
        String truncated = code.substring(0, maxLength);
        int lastNewline = truncated.lastIndexOf('\n');
        if (lastNewline > 0) {
            truncated = truncated.substring(0, lastNewline);
        }
        return truncated + "\n\n...[代码过长已截断]";
    }

    /**
     * 截断字符串用于日志
     */
    private String truncateForLog(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "...[截断]" : text;
    }

    /**
     * 从任务中提取文件名信息
     */
    private String extractFileNameFromTask(ScanTask task) {
        // 优先使用filePath
        if (task.getFilePath() != null && !task.getFilePath().isEmpty()) {
            String filePath = task.getFilePath();
            int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
            if (lastSlash >= 0) {
                return filePath.substring(lastSlash + 1);
            }
            return filePath;
        }
        
        // 从targetCode描述中提取文件名
        String targetCode = task.getTargetCode();
        if (targetCode == null || targetCode.isEmpty()) {
            return "项目整体代码";
        }
        // 尝试从描述中提取文件路径
        Pattern filePattern = Pattern.compile("文件[:：]\\s*(.+?)[|\\r\\n]");
        Matcher m = filePattern.matcher(targetCode);
        if (m.find()) {
            String filePath = m.group(1).trim();
            int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
            if (lastSlash >= 0) {
                return filePath.substring(lastSlash + 1);
            }
            return filePath;
        }
        return "描述: " + truncateForLog(targetCode, 30);
    }

    /**
     * 从项目代码中提取指定文件的内容
     * 项目代码格式: ===== File: xxx.java =====
     * 使用字符串查找代替正则表达式，避免大文件导致StackOverflowError
     */
    private String extractFileContentFromProject(String projectCode, String filePath) {
        if (projectCode == null || filePath == null || projectCode.isEmpty()) {
            return null;
        }
        
        // 尝试按文件名（短名称）匹配 - 例如 "pom.xml"
        String fileName = filePath;
        int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            fileName = filePath.substring(lastSlash + 1);
        }
        
        String headerPrefix = "===== File:";
        String headerSuffix = "=====";
        String endMarker = "===== End of Project =====";
        
        // 策略1: 按短文件名匹配（如 "pom.xml"）
        String result = extractByFileName(projectCode, fileName, headerPrefix, headerSuffix, endMarker);
        if (result != null) {
            return result;
        }
        
        // 策略2: 按完整路径匹配（统一用正斜杠）
        String normalizedPath = filePath.replace('\\', '/');
        result = extractByFileName(projectCode, normalizedPath, headerPrefix, headerSuffix, endMarker);
        if (result != null) {
            return result;
        }
        
        // 策略3: 按原始路径匹配
        if (!filePath.equals(normalizedPath)) {
            result = extractByFileName(projectCode, filePath, headerPrefix, headerSuffix, endMarker);
            if (result != null) {
                return result;
            }
        }
        
        return null;
    }
    
    /**
     * 根据文件名或路径在项目代码中提取文件内容
     * 使用字符串查找而非正则，避免StackOverflowError
     */
    private String extractByFileName(String projectCode, String targetName, 
                                     String headerPrefix, String headerSuffix, String endMarker) {
        // 查找文件头标记
        String searchHeader = headerPrefix + " ";
        int searchFrom = 0;
        
        while (searchFrom < projectCode.length()) {
            int headerStart = projectCode.indexOf(searchHeader, searchFrom);
            if (headerStart < 0) {
                break;
            }
            
            // 找到文件头结束位置
            int headerEnd = projectCode.indexOf(headerSuffix, headerStart + searchHeader.length());
            if (headerEnd < 0) {
                break;
            }
            
            // 提取文件路径
            String pathPart = projectCode.substring(headerStart + searchHeader.length(), headerEnd).trim();
            
            // 检查是否匹配目标文件名
            if (pathPart.equals(targetName) || pathPart.endsWith("/" + targetName) || pathPart.endsWith("\\" + targetName)) {
                // 找到内容开始位置
                int contentStart = headerEnd + headerSuffix.length();
                // 跳过可能的换行符
                while (contentStart < projectCode.length() && 
                       (projectCode.charAt(contentStart) == '\r' || projectCode.charAt(contentStart) == '\n')) {
                    contentStart++;
                }
                
                // 查找内容结束位置（下一个文件头或结束标记）
                int contentEnd = projectCode.indexOf(headerPrefix, contentStart);
                int endMarkerPos = projectCode.indexOf(endMarker, contentStart);
                
                if (contentEnd < 0 || (endMarkerPos >= 0 && endMarkerPos < contentEnd)) {
                    contentEnd = endMarkerPos >= 0 ? endMarkerPos : projectCode.length();
                }
                
                if (contentEnd > contentStart) {
                    return projectCode.substring(contentStart, contentEnd).trim();
                }
                return null;
            }
            
            // 继续搜索
            searchFrom = headerEnd + headerSuffix.length();
        }
        
        return null;
    }

    /**
     * 尝试从LLM响应中提取思考过程并记录
     */
    private void tryExtractAndLogThinking(ScanProject project, ScanTask task, String response) {
        if (response == null) return;
        
        // 如果思考过程已经被正确设置（通过多轮对话方法），不再覆盖
        if (task.getThinkingProcess() != null && !task.getThinkingProcess().isEmpty()) return;
        
        StringBuilder thinkingResult = new StringBuilder();
        
        // 常见的思考过程标签
        String[] thinkingPatterns = {"thinking", "思考过程", "reasoning", "analysis", "分析过程"};
        for (String pattern : thinkingPatterns) {
            Pattern p = Pattern.compile(pattern + "[:：]\\s*(.+?)(?:\\n\\n|$)", Pattern.DOTALL);
            Matcher m = p.matcher(response);
            if (m.find()) {
                String thinking = m.group(1).trim();
                if (!thinking.isEmpty()) {
                    thinkingResult.append(thinking);
                    saveLog(project, task, ScanLog.LogType.LLM_THINKING,
                            "【思考过程-完整】\n" + thinking);
                    break;
                }
            }
        }
        
        // 如果没有找到特定标签，尝试提取前500字符作为思考预览
        if (thinkingResult.length() == 0 && response.length() > 100) {
            String preview = response.substring(0, Math.min(500, response.length()));
            thinkingResult.append(preview);
        }
        
        // 保存思考过程到任务
        if (thinkingResult.length() > 0) {
            task.setThinkingProcess(thinkingResult.toString());
        }
    }

    /**
     * 使用多轮对话思考模式处理LLM响应（DeepSeek思考模式）
     * 支持协议标签:
     * - more:文件包名;文件包名:more  -> 请求更多相关文件（多个文件用;分隔）
     * - end:分析内容:end          -> 结束分析
     */
    private String processLlmResponseWithProtocolMultiTurn(
            Long projectId, ScanTask task, String initialResponse,
            SseEmitter emitter, Long llmConfigId, 
            String projectCode, String originalCode,
            String systemPrompt, String userPrompt,
            StringBuilder initialReasoning) {
        
        if (initialResponse == null) return "";

        ScanProject project = projectRepository.findById(projectId).orElse(null);
        String codePath = project != null ? project.getCodePath() : null;

        // 初始化多轮对话历史
        List<Map<String, String>> messages = new ArrayList<>();
        
        // 添加系统提示
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);
        }
        
        // 添加初始用户问题
        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);
        
        // 添加初始LLM响应到对话历史（不带reasoning_content，因为非工具调用场景下不需要）
        Map<String, String> assistantMsg = new HashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", initialResponse);
        messages.add(assistantMsg);

        // 保存第1轮对话的JSON格式
        String round1Json = formatMessagesAsJson(messages);
        task.setLlmRequest("第1轮对话\n" + round1Json);
        task.setLlmResponse("大模型回复1\n" + initialResponse);

        // 保存思考过程1到 task.thinkingProcess（确保第一轮就有值）
        if (initialReasoning != null && initialReasoning.length() > 0) {
            task.setThinkingProcess("思考过程1\n" + initialReasoning.toString());
        } else {
            task.setThinkingProcess("思考过程1\n（模型未返回详细推理过程）");
        }

        taskRepository.saveAndFlush(task);

        StringBuilder finalResult = new StringBuilder(initialResponse);
        StringBuilder finalReasoning = new StringBuilder(initialReasoning != null ? initialReasoning.toString() : "");
        int maxIterations = 15;
        int iteration = 0;
        String response = initialResponse;

        while (iteration < maxIterations) {
            iteration++;

            // 先检查是否包含more标签 - 支持多文件格式: more:file1;file2:more
            Pattern morePattern = Pattern.compile("more:(.*?):more", Pattern.DOTALL);
            Matcher moreMatcher = morePattern.matcher(response);
            boolean hasMoreTag = moreMatcher.find();
            
            // 检查是否包含end标签
            Pattern endPattern = Pattern.compile("end:(.*?):end", Pattern.DOTALL);
            Matcher endMatcher = endPattern.matcher(response);
            boolean hasEndTag = endMatcher.find();

            // 兼容大模型遗漏 ":end" 结尾的情况：end: 之后的全部内容视为最终结论
            // 要求 end: 位于行首，避免误匹配 append:/depend: 等普通单词
            if (!hasEndTag) {
                Pattern endOpenPattern = Pattern.compile("^[ \\t]*end:(.*)$", Pattern.DOTALL | Pattern.MULTILINE);
                Matcher endOpenMatcher = endOpenPattern.matcher(response);
                if (endOpenMatcher.find()) {
                    hasEndTag = true;
                    endMatcher = endOpenMatcher;
                    log.info("[ScanTask] end标签缺少 :end 结尾，已按 end: 后全部内容解析结论, taskId={}", task.getId());
                }
            }

            // 兜底：思考模型偶发把 end 结论写进思考内容而正文缺失标签，
            // 从思考内容中提取 end:Vul!/Safe! 结论，避免误判 INCONCLUSIVE
            // 取最后一个有效匹配（思考中可能引用过 end:Vul!或Safe!开头... 的格式模板，真正结论在末尾）
            if (!hasEndTag && !hasMoreTag && finalReasoning.length() > 0) {
                Matcher rEnd = endPattern.matcher(finalReasoning);
                String lastCandidate = null;
                while (rEnd.find()) {
                    String candidate = rEnd.group(1).trim();
                    if ((candidate.startsWith("Vul!") || candidate.startsWith("Safe!")) && candidate.length() >= 10) {
                        lastCandidate = candidate;
                    }
                }
                if (lastCandidate != null) {
                    response = response + "\n\nend:" + lastCandidate + ":end";
                    endMatcher = endPattern.matcher(response);
                    endMatcher.find();
                    hasEndTag = true;
                    log.info("[ScanTask] 正文缺少结论标签，已从思考内容中提取 end 结论, taskId={}", task.getId());
                }
            }
            
            // 如果只有end标签（没有more标签），直接结束分析
            if (hasEndTag && !hasMoreTag) {
                int roundNum = iteration; // 当前 response 的轮次号

                // 确保 finalReasoning 也追加到 thinkingProcess
                if (finalReasoning.length() > 0) {
                    String existingThinking = task.getThinkingProcess() != null ? task.getThinkingProcess() : "";
                    if (existingThinking.isEmpty()) {
                        task.setThinkingProcess(finalReasoning.toString());
                    }
                }

                String analysisContent = endMatcher.group(1).trim();
                
                // 解析分析结果，判断是否存在漏洞
                String resultLabel;
                String summary = analysisContent;
                
                // 检查是否以 "Vul!" 或 "Safe!" 开头
                if (analysisContent.startsWith("Vul!")) {
                    summary = analysisContent.substring(4).trim(); // 去掉 "Vul!" 前缀
                    task.setHasVulnerability(true);
                    task.setResult("VULNERABILITY_FOUND");
                    resultLabel = "发现漏洞 (Vul!)";
                } else if (analysisContent.startsWith("Safe!")) {
                    summary = analysisContent.substring(5).trim(); // 去掉 "Safe!" 前缀
                    task.setHasVulnerability(false);
                    task.setResult("SAFE");
                    resultLabel = "接口安全 (Safe!)";
                } else {
                    resultLabel = "未指定结果";
                }
                
                // 设置简化的分析结果到task
                task.setAnalysisResult(summary);
                
                // 执行日志：合并为一条简洁的 TASK_COMPLETE（轮数+结论+简要分析）
                String logMsg = String.format("任务完成 | 第%d轮 | %s | 任务: %s",
                        roundNum, resultLabel, task.getTaskName());
                saveLog(project, task, ScanLog.LogType.TASK_COMPLETE, logMsg);
                if (task.getHasVulnerability() != null && task.getHasVulnerability()) {
                    saveLog(project, task, ScanLog.LogType.FINDING, summary);
                }
                
                appendConclusionBlock(task);
                taskRepository.saveAndFlush(task);
                return finalResult.toString();
            }
            
            // 如果有more标签，处理文件请求（即使同时有end标签，也先尝试发送文件让大模型重新分析）
            if (hasMoreTag) {
                String keyword = moreMatcher.group(1).trim();
                saveLog(project, task, ScanLog.LogType.LLM_REQUEST,
                        String.format("【LLM请求更多上下文】迭代#%d | 关键词/文件: %s", iteration, keyword));

                // 解析关键词，支持多个文件用分号或中文分号分隔
                String[] keywords = keyword.split("[;,；]");
                StringBuilder allRelatedFiles = new StringBuilder();
                int foundFileCount = 0;

                for (String kw : keywords) {
                    kw = kw.trim();
                    if (kw.isEmpty()) continue;
                    
                    String relatedContent = findRelatedContent(projectCode, codePath, kw);
                    if (relatedContent != null && !relatedContent.isEmpty()) {
                        allRelatedFiles.append("\n\n===== Related: ").append(kw).append(" =====\n")
                                .append(truncateCodeForLog(relatedContent));
                        foundFileCount++;
                        saveLog(project, task, ScanLog.LogType.FILE_CONTEXT,
                                String.format("【找到相关文件】关键词: %s | 找到 %d 字符", kw, relatedContent.length()));
                    } else {
                        saveLog(project, task, ScanLog.LogType.FILE_CONTEXT,
                                "【未找到文件】关键词: " + kw);
                    }
                }

                if (foundFileCount > 0) {
                    // 将请求更多文件的用户消息添加到对话历史
                    Map<String, String> followUpMsg = new HashMap<>();
                    followUpMsg.put("role", "user");
                    followUpMsg.put("content", String.format(
                            "请分析以下相关文件内容:\n%s",
                            allRelatedFiles));
                    messages.add(followUpMsg);
                    
                    int roundNum = iteration + 1;  // 第N轮对话
                    
                    // 保存当前轮对话的JSON格式到llmRequest
                    String existingRequest = task.getLlmRequest() != null ? task.getLlmRequest() : "";
                    String roundJson = formatMessagesAsJson(messages);
                    String requestSection = String.format("\n\n第%d轮对话\n%s", roundNum, roundJson);
                    task.setLlmRequest(existingRequest + requestSection);
                    
                    saveLog(project, task, ScanLog.LogType.LLM_REQUEST,
                            String.format("大模型请求%d | 找到 %d 个相关文件 | 对话历史长度: %d", 
                                    roundNum, foundFileCount, roundJson.length()));
                    saveLog(project, task, ScanLog.LogType.LLM_REQUEST,
                            "大模型请求" + roundNum + "\n" + roundJson);
                    
                    long startTime = System.currentTimeMillis();
                    try {
                        // 使用带思考模式的多轮对话方法
                        LlmResponse llmResponse = llmService.multiTurnChatWithThinking(messages, llmConfigId, 120000);
                        long duration = System.currentTimeMillis() - startTime;
                        
                        String newResponse = llmResponse.getContent();

                        log.info("[ScanTask] round={} hasReasoning={}, reasoningLen={}, contentLen={}, taskId={}",
                                roundNum, llmResponse.hasReasoning(),
                                llmResponse.hasReasoning() ? llmResponse.getReasoningContent().length() : 0,
                                newResponse != null ? newResponse.length() : 0, task.getId());
                        
                        // 累积 finalReasoning（供 hasEndTag / maxIterations 等 early-return 分支使用）
                        if (llmResponse.hasReasoning()) {
                            finalReasoning.append("\n\n--- Reasoning (iteration ").append(iteration).append(") ---\n")
                                    .append(llmResponse.getReasoningContent());
                        }
                        
                        saveLog(project, task, ScanLog.LogType.LLM_RESPONSE,
                                String.format("大模型回复%d | 耗时: %dms | 响应长度: %d字符",
                                        roundNum, duration, newResponse != null ? newResponse.length() : 0));
                        
                        if (newResponse != null) {
                            saveLog(project, task, ScanLog.LogType.LLM_RESPONSE,
                                    "大模型回复" + roundNum + "\n" + newResponse);
                            
                            // 将新响应添加到对话历史
                            Map<String, String> newAssistantMsg = new HashMap<>();
                            newAssistantMsg.put("role", "assistant");
                            newAssistantMsg.put("content", newResponse);
                            messages.add(newAssistantMsg);
                            
                            response = newResponse;
                            finalResult.append("\n\n--- Multi-Turn Analysis (iteration ")
                                    .append(iteration).append(") ---\n").append(newResponse);

                            // === 统一追加本轮大模型回复和思考过程 ===
                            String existingResponse2 = task.getLlmResponse() != null ? task.getLlmResponse() : "";
                            task.setLlmResponse(existingResponse2 + String.format("\n\n大模型回复%d\n%s", roundNum, newResponse));

                            String existingThinking2 = task.getThinkingProcess() != null ? task.getThinkingProcess() : "";
                            if (llmResponse.hasReasoning() && llmResponse.getReasoningContent() != null) {
                                task.setThinkingProcess(existingThinking2 + String.format("\n\n思考过程%d\n%s", roundNum, llmResponse.getReasoningContent()));
                            } else {
                                task.setThinkingProcess(existingThinking2 + String.format("\n\n思考过程%d\n（模型未返回详细推理过程）", roundNum));
                            }
                            taskRepository.saveAndFlush(task);
                        } else {
                            break;
                        }
                    } catch (Exception e) {
                        log.warn("多轮对话失败(hasMore+foundFile): round={}, err={}", iteration, e.getMessage(), e);
                        saveLog(project, task, ScanLog.LogType.ERROR,
                                "多轮对话失败: " + e.getMessage());
                        appendConclusionBlock(task);
                        taskRepository.saveAndFlush(task);
                        return finalResult.toString();
                    }
                } else {
                    saveLog(project, task, ScanLog.LogType.FILE_CONTEXT,
                            "【无相关文件】所有关键词均未找到匹配文件");
                    // 如果同时有end标签（大模型既请求文件又给出了结论），直接使用end标签的内容
                    if (hasEndTag) {
                        String analysisContent = endMatcher.group(1).trim();
                        int roundNum = iteration; // 当前 response 的轮次号

                        // 确保 thinkingProcess 不丢失
                        String existingThinking = task.getThinkingProcess() != null ? task.getThinkingProcess() : "";
                        if (existingThinking.isEmpty() && finalReasoning.length() > 0) {
                            task.setThinkingProcess(finalReasoning.toString());
                        }

                        // 解析结论
                        String summary = analysisContent;
                        String resultLabel;
                        if (analysisContent.startsWith("Vul!")) {
                            summary = analysisContent.substring(4).trim();
                            task.setHasVulnerability(true);
                            task.setResult("VULNERABILITY_FOUND");
                            resultLabel = "发现漏洞 (Vul!)";
                        } else if (analysisContent.startsWith("Safe!")) {
                            summary = analysisContent.substring(5).trim();
                            task.setHasVulnerability(false);
                            task.setResult("SAFE");
                            resultLabel = "接口安全 (Safe!)";
                        } else {
                            resultLabel = "未指定结果";
                        }
                        task.setAnalysisResult(summary);

                        // 执行日志：合并为一条简洁的 TASK_COMPLETE
                        String logMsg = String.format("任务完成(文件未找到) | 第%d轮 | %s | 任务: %s",
                                roundNum, resultLabel, task.getTaskName());
                        saveLog(project, task, ScanLog.LogType.TASK_COMPLETE, logMsg);
                        if (task.getHasVulnerability() != null && task.getHasVulnerability()) {
                            saveLog(project, task, ScanLog.LogType.FINDING, summary);
                        }

                        appendConclusionBlock(task);
                        taskRepository.saveAndFlush(task);
                        return finalResult.toString();
                    }
                    // 无相关文件，添加一条用户消息说明未找到文件，让LLM继续分析
                    Map<String, String> noFileMsg = new HashMap<>();
                    noFileMsg.put("role", "user");
                    noFileMsg.put("content", "未能找到相关文件，请基于已有信息继续分析，使用 end:Vul!或Safe!开头+简要分析:end 格式输出最终结论。");
                    messages.add(noFileMsg);
                    
                    int roundNum = iteration + 1;  // 第N轮对话
                    
                    // 保存当前轮对话的JSON格式到llmRequest
                    String existingRequest = task.getLlmRequest() != null ? task.getLlmRequest() : "";
                    String roundJson = formatMessagesAsJson(messages);
                    String requestSection = String.format("\n\n第%d轮对话\n%s", roundNum, roundJson);
                    task.setLlmRequest(existingRequest + requestSection);
                    
                    saveLog(project, task, ScanLog.LogType.LLM_REQUEST,
                            String.format("大模型请求%d | 对话历史长度: %d", 
                                    roundNum, roundJson.length()));
                    saveLog(project, task, ScanLog.LogType.LLM_REQUEST,
                            "大模型请求" + roundNum + "\n" + roundJson);
                    
                    // 进行下一轮对话
                    try {
                        long startTime = System.currentTimeMillis();
                        LlmResponse llmResponse = llmService.multiTurnChatWithThinking(messages, llmConfigId, 120000);
                        long duration = System.currentTimeMillis() - startTime;
                        String newResponse = llmResponse.getContent();
                        
                        log.info("[ScanTask] round={} hasReasoning={}, reasoningLen={}, contentLen={}, taskId={}",
                                roundNum, llmResponse.hasReasoning(),
                                llmResponse.hasReasoning() ? llmResponse.getReasoningContent().length() : 0,
                                newResponse != null ? newResponse.length() : 0, task.getId());
                        
                        // 累积 finalReasoning（供 hasEndTag / maxIterations 等 early-return 分支使用）
                        if (llmResponse.hasReasoning()) {
                            finalReasoning.append("\n\n--- Reasoning (iteration ").append(iteration).append(") ---\n")
                                    .append(llmResponse.getReasoningContent());
                        }
                        
                        saveLog(project, task, ScanLog.LogType.LLM_RESPONSE,
                                String.format("大模型回复%d | 耗时: %dms | 响应长度: %d字符",
                                        roundNum, duration, newResponse != null ? newResponse.length() : 0));
                        
                        if (newResponse != null) {
                            saveLog(project, task, ScanLog.LogType.LLM_RESPONSE,
                                    "大模型回复" + roundNum + "\n" + newResponse);
                            
                            Map<String, String> newAssistantMsg = new HashMap<>();
                            newAssistantMsg.put("role", "assistant");
                            newAssistantMsg.put("content", newResponse);
                            messages.add(newAssistantMsg);
                            
                            response = newResponse;
                            finalResult.append("\n\n--- No File Found Analysis (iteration ")
                                    .append(iteration).append(") ---\n").append(newResponse);

                            // === 统一追加本轮大模型回复和思考过程 ===
                            String existingResponse3 = task.getLlmResponse() != null ? task.getLlmResponse() : "";
                            task.setLlmResponse(existingResponse3 + String.format("\n\n大模型回复%d\n%s", roundNum, newResponse));

                            String existingThinking3 = task.getThinkingProcess() != null ? task.getThinkingProcess() : "";
                            if (llmResponse.hasReasoning() && llmResponse.getReasoningContent() != null) {
                                task.setThinkingProcess(existingThinking3 + String.format("\n\n思考过程%d\n%s", roundNum, llmResponse.getReasoningContent()));
                            } else {
                                task.setThinkingProcess(existingThinking3 + String.format("\n\n思考过程%d\n（模型未返回详细推理过程）", roundNum));
                            }
                            taskRepository.saveAndFlush(task);
                        } else {
                            break;
                        }
                    } catch (Exception e) {
                        log.warn("多轮对话失败(hasMore+noFile): round={}, err={}", iteration, e.getMessage(), e);
                        saveLog(project, task, ScanLog.LogType.ERROR,
                                "多轮对话失败: " + e.getMessage());
                        appendConclusionBlock(task);
                        taskRepository.saveAndFlush(task);
                        return finalResult.toString();
                    }
                }
            } else {
                // 没有more也没有end，检查响应中是否有漏洞发现但未使用end标签
                int roundNum = iteration;

                // 确保 thinkingProcess 不丢失
                String existingThinking = task.getThinkingProcess() != null ? task.getThinkingProcess() : "";
                if (existingThinking.isEmpty() && finalReasoning.length() > 0) {
                    task.setThinkingProcess(finalReasoning.toString());
                } else if (existingThinking.isEmpty()) {
                    task.setThinkingProcess("基于代码静态分析的自动化审计结果");
                }

                // 设默认结论：未检测到 Vul!/Safe! 标签
                task.setAnalysisResult("未检测到明确结论标签，基于已有对话内容结束分析");
                task.setResult("INCONCLUSIVE");

                String hint = (response.contains("漏洞") || response.contains("风险") || response.contains("finding"))
                        ? "检测到漏洞相关内容但未使用end标签" : "正常结束";
                saveLog(project, task, ScanLog.LogType.TASK_COMPLETE,
                        String.format("任务完成(%s) | 第%d轮 | 未明确(Vul!/Safe!) | 任务: %s",
                                hint, roundNum, task.getTaskName()));

                appendConclusionBlock(task);
                taskRepository.saveAndFlush(task);
                break;
            }
        }

        // 如果达到最大迭代次数仍未结束，保存当前结果
        if (iteration >= maxIterations) {
            int roundNum = iteration;

            // 确保 thinkingProcess 不丢失
            String existingThinking = task.getThinkingProcess() != null ? task.getThinkingProcess() : "";
            if (existingThinking.isEmpty() && finalReasoning.length() > 0) {
                task.setThinkingProcess(finalReasoning.toString());
            } else if (existingThinking.isEmpty()) {
                task.setThinkingProcess("基于代码静态分析的自动化审计结果");
            }

            // 设默认结论
            task.setAnalysisResult("达到最大迭代次数(" + maxIterations + ")，未收到明确结论标签");
            task.setResult("INCONCLUSIVE");

            saveLog(project, task, ScanLog.LogType.TASK_COMPLETE,
                    String.format("任务完成(最大轮次) | 第%d轮 | 未明确(Vul!/Safe!) | 任务: %s",
                            roundNum, task.getTaskName()));

            appendConclusionBlock(task);
            taskRepository.saveAndFlush(task);
        }

        return finalResult.toString();
    }

    /**
     * 在 llmResponse 和 thinkingProcess 末尾追加统一的扫描结论块
     * 包含：是否存在漏洞、结论类型、简要分析，供后续审计报告使用
     */
    private void appendConclusionBlock(ScanTask task) {
        Boolean hasVul = task.getHasVulnerability();
        String result = task.getResult();
        String summary = task.getAnalysisResult();

        // 构建结论文本
        StringBuilder conclusion = new StringBuilder();
        conclusion.append("\n\n============================================================\n");
        conclusion.append("【任务扫描结论】\n");
        conclusion.append("============================================================\n");
        if (hasVul != null && hasVul) {
            conclusion.append("是否存在漏洞: 是\n");
            conclusion.append("结论类型: VULNERABILITY_FOUND (Vul!)\n");
        } else if (hasVul != null && !hasVul) {
            conclusion.append("是否存在漏洞: 否\n");
            conclusion.append("结论类型: SAFE (Safe!)\n");
        } else {
            conclusion.append("是否存在漏洞: 未明确\n");
            conclusion.append("结论类型: ").append(result != null ? result : "INCONCLUSIVE").append("\n");
        }
        if (summary != null && !summary.isEmpty()) {
            conclusion.append("简要分析: ").append(summary).append("\n");
        }
        conclusion.append("============================================================");

        // 追加到 llmResponse 末尾
        String existingResponse = task.getLlmResponse() != null ? task.getLlmResponse() : "";
        task.setLlmResponse(existingResponse + conclusion);

        // 追加到 thinkingProcess 末尾（如果为空则设默认值）
        String existingThinking = task.getThinkingProcess() != null ? task.getThinkingProcess() : "";
        if (existingThinking.isEmpty()) {
            task.setThinkingProcess("基于代码静态分析的自动化审计结果");
            existingThinking = task.getThinkingProcess();
        }
        task.setThinkingProcess(existingThinking + conclusion);
    }

    /**
     * 格式化对话历史为日志字符串
     * @param messages 对话历史列表
     * @return 格式化后的字符串
     */
    private String formatMessagesForLog(List<Map<String, String>> messages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Map<String, String> msg = messages.get(i);
            String role = msg.get("role");
            String content = msg.get("content");
            
            if (role == null) role = "unknown";
            if (content == null) content = "";
            
            // 使用简洁格式显示每轮消息
            sb.append(String.format("[%s]%n", role.toUpperCase()));
            sb.append(content);
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 将消息列表格式化为DeepSeek标准的JSON格式
     * 示例:
     * [
     *   {"role": "user", "content": "..."},
     *   {"role": "assistant", "content": "..."}
     * ]
     */
    private String formatMessagesAsJson(List<Map<String, String>> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < messages.size(); i++) {
            Map<String, String> msg = messages.get(i);
            String role = msg.get("role");
            String content = msg.get("content");
            
            if (role == null) role = "unknown";
            if (content == null) content = "";
            
            // 转义JSON特殊字符
            String escapedContent = content.replace("\\", "\\\\")
                                          .replace("\"", "\\\"")
                                          .replace("\n", "\\n")
                                          .replace("\r", "\\r")
                                          .replace("\t", "\\t");
            
            sb.append(String.format("  {\"role\": \"%s\", \"content\": \"%s\"}", role, escapedContent));
            
            if (i < messages.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * @deprecated 使用 processLlmResponseWithProtocolMultiTurn 替代
     */
    @Deprecated
    private String processLlmResponseWithProtocol(Long projectId, ScanTask task, String response,
                                                    SseEmitter emitter, Long llmConfigId, 
                                                    String projectCode, String originalCode) {
        return processLlmResponseWithProtocolMultiTurn(
                projectId, task, response, emitter, llmConfigId, 
                projectCode, originalCode, "", "", new StringBuilder());
    }

    /**
     * 查找相关内容 - 支持类名、包名、关键词等多种方式
     * 优先根据类名/完整包名直接查找文件
     */
    private String findRelatedContent(String projectCode, String codePath, String keyword) {
        if (keyword == null || keyword.isEmpty()) return null;
        
        // 1. 优先使用fileService按类名/包名直接查找文件
        if (codePath != null && !codePath.isEmpty()) {
            try {
                // 尝试按类名查找（支持完整包名如 com.example.MyClass 或短类名 MyClass）
                String classFileResult = fileService.findFileByClassName(codePath, keyword);
                if (classFileResult != null && !classFileResult.isEmpty()) {
                    return classFileResult;
                }
                
                // 如果是完整包名（包含多个点号），提取短类名再次尝试
                if (keyword.contains(".") && keyword.chars().filter(ch -> ch == '.').count() >= 2) {
                    String shortClassName = keyword.substring(keyword.lastIndexOf('.') + 1);
                    log.info("按完整包名查找失败，尝试用短类名: {}", shortClassName);
                    String shortNameResult = fileService.findFileByClassName(codePath, shortClassName);
                    if (shortNameResult != null && !shortNameResult.isEmpty()) {
                        return shortNameResult;
                    }
                }
            } catch (Exception e) {
                log.debug("按类名查找文件失败: {}", e.getMessage());
            }
        }
        
        // 2. 回退到内容搜索方式（最后手段）
        if (codePath != null && !codePath.isEmpty()) {
            try {
                String result = fileService.findRelatedFiles(codePath, keyword);
                if (result != null && !result.isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                log.debug("fileService查找失败: {}", e.getMessage());
            }
        }
        
        // 3. 从项目代码中按关键词查找（兜底）
        if (keyword.contains(".")) {
            String shortClassName = keyword.substring(keyword.lastIndexOf('.') + 1);
            String packageName = keyword.substring(0, keyword.lastIndexOf('.'));
            
            String classContent = findClassContent(projectCode, shortClassName);
            if (classContent != null) {
                return classContent;
            }
            
            String packageContent = findPackageContent(projectCode, packageName);
            if (packageContent != null) {
                return packageContent;
            }
        }
        
        String content = findClassContent(projectCode, keyword);
        if (content != null) {
            return content;
        }
        
        return findKeywordContent(projectCode, keyword);
    }
    
    /**
     * 按类名查找类的完整内容
     */
    private String findClassContent(String projectCode, String className) {
        // 查找类定义
        Pattern classPattern = Pattern.compile(
                "class\\s+" + Pattern.quote(className) + "\\b", Pattern.DOTALL);
        Matcher m = classPattern.matcher(projectCode);
        if (!m.find()) {
            // 尝试查找接口定义
            classPattern = Pattern.compile(
                    "interface\\s+" + Pattern.quote(className) + "\\b", Pattern.DOTALL);
            m = classPattern.matcher(projectCode);
        }
        
        if (m.find()) {
            int start = m.start();
            // 向前找到文件开始或上一个文件结束标记
            int fileStart = start;
            Pattern fileMarker = Pattern.compile("文件[:：]\\s*");
            Matcher fm = fileMarker.matcher(projectCode.substring(0, start));
            while (fm.find()) {
                fileStart = fm.start();
            }
            
            // 向后找到类结束（简单估计，取最多2000字符）
            int end = Math.min(projectCode.length(), start + 2000);
            
            // 确保包含完整的类定义
            String snippet = projectCode.substring(fileStart, end);
            return "找到类 " + className + ":\n" + snippet;
        }
        
        return null;
    }
    
    /**
     * 按包名查找相关导入和使用
     */
    private String findPackageContent(String projectCode, String packageName) {
        // 查找import语句
        Pattern importPattern = Pattern.compile(
                "import\\s+" + Pattern.quote(packageName) + "\\.\\w+\\s*;", Pattern.DOTALL);
        Matcher m = importPattern.matcher(projectCode);
        
        StringBuilder result = new StringBuilder();
        int importCount = 0;
        while (m.find() && importCount < 10) {
            result.append(m.group()).append("\n");
            importCount++;
        }
        
        if (importCount > 0) {
            return "包 " + packageName + " 的导入语句:\n" + result.toString();
        }
        
        return null;
    }
    
    /**
     * 按关键词搜索代码内容
     */
    private String findKeywordContent(String projectCode, String keyword) {
        // 使用关键词进行正则匹配
        Pattern keywordPattern = Pattern.compile(Pattern.quote(keyword), Pattern.DOTALL);
        Matcher m = keywordPattern.matcher(projectCode);
        
        StringBuilder result = new StringBuilder();
        int matchCount = 0;
        int maxMatches = 5;
        
        while (m.find() && matchCount < maxMatches) {
            int start = Math.max(0, m.start() - 100);
            int end = Math.min(projectCode.length(), m.end() + 200);
            
            result.append("--- 匹配 ").append(matchCount + 1).append(" ---\n");
            result.append(projectCode.substring(start, end)).append("\n\n");
            matchCount++;
        }
        
        if (matchCount > 0) {
            return "关键词 '" + keyword + "' 的匹配结果:\n" + result.toString();
        }
        
        return null;
    }

    private String getPhaseFromTaskType(ScanTask.TaskType taskType) {
        return switch (taskType) {
            case DEPENDENCY_SCAN, SUB_DEPENDENCY_SCAN -> "DEPENDENCY_SCAN";
            case INTERFACE_SCAN, SUB_INTERFACE_SCAN, SUB_INTERFACE_VULNERABILITY_SCAN -> "INTERFACE_SCAN";
            case HIGH_RISK_OPERATION_SCAN, SUB_HIGH_RISK_SCAN -> "HIGH_RISK_OPERATION_SCAN";
            default -> "DEPENDENCY_SCAN";
        };
    }

    /**
     * 为接口扫描子任务创建漏洞类型孙任务（仅创建，不执行）
     * 根据启用的接口扫描配置创建孙任务，配置名称即为漏洞类型
     */
    @Transactional
    public void createVulnerabilityGrandchildTasks(ScanTask interfaceSubTask, String projectCode) {
        ScanProject project = projectRepository.findById(interfaceSubTask.getProject().getId())
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        // 获取父任务的文件路径和描述
        String parentFilePath = interfaceSubTask.getFilePath();
        String parentDesc = interfaceSubTask.getTargetCode();  // 接口描述信息

        // 获取启用的接口扫描配置
        List<PromptConfig> configs = getEnabledInterfaceScanConfigs();
        
        // 如果没有启用的接口扫描配置，跳过创建孙任务
        if (configs.isEmpty()) {
            log.info("接口扫描子任务 [{}] 未启用任何接口扫描配置，跳过创建孙任务", interfaceSubTask.getTaskName());
            saveLog(project, interfaceSubTask, ScanLog.LogType.INFO,
                    "未启用任何接口扫描配置，跳过创建漏洞分析子任务");
            return;
        }
        
        List<ScanTask> tasksToSave = new ArrayList<>();
        List<String> taskNames = new ArrayList<>();

        for (PromptConfig config : configs) {
            // 只存储接口描述 + 漏洞类型，不存储代码内容
            String vulnDesc = String.format("%s | 漏洞类型: %s", 
                    parentDesc != null ? parentDesc : "", config.getName());
                    
            ScanTask vulnSubTask = ScanTask.builder()
                    .project(project)
                    .parentTask(interfaceSubTask)
                    .taskName(interfaceSubTask.getTaskName() + " - " + config.getName())
                    .taskDescription(config.getName())
                    .taskType(ScanTask.TaskType.SUB_INTERFACE_VULNERABILITY_SCAN)
                    .targetCode(vulnDesc)  // 存描述信息，不存代码
                    .filePath(parentFilePath)  // 继承父任务的文件路径
                    .status(ScanTask.TaskStatus.PENDING)
                    .progress(0)
                    .llmConfigId(interfaceSubTask.getLlmConfigId())
                    .llmConfigName(interfaceSubTask.getLlmConfigName())
                    .promptConfigId(config.getId())
                    .build();
            tasksToSave.add(vulnSubTask);
            taskNames.add(config.getName());
        }

        // 批量保存，减少数据库交互
        taskRepository.saveAll(tasksToSave);

        interfaceSubTask.setProgress(addProgress(interfaceSubTask.getProgress(), 5));
        taskRepository.save(interfaceSubTask);

        project.setTotalTasks(project.getTotalTasks() + tasksToSave.size());
        projectRepository.save(project);
        
        log.info("接口扫描子任务 [{}] 创建了 {} 个漏洞类型孙任务: {}", 
                interfaceSubTask.getTaskName(), tasksToSave.size(), String.join(", ", taskNames));
    }

    /**
     * 获取启用的接口扫描配置列表
     * 返回配置实体列表，用于创建孙任务
     */
    private List<PromptConfig> getEnabledInterfaceScanConfigs() {
        return promptConfigService.getEnabledPromptConfigEntitiesByPhase("INTERFACE_SCAN");
    }

    /**
     * 按启用的高危扫描配置创建子任务和孙任务
     * 第一层：按配置名称创建子任务
     * 第二层：按匹配到的文件位置创建孙任务
     * 只有匹配到危险函数的配置才会创建任务
     */
    @Transactional
    public void createHighRiskSubTasksByConfig(ScanTask highRiskTask, String projectCode) {
        ScanProject project = projectRepository.findById(highRiskTask.getProject().getId())
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        Map<PromptConfig, List<DangerousFunctionInfo>> configMap = findDangerousFunctionsByConfig(projectCode);
        
        if (configMap.isEmpty()) {
            log.info("高危操作扫描没有启用的配置或未匹配到危险函数，跳过创建子任务");
            saveLog(project, highRiskTask, ScanLog.LogType.INFO,
                    "高危操作扫描没有启用的配置或未匹配到危险函数");
            return;
        }

        int totalTaskCount = 0;

        for (Map.Entry<PromptConfig, List<DangerousFunctionInfo>> configEntry : configMap.entrySet()) {
            PromptConfig config = configEntry.getKey();
            List<DangerousFunctionInfo> dangerousFunctions = configEntry.getValue();
            String configName = config.getName();

            // 按文件路径分组
            Map<String, List<DangerousFunctionInfo>> fileGroups = new LinkedHashMap<>();
            for (DangerousFunctionInfo func : dangerousFunctions) {
                String filePath = func.getFilePath() != null ? func.getFilePath() : "未知文件";
                fileGroups.computeIfAbsent(filePath, k -> new ArrayList<>()).add(func);
            }

            // 第一层：按配置名称创建子任务，关联配置ID
            // 只存储描述信息，不存储代码内容
            String configDesc = String.format("高危扫描类型: %s，共匹配 %d 处危险点，分布在 %d 个文件",
                    configName, dangerousFunctions.size(), fileGroups.size());
                    
            ScanTask configSubTask = ScanTask.builder()
                    .project(project)
                    .parentTask(highRiskTask)
                    .taskName(configName)
                    .taskDescription(configDesc)
                    .taskType(ScanTask.TaskType.SUB_HIGH_RISK_SCAN)
                    .targetCode(configDesc)  // 存描述信息，不存代码
                    .filePath(dangerousFunctions.get(0).getFilePath())  // 绑定第一个匹配文件的路径
                    .status(ScanTask.TaskStatus.PENDING)
                    .progress(0)
                    .llmConfigId(highRiskTask.getLlmConfigId())
                    .llmConfigName(highRiskTask.getLlmConfigName())
                    .promptConfigId(config.getId())
                    .build();
            taskRepository.save(configSubTask);

            // 第二层：按文件位置创建孙任务
            List<ScanTask> grandchildTasks = new ArrayList<>();
            for (Map.Entry<String, List<DangerousFunctionInfo>> fileEntry : fileGroups.entrySet()) {
                String filePath = fileEntry.getKey();
                List<DangerousFunctionInfo> functions = fileEntry.getValue();

                StringBuilder funcNames = new StringBuilder();
                StringBuilder funcLocations = new StringBuilder();

                for (DangerousFunctionInfo func : functions) {
                    if (funcNames.length() > 0) {
                        funcNames.append(", ");
                        funcLocations.append("; ");
                    }
                    funcNames.append(func.getFunctionName());
                    funcLocations.append(String.format("%s:%d", func.getFunctionName(), func.getLineNumber()));
                }

                // 使用文件位置命名
                String fileName = filePath.contains("/") || filePath.contains("\\") 
                        ? filePath.substring(Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\')) + 1)
                        : filePath;

                String grandchildName = functions.size() > 1
                        ? String.format("%s - %d处(%s)", fileName, functions.size(), funcNames)
                        : String.format("%s:%d - %s", fileName, functions.get(0).getLineNumber(), functions.get(0).getFunctionName());

                // 只存储描述信息，不存储代码内容
                String grandchildDesc = String.format("%s | 文件: %s | 危险函数: %s | 位置: %s",
                        configName, filePath, funcNames, funcLocations);

                ScanTask grandchildTask = ScanTask.builder()
                        .project(project)
                        .parentTask(configSubTask)
                        .taskName(grandchildName)
                        .taskDescription(configName + " - 分析危险函数: " + funcNames)
                        .taskType(ScanTask.TaskType.SUB_HIGH_RISK_SCAN)
                        .targetCode(grandchildDesc)  // 存描述信息，不存代码
                        .filePath(filePath)  // 绑定文件路径
                        .status(ScanTask.TaskStatus.PENDING)
                        .progress(0)
                        .llmConfigId(highRiskTask.getLlmConfigId())
                        .llmConfigName(highRiskTask.getLlmConfigName())
                        .promptConfigId(config.getId())
                        .build();
                grandchildTasks.add(grandchildTask);
            }

            // 批量保存孙任务
            taskRepository.saveAll(grandchildTasks);
            totalTaskCount += 1 + grandchildTasks.size(); // 子任务 + 孙任务

            log.info("高危扫描配置 [{}] 创建了 1 个子任务 + {} 个孙任务", configName, grandchildTasks.size());
        }

        highRiskTask.setProgress(addProgress(highRiskTask.getProgress(), 5));
        taskRepository.save(highRiskTask);

        project.setTotalTasks(project.getTotalTasks() + totalTaskCount);
        projectRepository.save(project);

        log.info("高危操作扫描共创建 {} 个任务 ({} 个配置)", totalTaskCount, configMap.size());
    }
    
    /**
     * 构建合并的上下文代码
     */
    private String buildCombinedContext(String projectCode, List<DangerousFunctionInfo> functions) {
        StringBuilder context = new StringBuilder();
        for (DangerousFunctionInfo func : functions) {
            String funcCode = extractFunctionContext(projectCode, func);
            context.append(funcCode).append("\n\n");
        }
        return context.toString();
    }
    
    /**
     * 兼容旧接口：按文件位置分组创建高危子任务
     */
    @Transactional
    public void createHighRiskSubTasks(ScanTask highRiskTask,
                                        List<DangerousFunctionInfo> dangerousFunctions,
                                        String projectCode) {
        ScanProject project = projectRepository.findById(highRiskTask.getProject().getId())
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        // 按文件路径和行号分组
        Map<String, List<DangerousFunctionInfo>> fileGroups = new LinkedHashMap<>();
        for (DangerousFunctionInfo func : dangerousFunctions) {
            String filePath = func.getFilePath() != null ? func.getFilePath() : "未知文件";
            fileGroups.computeIfAbsent(filePath, k -> new ArrayList<>()).add(func);
        }

        List<ScanTask> tasksToSave = new ArrayList<>();

        for (Map.Entry<String, List<DangerousFunctionInfo>> entry : fileGroups.entrySet()) {
            String filePath = entry.getKey();
            List<DangerousFunctionInfo> functions = entry.getValue();

            StringBuilder groupCode = new StringBuilder();
            StringBuilder funcNames = new StringBuilder();

            for (DangerousFunctionInfo func : functions) {
                String funcCode = extractFunctionContext(projectCode, func);
                groupCode.append(funcCode).append("\n\n");
                if (funcNames.length() > 0) {
                    funcNames.append(", ");
                }
                funcNames.append(func.getFunctionName());
            }

            // 使用文件位置命名
            String fileName = filePath.contains("/") || filePath.contains("\\") 
                    ? filePath.substring(Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\')) + 1)
                    : filePath;
                    
            String groupName = functions.size() > 1
                    ? String.format("%s - %d处危险函数(%s)", fileName, functions.size(), funcNames)
                    : String.format("%s:%d - %s", fileName, functions.get(0).getLineNumber(), functions.get(0).getFunctionName());

            ScanTask subTask = ScanTask.builder()
                    .project(project)
                    .parentTask(highRiskTask)
                    .taskName(groupName)
                    .taskDescription("分析危险函数: " + funcNames)
                    .taskType(ScanTask.TaskType.SUB_HIGH_RISK_SCAN)
                    .targetCode(groupCode.toString())
                    .status(ScanTask.TaskStatus.PENDING)
                    .progress(0)
                    .llmConfigId(highRiskTask.getLlmConfigId())
                    .llmConfigName(highRiskTask.getLlmConfigName())
                    .build();
            tasksToSave.add(subTask);
        }

        // 批量保存
        taskRepository.saveAll(tasksToSave);

        highRiskTask.setProgress(addProgress(highRiskTask.getProgress(), 5));
        taskRepository.save(highRiskTask);

        project.setTotalTasks(project.getTotalTasks() + tasksToSave.size());
        projectRepository.save(project);
    }

    /**
     * 执行所有PENDING状态的任务
     * @return true 如果有任何阶段失败，false 如果所有阶段都成功
     */
    private boolean executeAllTasks(Long projectId, SseEmitter emitter, Long llmConfigId, String projectCode) {
        ScanProject project = projectRepository.findById(projectId).orElseThrow();

        // 更新阶段任务状态为RUNNING
        updatePhaseTaskStatus(projectId, "依赖扫描", ScanTask.TaskStatus.RUNNING);
        updatePhaseTaskStatus(projectId, "接口扫描", ScanTask.TaskStatus.RUNNING);
        updatePhaseTaskStatus(projectId, "高危操作扫描", ScanTask.TaskStatus.RUNNING);

        boolean dependencyFailed = false;
        boolean interfaceFailed = false;
        boolean highRiskFailed = false;

        // 按层级执行任务（每个阶段结束后立即更新该阶段父任务状态，避免全程 RUNNING）
        // 阶段父任务只有两种终态：未被终止即已完成（子任务失败不影响），终止则已取消
        try {
            // 1. 执行依赖子任务
            self.executeTasksByType(projectId, emitter, llmConfigId, projectCode,
                    List.of(ScanTask.TaskType.SUB_DEPENDENCY_SCAN));
            // 检查依赖子任务是否有失败
            dependencyFailed = checkPhaseHasFailures(projectId, ScanTask.TaskType.SUB_DEPENDENCY_SCAN);
            updatePhaseTaskStatus(projectId, "依赖扫描",
                    taskService.isCancelled(projectId) ? ScanTask.TaskStatus.CANCELLED : ScanTask.TaskStatus.COMPLETED);
        } catch (Exception e) {
            log.warn("依赖子任务执行异常: {}", e.getMessage());
            dependencyFailed = true;
            updatePhaseTaskStatus(projectId, "依赖扫描",
                    taskService.isCancelled(projectId) ? ScanTask.TaskStatus.CANCELLED : ScanTask.TaskStatus.COMPLETED);
        }

        try {
            // 2. 直接执行漏洞孙任务（接口子任务下的漏洞类型任务）
            // 接口子任务不执行 LLM，而是作为容器，随孙任务完成实时汇总状态
            self.executeTasksByType(projectId, emitter, llmConfigId, projectCode,
                    List.of(ScanTask.TaskType.SUB_INTERFACE_VULNERABILITY_SCAN));

            // 最终兜底：汇总接口子任务的执行结果
            aggregateInterfaceSubTasks(projectId);
            // 检查漏洞孙任务是否有失败
            interfaceFailed = checkPhaseHasFailures(projectId, ScanTask.TaskType.SUB_INTERFACE_VULNERABILITY_SCAN);
            updatePhaseTaskStatus(projectId, "接口扫描",
                    taskService.isCancelled(projectId) ? ScanTask.TaskStatus.CANCELLED : ScanTask.TaskStatus.COMPLETED);
        } catch (Exception e) {
            log.warn("漏洞孙任务执行异常: {}", e.getMessage());
            interfaceFailed = true;
            updatePhaseTaskStatus(projectId, "接口扫描",
                    taskService.isCancelled(projectId) ? ScanTask.TaskStatus.CANCELLED : ScanTask.TaskStatus.COMPLETED);
        }

        try {
            // 3. 执行高危扫描孙任务
            self.executeHighRiskGrandchildTasks(projectId, emitter, llmConfigId, projectCode);
            // 检查高危扫描孙任务是否有失败
            highRiskFailed = checkPhaseHasFailures(projectId, ScanTask.TaskType.SUB_HIGH_RISK_SCAN);
            updatePhaseTaskStatus(projectId, "高危操作扫描",
                    taskService.isCancelled(projectId) ? ScanTask.TaskStatus.CANCELLED : ScanTask.TaskStatus.COMPLETED);
        } catch (Exception e) {
            log.warn("高危扫描孙任务执行异常: {}", e.getMessage());
            highRiskFailed = true;
            updatePhaseTaskStatus(projectId, "高危操作扫描",
                    taskService.isCancelled(projectId) ? ScanTask.TaskStatus.CANCELLED : ScanTask.TaskStatus.COMPLETED);
        }

        try {
            project = projectRepository.findById(projectId).orElseThrow();
            saveLog(projectId, null, ScanLog.LogType.TASK_COMPLETE, "所有扫描任务执行完成");
        } catch (Exception e) {
            log.error("保存完成日志失败", e);
        }
        
        // 返回是否有任何阶段失败
        return dependencyFailed || interfaceFailed || highRiskFailed;
    }
    
    /**
     * 检查指定阶段的任务是否有失败
     */
    private boolean checkPhaseHasFailures(Long projectId, ScanTask.TaskType taskType) {
        List<ScanTask> tasks = taskRepository.findByProjectIdAndTaskType(projectId, taskType);
        return tasks.stream().anyMatch(t -> t.getStatus() == ScanTask.TaskStatus.FAILED);
    }
    
    /**
     * 执行高危扫描孙任务（两层结构）
     * 1. 找到文件位置级别的孙任务（没有子任务的叶子节点）
     * 2. 逐个执行（每个任务独立事务）
     * 3. 孙任务完成后，将父任务（配置级）标记为完成
     */
    public void executeHighRiskGrandchildTasks(Long projectId, SseEmitter emitter, 
                                                 Long llmConfigId, String projectCode) {
        // 获取所有高危扫描任务
        List<ScanTask> allHighRiskTasks = taskRepository.findByProjectIdAndTaskTypeIn(projectId,
                List.of(ScanTask.TaskType.SUB_HIGH_RISK_SCAN));
        
        // 找出叶子任务（没有子任务的孙任务）
        List<ScanTask> leafTasks = new ArrayList<>();
        
        for (ScanTask task : allHighRiskTasks) {
            long childCount = taskRepository.countByParentTaskId(task.getId());
            
            if (childCount == 0 && task.getStatus() == ScanTask.TaskStatus.PENDING) {
                leafTasks.add(task);
            }
        }
        
        if (leafTasks.isEmpty()) {
            log.info("没有待执行的高危扫描孙任务");
            return;
        }
        
        log.info("高危扫描：找到 {} 个待执行的孙任务", leafTasks.size());
        
        int successCount = 0;
        int failCount = 0;
        
        // 执行所有叶子任务（每个任务独立事务）
        for (ScanTask task : leafTasks) {
            // 终止后立即退出循环，避免继续覆盖任务状态
            if (taskService.isCancelled(projectId)) {
                log.info("检测到扫描已终止，停止调度剩余高危孙任务");
                break;
            }
            try {
                self.executeSingleTask(projectId, task, emitter, llmConfigId, projectCode);
                
                if (task.getStatus() == ScanTask.TaskStatus.COMPLETED) {
                    successCount++;
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                log.warn("执行高危孙任务异常: {} - {}", task.getTaskName(), e.getMessage());
                failCount++;
            }
            
            // 检查父任务是否所有子任务都完成了
            if (task.getParentTaskId() != null) {
                checkAndCompleteParentTask(task.getParentTaskId());
            }
        }
        
        log.info("高危扫描孙任务执行完成: 成功={}, 失败={}", successCount, failCount);
    }
    
    /**
     * 汇总接口子任务状态
     * 当所有漏洞孙任务完成后，汇总接口子任务的执行状态和结果
     */
    public void aggregateInterfaceSubTasks(Long projectId) {
        List<ScanTask> interfaceSubTasks = taskRepository.findByProjectIdAndTaskType(
                projectId, ScanTask.TaskType.SUB_INTERFACE_SCAN);
        
        if (interfaceSubTasks.isEmpty()) {
            log.info("没有接口子任务需要汇总");
            return;
        }
        
        log.info("开始汇总 {} 个接口子任务的状态", interfaceSubTasks.size());
        
        for (ScanTask subTask : interfaceSubTasks) {
            // 获取该子任务下的所有漏洞孙任务
            List<ScanTask> vulnTasks = taskRepository.findByParentTaskId(subTask.getId());
            
            if (vulnTasks.isEmpty()) {
                // 没有孙任务，直接标记完成
                subTask.setStatus(ScanTask.TaskStatus.COMPLETED);
                subTask.setProgress(100);
                subTask.setCompletedAt(LocalDateTime.now());
                subTask.setLlmResponse("该接口未配置漏洞类型扫描");
                taskRepository.saveAndFlush(subTask);
                continue;
            }
            
            // 统计孙任务状态
            long completedCount = vulnTasks.stream()
                    .filter(t -> t.getStatus() == ScanTask.TaskStatus.COMPLETED)
                    .count();
            long failedCount = vulnTasks.stream()
                    .filter(t -> t.getStatus() == ScanTask.TaskStatus.FAILED)
                    .count();
            long pendingCount = vulnTasks.stream()
                    .filter(t -> t.getStatus() == ScanTask.TaskStatus.PENDING)
                    .count();
            
            // 汇总孙任务的分析结果
            StringBuilder summary = new StringBuilder();
            summary.append(String.format("接口漏洞分析汇总：共 %d 项漏洞检查\n", vulnTasks.size()));
            summary.append(String.format("- 完成: %d 项\n", completedCount));
            summary.append(String.format("- 失败: %d 项\n", failedCount));
            summary.append(String.format("- 待执行: %d 项\n\n", pendingCount));
            
            // 收集漏洞发现
            StringBuilder findingsSummary = new StringBuilder();
            for (ScanTask vulnTask : vulnTasks) {
                if (vulnTask.getStatus() == ScanTask.TaskStatus.COMPLETED && vulnTask.getLlmResponse() != null) {
                    String vulnName = vulnTask.getTaskName();
                    String response = vulnTask.getLlmResponse();
                    // 提取漏洞相关内容
                    if (response.contains("漏洞") || response.contains("风险") || response.contains("finding")) {
                        findingsSummary.append(String.format("【%s】发现安全风险\n", vulnName));
                    } else {
                        findingsSummary.append(String.format("【%s】未发现安全风险\n", vulnName));
                    }
                } else if (vulnTask.getStatus() == ScanTask.TaskStatus.FAILED) {
                    findingsSummary.append(String.format("【%s】扫描失败\n", vulnTask.getTaskName()));
                }
            }
            
            if (findingsSummary.length() > 0) {
                summary.append("漏洞扫描结果：\n").append(findingsSummary);
            }
            
            // 保存汇总结果到子任务
            subTask.setStatus(ScanTask.TaskStatus.COMPLETED);
            subTask.setProgress(100);
            subTask.setCompletedAt(LocalDateTime.now());
            subTask.setStartedAt(subTask.getStartedAt() != null ? subTask.getStartedAt() : LocalDateTime.now());
            subTask.setLlmResponse(summary.toString());
            subTask.setThinkingProcess("基于代码静态分析的自动化审计结果");
            
            // 保存汇总日志
            ScanProject project = projectRepository.findById(projectId).orElse(null);
            if (project != null) {
                saveLog(project, subTask, ScanLog.LogType.TASK_COMPLETE,
                        String.format("接口分析汇总: %s", subTask.getTaskName()));
                saveLog(project, subTask, ScanLog.LogType.LLM_RESPONSE, summary.toString());
            }
            
            taskRepository.saveAndFlush(subTask);
            
            log.info("汇总接口子任务: {}, 完成孙任务: {}/{}", 
                    subTask.getTaskName(), completedCount, vulnTasks.size());
        }
    }
    
    /**
     * 检查并完成父任务（当所有子任务都完成时）
     */
    private void checkAndCompleteParentTask(Long parentId) {
        ScanTask parentTask = taskRepository.findById(parentId).orElse(null);
        if (parentTask == null
                || (parentTask.getTaskType() != ScanTask.TaskType.SUB_HIGH_RISK_SCAN
                        && parentTask.getTaskType() != ScanTask.TaskType.SUB_INTERFACE_SCAN)) {
            return;
        }
        
        long pendingCount = taskRepository.countByParentTaskIdAndStatusIn(parentId, 
                List.of(ScanTask.TaskStatus.PENDING, ScanTask.TaskStatus.RUNNING));
        
        if (pendingCount == 0) {
            // 所有子任务都完成了
            parentTask.setStatus(ScanTask.TaskStatus.COMPLETED);
            parentTask.setCompletedAt(LocalDateTime.now());
            parentTask.setProgress(100);
            taskRepository.saveAndFlush(parentTask);
            
            // 如果父任务还有父任务，继续检查
            if (parentTask.getParentTaskId() != null) {
                checkAndCompleteParentTask(parentTask.getParentTaskId());
            }
        }
    }

    /**
     * 执行单个任务（独立事务）
     * 每个任务在自己的事务中执行，避免一个任务失败导致整个批次的事务回滚
     * 使用 REQUIRES_NEW 确保每个任务在独立事务中执行
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeSingleTask(Long projectId, ScanTask task, SseEmitter emitter, 
                                   Long llmConfigId, String projectCode) {
        try {
            taskService.checkPauseCancel(projectId);
            executeSubTaskWithProtocol(projectId, task, emitter, llmConfigId, projectCode);
        } catch (Exception e) {
            log.warn("执行任务失败: {} - {}", task.getTaskName(), e.getMessage());
            // 因终止导致的异常：任务保持/置为 CANCELLED，不算失败
            boolean cancelled = e.getMessage() != null && e.getMessage().contains("任务已取消");
            task.setStatus(cancelled ? ScanTask.TaskStatus.CANCELLED : ScanTask.TaskStatus.FAILED);
            task.setCompletedAt(LocalDateTime.now());
            if (!cancelled) {
                task.setProgress(100);
            }
            try {
                taskRepository.saveAndFlush(task);
            } catch (Exception saveEx) {
                log.error("保存任务失败状态失败: {}", saveEx.getMessage());
            }
        }
    }

    /**
     * 按类型执行任务（批次调度，无事务）
     * 每个任务通过 executeSingleTask 在独立事务中执行
     */
    public void executeTasksByType(Long projectId, SseEmitter emitter, Long llmConfigId, 
                                     String projectCode, List<ScanTask.TaskType> taskTypes) {
        List<ScanTask> tasks = taskRepository.findByProjectIdAndStatusIn(projectId, List.of(ScanTask.TaskStatus.PENDING))
                .stream()
                .filter(t -> taskTypes.contains(t.getTaskType()))
                .toList();

        log.info("按类型执行任务: 类型={}, 数量={}", taskTypes, tasks.size());

        int successCount = 0;
        int failCount = 0;
        int totalTasks = tasks.size();
        int currentIndex = 0;
        
        for (ScanTask task : tasks) {
            // 终止后立即退出循环，避免继续覆盖任务状态
            if (taskService.isCancelled(projectId)) {
                log.info("检测到扫描已终止，停止调度剩余任务: 已执行 {}/{}", currentIndex, totalTasks);
                break;
            }
            currentIndex++;
            log.info("开始执行任务 {}/{}: [{}] {}", currentIndex, totalTasks, task.getTaskType(), task.getTaskName());
            try {
                self.executeSingleTask(projectId, task, emitter, llmConfigId, projectCode);
                if (task.getStatus() == ScanTask.TaskStatus.COMPLETED) {
                    successCount++;
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                log.warn("执行任务异常: {} - {}", task.getTaskName(), e.getMessage());
                failCount++;
            }

            // 漏洞孙任务完成后，实时检查其接口子任务是否全部完成
            if (task.getTaskType() == ScanTask.TaskType.SUB_INTERFACE_VULNERABILITY_SCAN
                    && task.getParentTaskId() != null) {
                checkAndCompleteParentTask(task.getParentTaskId());
            }
        }
        
        log.info("按类型执行任务完成: 类型={}, 成功={}, 失败={}", taskTypes, successCount, failCount);
    }

    /** 是否为阶段父任务类型（依赖扫描/接口扫描/高危操作扫描） */
    private boolean isPhaseTaskType(ScanTask.TaskType taskType) {
        return taskType == ScanTask.TaskType.DEPENDENCY_SCAN
                || taskType == ScanTask.TaskType.INTERFACE_SCAN
                || taskType == ScanTask.TaskType.HIGH_RISK_OPERATION_SCAN;
    }

    /**
     * 更新阶段任务状态
     */
    private void updatePhaseTaskStatus(Long projectId, String taskName, ScanTask.TaskStatus status) {
        List<ScanTask> tasks = taskRepository.findByProjectIdAndTaskName(projectId, taskName);
        for (ScanTask task : tasks) {
            task.setStatus(status);
            if (status == ScanTask.TaskStatus.RUNNING) {
                task.setStartedAt(LocalDateTime.now());
            } else if (status == ScanTask.TaskStatus.COMPLETED || status == ScanTask.TaskStatus.FAILED
                    || status == ScanTask.TaskStatus.CANCELLED) {
                task.setCompletedAt(LocalDateTime.now());
                if (status != ScanTask.TaskStatus.CANCELLED) {
                    task.setProgress(100);
                }
            }
            taskRepository.saveAndFlush(task);
        }
    }

    /**
     * 查找代码中的pom.xml内容
     */
    private List<String> findPomFiles(String projectCode) {
        List<String> pomFiles = new ArrayList<>();
        
        // 首先从文件标记中提取pom.xml的完整内容
        // 匹配格式: ===== File: xxx/pom.xml ===== 或 ===== File: pom.xml =====
        Pattern pomMarkerPattern = Pattern.compile(
                "===== File:[^\\n]*pom\\.xml[^\\n]*=====[\\r\\n](.*?)(?=\\s*===== File:|\\s*===== End of Project =====|$)",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher markerMatcher = pomMarkerPattern.matcher(projectCode);
        
        while (markerMatcher.find()) {
            String pomContent = markerMatcher.group(1).trim();
            if (!pomContent.isEmpty()) {
                pomFiles.add(pomContent);
            }
        }
        
        // 如果通过文件标记没找到，回退到正则匹配<project>标签
        if (pomFiles.isEmpty()) {
            Pattern pomPattern = Pattern.compile(
                    "<project[^>]*>[\\s\\S]*?</project>",
                    Pattern.DOTALL);
            Matcher matcher = pomPattern.matcher(projectCode);
            
            while (matcher.find()) {
                pomFiles.add(matcher.group());
            }
            
            // 如果还是没找到，尝试只匹配<dependencies>块
            if (pomFiles.isEmpty()) {
                Pattern depsPattern = Pattern.compile(
                        "<dependencies>[\\s\\S]*?</dependencies>");
                matcher = depsPattern.matcher(projectCode);
                while (matcher.find()) {
                    pomFiles.add(matcher.group());
                }
            }
        }
        
        return pomFiles;
    }

    /**
     * 从pom内容中提取依赖信息
     */
    private String extractDependenciesFromPom(String pomContent) {
        StringBuilder deps = new StringBuilder();
        Matcher matcher = DEPENDENCY_PATTERN.matcher(pomContent);
        
        while (matcher.find()) {
            String groupId = matcher.group(1);
            String artifactId = matcher.group(2);
            String version = matcher.group(3);
            
            deps.append(String.format("- %s:%s:%s%n", 
                    groupId, artifactId, version != null ? version : "unknown"));
        }
        
        return deps.toString();
    }

    /**
     * 查找Spring MVC接口注解 - 统一匹配所有注解类型
     */
    private List<EndpointInfo> findInterfaceAnnotations(String projectCode) {
        List<EndpointInfo> endpoints = new ArrayList<>();

        log.info("开始扫描接口注解，代码长度: {} 字符", projectCode.length());
        
        // 构建文件边界映射：行号 -> 文件路径
        Map<Integer, String> fileLineMap = buildFileLineMap(projectCode);

        // 第1步：查找所有类级别的@RequestMapping前缀
        Map<String, String> classPrefixes = findClassLevelPrefixes(projectCode);
        log.info("找到 {} 个类级别RequestMapping前缀", classPrefixes.size());
        for (Map.Entry<String, String> entry : classPrefixes.entrySet()) {
            log.info("类前缀: {} -> {}", entry.getKey(), entry.getValue());
        }

        // 第2步：匹配所有HTTP映射注解名称，然后手动提取参数
        Matcher matcher = HTTP_MAPPING_ANNOTATION_PATTERN.matcher(projectCode);
        int matchCount = 0;
        
        while (matcher.find()) {
            matchCount++;
            String annotationType = matcher.group(1); // GetMapping, PostMapping等
            int annotationEnd = matcher.end(); // 注解名结束位置
            int matchStart = matcher.start(); // 注解开始位置
            
            // 手动提取括号内的参数（处理嵌套括号）
            String params = extractAnnotationParams(projectCode, annotationEnd);
            
            // 查找所在类的前缀
            String className = findClassNameForMatch(projectCode, matchStart);
            String classPrefix = classPrefixes.getOrDefault(className, "");
            
            // 计算行号和文件路径
            int lineNumber = getLineNumberFromPosition(projectCode, matchStart);
            String filePath = fileLineMap.getOrDefault(lineNumber, "未知文件");
            
            // 获取方法名
            String methodName = findMethodNameForMatch(projectCode, matchStart);
            
            // 跳过类级别的RequestMapping（已经在classPrefixes中处理过了）
            if ("RequestMapping".equals(annotationType)) {
                // 检查后面是否跟着class定义
                String afterAnnotation = projectCode.substring(annotationEnd, 
                        Math.min(annotationEnd + 500, projectCode.length()));
                Pattern classCheckPattern = Pattern.compile(
                        "(?:@\\w+\\s*(?:\\([^)]*\\)\\s*\\n?\\s*)*)*(?:public|private|protected|abstract|final|static)?\\s*class\\s+(\\w+)");
                Matcher classCheckMatcher = classCheckPattern.matcher(afterAnnotation);
                if (classCheckMatcher.find()) {
                    log.debug("跳过类级别RequestMapping: @{}", annotationType);
                    continue; // 跳过类级别的
                }
            }
            
            // 确定HTTP方法
            String httpMethod = mapAnnotationToMethod(annotationType);
            
            // 如果是RequestMapping且指定了method参数，使用指定的方法
            if ("RequestMapping".equals(annotationType) && params != null && !params.isEmpty()) {
                String specifiedMethod = extractMethodFromRequestMapping(params);
                if (specifiedMethod != null) {
                    httpMethod = specifiedMethod;
                }
            }
            
            // 提取路径
            List<String> paths = extractPathsFromAnnotation(params, matcher.group());
            
            log.debug("匹配到注解: @{} 类={} 前缀={} 路径数={} 文件={} 行号={}", 
                    annotationType, className, classPrefix, paths.size(), filePath, lineNumber);
            
            // 为每个路径创建端点
            for (String path : paths) {
                // 如果路径为空，使用HTTP方法名作为路径标识
                if (path == null || path.isEmpty()) {
                    path = "/" + httpMethod.toLowerCase();
                }
                String fullPath = classPrefix + path;
                EndpointInfo endpoint = new EndpointInfo();
                endpoint.setMethod(httpMethod);
                endpoint.setPath(fullPath);
                endpoint.setDescription("");
                endpoint.setFilePath(filePath);
                endpoint.setLineNumber(lineNumber);
                endpoint.setClassName(className);
                endpoint.setMethodName(methodName);
                endpoints.add(endpoint);
                log.debug("添加接口: {} {} | 文件: {} | 类: {} | 方法: {}", 
                        httpMethod, fullPath, filePath, className, methodName);
            }
        }
        
        log.info("注解匹配总数: {}, 接口扫描完成，共找到 {} 个接口", matchCount, endpoints.size());
        return endpoints;
    }

    /**
     * 手动提取注解括号内的参数（处理嵌套括号）
     * 从注解名结束位置开始，跳过空白，找到开括号，然后匹配到对应的闭括号
     */
    private String extractAnnotationParams(String code, int fromIndex) {
        int i = fromIndex;
        int len = code.length();
        
        // 跳过空白字符
        while (i < len && Character.isWhitespace(code.charAt(i))) {
            i++;
        }
        
        // 检查是否有括号
        if (i >= len || code.charAt(i) != '(') {
            return ""; // 无参数
        }
        
        // 找到匹配的闭括号
        int depth = 1;
        int start = i + 1;
        i++;
        while (i < len && depth > 0) {
            char c = code.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (depth > 0) i++;
        }
        
        if (depth == 0) {
            return code.substring(start, i);
        }
        return "";
    }

    /**
     * 查找所有类级别的@RequestMapping前缀
     * 支持多种格式：@RequestMapping("/path")、@RequestMapping(value="/path")等
     */
    private Map<String, String> findClassLevelPrefixes(String projectCode) {
        Map<String, String> classPrefixes = new HashMap<>();
        
        // 查找所有@RequestMapping注解（使用统一的注解匹配模式）
        Pattern requestMappingNamePattern = Pattern.compile(
                "@RequestMapping(?=\\s*(?:\\(|@|$|\\s))", Pattern.DOTALL);
        Matcher rmMatcher = requestMappingNamePattern.matcher(projectCode);
        
        while (rmMatcher.find()) {
            int annotationEnd = rmMatcher.end();
            
            // 提取括号参数
            String params = extractAnnotationParams(projectCode, annotationEnd);
            List<String> paths = extractPathsFromAnnotation(params, rmMatcher.group());
            
            if (!paths.isEmpty()) {
                // 从注解位置向后搜索最多1000字符，找到class关键字
                String afterAnnotation = projectCode.substring(annotationEnd, 
                        Math.min(annotationEnd + 1000, projectCode.length()));
                
                // 跳过其他注解和修饰符，找到class关键字
                Pattern classPattern = Pattern.compile(
                        "(?:@\\w+\\s*(?:\\([^)]*\\)\\s*\\n?\\s*)*)*(?:public|private|protected|abstract|final|static)?\\s*class\\s+(\\w+)");
                Matcher classMatcher = classPattern.matcher(afterAnnotation);
                
                if (classMatcher.find()) {
                    String className = classMatcher.group(1);
                    String prefix = paths.get(0); // 第一个路径作为前缀
                    classPrefixes.put(className, prefix);
                    log.info("找到类级别映射: {} -> {}", className, prefix);
                } else {
                    log.debug("RequestMapping后面未找到class定义，可能是方法级别的注解");
                }
            }
        }
        
        return classPrefixes;
    }

    /**
     * 将注解类型映射为HTTP方法
     */
    private String mapAnnotationToMethod(String annotationType) {
        return switch (annotationType) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            case "RequestMapping" -> "GET"; // 默认GET，除非指定了method
            default -> "GET";
        };
    }

    /**
     * 从RequestMapping参数中提取指定的HTTP方法
     */
    private String extractMethodFromRequestMapping(String params) {
        if (params == null) return null;
        Pattern methodPattern = Pattern.compile("method\\s*=\\s*RequestMethod\\.(\\w+)");
        Matcher m = methodPattern.matcher(params);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * 从注解参数中提取所有路径
     * 支持: @GetMapping("/path"), @GetMapping(path="/path"), @GetMapping({"/p1","/p2"})
     */
    private List<String> extractPathsFromAnnotation(String params, String fullMatch) {
        List<String> paths = new ArrayList<>();
        
        if (params == null || params.trim().isEmpty()) {
            paths.add("");
            return paths;
        }
        
        // 首先检查是否包含数组格式（带引号的花括号）
        // 数组格式特征: 包含 { 且内部有 " 字符
        Matcher braceMatcher = ARRAY_PATH_BRACE_PATTERN.matcher(params);
        if (braceMatcher.find()) {
            String braceContent = braceMatcher.group(1);
            // 如果花括号内容包含引号，说明是数组格式
            if (braceContent.contains("\"")) {
                Matcher stringMatcher = ARRAY_STRING_PATTERN.matcher(braceContent);
                while (stringMatcher.find()) {
                    String path = stringMatcher.group(1);
                    if (path != null && !path.isEmpty()) {
                        paths.add(path);
                    }
                }
                if (!paths.isEmpty()) {
                    return paths;
                }
            }
        }
        
        // 尝试匹配单个路径格式: "/path" 或 value="/path" 或 path="/path"
        Matcher pathMatcher = PATH_EXTRACT_PATTERN.matcher(params);
        while (pathMatcher.find()) {
            String path = pathMatcher.group(1);
            if (path == null) {
                path = pathMatcher.group(2);
            }
            if (path != null && !path.isEmpty()) {
                paths.add(path);
            }
        }
        
        // 如果没有找到路径，使用默认
        if (paths.isEmpty()) {
            paths.add("");
        }
        
        return paths;
    }

    /**
     * 从匹配位置向前查找所在的类名
     */
    private String findClassNameForMatch(String code, int matchPosition) {
        // 向前搜索最多2000个字符
        int searchStart = Math.max(0, matchPosition - 2000);
        String precedingCode = code.substring(searchStart, matchPosition);
        
        // 查找最近的class声明
        Pattern classPattern = Pattern.compile("class\\s+(\\w+)");
        Matcher m = classPattern.matcher(precedingCode);
        
        String lastClassName = null;
        while (m.find()) {
            lastClassName = m.group(1);
        }
        
        return lastClassName != null ? lastClassName : "";
    }

    /**
     * 从代码位置计算行号
     */
    private int getLineNumberFromPosition(String code, int position) {
        if (position < 0 || position >= code.length()) {
            return 1;
        }
        int lineNumber = 1;
        for (int i = 0; i < position; i++) {
            if (code.charAt(i) == '\n') {
                lineNumber++;
            }
        }
        return lineNumber;
    }

    /**
     * 查找匹配位置之后的方法名
     */
    private String findMethodNameForMatch(String code, int matchPosition) {
        // 向后搜索最多500个字符
        int searchEnd = Math.min(code.length(), matchPosition + 500);
        String followingCode = code.substring(matchPosition, searchEnd);
        
        // 查找方法签名：修饰符 + 返回类型 + 方法名 + (
        Pattern methodPattern = Pattern.compile(
                "(?:public|private|protected)\\s+(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?" +
                "(?:[\\w<>\\[\\],\\s]+?)\\s+(\\w+)\\s*\\(");
        Matcher m = methodPattern.matcher(followingCode);
        
        if (m.find()) {
            return m.group(1);
        }
        
        // 尝试更简单的匹配
        Pattern simplePattern = Pattern.compile("(\\w+)\\s*\\(");
        m = simplePattern.matcher(followingCode);
        if (m.find()) {
            // 跳过注解和关键字
            String name = m.group(1);
            if (!name.startsWith("@") && !name.equals("return") && !name.equals("if") 
                    && !name.equals("for") && !name.equals("while") && !name.equals("try")) {
                return name;
            }
        }
        
        return "";
    }

    /**
     * 提取接口上下文代码
     */
    private String extractEndpointContext(String projectCode, EndpointInfo endpoint) {
        StringBuilder context = new StringBuilder();
        context.append("接口: ").append(endpoint.getMethod()).append(" ").append(endpoint.getPath()).append("\n\n");
        
        // 查找接口相关的代码
        String[] lines = projectCode.split("\n");
        boolean found = false;
        int contextStart = 0;
        int contextEnd = lines.length;
        
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("@" + endpoint.getMethod() + "Mapping") ||
                lines[i].contains("@RequestMapping")) {
                // 向前找类定义
                for (int j = Math.max(0, i - 50); j < i; j++) {
                    if (lines[j].contains("class ") || lines[j].contains("@RequestMapping")) {
                        contextStart = j;
                        break;
                    }
                }
                // 向后找方法结束
                int braceCount = 0;
                boolean inMethod = false;
                for (int j = i; j < Math.min(lines.length, i + 100); j++) {
                    if (lines[j].contains("(")) inMethod = true;
                    if (inMethod) {
                        braceCount += countChar(lines[j], '{');
                        braceCount -= countChar(lines[j], '}');
                        if (braceCount <= 0 && j > i) {
                            contextEnd = j + 1;
                            found = true;
                            break;
                        }
                    }
                }
                break;
            }
        }
        
        if (found) {
            for (int i = contextStart; i < contextEnd && i < lines.length; i++) {
                context.append(lines[i]).append("\n");
            }
        } else {
            context.append("接口信息: ").append(endpoint.getMethod()).append(" ").append(endpoint.getPath());
        }
        
        return context.toString();
    }

    /**
     * 查找危险函数，按启用的高危扫描配置分组匹配
     * 返回Map: configName -> List<DangerousFunctionInfo>
     */
    /**
     * 查找危险函数，按启用的高危扫描配置分组匹配
     * 返回Map: config -> List<DangerousFunctionInfo>
     * 只有匹配到危险函数的配置才会返回
     */
    private Map<PromptConfig, List<DangerousFunctionInfo>> findDangerousFunctionsByConfig(String projectCode) {
        Map<PromptConfig, List<DangerousFunctionInfo>> result = new LinkedHashMap<>();
        
        List<PromptConfig> configs = promptConfigService.getEnabledPromptConfigEntitiesByPhase("HIGH_RISK_OPERATION_SCAN");
        
        if (configs.isEmpty()) {
            log.info("高危操作扫描没有启用的配置");
            return result;
        }
        
        // 构建文件边界映射：行号 -> 文件路径
        Map<Integer, String> fileLineMap = buildFileLineMap(projectCode);
        
        for (PromptConfig config : configs) {
            List<DangerousFunctionInfo> matchedFunctions = new ArrayList<>();
            Set<String> seenPatterns = new HashSet<>();
            
            // 获取危险函数模式，如果没有配置则使用配置名称作为默认模式
            String patterns = config.getDangerousFunctionPatterns();
            if (patterns == null || patterns.isEmpty()) {
                // 使用配置名称作为默认搜索模式
                patterns = config.getName();
                log.info("高危扫描配置 [{}] 未配置危险函数模式，使用配置名称作为默认模式", config.getName());
            }
            
            for (String patternStr : patterns.split(",")) {
                String trimmed = patternStr.trim();
                if (trimmed.isEmpty() || !seenPatterns.add(trimmed)) {
                    continue;
                }
                
                try {
                    // 转义特殊字符，将普通字符串转换为正则
                    String escapedPattern = Pattern.quote(trimmed);
                    Pattern pattern = Pattern.compile(escapedPattern, Pattern.DOTALL);
                    Matcher matcher = pattern.matcher(projectCode);
                    while (matcher.find()) {
                        String functionName = extractFunctionName(matcher.group());
                        int lineNumber = getLineNumber(projectCode, matcher.start());
                        String filePath = fileLineMap.getOrDefault(lineNumber, "未知文件");
                        matchedFunctions.add(new DangerousFunctionInfo(functionName, matcher.group(), lineNumber, filePath));
                    }
                } catch (Exception e) {
                    log.warn("无效的正则表达式模式 '{}' 在配置 '{}': {}", trimmed, config.getName(), e.getMessage());
                }
            }
            
            // 只有匹配到危险函数时才添加该配置
            if (!matchedFunctions.isEmpty()) {
                result.put(config, matchedFunctions);
                log.info("高危扫描配置 [{}] 匹配到 {} 处危险函数", config.getName(), matchedFunctions.size());
            } else {
                log.info("高危扫描配置 [{}] 未匹配到危险函数，跳过创建任务", config.getName());
            }
        }
        
        return result;
    }
    
    /**
     * 查找危险函数（兼容旧接口）
     */
    private List<DangerousFunctionInfo> findDangerousFunctions(String projectCode) {
        Map<PromptConfig, List<DangerousFunctionInfo>> configMap = findDangerousFunctionsByConfig(projectCode);
        List<DangerousFunctionInfo> all = new ArrayList<>();
        for (List<DangerousFunctionInfo> funcs : configMap.values()) {
            all.addAll(funcs);
        }
        return all;
    }

    /**
     * 解析项目代码中的文件标记，构建行号到文件路径的映射
     */
    private Map<Integer, String> buildFileLineMap(String projectCode) {
        Map<Integer, String> fileLineMap = new HashMap<>();
        String[] lines = projectCode.split("\n");
        String currentFile = "未知文件";
        int currentLine = 0;
        
        for (String line : lines) {
            currentLine++;
            if (line.startsWith("===== File: ") && line.endsWith(" =====")) {
                currentFile = line.substring(12, line.length() - 6);  // 修复：后缀" ====="是6个字符
            } else {
                fileLineMap.put(currentLine, currentFile);
            }
        }
        return fileLineMap;
    }

    /**
     * 从所有启用的高危扫描提示词配置获取危险函数匹配模式，合并去重
     */
    private List<Pattern> getConfiguredDangerousFunctionPatterns() {
        List<PromptConfig> configs = promptConfigService.getEnabledPromptConfigEntitiesByPhase("HIGH_RISK_OPERATION_SCAN");
        List<Pattern> patterns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        
        for (PromptConfig config : configs) {
            if (config.getDangerousFunctionPatterns() != null && !config.getDangerousFunctionPatterns().isEmpty()) {
                for (String patternStr : config.getDangerousFunctionPatterns().split(",")) {
                    String trimmed = patternStr.trim();
                    if (!trimmed.isEmpty() && !seen.contains(trimmed)) {
                        seen.add(trimmed);
                        try {
                            patterns.add(Pattern.compile(trimmed, Pattern.DOTALL));
                        } catch (Exception e) {
                            log.warn("无效的正则表达式模式: {}", trimmed);
                        }
                    }
                }
            }
        }
        
        if (!patterns.isEmpty()) {
            return patterns;
        }
        return DANGEROUS_FUNCTION_PATTERNS;
    }

    /**
     * 提取危险函数上下文
     */
    private String extractFunctionContext(String projectCode, DangerousFunctionInfo funcInfo) {
        StringBuilder context = new StringBuilder();
        context.append("危险函数: ").append(funcInfo.getFunctionName())
                .append(" (第").append(funcInfo.getLineNumber()).append("行)\n\n");
        
        // 提取上下文代码
        String[] lines = projectCode.split("\n");
        int start = Math.max(0, funcInfo.getLineNumber() - 10);
        int end = Math.min(lines.length, funcInfo.getLineNumber() + 10);
        
        for (int i = start; i < end; i++) {
            context.append(String.format("%d: %s%n", i + 1, lines[i]));
        }
        
        return context.toString();
    }

    private String extractFunctionName(String match) {
        if (match.contains("Runtime.getRuntime().exec")) return "Runtime.getRuntime().exec()";
        if (match.contains("ProcessBuilder")) return "ProcessBuilder";
        if (match.contains("System.exit")) return "System.exit()";
        if (match.contains("exec(")) return "exec()";
        if (match.contains("eval(")) return "eval()";
        if (match.contains("ScriptEngine")) return "ScriptEngine.eval()";
        if (match.contains("Class.forName")) return "Class.forName()";
        if (match.contains("Method.invoke")) return "Method.invoke()";
        if (match.contains("setAccessible")) return "setAccessible(true)";
        if (match.contains("ObjectInputStream")) return "ObjectInputStream";
        if (match.contains("XMLDecoder")) return "XMLDecoder";
        if (match.contains("execSQL")) return "execSQL()";
        if (match.contains("executeUpdate")) return "executeUpdate()";
        if (match.contains("Statement")) return "Statement.execute()";
        if (match.contains("DriverManager.getConnection")) return "DriverManager.getConnection()";
        if (match.contains("URL")) return "URL";
        if (match.contains("HttpURLConnection")) return "HttpURLConnection";
        if (match.contains("RestTemplate")) return "RestTemplate";
        if (match.contains("WebClient")) return "WebClient";
        if (match.contains("FileOutputStream")) return "FileOutputStream";
        if (match.contains("FileInputStream")) return "FileInputStream";
        if (match.contains("Paths.get")) return "Paths.get()";
        if (match.contains("Path.of")) return "Path.of()";
        if (match.contains("new File")) return "new File()";
        if (match.contains("ZipInputStream")) return "ZipInputStream";
        if (match.contains("ZipFile")) return "ZipFile";
        if (match.contains("getRealPath")) return "getRealPath()";
        if (match.contains("getParameter")) return "getParameter()";
        if (match.contains("sendRedirect")) return "sendRedirect()";
        if (match.contains("forward")) return "forward()";
        if (match.contains("setHeader")) return "setHeader()";
        if (match.contains("Cookie")) return "Cookie";
        return "未知危险函数";
    }

    private int getLineNumber(String code, int position) {
        int line = 1;
        for (int i = 0; i < position && i < code.length(); i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private int countChar(String str, char c) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == c) count++;
        }
        return count;
    }

    // ==================== 数据类 ====================

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class EndpointInfo {
        private String method;
        private String path;
        private String description;
        private String filePath;
        private int lineNumber;
        private String className;
        private String methodName;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class DangerousFunctionInfo {
        private String functionName;
        private String match;
        private int lineNumber;
        private String filePath;
    }

    // ==================== 原有方法保持兼容 ====================

    private void parseFindings(ScanProject project, ScanTask task, String analysisResult) {
        try {
            String cleanResult = extractJsonForParse(analysisResult);
            JsonNode root = objectMapper.readTree(cleanResult);

            if (root.has("findings")) {
                JsonNode findingsNode = root.get("findings");

                for (JsonNode findingNode : findingsNode) {
                    ScanFinding.FindingSeverity severity = ScanFinding.FindingSeverity.valueOf(
                            findingNode.has("severity") ? findingNode.get("severity").asText().toUpperCase() : "MEDIUM");

                    ScanFinding.FindingType findingType = ScanFinding.FindingType.valueOf(
                            findingNode.has("findingType") ? findingNode.get("findingType").asText().toUpperCase() : "INFORMATION_DISCLOSURE");

                    ScanFinding finding = ScanFinding.builder()
                            .project(project)
                            .task(task)
                            .title(findingNode.has("title") ? findingNode.get("title").asText() : "未命名漏洞")
                            .description(findingNode.has("description") ? findingNode.get("description").asText() : "")
                            .codeSnippet(findingNode.has("codeSnippet") ? findingNode.get("codeSnippet").asText() : "")
                            .filePath(findingNode.has("filePath") ? findingNode.get("filePath").asText() : "")
                            .lineNumber(findingNode.has("lineNumber") ? findingNode.get("lineNumber").asText() : "")
                            .severity(severity)
                            .findingType(findingType)
                            .exploitationPath(findingNode.has("exploitationPath") ? findingNode.get("exploitationPath").asText() : "")
                            .suggestion(findingNode.has("suggestion") ? findingNode.get("suggestion").asText() : "")
                            .verified(false)
                            .build();

                    findingRepository.save(finding);

                    saveLog(project, task, ScanLog.LogType.FINDING,
                            "发现漏洞 [" + severity + "]: " + finding.getTitle());
                }
            }
        } catch (Exception e) {
            log.error("解析漏洞结果失败", e);
            saveLog(project, task, ScanLog.LogType.WARNING,
                    "解析漏洞结果失败: " + e.getMessage());
        }
    }

    private String generateReport(ScanProject project) {
        List<ScanFinding> findings = findingRepository.findByProjectIdOrderBySeverityDesc(project.getId());

        StringBuilder report = new StringBuilder();
        report.append("# 代码安全审计报告\n\n");
        report.append("---\n\n");

        // 报告前置：审计系统说明
        report.append("## ■ 关于 CodesWatch 审计系统\n\n");
        report.append("本报告由 **CodesWatch 自动化代码安全审计平台** 生成。系统将人工代码审计流程（依赖分析 → 接口提取 → 高危操作识别）自动化，为每个待审计点独立建立子任务交由大语言模型逐一分析，审计能力主要依托大语言模型——模型能力越强，系统审计能力越强。\n\n");
        report.append("- ◆ 审计系统开源地址：https://github.com/mlf519/CodesWatch\n");
        report.append("- ◆ 三阶段审计流水线：依赖扫描 → 接口扫描 → 高危操作扫描\n");
        report.append("- ◆ 漏洞类型由提示词配置决定，可按需增减\n");
        report.append("- ◆ 全量接口独立子任务，保证扫描完整覆盖\n");
        report.append("- ◆ 提示词按阶段 / 漏洞类型可配置，支持多模型与思考模式\n\n");
        report.append("---\n\n");

        // 一、项目信息
        report.append("## ■ 一、项目信息\n\n");
        report.append("- ◆ 项目名称：").append(project.getProjectName()).append("\n");
        report.append("- ◆ 项目描述：").append(project.getProjectDescription() == null || project.getProjectDescription().isBlank() ? "（无）" : project.getProjectDescription().trim()).append("\n");
        report.append("- ◆ 扫描完成时间：").append(project.getCompletedAt() == null ? "（项目未全部扫完，报告为已扫描部分的结果）" : project.getCompletedAt()).append("\n");
        report.append("- ◆ 总任务数：").append(project.getTotalTasks()).append("\n");
        report.append("- ◆ 漏洞总数：").append(findings.size()).append("\n\n");

        // 二、漏洞统计
        report.append("## ■ 二、漏洞统计\n\n");
        long critical = findings.stream().filter(f -> f.getSeverity() == ScanFinding.FindingSeverity.CRITICAL).count();
        long high = findings.stream().filter(f -> f.getSeverity() == ScanFinding.FindingSeverity.HIGH).count();
        long medium = findings.stream().filter(f -> f.getSeverity() == ScanFinding.FindingSeverity.MEDIUM).count();
        long low = findings.stream().filter(f -> f.getSeverity() == ScanFinding.FindingSeverity.LOW).count();
        long info = findings.stream().filter(f -> f.getSeverity() == ScanFinding.FindingSeverity.INFO).count();

        report.append("- ■ 严重（CRITICAL）：").append(critical).append("\n");
        report.append("- ▲ 高危（HIGH）：").append(high).append("\n");
        report.append("- ◆ 中危（MEDIUM）：").append(medium).append("\n");
        report.append("- ● 低危（LOW）：").append(low).append("\n");
        report.append("- ○ 提示（INFO）：").append(info).append("\n\n");

        // 三、漏洞详情
        report.append("## ■ 三、漏洞详情\n\n");
        if (findings.isEmpty()) {
            report.append("本次扫描未发现安全漏洞。\n\n");
        }
        for (ScanFinding finding : findings) {
            report.append("### 【").append(severityText(finding.getSeverity())).append(" ").append(severityIcon(finding.getSeverity()))
                  .append("】").append(finding.getTitle()).append("\n\n");
            report.append("**漏洞类型**：").append(findingTypeText(finding.getFindingType())).append("\n\n");
            report.append("**漏洞位置**：").append(finding.getFilePath()).append(" : ").append(finding.getLineNumber()).append("\n\n");
            if (finding.getCodeSnippet() != null && !finding.getCodeSnippet().isBlank()) {
                report.append("**问题代码**：\n\n```\n").append(finding.getCodeSnippet().trim()).append("\n```\n\n");
            }
            report.append("**漏洞描述**：").append(finding.getDescription()).append("\n\n");
            report.append("**利用路径**：").append(finding.getExploitationPath()).append("\n\n");
            report.append("**修复建议**：").append(finding.getSuggestion()).append("\n\n");
        }

        // 任务结论汇总：完成任务显示大模型简要分析结论；
        // 已取消/失败但已产出结论的任务同样展示（部分结论），未完成明细只列实际执行扫描的叶子任务
        List<ScanTask> allTasks = taskRepository.findByProjectId(project.getId());
        // 叶子任务 = 未作为任何任务父节点的任务（容器类任务不单列展示）
        java.util.Set<Long> parentIds = new java.util.HashSet<>();
        for (ScanTask t : allTasks) {
            if (t.getParentTaskId() != null) {
                parentIds.add(t.getParentTaskId());
            }
        }
        java.util.function.Predicate<ScanTask> isLeaf = t -> !parentIds.contains(t.getId());

        long completedCount = allTasks.stream()
                .filter(t -> t.getStatus() == ScanTask.TaskStatus.COMPLETED).count();
        long cancelledCount = allTasks.stream()
                .filter(t -> t.getStatus() == ScanTask.TaskStatus.CANCELLED).count();
        long failedCount = allTasks.stream()
                .filter(t -> t.getStatus() == ScanTask.TaskStatus.FAILED).count();
        List<ScanTask> notCompletedLeaves = allTasks.stream()
                .filter(isLeaf)
                .filter(t -> t.getStatus() != ScanTask.TaskStatus.COMPLETED)
                .collect(java.util.stream.Collectors.toList());

        report.append("## ■ 四、任务结论汇总\n\n");
        report.append("- ◆ 任务总数：").append(allTasks.size()).append("\n");
        report.append("- ◆ 已完成：").append(completedCount).append("\n");
        report.append("- ◆ 已取消：").append(cancelledCount).append("\n");
        report.append("- ◆ 执行失败：").append(failedCount).append("\n");
        report.append("- ◆ 未完成待扫（实际扫描任务）：").append(notCompletedLeaves.size()).append("\n\n");

        if (!notCompletedLeaves.isEmpty()) {
            report.append("### ▶ 未完成任务明细（仅列出实际执行扫描的任务）\n\n");
            for (ScanTask t : notCompletedLeaves) {
                report.append("- △ [").append(taskStatusText(t.getStatus())).append("] ")
                      .append(t.getTaskName()).append("\n");
            }
            report.append("\n");
        }

        report.append("### ▶ 各任务分析结论\n\n");
        boolean anyConclusion = false;
        for (ScanTask t : allTasks) {
            // 只展示实际执行扫描的叶子任务；已取消/失败但已产出结论的同样纳入（标注部分结论）
            if (!isLeaf.test(t)) {
                continue;
            }
            boolean completed = t.getStatus() == ScanTask.TaskStatus.COMPLETED;
            boolean hasConclusion = t.getAnalysisResult() != null && !t.getAnalysisResult().isBlank();
            if (!completed && !hasConclusion) {
                continue;
            }
            anyConclusion = true;
            String statusTag = completed ? "" : "[" + taskStatusText(t.getStatus()) + "·部分结论] ";
            String vulnTag;
            if (Boolean.TRUE.equals(t.getHasVulnerability())) {
                vulnTag = "发现漏洞 ●";
            } else if (Boolean.FALSE.equals(t.getHasVulnerability())) {
                vulnTag = "未发现漏洞 ○";
            } else {
                vulnTag = "结论未知 ？";
            }
            String conclusion = hasConclusion ? t.getAnalysisResult().trim() : "（无结论记录）";
            report.append("- ").append(statusTag).append("[").append(vulnTag).append("] ")
                  .append(t.getTaskName())
                  .append("：").append(conclusion).append("\n");
        }
        if (!anyConclusion) {
            report.append("暂无任务产出分析结论。\n");
        }

        return report.toString();
    }

    /**
     * 任务状态转中文描述
     */
    private String taskStatusText(ScanTask.TaskStatus status) {
        switch (status) {
            case PENDING: return "未扫描";
            case RUNNING: return "执行中";
            case PAUSED: return "已暂停";
            case COMPLETED: return "已完成";
            case FAILED: return "执行失败";
            case CANCELLED: return "已取消";
            default: return status.name();
        }
    }

    /**
     * 漏洞等级转中文描述
     */
    private String severityText(ScanFinding.FindingSeverity severity) {
        switch (severity) {
            case CRITICAL: return "严重";
            case HIGH: return "高危";
            case MEDIUM: return "中危";
            case LOW: return "低危";
            case INFO: return "提示";
            default: return severity.name();
        }
    }

    /**
     * 漏洞等级图标
     */
    private String severityIcon(ScanFinding.FindingSeverity severity) {
        switch (severity) {
            case CRITICAL: return "■";
            case HIGH: return "▲";
            case MEDIUM: return "◆";
            case LOW: return "●";
            case INFO: return "○";
            default: return "●";
        }
    }

    /**
     * 漏洞类型转中文描述
     */
    private String findingTypeText(ScanFinding.FindingType type) {
        if (type == null) {
            return "未分类";
        }
        switch (type) {
            case SQL_INJECTION: return "SQL 注入";
            case XSS: return "XSS 跨站脚本";
            case COMMAND_INJECTION: return "命令注入";
            case PATH_TRAVERSAL: return "路径穿越";
            case SSRF: return "SSRF 服务端请求伪造";
            case FILE_UPLOAD: return "文件上传漏洞";
            case DESERIALIZATION: return "反序列化漏洞";
            case INSECURE_DEPENDENCY: return "不安全依赖组件";
            case AUTH_BYPASS: return "鉴权绕过";
            case CSRF: return "CSRF 跨站请求伪造";
            case OPEN_REDIRECT: return "开放重定向";
            case INFORMATION_DISCLOSURE: return "信息泄露";
            case RACE_CONDITION: return "竞态条件";
            default: return type.name();
        }
    }

    /**
     * 获取报告内容；若尚未生成（如已结束但未生成报告的项目），现场生成并落库
     */
    @Transactional
    public String getOrGenerateReport(Long projectId) {
        ScanProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        // 每次预览/导出都基于当前扫描数据实时重新生成（含已取消项目的部分扫描结果），保证内容最新
        String report = generateReport(project);
        project.setReportContent(report);
        projectRepository.saveAndFlush(project);
        return report;
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(PromptConfig promptConfig, ScanTask task) {
        StringBuilder sb = new StringBuilder();
        
        if (promptConfig != null && promptConfig.getSystemPrompt() != null 
                && !promptConfig.getSystemPrompt().isEmpty()) {
            sb.append(promptConfig.getSystemPrompt()).append("\n\n");
        } else {
            sb.append("你是一个专业的代码安全审计专家。请仔细分析代码，识别其中的安全漏洞。\n");
            sb.append("在分析过程中，如果你需要查看其他文件或类的代码，请使用 more:包名或类名:more 格式请求。\n");
            sb.append("分析完成后，请使用 end:Vul!或Safe!开头+简要分析:end 格式输出最终结论。\n");
            sb.append("格式说明：\n");
            sb.append("- 如果发现漏洞，使用 end:Vul!开头+简要分析:end\n");
            sb.append("- 如果未发现漏洞，使用 end:Safe!开头+简要分析:end\n\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 构建用户问题提示词
     */
    private String buildUserPrompt(String code, ScanTask task, String phase, 
                                    PromptConfig promptConfig, String interfaceInfo) {
        StringBuilder sb = new StringBuilder();
        
        // 添加任务信息
        sb.append("【任务】\n");
        sb.append("任务名称: ").append(task.getTaskName()).append("\n");
        sb.append("任务类型: ").append(task.getTaskType().name()).append("\n");
        
        if (task.getTaskDescription() != null && !task.getTaskDescription().isEmpty()) {
            sb.append("任务描述: ").append(task.getTaskDescription()).append("\n");
        }
        
        // 添加接口信息（如果有）
        if (interfaceInfo != null && !interfaceInfo.isEmpty()) {
            sb.append("\n【目标接口】\n");
            sb.append(interfaceInfo).append("\n");
        }
        
        // 添加漏洞类型提示（如果是漏洞扫描任务）
        if (task.getTaskType() == ScanTask.TaskType.SUB_INTERFACE_VULNERABILITY_SCAN) {
            sb.append("\n【扫描漏洞类型】\n");
            sb.append(task.getTaskDescription()).append("\n");
        }
        
        // 添加分析要求
        if (promptConfig != null && promptConfig.getAnalysisPrompt() != null 
                && !promptConfig.getAnalysisPrompt().isEmpty()) {
            sb.append("\n【分析要求】\n");
            sb.append(promptConfig.getAnalysisPrompt()).append("\n");
        }
        
        // 添加代码内容
        sb.append("\n【代码内容】\n");
        sb.append(code).append("\n");
        
        // 添加协议说明
        sb.append("\n【协议说明】\n");
        sb.append("- 如果需要查看其他相关文件，请使用 more:包名1;包名2:more 格式请求\n");
        sb.append("- 分析完成后，请使用 end:Vul!或Safe!开头+简要分析:end 格式输出最终结论\n");
        sb.append("  - 发现漏洞: end:Vul!简要分析:end\n");
        sb.append("  - 未发现漏洞: end:Safe!简要分析:end\n");
        
        return sb.toString();
    }

    /**
     * 精简 ScanLog：只记录时间和结论类的日志，详细 LLM 请求/响应/思考内容已存入 task 实体字段
     * 执行日志只保留：任务开始/任务完成/漏洞发现/错误 四类
     */
    private static final Set<ScanLog.LogType> PERSISTED_LOG_TYPES = EnumSet.of(
            ScanLog.LogType.TASK_START,
            ScanLog.LogType.TASK_COMPLETE,
            ScanLog.LogType.ERROR,
            ScanLog.LogType.FINDING
    );

    /**
     * 进度累加并封顶100，避免多次重启/创建子任务导致进度超过100%
     */
    private int addProgress(int current, int delta) {
        return Math.min(100, current + delta);
    }

    private void saveLog(ScanProject project, ScanTask task, ScanLog.LogType logType, String message) {
        // 精简：详细 LLM 内容（请求/响应/思考/文件上下文）已存入 task 实体字段，不在 ScanLog 中重复存储
        if (!PERSISTED_LOG_TYPES.contains(logType)) {
            return;
        }
        // 防止消息过长导致数据库列溢出（MEDIUMTEXT最大16MB）
        if (message != null && message.length() > 500000) {
            message = message.substring(0, 500000) + "...[截断]";
        }
        ScanLog scanLog = ScanLog.builder()
                .project(project)
                .task(task)
                .logType(logType)
                .message(message)
                .build();
        logRepository.saveAndFlush(scanLog);
    }

    @Transactional(readOnly = true)
    public List<ScanLog> getLogs(Long projectId) {
        return logRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
    }

    private void saveLog(Long projectId, ScanTask task, ScanLog.LogType logType, String message) {
        ScanProject project = projectRepository.findById(projectId).orElse(null);
        if (project != null) {
            saveLog(project, task, logType, message);
        }
    }

    /**
     * 安全保存日志 - 内部捕获所有异常，确保日志保存失败不影响主流程
     */
    private void safeSaveLog(ScanProject project, ScanTask task, ScanLog.LogType logType, String message) {
        try {
            saveLog(project, task, logType, message);
        } catch (Exception e) {
            log.warn("保存日志失败: type={}, task={}, error={}", logType, 
                    task != null ? task.getTaskName() : "null", e.getMessage());
        }
    }

    /**
     * 重启并立即执行单个任务（异步）
     * 供 TaskService.restartTask 在事务提交后调用
     */
    public void restartAndExecuteTask(Long taskId) {
        ScanTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("重启执行失败：任务 {} 不存在", taskId);
            return;
        }
        if (task.getStatus() != ScanTask.TaskStatus.PENDING) {
            log.warn("重启执行跳过：任务 {} 当前状态为 {}，非 PENDING", taskId, task.getStatus());
            return;
        }

        ScanProject project = task.getProject();
        if (project == null) {
            log.warn("重启执行失败：任务 {} 关联的项目为空", taskId);
            return;
        }

        String projectCode = getProjectCode(project);
        if (projectCode == null || projectCode.isEmpty()) {
            log.warn("重启执行失败：项目 {} 无代码内容", project.getId());
            return;
        }

        Long llmConfigId = task.getLlmConfigId();
        if (llmConfigId == null) {
            // 用项目上次使用的配置
            llmConfigId = project.getLastLlmConfigId();
        }
        if (llmConfigId == null) {
            log.warn("重启执行失败：任务 {} 无 llmConfigId", taskId);
            return;
        }

        // 获取（或创建一个新的）SSE emitter，用于前端状态推送
        SseEmitter emitter = taskService.getTaskEmitter(project.getId());

        final Long finalProjectId = project.getId();
        final Long finalTaskId = taskId;
        final Long finalLlmConfigId = llmConfigId;
        final String finalProjectCode = projectCode;

        log.info("异步重启执行任务: taskId={}, taskName={}", taskId, task.getTaskName());

        scanExecutor.submit(() -> {
            try {
                // 任务可能在主线程事务提交前还是旧状态，这里 refresh 一下
                ScanTask freshTask = taskRepository.findById(finalTaskId).orElse(null);
                if (freshTask == null) {
                    log.warn("重启执行中：任务 {} 已不存在", finalTaskId);
                    return;
                }
                if (freshTask.getStatus() != ScanTask.TaskStatus.PENDING) {
                    log.info("重启执行跳过：任务 {} 状态已变为 {}", finalTaskId, freshTask.getStatus());
                    return;
                }

                self.executeSingleTask(finalProjectId, freshTask, emitter, finalLlmConfigId, finalProjectCode);

                // 执行完成后，更新项目 findingCount
                if (freshTask.getStatus() == ScanTask.TaskStatus.COMPLETED) {
                    ScanProject updatedProject = projectRepository.findById(finalProjectId).orElse(null);
                    if (updatedProject != null) {
                        long findings = findingRepository.countByProjectId(finalProjectId);
                        updatedProject.setFindingCount((int) findings);
                        projectRepository.saveAndFlush(updatedProject);
                    }
                    // 检查父任务是否需要重新汇总
                    if (freshTask.getParentTask() != null) {
                        log.info("任务 {} 重启执行完成，父任务 {} 需要重新汇总", finalTaskId, freshTask.getParentTask().getId());
                    }
                }

                log.info("异步重启执行完成: taskId={}, taskName={}, status={}", 
                        finalTaskId, freshTask.getTaskName(), freshTask.getStatus());
            } catch (Exception e) {
                log.error("异步重启执行异常: taskId={}", finalTaskId, e);
            }
        });
    }
}
