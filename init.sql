-- ============================================================
-- CodesWatch 数据库初始化脚本
-- 仓库地址: https://github.com/mlf519/CodesWatch
-- 说明: 仅初始化库表结构，不包含任何个人数据（大模型配置等
--       需在系统"模型配置"页面自行填写）
-- ============================================================

CREATE DATABASE IF NOT EXISTS CodesWatch DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE CodesWatch;

-- 扫描项目主表
CREATE TABLE IF NOT EXISTS scan_project (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_name VARCHAR(255) NOT NULL UNIQUE,
    project_description TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    project_code LONGTEXT,
    report_content LONGTEXT,
    total_tasks INT NOT NULL DEFAULT 0,
    completed_tasks INT NOT NULL DEFAULT 0,
    finding_count INT NOT NULL DEFAULT 0,
    code_path VARCHAR(500),
    scan_session_id VARCHAR(100),
    last_llm_config_id BIGINT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME,
    started_at DATETIME,
    completed_at DATETIME,
    INDEX idx_project_status (status),
    INDEX idx_project_created (created_at),
    INDEX idx_project_session (scan_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 扫描任务表（支持父子多级任务）
CREATE TABLE IF NOT EXISTS scan_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    parent_id BIGINT,
    task_name VARCHAR(500) NOT NULL,
    task_description TEXT,
    task_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    target_code MEDIUMTEXT,
    llm_request MEDIUMTEXT,
    llm_response MEDIUMTEXT,
    thinking_process MEDIUMTEXT,
    has_vulnerability BOOLEAN,
    result VARCHAR(50),
    analysis_result TEXT,
    progress INT NOT NULL DEFAULT 0,
    llm_config_id BIGINT,
    llm_config_name VARCHAR(255),
    prompt_config_id BIGINT,
    file_path VARCHAR(500),
    created_at DATETIME NOT NULL,
    updated_at DATETIME,
    started_at DATETIME,
    completed_at DATETIME,
    INDEX idx_task_project (project_id),
    INDEX idx_task_parent (parent_id),
    INDEX idx_task_status (status),
    FOREIGN KEY (project_id) REFERENCES scan_project(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_id) REFERENCES scan_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 漏洞发现表
CREATE TABLE IF NOT EXISTS scan_finding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    task_id BIGINT,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    code_snippet TEXT,
    file_path VARCHAR(100),
    line_number VARCHAR(20),
    severity VARCHAR(20) NOT NULL,
    finding_type VARCHAR(100),
    exploitation_path TEXT,
    suggestion TEXT,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL,
    INDEX idx_finding_project (project_id),
    INDEX idx_finding_task (task_id),
    INDEX idx_finding_severity (severity),
    INDEX idx_finding_type (finding_type),
    FOREIGN KEY (project_id) REFERENCES scan_project(id) ON DELETE CASCADE,
    FOREIGN KEY (task_id) REFERENCES scan_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 大模型配置表（结构定义，个人 API Key 等数据请通过页面配置，勿提交到代码库）
CREATE TABLE IF NOT EXISTS llm_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_name VARCHAR(100),
    provider_name VARCHAR(50) NOT NULL,
    base_url VARCHAR(255),
    api_key VARCHAR(500),
    model VARCHAR(100),
    thinking_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL,
    updated_at DATETIME,
    INDEX idx_llm_provider (provider_name),
    INDEX idx_llm_enabled (enabled),
    INDEX idx_llm_default (is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 提示词配置表（扫描阶段 / 全局提示词）
CREATE TABLE IF NOT EXISTS prompt_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    phase VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    system_prompt TEXT,
    analysis_prompt TEXT,
    prefix_prompt TEXT,
    suffix_prompt TEXT,
    vulnerability_types TEXT,
    dangerous_function_patterns TEXT,
    vulnerability_prompts TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME,
    INDEX idx_prompt_phase (phase),
    INDEX idx_prompt_enabled (enabled),
    INDEX idx_prompt_order (display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 扫描日志表
CREATE TABLE IF NOT EXISTS scan_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    task_id BIGINT,
    log_type ENUM('THOUGHT','ACTION','OBSERVATION','INFO','WARNING','ERROR','TASK_START','TASK_COMPLETE','FINDING','LLM_REQUEST','LLM_RESPONSE','LLM_THINKING','FILE_CONTEXT') NOT NULL,
    message MEDIUMTEXT NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_log_project (project_id),
    INDEX idx_log_task (task_id),
    INDEX idx_log_type (log_type),
    INDEX idx_log_created (created_at),
    FOREIGN KEY (project_id) REFERENCES scan_project(id) ON DELETE CASCADE,
    FOREIGN KEY (task_id) REFERENCES scan_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 提示词配置初始数据（系统默认提示词，可在提示词配置页面调整）
-- ============================================================

INSERT INTO `prompt_config` (`enabled`, `created_at`, `id`, `updated_at`, `analysis_prompt`, `name`, `system_prompt`, `phase`, `dangerous_function_patterns`, `vulnerability_types`, `vulnerability_prompts`, `display_order`, `prefix_prompt`, `suffix_prompt`) VALUES (0x01,'2026-08-04 17:11:03.873444',1,'2026-09-03 09:18:58.370829','分析该文件下的所有依赖版本，分析是否存在安全漏洞，只分析是否存在文件上传、任意文件下载、sql注入、XXE、命令执行和反序列化漏洞。','pom文件扫描',NULL,'DEPENDENCY_SCAN',NULL,NULL,NULL,0,NULL,NULL),(0x01,'2026-08-04 17:11:39.278580',2,'2026-09-03 09:19:10.832340','审计该接口是否存在文件上传漏洞，要求从接口入口开始分析到文件上传到服务器上的全流程，分析是否存在文件上传漏洞和是否存在文件类型过滤防护，如果发现是上传到文件桶则终止分析并给出安全结论。','文件上传',NULL,'INTERFACE_SCAN',NULL,NULL,NULL,0,NULL,NULL),(0x01,'2026-08-04 17:13:02.174478',3,'2026-08-07 11:07:20.003018','审计该接口是否存在sql注入漏洞，要求从接口入口开始分析到sql语句执行全流程，分析是否存在sql注入漏洞和是否存在过滤防护。','sql注入',NULL,'INTERFACE_SCAN',NULL,NULL,NULL,0,NULL,NULL),(0x00,'2026-08-04 17:13:34.981449',4,'2026-08-13 19:42:59.527196','审计该接口是否存在文件上传漏洞，要求从接口入口开始分析到文件上传到服务器上的全流程，分析是否存在文件上传漏洞和是否存在文件类型过滤防护，如果发现是上传到文件桶则终止分析并给出安全结论。','new File(',NULL,'HIGH_RISK_OPERATION_SCAN',NULL,NULL,NULL,0,NULL,NULL),(0x01,'2026-08-07 09:51:04.856764',5,'2026-08-18 20:20:09.435337',NULL,'全局安全审计配置','你是一个专业的代码安全审计专家。','GLOBAL_CONFIG',NULL,NULL,NULL,0,'只需要分析当前接口是否存在指定漏洞类型，不要被其他接口干扰。','当你需要了解其他函数时：\n1.首先分析是否为项目中的函数，如不是，则应为java自带或开源组件的函数，自行搜索了解；\n2.如果是，则给出具体的函数在的包名，下一个请求将携带你需要了解的包文件发送给你，给出具体函数所在的包，注意不要给出猜测的包名，从给你的代码文件中获取包名，其他需要了解的包名会在之后给你的代码文件中出现。在你回复的末尾另起一行添加指令，格式使用more::more进行包裹，例子：more:com.codewatcher.service.LlmService;com.其他包:more。如果需要其他xml配置文件，以同样格式给出需要的文件包名。\n3.当你分析完该漏洞后，在你回复的末尾另起一行添加指令，格式使用end:Vul!或者Safe!开头+另起一行加上简要分析:end进行包裹，例子：end:Vul!另起一行加上此处总结针对该接口是否存在指定漏洞的简要分析:end\n4.标签more和end，单次对话大模型回复只能携带一种标签');

ALTER TABLE prompt_config AUTO_INCREMENT = 6;
