package com.codeswatch.controller;

import com.codeswatch.dto.*;
import com.codeswatch.entity.ScanProject;
import com.codeswatch.repository.ScanLogRepository;
import com.codeswatch.repository.ScanProjectRepository;
import com.codeswatch.service.FileService;
import com.codeswatch.service.ProjectService;
import com.codeswatch.service.ScanEngineService;
import com.codeswatch.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
@Slf4j
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final ScanEngineService scanEngineService;
    private final FileService fileService;
    private final ScanProjectRepository projectRepository;
    private final ScanLogRepository logRepository;
    private final TaskService taskService;

    @GetMapping
    public ResponseEntity<List<ScanProjectDTO>> getAllProjects(
            @RequestParam(required = false) String search) {
        List<ScanProjectDTO> projects = search != null 
                ? projectService.searchProjects(search) 
                : projectService.getAllProjects();
        return ResponseEntity.ok(projects);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScanProjectDTO> getProject(@PathVariable Long id) {
        return ResponseEntity.ok(projectService.getProject(id));
    }

    /**
     * 获取项目审计报告内容（Markdown），避免在详情 DTO 中传输大字段
     * scanRound 为空时统计全部批次，否则仅统计指定扫描任务批次
     */
    @GetMapping("/{id}/report")
    public ResponseEntity<Map<String, String>> getProjectReport(
            @PathVariable Long id,
            @RequestParam(required = false) Integer scanRound) {
        // 报告尚未生成时现场生成（支持已结束项目预览）
        String content = scanEngineService.getOrGenerateReport(id, scanRound);
        return ResponseEntity.ok(Map.of("reportContent", content));
    }

    /**
     * 导出审计报告文件（md/pdf）。报告尚未生成时现场生成，支持已结束项目导出
     */
    @GetMapping("/{id}/report/export")
    public ResponseEntity<byte[]> exportReport(
            @PathVariable Long id,
            @RequestParam(required = false) Integer scanRound,
            @RequestParam(defaultValue = "md") String format) {
        String content = scanEngineService.getOrGenerateReport(id, scanRound);
        ScanProject project = projectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("项目不存在"));
        String fileName = project.getProjectName() + "_审计报告";

        try {
            if ("pdf".equalsIgnoreCase(format)) {
                byte[] pdf = renderPdfReport(content);
                String encoded = java.net.URLEncoder.encode(fileName + ".pdf", java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20");
                return ResponseEntity.ok()
                        .header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded)
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(pdf);
            }
            String encoded = java.net.URLEncoder.encode(fileName + ".md", java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded)
                    .contentType(new MediaType("text", "markdown", java.nio.charset.StandardCharsets.UTF_8))
                    .body(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("导出报告失败: projectId={}, format={}", id, format, e);
            throw new RuntimeException("导出报告失败: " + e.getMessage());
        }
    }

    /**
     * 将 Markdown 报告渲染为 PDF（支持中文，标题分级，列表项）
     */
    private byte[] renderPdfReport(String markdown) throws Exception {
        com.lowagie.text.Document document = new com.lowagie.text.Document(
                com.lowagie.text.PageSize.A4, 40, 40, 40, 40);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        com.lowagie.text.pdf.PdfWriter.getInstance(document, out);
        document.open();

        com.lowagie.text.pdf.BaseFont baseFont;
        try {
            // CJK 字体（openpdf-fonts-extra 提供）
            baseFont = com.lowagie.text.pdf.BaseFont.createFont(
                    "STSong-Light", "UniGB-UCS2-H",
                    com.lowagie.text.pdf.BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            // 回退：Windows 中文字体
            try {
                baseFont = com.lowagie.text.pdf.BaseFont.createFont(
                        "c:/windows/fonts/simsun.ttc,0",
                        com.lowagie.text.pdf.BaseFont.IDENTITY_H,
                        com.lowagie.text.pdf.BaseFont.EMBEDDED);
            } catch (Exception e2) {
                baseFont = com.lowagie.text.pdf.BaseFont.createFont(
                        com.lowagie.text.pdf.BaseFont.HELVETICA,
                        com.lowagie.text.pdf.BaseFont.WINANSI,
                        com.lowagie.text.pdf.BaseFont.NOT_EMBEDDED);
            }
        }

        com.lowagie.text.Font fontTitle = new com.lowagie.text.Font(baseFont, 20, com.lowagie.text.Font.BOLD);
        com.lowagie.text.Font fontH2 = new com.lowagie.text.Font(baseFont, 14, com.lowagie.text.Font.BOLD,
                new java.awt.Color(31, 56, 100));
        com.lowagie.text.Font fontH3 = new com.lowagie.text.Font(baseFont, 12, com.lowagie.text.Font.BOLD);
        com.lowagie.text.Font fontBody = new com.lowagie.text.Font(baseFont, 10.5f, com.lowagie.text.Font.NORMAL);
        com.lowagie.text.Font fontCode = new com.lowagie.text.Font(baseFont, 9, com.lowagie.text.Font.NORMAL,
                new java.awt.Color(60, 60, 60));

        boolean inCodeBlock = false;
        for (String rawLine : markdown.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            // Markdown 代码块围栏
            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }
            com.lowagie.text.Paragraph p;
            if (inCodeBlock) {
                // 代码块内容：等宽缩进呈现
                p = new com.lowagie.text.Paragraph(stripMd(line), fontCode);
                p.setIndentationLeft(20f);
            } else if (line.startsWith("---") && line.replaceAll("-", "").isEmpty()) {
                // 分隔线
                com.lowagie.text.pdf.draw.LineSeparator sep = new com.lowagie.text.pdf.draw.LineSeparator(
                        0.8f, 100f, new java.awt.Color(160, 160, 160),
                        com.lowagie.text.Element.ALIGN_CENTER, -2f);
                p = new com.lowagie.text.Paragraph(new com.lowagie.text.Chunk(sep));
                p.setSpacingBefore(6f);
                p.setSpacingAfter(10f);
            } else if (line.startsWith("### ")) {
                String text = stripMd(line.substring(4));
                p = new com.lowagie.text.Paragraph(text, severityFont(text, baseFont, fontH3));
                p.setSpacingBefore(10f);
            } else if (line.startsWith("## ")) {
                p = new com.lowagie.text.Paragraph(stripMd(line.substring(3)), fontH2);
                p.setSpacingBefore(14f);
            } else if (line.startsWith("# ")) {
                p = new com.lowagie.text.Paragraph(stripMd(line.substring(2)), fontTitle);
                p.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
                p.setSpacingAfter(10f);
            } else if (line.startsWith("- ")) {
                String text = stripMd(line.substring(2));
                // 内容已带图标符号时不再重复添加列表符号
                String prefix = text.isEmpty() || ICONS.indexOf(text.charAt(0)) < 0 ? "• " : "";
                p = new com.lowagie.text.Paragraph(prefix + text, fontBody);
                p.setIndentationLeft(14f);
            } else {
                p = new com.lowagie.text.Paragraph(stripMd(line), fontBody);
            }
            p.setSpacingAfter(4f);
            document.add(p);
        }

        document.close();
        return out.toByteArray();
    }

    /** 报告中使用的图标符号集 */
    private static final String ICONS = "■▲◆●○△▶★☆";

    /**
     * 漏洞等级标题着色：【严重】红色、【高危】橙色、【中危】暗黄、【低危】蓝、【提示】灰
     */
    private com.lowagie.text.Font severityFont(String text, com.lowagie.text.pdf.BaseFont baseFont,
            com.lowagie.text.Font fallback) {
        java.awt.Color color = null;
        if (text.contains("【严重")) {
            color = new java.awt.Color(192, 0, 0);
        } else if (text.contains("【高危")) {
            color = new java.awt.Color(224, 96, 0);
        } else if (text.contains("【中危")) {
            color = new java.awt.Color(176, 138, 0);
        } else if (text.contains("【低危")) {
            color = new java.awt.Color(0, 102, 153);
        } else if (text.contains("【提示")) {
            color = new java.awt.Color(110, 110, 110);
        }
        if (color == null) {
            return fallback;
        }
        return new com.lowagie.text.Font(baseFont, fallback.getSize(), com.lowagie.text.Font.BOLD, color);
    }

    private String stripMd(String text) {
        return text.replace("**", "").replace("`", "");
    }

    @PutMapping("/{id}")
    public ResponseEntity<ScanProjectDTO> updateProject(
            @PathVariable Long id, 
            @Valid @RequestBody CreateProjectRequest request) {
        return ResponseEntity.ok(projectService.updateProject(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(@PathVariable Long id) {
        projectService.deleteProject(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/findings")
    public ResponseEntity<List<FindingDTO>> getProjectFindings(@PathVariable Long id) {
        return ResponseEntity.ok(projectService.getProjectFindings(id));
    }

    @GetMapping("/{id}/start-scan")
    public SseEmitter startScan(@PathVariable Long id,
                                @RequestParam(required = false) Long llmConfigId) {
        SseEmitter emitter = new SseEmitter(900000L);
        scanEngineService.startScan(id, emitter, llmConfigId);
        return emitter;
    }

    @PostMapping("/upload")
    public ResponseEntity<ScanProjectDTO> uploadProject(
            @RequestParam("file") MultipartFile file,
            @RequestParam("projectName") String projectName,
            @RequestParam(value = "projectDescription", required = false) String projectDescription) {

        ScanProject project = ScanProject.builder()
                .projectName(projectName)
                .projectDescription(projectDescription)
                .status(ScanProject.ProjectStatus.PENDING)
                .totalTasks(0)
                .completedTasks(0)
                .findingCount(0)
                .build();
        project = projectRepository.save(project);

        String codePath = fileService.uploadAndExtract(project.getId(), projectName, file);

        project.setCodePath(codePath);
        project.setProjectCode(null);
        project.setScanSessionId(UUID.randomUUID().toString());
        project = projectRepository.save(project);

        ScanProjectDTO dto = projectService.convertToDTO(project);
        return ResponseEntity.ok(dto);
    }

    @PutMapping("/{id}/upload")
    public ResponseEntity<ScanProjectDTO> replaceProjectFile(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {

        ScanProject project = projectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        String oldCodePath = project.getCodePath();
        String newCodePath = fileService.replaceAndExtract(
                project.getId(), project.getProjectName(), file, oldCodePath);

        project.setCodePath(newCodePath);
        project.setProjectCode(null);
        project.setScanSessionId(UUID.randomUUID().toString());
        project.setStatus(ScanProject.ProjectStatus.PENDING);
        project.setTotalTasks(0);
        project.setCompletedTasks(0);
        project.setFindingCount(0);
        project.setStartedAt(null);
        project.setCompletedAt(null);
        project.setReportContent(null);
        project = projectRepository.save(project);

        ScanProjectDTO dto = projectService.convertToDTO(project);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/{id}/cancel-scan")
    public ResponseEntity<ScanProjectDTO> cancelScan(@PathVariable Long id) {
        ScanProject project = projectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        taskService.cancelTaskByProjectId(id);

        project = projectRepository.findById(id).orElseThrow();
        ScanProjectDTO dto = projectService.convertToDTO(project);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/{id}/logs")
    @Transactional(readOnly = true)
    public ResponseEntity<List<LogEntryDTO>> getProjectLogs(@PathVariable Long id) {
        List<LogEntryDTO> logs = scanEngineService.getLogs(id).stream()
                .map(scanLog -> {
                    LogEntryDTO.LogEntryDTOBuilder builder = LogEntryDTO.builder()
                            .id(scanLog.getId())
                            .logType(scanLog.getLogType().name())
                            .message(scanLog.getMessage())
                            .createdAt(scanLog.getCreatedAt());
                    
                    // 添加任务信息
                    if (scanLog.getTask() != null) {
                        builder.taskId(scanLog.getTask().getId())
                               .taskName(scanLog.getTask().getTaskName());
                    }
                    
                    return builder.build();
                })
                .toList();
        return ResponseEntity.ok(logs);
    }
}