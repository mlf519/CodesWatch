package com.codeswatch.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@Slf4j
public class FileService {

    private static final String BASE_DIR = "CodeData";
    private static final long MAX_CODE_SIZE = 10 * 1024 * 1024;
    private static final int MAX_FILES = 500;
    private static final long MAX_FILE_SIZE = 500 * 1024;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".hg", ".github",
            "target", "build", "dist", "node_modules", ".gradle",
            ".idea", ".vscode", "out", "bin", "obj", ".settings"
    );
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            ".java", ".py", ".js", ".ts", ".jsx", ".tsx", ".c", ".cpp", ".h", ".cs",
            ".go", ".rs", ".rb", ".php", ".html", ".css", ".scss", ".vue",
            ".kt", ".swift", ".scala", ".xml", ".json", ".yml", ".yaml",
            ".properties", ".gradle", ".sql", ".sh", ".bat", ".cmd", ".ps1", ".txt"
    );
    private static final Set<String> IMPORTANT_FILE_NAMES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
            "package.json", "requirements.txt", "gemfile", "composer.json",
            "cargo.toml", "go.mod", "dockerfile", "docker-compose.yml",
            ".env", "web.xml", "application.properties", "application.yml",
            "application.yaml", "server.xml", "struts.xml"
    );

    public String uploadAndExtract(Long projectId, String projectName, MultipartFile file) {
        try {
            Path basePath = Paths.get(BASE_DIR).toAbsolutePath();
            if (!Files.exists(basePath)) {
                Files.createDirectories(basePath);
            }

            String safeName = projectName.replaceAll("[^a-zA-Z0-9_-]", "_");
            Path projectDir = basePath.resolve(safeName + "_" + projectId);
            
            if (Files.exists(projectDir)) {
                deleteDirectory(projectDir.toFile());
                if (Files.exists(projectDir)) {
                    log.warn("目录仍存在，尝试清空内容");
                    try (Stream<Path> walk = Files.walk(projectDir)) {
                        walk.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                if (!path.equals(projectDir)) {
                                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                                }
                            });
                    }
                }
            }
            Files.createDirectories(projectDir);

            String originalFilename = file.getOriginalFilename() != null
                    ? file.getOriginalFilename()
                    : "upload.zip";
            Path zipPath = projectDir.resolve(originalFilename);

            try (InputStream is = file.getInputStream()) {
                Files.copy(is, zipPath, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("ZIP文件已保存: {}", zipPath.toAbsolutePath());

            Path extractDir = projectDir.resolve("extracted");
            Files.createDirectories(extractDir);

            extractZip(zipPath, extractDir);
            log.info("ZIP解压完成: {}", extractDir.toAbsolutePath());

            return extractDir.toAbsolutePath().toString();
        } catch (IOException e) {
            log.error("文件上传解压失败", e);
            throw new RuntimeException("文件上传解压失败: " + e.getMessage());
        }
    }

    public String replaceAndExtract(Long projectId, String projectName, MultipartFile file, String oldCodePath) {
        if (oldCodePath != null && !oldCodePath.isEmpty()) {
            try {
                Path oldDir = Paths.get(oldCodePath);
                if (Files.exists(oldDir)) {
                    Path projectDir = oldDir.getParent();
                    if (projectDir != null && Files.exists(projectDir)) {
                        String oldDirName = projectDir.getFileName().toString();
                        Path backupPath = projectDir.resolveSibling(oldDirName + "_old_" + System.currentTimeMillis());
                        Files.move(projectDir, backupPath);
                        log.info("已将旧项目目录重命名为: {}", backupPath);
                        new Thread(() -> {
                            try {
                                Thread.sleep(5000);
                                deleteDirectory(backupPath.toFile());
                            } catch (InterruptedException ignored) {}
                        }).start();
                    }
                }
            } catch (Exception e) {
                log.warn("重命名旧文件失败，使用新目录: {}", e.getMessage());
            }
        }

        String safeName = projectName.replaceAll("[^a-zA-Z0-9_-]", "_");
        Path projectDir = Paths.get(BASE_DIR).toAbsolutePath().resolve(safeName + "_" + projectId);
        
        if (Files.exists(projectDir)) {
            String suffix = "_" + System.currentTimeMillis();
            projectDir = Paths.get(BASE_DIR).toAbsolutePath().resolve(safeName + "_" + projectId + suffix);
            log.info("目录已存在，使用新路径: {}", projectDir);
        }
        
        try {
            Path basePath = Paths.get(BASE_DIR).toAbsolutePath();
            if (!Files.exists(basePath)) {
                Files.createDirectories(basePath);
            }

            log.info("创建项目目录: {}", projectDir);
            Files.createDirectories(projectDir);

            String originalFilename = file.getOriginalFilename() != null
                    ? file.getOriginalFilename()
                    : "upload.zip";
            Path zipPath = projectDir.resolve(originalFilename);

            log.info("保存ZIP文件: {}", zipPath);
            try (InputStream is = file.getInputStream()) {
                Files.copy(is, zipPath, StandardCopyOption.REPLACE_EXISTING);
            }

            Path extractDir = projectDir.resolve("extracted");
            log.info("创建解压目录: {}", extractDir);
            if (Files.exists(extractDir)) {
                log.info("解压目录已存在，清空内容");
                try (Stream<Path> walk = Files.walk(extractDir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            if (!p.equals(extractDir)) {
                                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                            }
                        });
                }
            }
            Files.createDirectories(extractDir);

            log.info("开始解压ZIP: {}", zipPath);
            extractZip(zipPath, extractDir);
            log.info("ZIP解压完成: {}", extractDir.toAbsolutePath());

            return extractDir.toAbsolutePath().toString();
        } catch (IOException e) {
            log.error("文件上传解压失败: {}", e.getMessage(), e);
            throw new RuntimeException("文件上传解压失败: " + e.getMessage());
        }
    }

    public String readCodeFiles(String codePath) {
        Path dir = Paths.get(codePath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw new RuntimeException("代码目录不存在: " + codePath);
        }

        List<Path> candidateFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !isInSkipDir(path, dir))
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        if (IMPORTANT_FILE_NAMES.contains(name)) return true;
                        int dotIdx = name.lastIndexOf('.');
                        if (dotIdx == -1) return false;
                        String ext = name.substring(dotIdx);
                        return CODE_EXTENSIONS.contains(ext);
                    })
                    .forEach(candidateFiles::add);
        } catch (IOException e) {
            log.error("扫描代码文件失败", e);
        }

        candidateFiles.sort((a, b) -> {
            String aName = a.getFileName().toString().toLowerCase();
            String bName = b.getFileName().toString().toLowerCase();
            boolean aImportant = IMPORTANT_FILE_NAMES.contains(aName);
            boolean bImportant = IMPORTANT_FILE_NAMES.contains(bName);
            // Java源代码文件优先于其他文件
            boolean aIsJava = aName.endsWith(".java");
            boolean bIsJava = bName.endsWith(".java");
            if (aIsJava && !bIsJava) return -1;
            if (!aIsJava && bIsJava) return 1;
            if (aImportant && !bImportant) return -1;
            if (!aImportant && bImportant) return 1;
            try {
                return Long.compare(Files.size(a), Files.size(b));
            } catch (IOException e) {
                return 0;
            }
        });

        StringBuilder combinedCode = new StringBuilder();
        long totalSize = 0;
        int fileCount = 0;

        for (Path path : candidateFiles) {
            try {
                long fileSize = Files.size(path);
                if (fileSize > MAX_FILE_SIZE) {
                    log.debug("跳过过大的文件: {} ({}KB)", path.getFileName(), fileSize / 1024);
                    continue;
                }
                if (totalSize + fileSize > MAX_CODE_SIZE) {
                    log.info("达到代码大小限制，停止读取更多文件");
                    break;
                }
                if (fileCount >= MAX_FILES) {
                    log.info("达到文件数量限制，停止读取");
                    break;
                }

                String relativePath = dir.relativize(path).toString();
                String content = Files.readString(path, StandardCharsets.UTF_8);
                content = sanitizeCodeContent(content);
                combinedCode.append("\n===== File: ").append(relativePath).append(" =====\n");
                combinedCode.append(content).append("\n");
                totalSize += fileSize;
                fileCount++;
            } catch (IOException e) {
                log.warn("读取文件失败: {}", path, e);
            }
        }

        log.info("代码读取完成: {}个文件, {}KB", fileCount, totalSize / 1024);
        return combinedCode.toString();
    }

    private boolean isInSkipDir(Path path, Path baseDir) {
        Path relativePath = baseDir.relativize(path);
        for (Path segment : relativePath) {
            if (SKIP_DIRS.contains(segment.toString().toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    public String getCodePath(Long projectId) {
        Path basePath = Paths.get(BASE_DIR);
        if (!Files.exists(basePath)) {
            return null;
        }

        try (Stream<Path> dirs = Files.list(basePath)) {
            return dirs.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().endsWith("_" + projectId))
                    .map(p -> p.resolve("extracted").toAbsolutePath().toString())
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.error("获取代码路径失败", e);
            return null;
        }
    }

    public String findRelatedFiles(String codePath, String keyword) {
        if (codePath == null || keyword == null || keyword.isEmpty()) {
            return "";
        }

        Path dir = Paths.get(codePath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return "";
        }

        List<Path> matchingFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !isInSkipDir(path, dir))
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        int dotIdx = name.lastIndexOf('.');
                        if (dotIdx == -1) return false;
                        String ext = name.substring(dotIdx);
                        return CODE_EXTENSIONS.contains(ext);
                    })
                    .forEach(matchingFiles::add);
        } catch (IOException e) {
            log.error("搜索相关文件失败", e);
            return "";
        }

        StringBuilder result = new StringBuilder();
        int maxFiles = 10;
        int fileCount = 0;

        for (Path path : matchingFiles) {
            if (fileCount >= maxFiles) break;
            
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                if (content.toLowerCase().contains(keyword.toLowerCase())) {
                    String relativePath = dir.relativize(path).toString();
                    result.append("\n===== Related File: ").append(relativePath).append(" =====\n");
                    
                    int idx = content.toLowerCase().indexOf(keyword.toLowerCase());
                    int start = Math.max(0, idx - 200);
                    int end = Math.min(content.length(), idx + keyword.length() + 300);
                    result.append(content, start, end).append("\n");
                    fileCount++;
                }
            } catch (IOException e) {
                log.warn("读取相关文件失败: {}", path);
            }
        }

        return result.toString();
    }

    /**
     * 根据类名或完整包名查找对应的源文件
     * 支持格式:
     * - 短类名: LlmService
     * - 完整包名: com.codeswatch.service.LlmService
     * - 带扩展名: LlmService.java
     * 
     * @param codePath 项目代码根目录
     * @param className 类名或完整包名
     * @return 找到的文件内容，包含相对路径
     */
    public String findFileByClassName(String codePath, String className) {
        if (codePath == null || className == null || className.isEmpty()) {
            return "";
        }

        Path dir = Paths.get(codePath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return "";
        }

        // 判断规则：
        // 1. 如果以 .java 结尾 -> Java文件
        // 2. 如果包含已知配置文件扩展名 (.xml, .yml, .yaml, .properties, .json 等) -> 非Java配置文件
        // 3. 如果包含多个点号（如 com.example.MyClass）-> Java类路径
        // 4. 如果是纯类名（如 PageQueryUtil）-> Java文件
        // 5. 否则（如 pom 无扩展名的配置名）-> 尝试查找非Java文件
        
        String trimmedName = className.trim();
        
        // 检查是否以 .java 结尾（Java文件）
        if (trimmedName.toLowerCase().endsWith(".java")) {
            return findJavaFile(dir, trimmedName);
        }
        
        // 检查是否包含已知的配置文件扩展名
        Set<String> configExtensions = Set.of(".xml", ".yml", ".yaml", ".properties", ".json", 
                ".html", ".css", ".js", ".ts", ".sql", ".sh", ".bat", ".gradle", ".txt");
        for (String ext : configExtensions) {
            if (trimmedName.toLowerCase().endsWith(ext)) {
                return findNonJavaFile(dir, trimmedName);
            }
        }
        
        // 如果包含多个点号，视为Java完整类路径（如 com.example.MyClass）
        // 排除只有一个点号且以配置文件扩展结尾的情况
        long dotCount = trimmedName.chars().filter(ch -> ch == '.').count();
        if (dotCount >= 2) {
            return findJavaFile(dir, trimmedName);
        }
        
        // 如果只有一个点号，检查是否是配置文件名（如 pom.xml, application.yml）
        if (dotCount == 1) {
            // 尝试作为配置文件搜索
            String nonJavaResult = findNonJavaFile(dir, trimmedName);
            if (!nonJavaResult.isEmpty()) {
                return nonJavaResult;
            }
            // 如果没找到，尝试作为Java类搜索（如 PageQueryUtil 可以是类名）
            return findJavaFile(dir, trimmedName);
        }
        
        // 没有点号，先尝试作为Java类名搜索
        String javaResult = findJavaFile(dir, trimmedName);
        if (!javaResult.isEmpty()) {
            return javaResult;
        }
        
        // 回退：尝试作为配置文件搜索（如 "pom" -> pom.xml）
        return findNonJavaFile(dir, trimmedName);
    }

    /**
     * 查找Java文件，同时查找相关的XML映射文件和实现类
     */
    private String findJavaFile(Path dir, String className) {
        // 解析类名，提取短类名和可能的扩展名
        String shortClassName = className;
        String extension = null;
        
        // 如果是完整包名 (com.example.MyClass)，提取最后一部分
        if (className.contains(".")) {
            String[] parts = className.split("\\.");
            // 检查最后一部分是否包含扩展名
            String lastPart = parts[parts.length - 1];
            if (lastPart.contains(".")) {
                int extIdx = lastPart.lastIndexOf('.');
                extension = lastPart.substring(extIdx);
                shortClassName = lastPart.substring(0, extIdx);
            } else {
                shortClassName = lastPart;
            }
        } else if (className.contains(".")) {
            // 已经是短类名带扩展名 (MyClass.java)
            int extIdx = className.lastIndexOf('.');
            extension = className.substring(extIdx);
            shortClassName = className.substring(0, extIdx);
        }

        // 转换为文件名
        String targetFileName = shortClassName + (extension != null ? extension : ".java");
        
        // 收集所有需要返回的文件
        List<Path> matchingFiles = new ArrayList<>();
        
        // 1. 查找精确匹配的Java文件
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !isInSkipDir(path, dir))
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.equalsIgnoreCase(targetFileName);
                    })
                    .forEach(matchingFiles::add);
        } catch (IOException e) {
            log.error("查找类文件失败", e);
            return "";
        }

        // 2. 如果没有找到精确匹配，尝试用短类名搜索
        if (matchingFiles.isEmpty()) {
            String fuzzyResult = findFilesByShortName(dir, shortClassName, extension);
            if (fuzzyResult != null && !fuzzyResult.isEmpty()) {
                return fuzzyResult;
            }
            return "";
        }

        // 3. 查找相关的实现类（如果是接口，查找XxxServiceImpl.java或XxxImpl.java）
        findRelatedImplFiles(dir, shortClassName, matchingFiles);
        
        // 4. 查找相关的Mapper XML文件
        findRelatedXmlFiles(dir, shortClassName, matchingFiles);

        // 5. 收集所有文件内容
        StringBuilder result = new StringBuilder();
        for (Path path : matchingFiles) {
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                String relativePath = dir.relativize(path).toString();
                result.append("\n===== File: ").append(relativePath).append(" =====\n");
                // 如果文件太大，截断
                if (content.length() > 500000) {
                    result.append(content, 0, 500000).append("\n... (文件已截断)");
                } else {
                    result.append(content).append("\n");
                }
            } catch (IOException e) {
                log.warn("读取类文件失败: {}", path);
            }
        }

        return result.toString();
    }
    
    /**
     * 查找相关的实现类文件
     */
    private void findRelatedImplFiles(Path dir, String interfaceName, List<Path> existingFiles) {
        // 跳过Service接口（它们不是Mapper接口）
        // 查找可能的实现类：XxxServiceImpl.java, XxxImpl.java
        String[] implPatterns = {
            interfaceName + "Impl.java",
            interfaceName + "ServiceImpl.java"
        };
        
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !isInSkipDir(path, dir))
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        for (String pattern : implPatterns) {
                            if (fileName.equalsIgnoreCase(pattern)) {
                                // 检查是否已存在
                                boolean exists = existingFiles.stream()
                                    .anyMatch(f -> f.getFileName().toString().equalsIgnoreCase(pattern));
                                return !exists;
                            }
                        }
                        return false;
                    })
                    .forEach(existingFiles::add);
        } catch (IOException e) {
            log.debug("查找实现类文件失败: {}", e.getMessage());
        }
    }
    
    /**
     * 查找相关的Mapper XML文件
     */
    private void findRelatedXmlFiles(Path dir, String className, List<Path> existingFiles) {
        // 构建可能的XML文件名
        String[] xmlPatterns = {
            className + "Mapper.xml",
            className + ".xml",
            className.toLowerCase() + "Mapper.xml"
        };
        
        // 对于Service接口，也查找对应的Mapper XML
        // Service -> XxxMapper.xml
        if (className.endsWith("Service")) {
            String mapperName = className.substring(0, className.length() - 7) + "Mapper.xml";
            xmlPatterns = new String[]{
                mapperName,
                className + "Mapper.xml",
                className + ".xml"
            };
        }
        
        for (String pattern : xmlPatterns) {
            if (pattern == null) continue;
            
            boolean alreadyFound = existingFiles.stream()
                .anyMatch(f -> f.getFileName().toString().equalsIgnoreCase(pattern));
            if (alreadyFound) continue;
            
            try (Stream<Path> walk = Files.walk(dir)) {
                Optional<Path> found = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> !isInSkipDir(path, dir))
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(pattern))
                    .findFirst();
                
                found.ifPresent(existingFiles::add);
                if (found.isPresent()) {
                    log.debug("找到相关XML文件: {}", found.get().getFileName());
                    break; // 找到一个就停止
                }
            } catch (IOException e) {
                log.debug("查找XML文件失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 查找非Java文件（如 pom.xml, application.yml, .properties 等）
     */
    private String findNonJavaFile(Path dir, String fileName) {
        List<Path> matchingFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !isInSkipDir(path, dir))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.equalsIgnoreCase(fileName);
                    })
                    .forEach(matchingFiles::add);
        } catch (IOException e) {
            log.error("查找非Java文件失败", e);
            return "";
        }

        if (matchingFiles.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        for (Path path : matchingFiles) {
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                String relativePath = dir.relativize(path).toString();
                result.append("\n===== File: ").append(relativePath).append(" =====\n");
                if (content.length() > 500000) {
                    result.append(content, 0, 500000).append("\n... (文件已截断)");
                } else {
                    result.append(content).append("\n");
                }
            } catch (IOException e) {
                log.warn("读取非Java文件失败: {}", path);
            }
        }

        return result.toString();
    }

    /**
     * 根据短类名搜索文件（不区分大小写，支持模糊匹配）
     */
    private String findFilesByShortName(Path dir, String shortClassName, String extension) {
        List<Path> matchingFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !isInSkipDir(path, dir))
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        String fileNameLower = fileName.toLowerCase();
                        String targetNameLower = shortClassName.toLowerCase();
                        
                        // 检查文件名是否以类名开头 (如 LlmService.java, LlmServiceImpl.java)
                        if (fileNameLower.startsWith(targetNameLower)) {
                            // 如果指定了扩展名，检查扩展名
                            if (extension != null) {
                                return fileNameLower.equals(targetNameLower + extension.toLowerCase()) 
                                       || fileNameLower.startsWith(targetNameLower);
                            }
                            return true;
                        }
                        return false;
                    })
                    .forEach(matchingFiles::add);
        } catch (IOException e) {
            log.error("模糊搜索类文件失败", e);
            return "";
        }

        if (matchingFiles.isEmpty()) {
            return "";
        }

        // 最多返回10个文件
        int maxFiles = 10;
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (Path path : matchingFiles) {
            if (count >= maxFiles) break;
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                String relativePath = dir.relativize(path).toString();
                result.append("\n===== File: ").append(relativePath).append(" =====\n");
                if (content.length() > 500000) {
                    result.append(content, 0, 500000).append("\n... (文件已截断)");
                } else {
                    result.append(content).append("\n");
                }
                count++;
            } catch (IOException e) {
                log.warn("读取类文件失败: {}", path);
            }
        }

        return result.toString();
    }

    private void extractZip(Path zipPath, Path extractDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath.toFile()))) {
            ZipEntry entry;
            int entryCount = 0;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                String entryName = entry.getName();
                log.info("处理ZIP条目#{}: name='{}', isDir={}, size={}, compressed={}", 
                    entryCount, entryName, entry.isDirectory(), entry.getSize(), entry.getCompressedSize());
                
                if (entryName.isEmpty() || entryName.equals("/") || entryName.equals("\\")) {
                    log.warn("跳过空名称条目");
                    zis.closeEntry();
                    continue;
                }
                
                Path entryPath = extractDir.resolve(entryName).normalize();
                log.info("解析路径: {}", entryPath);

                if (!entryPath.startsWith(extractDir.normalize())) {
                    throw new IOException("ZIP条目超出目标目录: " + entryName);
                }

                if (entry.isDirectory()) {
                    if (!Files.exists(entryPath)) {
                        Files.createDirectories(entryPath);
                    }
                } else {
                    Path parentDir = entryPath.getParent();
                    if (parentDir != null && !Files.exists(parentDir)) {
                        log.info("创建父目录: {}", parentDir);
                        Files.createDirectories(parentDir);
                    }
                    log.info("写入文件: {}", entryPath);
                    try (FileOutputStream fos = new FileOutputStream(entryPath.toFile())) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
            log.info("ZIP解压完成，共处理{}个条目", entryCount);
        }
    }

    private String sanitizeCodeContent(String content) {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || (c >= 32 && c != 127)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void deleteDirectory(File dir) {
        if (!dir.exists()) {
            return;
        }
        
        String path = dir.getAbsolutePath();
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "rmdir", "/s", "/q", path);
            } else {
                pb = new ProcessBuilder("rm", "-rf", path);
            }
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("已删除目录: {}", path);
                return;
            }
        } catch (Exception e) {
            log.warn("系统命令删除失败: {}", e.getMessage());
        }
        
        try {
            Path dirPath = dir.toPath();
            Files.walk(dirPath)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
            if (!Files.exists(dirPath)) {
                log.info("已通过NIO删除目录: {}", path);
                return;
            }
        } catch (Exception e) {
            log.warn("NIO删除失败: {}", e.getMessage());
        }
        
        if (dir.exists()) {
            log.warn("无法删除目录，标记为onExit: {}", path);
            dir.deleteOnExit();
        }
    }
}