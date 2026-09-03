const API_BASE = '/api';

let currentProjectId = null;
let currentModalType = null;
let currentTaskFilter = 'all';

document.addEventListener('DOMContentLoaded', () => {
    initMatrixRain();
    initNavigation();
    initModals();
    initDashboard();
    initProjects();
    initProjectDetail();
    initLlmConfig();
    initTaskManagement();
    initFileUpload();
    initModelSelectModal();
    initCreateTaskModal();
    initPromptConfig();
});

function initMatrixRain() {
    const canvas = document.getElementById('matrix-canvas');
    const ctx = canvas.getContext('2d');
    
    function resizeCanvas() {
        canvas.width = window.innerWidth;
        canvas.height = window.innerHeight;
    }
    resizeCanvas();
    window.addEventListener('resize', resizeCanvas);
    
    const matrix = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@#$%^&*()_+-=[]{}|;:,.<>?abcdefghijklmnopqrstuvwxyz';
    const chars = matrix.split('');
    const fontSize = 14;
    const columns = Math.floor(canvas.width / fontSize);
    const drops = [];
    
    for (let i = 0; i < columns; i++) {
        drops[i] = Math.random() * -100;
    }
    
    function draw() {
        ctx.fillStyle = 'rgba(0, 0, 0, 0.05)';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        
        ctx.fillStyle = '#00ff41';
        ctx.font = fontSize + 'px monospace';
        
        for (let i = 0; i < drops.length; i++) {
            const text = chars[Math.floor(Math.random() * chars.length)];
            const x = i * fontSize;
            const y = drops[i] * fontSize;
            
            if (Math.random() > 0.9) {
                ctx.fillStyle = '#ffffff';
            } else {
                ctx.fillStyle = '#00ff41';
            }
            
            ctx.fillText(text, x, y);
            
            if (y > canvas.height && Math.random() > 0.975) {
                drops[i] = 0;
            }
            drops[i]++;
        }
    }
    
    setInterval(draw, 50);
}

function initNavigation() {
    const navItems = document.querySelectorAll('.nav-item');
    const pageTitle = document.getElementById('page-title');
    
    navItems.forEach(item => {
        item.addEventListener('click', (e) => {
            e.preventDefault();
            
            navItems.forEach(nav => nav.classList.remove('active'));
            item.classList.add('active');
            
            const page = item.dataset.page;
            showPage(page);

            stopTasksAutoRefresh();
            switch(page) {
                case 'dashboard':
                    pageTitle.textContent = '驾驶舱';
                    loadDashboard();
                    break;
                case 'projects':
                    pageTitle.textContent = '项目管理';
                    loadProjects();
                    break;
                case 'config':
                    pageTitle.textContent = '大模型配置';
                    loadLlmConfigs();
                    break;
                case 'tasks':
                    pageTitle.textContent = '任务管理';
                    loadTasks(currentTaskFilter);
                    startTasksAutoRefresh();
                    break;
                case 'prompt-config':
                    pageTitle.textContent = '提示词配置';
                    loadPromptConfigs();
                    break;
            }
        });
    });

    document.getElementById('refresh-btn').addEventListener('click', () => {
        const activePage = document.querySelector('.nav-item.active').dataset.page;
        if (activePage === 'dashboard') loadDashboard();
        else if (activePage === 'projects') loadProjects();
        else if (activePage === 'tasks') refreshTasksInPlace();
    });
}

function showPage(page) {
    stopLogStreaming();
    
    document.querySelectorAll('section').forEach(section => {
        section.classList.add('hidden');
    });
    
    const sidebar = document.querySelector('.sidebar');
    const mainContent = document.querySelector('.main-content');
    
    if (page === 'project-detail') {
        sidebar.style.display = 'none';
        mainContent.style.marginLeft = '0';
        document.getElementById('project-detail-section').classList.remove('hidden');
    } else {
        sidebar.style.display = 'flex';
        mainContent.style.marginLeft = '280px';
        if (page === 'dashboard') {
            document.getElementById('dashboard-section').classList.remove('hidden');
        } else if (page === 'projects') {
            document.getElementById('projects-section').classList.remove('hidden');
        } else if (page === 'config') {
            document.getElementById('config-section').classList.remove('hidden');
        } else if (page === 'tasks') {
            document.getElementById('tasks-section').classList.remove('hidden');
        } else if (page === 'prompt-config') {
            document.getElementById('prompt-config-section').classList.remove('hidden');
        }
    }
}

function initModals() {
    const modalOverlay = document.getElementById('modal-overlay');
    const llmModalOverlay = document.getElementById('llm-modal-overlay');
    
    document.getElementById('create-project-btn').addEventListener('click', () => {
        currentModalType = 'create';
        currentProjectId = null;
        document.getElementById('modal-title').textContent = '新建项目';
        document.getElementById('project-form').reset();
        document.getElementById('modal-submit').onclick = submitProjectForm;
        const codeStatusDiv = document.getElementById('code-status');
        if (codeStatusDiv) {
            codeStatusDiv.classList.add('hidden');
        }
        modalOverlay.classList.remove('hidden');
    });
    
    document.getElementById('modal-close').addEventListener('click', () => {
        modalOverlay.classList.add('hidden');
    });
    
    document.getElementById('modal-cancel').addEventListener('click', () => {
        modalOverlay.classList.add('hidden');
    });
    
    document.getElementById('add-llm-btn').addEventListener('click', () => {
        document.getElementById('llm-modal-title').textContent = '添加大模型配置';
        document.getElementById('llm-form').reset();
        document.getElementById('llm-form-id').value = '';
        llmModalOverlay.classList.remove('hidden');
    });
    
    document.getElementById('llm-modal-close').addEventListener('click', () => {
        llmModalOverlay.classList.add('hidden');
    });
    
    document.getElementById('llm-modal-cancel').addEventListener('click', () => {
        llmModalOverlay.classList.add('hidden');
    });
    
    document.getElementById('llm-modal-submit').addEventListener('click', () => {
        submitLlmForm();
    });
    
    document.getElementById('llm-provider').addEventListener('input', (e) => {
        const provider = e.target.value;
        const defaults = {
            openai: { url: 'https://api.openai.com/v1', model: 'gpt-4o' },
            gemini: { url: 'https://generativelanguage.googleapis.com/v1', model: 'gemini-1.5-pro' },
            qwen: { url: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-turbo' },
            doubao: { url: 'https://ark.cn-beijing.volces.com/api/v3', model: 'doubao-pro-32k' },
            zhipu: { url: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4' },
            moonshot: { url: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k' },
            baidu: { url: 'https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat', model: 'ernie-4.0' },
            anthropic: { url: 'https://api.anthropic.com/v1', model: 'claude-3.5-sonnet' },
            mistral: { url: 'https://api.mistral.ai/v1', model: 'mistral-large-latest' },
            perplexity: { url: 'https://api.perplexity.ai/chat/completions', model: 'llama-3.1-sonar-large-128k-online' },
            groq: { url: 'https://api.groq.com/openai/v1', model: 'llama-3.1-8b-instant' },
            ollama: { url: 'http://localhost:11434/v1', model: 'llama3' }
        };
        
        if (defaults[provider]) {
            document.getElementById('llm-base-url').value = defaults[provider].url;
            document.getElementById('llm-model').value = defaults[provider].model;
        }
    });
    
    document.getElementById('project-search').addEventListener('input', (e) => {
        if (e.target.value.length >= 2) {
            loadProjects(e.target.value);
        } else {
            loadProjects();
        }
    });
    
    document.getElementById('search-btn').addEventListener('click', () => {
        const query = document.getElementById('project-search').value;
        loadProjects(query);
    });
}

async function submitProjectForm() {
    const name = document.getElementById('form-name').value;
    const description = document.getElementById('form-description').value;
    const fileInput = document.getElementById('form-file');
    const file = fileInput && fileInput.files.length > 0 ? fileInput.files[0] : null;

    if (!name) {
        alert('请填写项目名称');
        return;
    }

    if (!file) {
        alert('请上传 ZIP 格式的代码包');
        return;
    }

    const formData = new FormData();
    formData.append('file', file);
    formData.append('projectName', name);
    formData.append('projectDescription', description || '');

    const response = await fetch(`${API_BASE}/projects/upload`, {
        method: 'POST',
        body: formData
    });

    if (response.ok) {
        const project = await response.json();
        document.getElementById('modal-overlay').classList.add('hidden');
        loadProjects();
        showToast('项目创建成功');
    } else {
        const error = await response.text();
        alert('创建失败: ' + error);
    }
}

async function submitLlmForm() {
    const id = document.getElementById('llm-form-id').value;
    const configName = document.getElementById('llm-config-name').value.trim();
    const provider = document.getElementById('llm-provider').value.trim();
    const baseUrl = document.getElementById('llm-base-url').value;
    const apiKey = document.getElementById('llm-api-key').value;
    const model = document.getElementById('llm-model').value;
    const thinkingEnabled = document.getElementById('llm-thinking').checked;
    const enabled = document.getElementById('llm-enabled').checked;
    const isDefault = document.getElementById('llm-default').checked;
    
    if (!configName || !provider || !baseUrl || !apiKey || !model) {
        alert('请填写必填项');
        return;
    }
    
    const method = id ? 'PUT' : 'POST';
    const url = id ? `${API_BASE}/llm-config/${id}` : `${API_BASE}/llm-config`;
    
    const response = await fetch(url, {
        method: method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            configName: configName,
            providerName: provider,
            baseUrl: baseUrl,
            apiKey: apiKey,
            model: model,
            thinkingEnabled: thinkingEnabled,
            enabled: enabled,
            isDefault: isDefault
        })
    });
    
    if (response.ok) {
        document.getElementById('llm-modal-overlay').classList.add('hidden');
        loadLlmConfigs();
        showToast('配置保存成功');
    } else {
        const error = await response.json();
        alert('保存失败: ' + (error.error || '未知错误'));
    }
}

function initDashboard() {
    loadDashboard();
}

async function loadDashboard() {
    const response = await fetch(`${API_BASE}/dashboard/stats`);
    if (response.ok) {
        const stats = await response.json();
        document.getElementById('total-projects').textContent = stats.totalProjects || 0;
        document.getElementById('completed-projects').textContent = stats.completedProjects || 0;
        document.getElementById('scanning-projects').textContent = stats.scanningProjects || 0;
        document.getElementById('total-findings').textContent = stats.totalFindings || 0;
        document.getElementById('critical-findings').textContent = stats.criticalFindings || 0;
        document.getElementById('high-findings').textContent = stats.highFindings || 0;
        
        await loadRecentProjects();
    }
}

async function loadRecentProjects() {
    const response = await fetch(`${API_BASE}/projects`);
    if (response.ok) {
        const projects = await response.json();
        const recent = projects.slice(0, 5);
        const tbody = document.getElementById('recent-projects-body');
        tbody.innerHTML = recent.map(p => `
            <tr>
                <td><a href="#" class="project-link" data-id="${p.id}">${p.projectName}</a></td>
                <td><span class="status-badge status-${p.status.toLowerCase()}">${getStatusText(p.status)}</span></td>
                <td>${p.findingCount || 0}</td>
                <td>${formatDate(p.createdAt)}</td>
                <td><button class="btn btn-sm btn-secondary view-btn" data-id="${p.id}">查看</button></td>
            </tr>
        `).join('');
        
        document.querySelectorAll('.view-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.preventDefault();
                viewProject(e.target.dataset.id);
            });
        });
        
        document.querySelectorAll('.project-link').forEach(link => {
            link.addEventListener('click', (e) => {
                e.preventDefault();
                viewProject(e.target.dataset.id);
            });
        });
    }
}

function initProjects() {
    loadProjects();
}

async function loadProjects(search = '') {
    const url = search ? `${API_BASE}/projects?search=${encodeURIComponent(search)}` : `${API_BASE}/projects`;
    const response = await fetch(url);
    if (response.ok) {
        const projects = await response.json();
        const tbody = document.getElementById('projects-table-body');
        tbody.innerHTML = projects.map(p => `
            <tr>
                <td><a href="#" class="project-link" data-id="${p.id}">${p.projectName}</a></td>
                <td>${p.projectDescription || '-'}</td>
                <td><span class="status-badge status-${p.status.toLowerCase()}">${getStatusText(p.status)}</span></td>
                <td>
                    <div class="progress-bar" style="height: 8px;">
                        <div class="progress-fill" style="width: ${p.totalTasks > 0 ? (p.completedTasks / p.totalTasks) * 100 : 0}%"></div>
                    </div>
                    ${p.completedTasks}/${p.totalTasks}
                </td>
                <td>${p.findingCount || 0}</td>
                <td>${formatDate(p.createdAt)}</td>
                <td>
                    <button class="btn btn-sm btn-secondary view-btn" data-id="${p.id}">查看</button>
                    <button class="btn btn-sm btn-secondary edit-btn" data-id="${p.id}">编辑</button>
                    ${p.status !== 'SCANNING' ? `<button class="btn btn-sm btn-success start-scan-list-btn" data-id="${p.id}">开始扫描</button>` : ''}
                    <button class="btn btn-sm btn-secondary export-report-list-btn" data-id="${p.id}">导出报告</button>
                    <button class="btn btn-sm btn-danger delete-btn" data-id="${p.id}">删除</button>
                </td>
            </tr>
        `).join('');

        document.querySelectorAll('.view-btn').forEach(btn => {
            btn.addEventListener('click', (e) => viewProject(e.target.dataset.id));
        });

        document.querySelectorAll('.edit-btn').forEach(btn => {
            btn.addEventListener('click', (e) => editProject(e.target.dataset.id));
        });

        document.querySelectorAll('.delete-btn').forEach(btn => {
            btn.addEventListener('click', (e) => deleteProject(e.target.dataset.id));
        });

        document.querySelectorAll('.start-scan-list-btn').forEach(btn => {
            btn.addEventListener('click', (e) => showModelSelectModal(e.target.dataset.id));
        });

        document.querySelectorAll('.export-report-list-btn').forEach(btn => {
            btn.addEventListener('click', (e) => exportReport(e.target.dataset.id, 'pdf'));
        });
        
        document.querySelectorAll('.project-link').forEach(link => {
            link.addEventListener('click', (e) => {
                e.preventDefault();
                viewProject(e.target.dataset.id);
            });
        });
    }
}

function getStatusText(status) {
    const map = {
        PENDING: '等待扫描',
        SCANNING: '扫描中',
        COMPLETED: '已完成',
        FAILED: '失败',
        CANCELLED: '已取消'
    };
    return map[status] || status;
}

/** 任务进度文本：已扫描任务数/总数 */
function taskProgressText(task) {
    const total = task.totalTaskCount != null ? task.totalTaskCount : 0;
    const completed = task.completedTaskCount != null ? task.completedTaskCount : 0;
    return `${completed}/${total}`;
}

/** 任务进度百分比（用于进度条宽度） */
function taskProgressPct(task) {
    const total = task.totalTaskCount != null ? task.totalTaskCount : 0;
    const completed = task.completedTaskCount != null ? task.completedTaskCount : 0;
    return total > 0 ? Math.round((completed / total) * 100) : 0;
}

function formatDate(dateStr) {
    if (!dateStr) return '-';
    const date = new Date(dateStr);
    return date.toLocaleString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
    });
}

async function viewProject(id) {
    currentProjectId = id;
    showPage('project-detail');
    await loadProjectDetail(id);
}

async function loadProjectDetail(id) {
    const response = await fetch(`${API_BASE}/projects/${id}`);
    if (response.ok) {
        const project = await response.json();
        
        document.getElementById('project-detail-title').textContent = project.projectName;
        document.getElementById('detail-name').textContent = project.projectName;
        document.getElementById('detail-description').textContent = project.projectDescription || '-';
        
        const statusBadge = document.getElementById('detail-status');
        statusBadge.textContent = getStatusText(project.status);
        statusBadge.className = `status-badge status-${project.status.toLowerCase()}`;
        
        document.getElementById('detail-created').textContent = formatDate(project.createdAt);
        document.getElementById('detail-started').textContent = formatDate(project.startedAt);
        document.getElementById('detail-completed').textContent = formatDate(project.completedAt);
        
        const progress = project.totalTasks > 0 ? (project.completedTasks / project.totalTasks) * 100 : 0;
        document.getElementById('detail-progress').style.width = `${progress}%`;
        document.getElementById('detail-progress-text').textContent = `${Math.round(progress)}%`;
        
        document.getElementById('detail-findings').textContent = project.findingCount || 0;

        const startBtn = document.getElementById('start-scan-btn');
        const cancelBtn = document.getElementById('cancel-scan-btn');
        if (project.status === 'SCANNING') {
            startBtn.style.display = 'none';
            startBtn.disabled = true;
            startBtn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 扫描中...';
            cancelBtn.style.display = 'inline-block';
        } else {
            startBtn.style.display = 'inline-block';
            startBtn.disabled = false;
            startBtn.innerHTML = '<i class="fas fa-play"></i> 开始扫描';
            cancelBtn.style.display = 'none';
        }
        
        renderTaskTree(project.tasks);
        initProjectDetailTaskTree();
        renderFindings(project.findings);

        loadLogs(id);
        currentDetailScanning = project.status === 'SCANNING';
        loadProjectReport(id);
        startReportAutoRefresh(id);
    }
}

function renderTaskTree(tasks) {
    const container = document.getElementById('task-tree');
    if (!tasks || tasks.length === 0) {
        container.innerHTML = '<p style="text-align:center; color:#00aa29; padding:20px;">暂无扫描任务</p>';
        return;
    }
    container.innerHTML = tasks.map(task => renderTaskNode(task, 0)).join('');
}

function renderTaskNode(task, depth = 0) {
    const padding = depth * 20;
    const typeLabel = getTaskTypeText(task.taskType);
    return `
        <div class="task-node" style="margin-left: ${padding}px;" data-depth="${depth}">
            <div class="task-node-header task-node-toggle" data-task-id="${task.id}">
                <i class="fas fa-chevron-right task-icon"></i>
                <span class="task-name">${escapeHtml(task.taskName)}</span>
                <span class="task-type-tag">${typeLabel}</span>
                <span class="task-progress">${taskProgressText(task)}</span>
                <span class="task-status status-${task.status.toLowerCase()}">${getStatusText(task.status)}</span>
                ${task.llmConfigName ? `<span class="task-llm-model">🤖 ${escapeHtml(task.llmConfigName)}</span>` : ''}
            </div>
            <div class="task-children" style="display: none;" data-loaded="false">
                <div style="padding:8px 16px; color:#888; font-size:12px;">点击展开加载子任务</div>
            </div>
        </div>
    `;
}

function initProjectDetailTaskTree() {
    const container = document.getElementById('task-tree');
    if (!container) return;
    
    const toggles = container.querySelectorAll('.task-node-toggle');
    console.log('Found', toggles.length, 'task-node-toggle elements');
    
    toggles.forEach((header, index) => {
        console.log('Binding toggle', index, 'with taskId:', header.dataset.taskId);
        header.addEventListener('click', (e) => {
            e.stopPropagation();
            e.preventDefault();
            const nodeId = header.dataset.taskId;
            console.log('Detail task clicked, nodeId:', nodeId);
            
            const isExpanded = header.classList.toggle('expanded');
            console.log('isExpanded:', isExpanded);
            
            const icon = header.querySelector('i');
            console.log('icon:', icon ? 'found' : 'not found', 'class:', icon ? icon.className : 'N/A');
            
            // 直接使用 nextElementSibling 替代 closest + querySelector
            const children = header.nextElementSibling;
            console.log('children:', children ? 'found' : 'not found', 'classList:', children ? children.className : 'N/A', 'loaded:', children ? children.dataset.loaded : 'N/A');
            
            if (isExpanded) {
                if (icon) icon.className = 'fas fa-chevron-down';
                if (children) {
                    children.style.display = 'block';
                    // 注意：dataset.loaded 返回字符串，必须显式比较 'true'
                    if (children.dataset.loaded !== 'true') {
                        console.log('Loading detail child tasks for:', nodeId);
                        loadDetailChildTasks(nodeId, children, header);
                    } else {
                        console.log('Already loaded, skipping');
                    }
                } else {
                    console.log('No children container found!');
                }
            } else {
                if (icon) icon.className = 'fas fa-chevron-right';
                if (children) children.style.display = 'none';
            }
        });
    });
}

async function loadDetailChildTasks(parentId, childrenContainer, header) {
    const icon = header.querySelector('i');
    const originalIcon = icon ? icon.className : '';
    if (icon) {
        icon.className = 'fas fa-spinner fa-spin';
    }
    
    try {
        const response = await fetch(`${API_BASE}/tasks/${parentId}/children`);
        if (response.ok) {
            const children = await response.json();
            childrenContainer.dataset.loaded = 'true';
            
            if (children.length > 0) {
                // 使用 parentElement 直接获取 depth
                const node = childrenContainer.parentElement;
                const depth = node ? parseInt(node.dataset.depth) + 1 : 1;
                childrenContainer.innerHTML = children.map(child => renderTaskNode(child, depth)).join('');
                // 绑定新加载的子任务的展开事件
                childrenContainer.querySelectorAll('.task-node-toggle').forEach(h => {
                    h.addEventListener('click', (e) => {
                        e.stopPropagation();
                        const nodeId = h.dataset.taskId;
                        const exp = h.classList.toggle('expanded');
                        const ic = h.querySelector('i');
                        const ch = h.nextElementSibling;
                        if (exp) {
                            if (ic) ic.className = 'fas fa-chevron-down';
                            if (ch) {
                                ch.style.display = 'block';
                                // 注意：dataset.loaded 返回字符串，必须显式比较
                                if (ch.dataset.loaded !== 'true') {
                                    loadDetailChildTasks(nodeId, ch, h);
                                }
                            }
                        } else {
                            if (ic) ic.className = 'fas fa-chevron-right';
                            if (ch) ch.style.display = 'none';
                        }
                    });
                });
            } else {
                childrenContainer.innerHTML = '<div style="padding:8px 16px; color:#888; font-size:12px;">暂无子任务</div>';
            }
        } else {
            childrenContainer.innerHTML = '<div style="padding:8px 16px; color:#ff4444; font-size:12px;">加载子任务失败</div>';
        }
    } catch (e) {
        console.error('加载子任务出错:', e);
        childrenContainer.innerHTML = '<div style="padding:8px 16px; color:#ff4444; font-size:12px;">加载子任务出错</div>';
    } finally {
        if (icon) {
            icon.className = originalIcon || 'fas fa-chevron-right';
        }
    }
}

function renderFindings(findings) {
    const container = document.getElementById('findings-list');
    container.innerHTML = findings.map(finding => `
        <div class="finding-card severity-${finding.severity.toLowerCase()}">
            <div class="finding-header">
                <h3>${finding.title}</h3>
                <span class="severity-tag">${getSeverityText(finding.severity)}</span>
            </div>
            <div class="finding-detail"><strong>类型:</strong> ${getFindingTypeText(finding.findingType)}</div>
            <div class="finding-detail"><strong>位置:</strong> ${finding.filePath || '-'}:${finding.lineNumber || '-'}</div>
            <div class="finding-detail"><strong>描述:</strong> ${finding.description || '-'}</div>
            ${finding.codeSnippet ? `<div class="finding-code">${escapeHtml(finding.codeSnippet)}</div>` : ''}
            ${finding.exploitationPath ? `<div class="finding-detail"><strong>利用路径:</strong> ${finding.exploitationPath}</div>` : ''}
            ${finding.suggestion ? `<div class="finding-suggestion"><strong>修复建议:</strong> ${finding.suggestion}</div>` : ''}
        </div>
    `).join('');
}

function getSeverityText(severity) {
    const map = {
        CRITICAL: '严重',
        HIGH: '高危',
        MEDIUM: '中危',
        LOW: '低危',
        INFO: '信息'
    };
    return map[severity] || severity;
}

function getFindingTypeText(type) {
    const map = {
        SQL_INJECTION: 'SQL注入',
        XSS: '跨站脚本',
        COMMAND_INJECTION: '命令注入',
        PATH_TRAVERSAL: '路径遍历',
        SSRF: '服务端请求伪造',
        FILE_UPLOAD: '文件上传',
        DESERIALIZATION: '反序列化',
        INSECURE_DEPENDENCY: '不安全依赖',
        AUTH_BYPASS: '认证绕过',
        CSRF: '跨站请求伪造',
        OPEN_REDIRECT: '开放重定向',
        INFORMATION_DISCLOSURE: '信息泄露',
        RACE_CONDITION: '竞争条件'
    };
    return map[type] || type;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * 解析多轮对话内容，提取每一轮的请求或回复
 * @param {string} content - 完整的对话内容
 * @param {string} prefix - 前缀标记（如 '大模型回复' 或 '思考过程'）
 * @returns {Array} - 解析后的段落数组 [{round, title, content}]
 */
function parseConversationSections(content, prefix) {
    const sections = [];
    if (!content) return sections;

    const esc = prefix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    // 匹配格式：字符串开头或空行后的 prefix+数字+换行+内容，内容到下一个标记或字符串末尾
    // 不使用 m 标志：$ 仅匹配字符串末尾，避免内容在首个换行处被截断
    const regex = new RegExp(`(?:^|\\n\\n)${esc}(\\d+)\\s*\\n([\\s\\S]*?)(?=\\n\\n${esc}\\d+|$)`, 'g');
    let match;

    while ((match = regex.exec(content)) !== null) {
        sections.push({
            round: parseInt(match[1], 10),
            title: `${prefix}${match[1]}`,
            content: match[2].trim()
        });
    }

    // 如果没有匹配到，尝试用简单的换行分割
    if (sections.length === 0) {
        sections.push({
            round: 1,
            title: `${prefix}1`,
            content: content.trim()
        });
    }

    return sections;
}

/**
 * 解析JSON格式的多轮对话历史
 * 格式：第N轮对话\n[...json...]
 * @returns {Array} - 解析后的段落数组 [{round, title, content}]
 */
function parseJsonConversationHistory(content) {
    const sections = [];
    if (!content) return sections;

    // 匹配格式：字符串开头或空行后的 第N轮对话 + 换行 + JSON数组
    const regex = /(?:^|\n\n)第(\d+)轮对话\s*\n([\s\S]*?)(?=\n\n第\d+轮对话|$)/g;
    let match;

    while ((match = regex.exec(content)) !== null) {
        sections.push({
            round: parseInt(match[1], 10),
            title: `第${match[1]}轮对话`,
            content: match[2].trim()
        });
    }

    // 如果没有匹配到，尝试直接解析整个内容为JSON
    if (sections.length === 0) {
        sections.push({
            round: 1,
            title: `第1轮对话`,
            content: content.trim()
        });
    }

    return sections;
}

/**
 * 渲染单个任务的对话记录：按轮次组合显示 大模型输入(JSON) 与 大模型输出
 * 按轮次号配对，避免按下标配对导致的内容错位
 * @param {Object} entry - {taskName, taskType, llmRequest, llmResponse}
 * @returns {string} - HTML
 */
function renderChatEntryRounds(entry) {
    const requestSections = entry.llmRequest ? parseJsonConversationHistory(entry.llmRequest) : [];
    const responseSections = entry.llmResponse ? parseConversationSections(entry.llmResponse, '大模型回复') : [];

    // 按轮次号建立映射
    const reqMap = {};
    requestSections.forEach(s => { reqMap[s.round] = s; });
    const respMap = {};
    responseSections.forEach(s => { respMap[s.round] = s; });

    // 合并所有出现过的轮次号并排序
    const roundNums = [...new Set([
        ...Object.keys(reqMap).map(Number),
        ...Object.keys(respMap).map(Number)
    ])].sort((a, b) => a - b);

    const roundParts = [];
    for (const n of roundNums) {
        const parts = [];

        // 大模型输入：该轮发送给大模型的完整消息历史(JSON)
        if (reqMap[n]) {
            parts.push(`
                <div style="margin:5px 0;">
                    <h5 style="color:#60a5fa;margin:0 0 5px 0;font-size:13px;">大模型输入</h5>
                    <pre style="margin:0;background:#1a1a2e;border:1px solid #444;border-radius:4px;padding:10px;white-space:pre-wrap;word-break:break-all;font-size:12px;line-height:1.5;color:#9cdcfe;">${escapeHtml(reqMap[n].content)}</pre>
                </div>
            `);
        }

        // 大模型输出：该轮大模型的回复内容
        if (respMap[n]) {
            parts.push(`
                <div style="margin:10px 0 5px 0;">
                    <h5 style="color:#10b981;margin:0 0 5px 0;font-size:13px;">大模型输出</h5>
                    <pre style="margin:0;background:#0f2a1a;border:1px solid #10b981;border-radius:4px;padding:10px;white-space:pre-wrap;word-break:break-all;font-size:12px;line-height:1.5;color:#6ee7b7;">${escapeHtml(respMap[n].content)}</pre>
                </div>
            `);
        } else {
            parts.push(`
                <div style="margin:10px 0 5px 0;">
                    <h5 style="color:#10b981;margin:0 0 5px 0;font-size:13px;">大模型输出</h5>
                    <pre style="margin:0;background:#0f2a1a;border:1px dashed #10b981;border-radius:4px;padding:10px;font-size:12px;color:#6ee7b7;">等待大模型响应...</pre>
                </div>
            `);
        }

        roundParts.push(`
            <div style="margin:10px 0;padding:8px;border:1px solid #444;border-radius:4px;background:#16213e;">
                <h5 style="color:#e5e7eb;margin:0 0 8px 0;font-size:13px;">第${n}轮对话</h5>
                ${parts.join('')}
            </div>
        `);
    }

    return roundParts.join('');
}

async function loadLogs(projectId) {
    const response = await fetch(`${API_BASE}/projects/${projectId}/logs`);
    if (response.ok) {
        const logs = await response.json();
        const container = document.getElementById('logs-content');
        container.innerHTML = renderLogsAsText(logs);
        container.scrollTop = container.scrollHeight;
    }
}

function renderProjectLogs(logs) {
    const container = document.getElementById('logs-content');
    container.innerHTML = renderLogsAsText(logs);
    container.scrollTop = container.scrollHeight;
}

function renderLogsAsText(logs) {
    if (!logs || logs.length === 0) {
        return '<p class="waiting-message">暂无执行日志</p>';
    }
    return logs.map(log => {
        const taskTag = log.taskName ? `<span class="log-task-tag">[${escapeHtml(log.taskName)}]</span> ` : '';
        const time = formatDate(log.createdAt);
        const msg = escapeHtml(log.message || '');
        return `<div class="log-entry log-${(log.logType || 'info').toLowerCase()}">${taskTag}<span>${time}</span> - <pre class="log-message-text">${msg}</pre></div>`;
    }).join('');
}

function initProjectDetail() {
    document.getElementById('back-btn').addEventListener('click', () => {
        currentProjectId = null;
        showPage('projects');
        document.querySelector('.main-content').style.marginLeft = '280px';
        document.querySelector('.sidebar').style.display = 'flex';
        loadProjects();
    });
    
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            // 任务详情弹窗的选项卡由其专属处理器处理，避免副作用（如触发日志流）
            if (btn.closest('#task-detail-modal')) return;
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
            document.querySelectorAll('.tab-pane').forEach(p => p.classList.add('hidden'));

            e.target.classList.add('active');
            const tab = e.target.dataset.tab;
            document.getElementById(`${tab}-tab`).classList.remove('hidden');

            if (tab === 'logs' && currentProjectId) {
                startLogStreaming(currentProjectId);
            } else {
                stopLogStreaming();
            }
        });
    });
    
    document.getElementById('start-scan-btn').addEventListener('click', () => {
        showModelSelectModal(currentProjectId);
    });

    document.getElementById('cancel-scan-btn').addEventListener('click', () => {
        cancelScan(currentProjectId);
    });
    
    document.getElementById('edit-project-btn').addEventListener('click', () => {
        editProject(currentProjectId);
    });
    
    document.getElementById('delete-project-btn').addEventListener('click', () => {
        deleteProject(currentProjectId);
    });
    
    document.getElementById('clear-logs-btn').addEventListener('click', () => {
        document.getElementById('logs-content').innerHTML = '';
    });
    
    document.getElementById('export-report-btn').addEventListener('click', () => {
        exportReport(currentProjectId, 'md');
    });

    document.getElementById('export-pdf-btn').addEventListener('click', () => {
        exportReport(currentProjectId, 'pdf');
    });
    
    document.getElementById('severity-filter').addEventListener('change', async (e) => {
        const severity = e.target.value;
        const response = await fetch(`${API_BASE}/projects/${currentProjectId}/findings`);
        if (response.ok) {
            const findings = await response.json();
            const filtered = severity === 'all' ? findings : findings.filter(f => f.severity === severity);
            renderFilteredFindings(filtered);
        }
    });
}

function renderFilteredFindings(findings) {
    const container = document.getElementById('findings-list');
    container.innerHTML = findings.map(finding => `
        <div class="finding-card severity-${finding.severity.toLowerCase()}">
            <div class="finding-header">
                <h3>${finding.title}</h3>
                <span class="severity-tag">${getSeverityText(finding.severity)}</span>
            </div>
            <div class="finding-detail"><strong>类型:</strong> ${getFindingTypeText(finding.findingType)}</div>
            <div class="finding-detail"><strong>位置:</strong> ${finding.filePath || '-'}:${finding.lineNumber || '-'}</div>
            <div class="finding-detail"><strong>描述:</strong> ${finding.description || '-'}</div>
            ${finding.codeSnippet ? `<div class="finding-code">${escapeHtml(finding.codeSnippet)}</div>` : ''}
            ${finding.exploitationPath ? `<div class="finding-detail"><strong>利用路径:</strong> ${finding.exploitationPath}</div>` : ''}
            ${finding.suggestion ? `<div class="finding-suggestion"><strong>修复建议:</strong> ${finding.suggestion}</div>` : ''}
        </div>
    `).join('');
}

function startScan(projectId, llmConfigId) {
    const btn = document.getElementById('start-scan-btn');
    const cancelBtn = document.getElementById('cancel-scan-btn');
    btn.disabled = true;
    btn.style.display = 'none';
    cancelBtn.style.display = 'inline-block';
    btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 扫描中...';
    
    const url = llmConfigId 
        ? `${API_BASE}/projects/${projectId}/start-scan?llmConfigId=${llmConfigId}`
        : `${API_BASE}/projects/${projectId}/start-scan`;
    
    const eventSource = new EventSource(url);
    let connectionError = false;
    
    eventSource.addEventListener('thinking', (e) => {
        addLog('THOUGHT', e.data);
    });
    
    eventSource.addEventListener('status', (e) => {
        addLog('INFO', e.data);
    });
    
    eventSource.addEventListener('tasks_created', (e) => {
        addLog('INFO', e.data);
        loadProjectDetail(projectId);
    });
    
    eventSource.addEventListener('complete', (e) => {
        eventSource.close();
        btn.disabled = false;
        btn.style.display = 'inline-block';
        btn.innerHTML = '<i class="fas fa-play"></i> 开始扫描';
        cancelBtn.style.display = 'none';
        showToast('扫描完成');
        loadProjectDetail(projectId);
    });
    
    eventSource.addEventListener('error', (e) => {
        if (!connectionError) {
            connectionError = true;
            eventSource.close();
            btn.disabled = false;
            btn.style.display = 'inline-block';
            btn.innerHTML = '<i class="fas fa-play"></i> 开始扫描';
            cancelBtn.style.display = 'none';
            showToast('扫描启动失败，请检查源码包和LLM配置');
            loadProjectDetail(projectId);
        }
    });

    // 轻量级定时刷新：只更新进度和状态，不重新加载日志
    const refreshInterval = setInterval(() => {
        if (eventSource.readyState === EventSource.OPEN) {
            updateScanProgress(projectId);
        } else {
            clearInterval(refreshInterval);
        }
    }, 8000);

    window._scanRefreshIntervals = window._scanRefreshIntervals || [];
    window._scanRefreshIntervals.push(refreshInterval);
}

// 轻量级进度更新，只更新进度条和状态，不重渲染任务树和日志
async function updateScanProgress(projectId) {
    try {
        const response = await fetch(`${API_BASE}/projects/${projectId}`);
        if (!response.ok) return;
        const project = await response.json();
        
        const progress = project.totalTasks > 0 ? (project.completedTasks / project.totalTasks) * 100 : 0;
        const progressBar = document.getElementById('detail-progress');
        const progressText = document.getElementById('detail-progress-text');
        if (progressBar) progressBar.style.width = `${progress}%`;
        if (progressText) progressText.textContent = `${Math.round(progress)}%`;
        
        const findingsEl = document.getElementById('detail-findings');
        if (findingsEl) findingsEl.textContent = project.findingCount || 0;
        
        const statusBadge = document.getElementById('detail-status');
        if (statusBadge) {
            statusBadge.textContent = getStatusText(project.status);
            statusBadge.className = `status-badge status-${project.status.toLowerCase()}`;
        }
        
        // 如果扫描完成或取消，刷新完整详情
        if (project.status === 'COMPLETED' || project.status === 'FAILED' || project.status === 'CANCELLED') {
            loadProjectDetail(projectId);
        }
    } catch (e) {
        // 静默失败
    }
}

async function cancelScan(projectId) {
    if (!confirm('确定要终止此项目的所有扫描任务吗？')) return;
    
    // 立即更新UI状态，提供即时反馈
    showToast('正在终止扫描...');
    
    // 先更新按钮状态
    const btn = document.getElementById('start-scan-btn');
    const cancelBtn = document.getElementById('cancel-scan-btn');
    btn.disabled = false;
    btn.style.display = 'inline-block';
    btn.innerHTML = '<i class="fas fa-play"></i> 开始扫描';
    cancelBtn.style.display = 'none';
    
    // 更新状态显示
    const statusBadge = document.getElementById('detail-status');
    if (statusBadge) {
        statusBadge.textContent = '已取消';
        statusBadge.className = 'status-badge status-cancelled';
    }
    
    // 清除所有扫描刷新定时器
    if (window._scanRefreshIntervals) {
        window._scanRefreshIntervals.forEach(interval => clearInterval(interval));
        window._scanRefreshIntervals = [];
    }
    
    try {
        const response = await fetch(`${API_BASE}/projects/${projectId}/cancel-scan`);
        if (response.ok) {
            showToast('扫描已终止');
            // 异步刷新完整数据
            setTimeout(() => loadProjectDetail(projectId), 300);
        } else {
            showToast('终止扫描失败');
            // 恢复按钮状态
            btn.disabled = false;
            btn.style.display = 'inline-block';
            btn.innerHTML = '<i class="fas fa-play"></i> 开始扫描';
            cancelBtn.style.display = 'none';
        }
    } catch (e) {
        showToast('终止扫描出错');
    }
}

let pendingScanProjectId = null;

function initModelSelectModal() {
    const modal = document.getElementById('model-select-modal');
    
    document.getElementById('model-select-close').addEventListener('click', () => {
        modal.classList.add('hidden');
        pendingScanProjectId = null;
    });
    
    document.getElementById('model-select-cancel').addEventListener('click', () => {
        modal.classList.add('hidden');
        pendingScanProjectId = null;
    });
    
    document.getElementById('model-select-confirm').addEventListener('click', () => {
        const llmConfigId = document.getElementById('model-select-dropdown').value;
        if (!llmConfigId) {
            alert('请选择一个大模型');
            return;
        }
        modal.classList.add('hidden');
        if (pendingScanProjectId) {
            startScan(pendingScanProjectId, parseInt(llmConfigId));
        }
        pendingScanProjectId = null;
    });
}

async function showModelSelectModal(projectId) {
    pendingScanProjectId = projectId;
    const modal = document.getElementById('model-select-modal');
    const dropdown = document.getElementById('model-select-dropdown');
    
    try {
        const response = await fetch(`${API_BASE}/llm-config/enabled`);
        if (response.ok) {
            const configs = await response.json();
            if (!configs.length) {
                dropdown.innerHTML = '<option value="">无可用模型，请先配置并启用模型</option>';
            } else {
                dropdown.innerHTML = configs.map(c => 
                    `<option value="${c.id}">${escapeHtml(c.configName || getProviderName(c.providerName))} (${getProviderName(c.providerName)}) - ${c.model}${c.isDefault ? ' (默认)' : ''}</option>`
                ).join('');
            }
        } else {
            dropdown.innerHTML = '<option value="">加载失败</option>';
        }
    } catch (err) {
        dropdown.innerHTML = '<option value="">加载失败</option>';
    }
    
    modal.classList.remove('hidden');
}

function addLog(type, message) {
    const container = document.getElementById('logs-content');
    const div = document.createElement('div');
    div.className = `log-entry log-${type.toLowerCase()}`;
    div.innerHTML = `<span>${formatDate(new Date())}</span> - ${escapeHtml(message)}`;
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
}

function startLogStreaming(projectId) {
    if (window._logStreamTimer) {
        clearInterval(window._logStreamTimer);
    }
    
    const container = document.getElementById('logs-content');
    container.innerHTML = '<div class="log-entry log-info"><span>' + formatDate(new Date()) + '</span> - 实时日志监听中...</div>';
    
    window._logStreamProjectId = projectId;
    window._logStreamTimer = setInterval(async () => {
        try {
            const response = await fetch(`${API_BASE}/projects/${projectId}/logs`);
            if (response.ok) {
                const logs = await response.json();
                renderProjectLogs(logs);
            }
        } catch (e) {
            console.warn('日志刷新失败:', e);
        }
    }, 2000);
}

function stopLogStreaming() {
    if (window._logStreamTimer) {
        clearInterval(window._logStreamTimer);
        window._logStreamTimer = null;
    }
}

// 渲染项目日志 - 逐条显示
function renderProjectLogs(logs) {
    const container = document.getElementById('logs-content');
    container.innerHTML = renderLogsAsText(logs);
    container.scrollTop = container.scrollHeight;
}

async function editProject(id) {
    const response = await fetch(`${API_BASE}/projects/${id}`);
    if (response.ok) {
        const project = await response.json();
        currentModalType = 'edit';
        currentProjectId = id;
        
        document.getElementById('modal-title').textContent = '编辑项目';
        document.getElementById('form-name').value = project.projectName;
        document.getElementById('form-description').value = project.projectDescription || '';

        const fileInput = document.getElementById('form-file');
        if (fileInput) {
            fileInput.value = '';
        }

        const codeStatusDiv = document.getElementById('code-status');
        if (codeStatusDiv) {
            codeStatusDiv.classList.remove('hidden');
            if (project.codePath) {
                const fileName = project.codePath.split(/[/\\]/).pop();
                codeStatusDiv.innerHTML = `
                    <div class="code-status-info">
                        <i class="fas fa-folder-open"></i>
                        <span>已上传源码: ${fileName}</span>
                        <span class="status-tag">ZIP文件</span>
                    </div>
                    <p class="file-hint">上传新的ZIP文件将自动替换现有源码</p>
                `;
            } else {
                codeStatusDiv.classList.add('hidden');
            }
        }
        
        document.getElementById('modal-submit').onclick = () => {
            updateProject(id);
        };
        
        document.getElementById('modal-overlay').classList.remove('hidden');
    }
}

async function updateProject(id) {
    const name = document.getElementById('form-name').value;
    const description = document.getElementById('form-description').value;
    const fileInput = document.getElementById('form-file');
    const file = fileInput && fileInput.files.length > 0 ? fileInput.files[0] : null;

    if (!name) {
        alert('请填写项目名称');
        return;
    }

    // 不发送 projectCode，避免覆盖已存储的源码
    const updateBody = JSON.stringify({ projectName: name, projectDescription: description });

    if (file) {
        const formData = new FormData();
        formData.append('file', file);

        const response = await fetch(`${API_BASE}/projects/${id}/upload`, {
            method: 'PUT',
            body: formData
        });

        if (response.ok) {
            const updateResponse = await fetch(`${API_BASE}/projects/${id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: updateBody
            });

            if (updateResponse.ok) {
                document.getElementById('modal-overlay').classList.add('hidden');
                document.getElementById('modal-submit').onclick = submitProjectForm;
                loadProjectDetail(id);
                showToast('项目更新成功，源码已替换');
            } else {
                alert('更新项目信息失败');
            }
        } else {
            alert('文件替换失败: ' + (await getErrorMessage(response)));
        }
        return;
    }

    const response = await fetch(`${API_BASE}/projects/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: updateBody
    });

    if (response.ok) {
        document.getElementById('modal-overlay').classList.add('hidden');
        document.getElementById('modal-submit').onclick = submitProjectForm;
        loadProjectDetail(id);
        showToast('项目更新成功');
    } else {
        alert('更新失败: ' + (await getErrorMessage(response)));
    }
}

async function getErrorMessage(response) {
    try {
        const error = await response.json();
        return error.error || '未知错误';
    } catch {
        return 'HTTP ' + response.status;
    }
}

async function deleteProject(id) {
    if (!confirm('确定要删除这个项目吗？')) return;
    
    const response = await fetch(`${API_BASE}/projects/${id}`, {
        method: 'DELETE'
    });
    
    if (response.ok) {
        showToast('项目删除成功');
        if (currentProjectId === parseInt(id)) {
            currentProjectId = null;
            showPage('projects');
            document.querySelector('.main-content').style.marginLeft = '280px';
            document.querySelector('.sidebar').style.display = 'flex';
        }
        loadProjects();
    } else {
        const error = await response.json();
        alert('删除失败: ' + (error.error || '未知错误'));
    }
}

async function exportReport(projectId, format = 'md') {
    const response = await fetch(`${API_BASE}/projects/${projectId}/report/export?format=${format}`);
    if (!response.ok) {
        let msg = '导出失败';
        try {
            const err = await response.json();
            msg = err.message || msg;
        } catch (e) { /* ignore */ }
        alert(msg);
        return;
    }
    const blob = await response.blob();
    const projectName = document.getElementById('project-detail-title').textContent.trim() || `project_${projectId}`;
    const ext = format === 'pdf' ? 'pdf' : 'md';
    const mime = ext === 'pdf' ? 'application/pdf' : 'text/markdown;charset=utf-8';

    const url = URL.createObjectURL(new Blob([blob], { type: mime }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `${projectName}_审计报告.${ext}`;
    a.click();
    URL.revokeObjectURL(url);
    showToast(ext === 'pdf' ? 'PDF 报告导出成功' : 'Markdown 报告导出成功');
}

let lastReportContent = '';
let reportAutoRefreshTimer = null;
let currentDetailScanning = false;
let pendingFinalReportRefresh = false;

async function loadProjectReport(projectId, silent = false) {
    const container = document.getElementById('report-content');
    if (!silent) {
        container.innerHTML = '<p style="text-align:center; color:#00aa29; padding:20px;">报告加载中...</p>';
        lastReportContent = '';
    }
    try {
        const response = await fetch(`${API_BASE}/projects/${projectId}/report`);
        if (!response.ok) throw new Error('加载失败');
        const data = await response.json();
        const report = (data.reportContent || '').trim();
        if (!report) {
            if (!silent) {
                container.innerHTML = '<p style="text-align:center; color:#00aa29; padding:20px;">暂无审计报告，请先完成扫描</p>';
            }
            return;
        }
        // 内容无变化时不重绘，避免自动刷新打断阅读/滚动
        if (report === lastReportContent) return;
        lastReportContent = report;
        container.innerHTML = renderSimpleMarkdown(report);
    } catch (err) {
        if (!silent) {
            container.innerHTML = '<p style="text-align:center; color:#ff4444; padding:20px;">报告加载失败</p>';
        }
    }
}

/**
 * 报告预览自动刷新：扫描进行中每 10 秒静默刷新一次（内容有变化才重绘）；
 * 扫描结束后再做一次最终刷新，离开详情页自动停止
 */
function startReportAutoRefresh(projectId) {
    stopReportAutoRefresh();
    pendingFinalReportRefresh = currentDetailScanning;
    reportAutoRefreshTimer = setInterval(() => {
        const section = document.getElementById('project-detail-section');
        if (!section || section.classList.contains('hidden') || currentProjectId !== projectId) {
            stopReportAutoRefresh();
            return;
        }
        if (!currentDetailScanning) {
            if (pendingFinalReportRefresh) {
                pendingFinalReportRefresh = false;
                loadProjectReport(projectId, true);
            }
            return;
        }
        loadProjectReport(projectId, true);
    }, 10000);
}

function stopReportAutoRefresh() {
    if (reportAutoRefreshTimer) {
        clearInterval(reportAutoRefreshTimer);
        reportAutoRefreshTimer = null;
    }
}

function renderSimpleMarkdown(md) {
    const inline = (text) => text
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/`([^`]+)`/g, '<code>$1</code>');
    const lines = md.split('\n');
    let html = '';
    let inList = false;
    let inCode = false;
    for (const line of lines) {
        const t = line.trim();
        // Markdown 代码块围栏
        if (/^```/.test(t)) {
            if (inList) { html += '</ul>'; inList = false; }
            if (inCode) {
                html += '</pre>';
            } else {
                html += '<pre style="background:#f4f6f8; border:1px solid #e0e4e8; border-radius:6px; padding:10px 12px; font-size:12px; line-height:1.5; overflow-x:auto; white-space:pre-wrap; word-break:break-all; margin:8px 0;">';
            }
            inCode = !inCode;
            continue;
        }
        if (inCode) {
            html += escapeHtml(line) + '\n';
            continue;
        }
        if (/^- /.test(t)) {
            if (!inList) { html += '<ul>'; inList = true; }
            html += `<li>${inline(t.slice(2))}</li>`;
            continue;
        }
        if (inList) { html += '</ul>'; inList = false; }
        if (/^-{3,}$/.test(t)) {
            html += '<hr style="border:none; border-top:1px solid #d8dce2; margin:14px 0;">';
        } else if (/^### /.test(t)) {
            html += `<h4>${inline(t.slice(4))}</h4>`;
        } else if (/^## /.test(t)) {
            html += `<h3>${inline(t.slice(3))}</h3>`;
        } else if (/^# /.test(t)) {
            html += `<h2 style="text-align:center;">${inline(t.slice(2))}</h2>`;
        } else if (t !== '') {
            html += `<p>${inline(t)}</p>`;
        }
    }
    if (inList) html += '</ul>';
    if (inCode) html += '</pre>';
    return html;
}

function initLlmConfig() {
    loadLlmConfigs();
}

async function loadLlmConfigs() {
    const response = await fetch(`${API_BASE}/llm-config`);
    if (response.ok) {
        const configs = await response.json();
        const grid = document.getElementById('config-grid');
        
        grid.innerHTML = configs.map(config => `
            <div class="config-card">
                <div class="config-header">
                    <h3><i class="fas fa-cpu"></i> ${escapeHtml(config.configName || getProviderName(config.providerName))}</h3>
                    ${config.isDefault ? '<span class="default-badge">默认</span>' : ''}
                </div>
                <div class="config-body">
                    <span><strong>厂商:</strong> ${getProviderName(config.providerName)}</span>
                    <span><strong>模型:</strong> ${config.model}</span>
                    <span><strong>URL:</strong> ${config.baseUrl}</span>
                    <span><strong>API Key:</strong> ${config.apiKey || '未设置'}</span>
                    <span><strong>状态:</strong> ${config.enabled ? '<span style="color: #22c55e;">启用</span>' : '<span style="color: #64748b;">禁用</span>'}</span>
                </div>
                <div class="config-footer">
                    <button class="btn btn-sm btn-secondary test-conn-btn" data-id="${config.id}">
                        <i class="fas fa-plug"></i> 测试连接
                    </button>
                    <button class="btn btn-sm btn-secondary edit-llm-btn" data-id="${config.id}">编辑</button>
                    ${!config.isDefault ? `<button class="btn btn-sm btn-primary set-default-btn" data-id="${config.id}">设为默认</button>` : ''}
                    <button class="btn btn-sm btn-danger delete-llm-btn" data-id="${config.id}">删除</button>
                </div>
            </div>
        `).join('');
        
        document.querySelectorAll('.test-conn-btn').forEach(btn => {
            btn.addEventListener('click', (e) => testLlmConnection(e.currentTarget.dataset.id, e.currentTarget));
        });
        
        document.querySelectorAll('.edit-llm-btn').forEach(btn => {
            btn.addEventListener('click', (e) => editLlmConfig(e.target.dataset.id));
        });
        
        document.querySelectorAll('.set-default-btn').forEach(btn => {
            btn.addEventListener('click', (e) => setDefaultLlm(e.target.dataset.id));
        });
        
        document.querySelectorAll('.delete-llm-btn').forEach(btn => {
            btn.addEventListener('click', (e) => deleteLlmConfig(e.target.dataset.id));
        });
    }
}

function getProviderName(name) {
    const map = {
        openai: 'OpenAI',
        gemini: 'Google Gemini',
        qwen: '阿里云 Qwen',
        doubao: '火山引擎 Doubao',
        zhipu: '智谱 AI',
        moonshot: '月之暗面',
        baidu: '百度文心一言',
        deepseek: '深度求索',
        anthropic: 'Anthropic Claude',
        mistral: 'Mistral AI',
        perplexity: 'Perplexity AI',
        groq: 'Groq',
        ollama: 'Ollama'
    };
    return map[name] || name;
}

async function editLlmConfig(id) {
    const response = await fetch(`${API_BASE}/llm-config/${id}`);
    if (response.ok) {
        const config = await response.json();
        
        document.getElementById('llm-modal-title').textContent = '编辑大模型配置';
        document.getElementById('llm-form-id').value = config.id;
        document.getElementById('llm-config-name').value = config.configName || '';
        document.getElementById('llm-provider').value = config.providerName;
        document.getElementById('llm-base-url').value = config.baseUrl;
        document.getElementById('llm-api-key').value = config.apiKey;
        document.getElementById('llm-model').value = config.model;
        document.getElementById('llm-thinking').checked = config.thinkingEnabled === true;
        document.getElementById('llm-enabled').checked = config.enabled;
        document.getElementById('llm-default').checked = config.isDefault;
        
        document.getElementById('llm-modal-overlay').classList.remove('hidden');
    }
}

async function setDefaultLlm(id) {
    const response = await fetch(`${API_BASE}/llm-config/${id}/set-default`, {
        method: 'POST'
    });
    
    if (response.ok) {
        loadLlmConfigs();
        showToast('已设置为默认配置');
    } else {
        const error = await response.json();
        alert('设置失败: ' + (error.error || '未知错误'));
    }
}

async function deleteLlmConfig(id) {
    if (!confirm('确定要删除这个配置吗？')) return;
    
    const response = await fetch(`${API_BASE}/llm-config/${id}`, {
        method: 'DELETE'
    });
    
    if (response.ok) {
        loadLlmConfigs();
        showToast('配置已删除');
    } else {
        const error = await response.json();
        alert('删除失败: ' + (error.error || '未知错误'));
    }
}

function initFileUpload() {
    const fileInput = document.getElementById('form-file');
    if (!fileInput) return;
    
    fileInput.addEventListener('change', async (e) => {
        const file = e.target.files[0];
        if (!file) return;
        
        if (!file.name.endsWith('.zip')) {
            alert('请上传ZIP格式文件');
            fileInput.value = '';
            return;
        }
        
        // 编辑模式下不自动上传，由 updateProject 处理
        if (currentModalType === 'edit') {
            return;
        }
        
        const nameInput = document.getElementById('form-name');
        const descInput = document.getElementById('form-description');
        const submitBtn = document.getElementById('modal-submit');
        const originalText = submitBtn.textContent;
        submitBtn.disabled = true;
        submitBtn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 正在上传...';
        
        try {
            const formData = new FormData();
            formData.append('file', file);
            formData.append('projectName', nameInput.value || file.name.replace('.zip', ''));
            formData.append('projectDescription', descInput.value || '');
            
            const response = await fetch(`${API_BASE}/projects/upload`, {
                method: 'POST',
                body: formData
            });
            
            if (response.ok) {
                const project = await response.json();
                showToast('代码上传成功: ' + file.name);
                document.getElementById('modal-overlay').classList.add('hidden');
                loadProjects();
                viewProject(project.id);
            } else {
                const error = await response.text();
                alert('上传失败: ' + error);
            }
        } catch (err) {
            alert('上传出错: ' + err.message);
        } finally {
            submitBtn.disabled = false;
            submitBtn.textContent = originalText;
            fileInput.value = '';
        }
    });
}

function initTaskManagement() {
    loadTasks('all');
    
    document.querySelectorAll('.filter-tab').forEach(tab => {
        tab.addEventListener('click', (e) => {
            document.querySelectorAll('.filter-tab').forEach(t => t.classList.remove('active'));
            e.target.classList.add('active');
            currentTaskFilter = e.target.dataset.filter;
            loadTasks(currentTaskFilter);
        });
    });
}

async function loadTasks(filter = 'all') {
    let url = `${API_BASE}/tasks`;
    if (filter === 'active') url = `${API_BASE}/tasks/active`;
    else if (filter === 'completed') url = `${API_BASE}/tasks/completed`;

    const response = await fetch(url);
    if (response.ok) {
        const tasks = await response.json();
        const container = document.getElementById('task-management-tree');

        if (!tasks.length) {
            container.innerHTML = '<p style="text-align:center; padding:40px; color:#00aa29;">暂无扫描任务</p>';
            return;
        }

        container.innerHTML = tasks.map(task => renderTaskManagementNode(task, 0)).join('');

        // 绑定所有按钮和事件
        bindTaskManagementEvents(container);
    }
}

/**
 * 原位增量刷新：只更新状态/进度发生变化的任务行，不重绘整棵树，
 * 展开状态、滚动位置、正在加载的子任务均不受影响
 */
async function refreshTasksInPlace() {
    const container = document.getElementById('task-management-tree');
    let url = `${API_BASE}/tasks`;
    if (currentTaskFilter === 'active') url = `${API_BASE}/tasks/active`;
    else if (currentTaskFilter === 'completed') url = `${API_BASE}/tasks/completed`;

    let tasks;
    try {
        const response = await fetch(url);
        if (!response.ok) return;
        tasks = await response.json();
    } catch (e) {
        return;
    }

    // 空列表：清空展示（与 loadTasks 行为一致）
    if (!tasks.length) {
        container.innerHTML = '<p style="text-align:center; padding:40px; color:#00aa29;">暂无扫描任务</p>';
        return;
    }

    // 更新根任务行
    const rootRows = new Map();
    container.querySelectorAll(':scope > .task-mgmt-node > .task-tree-toggle').forEach(row => {
        rootRows.set(String(row.dataset.taskId), row);
    });
    const seen = new Set();
    for (const task of tasks) {
        const id = String(task.id);
        seen.add(id);
        const row = rootRows.get(id);
        if (row) {
            patchRootRow(row, task);
        } else {
            container.insertAdjacentHTML('beforeend', renderTaskManagementNode(task, 0));
            const newNode = container.querySelector(`:scope > .task-mgmt-node:last-child`);
            if (newNode) {
                bindRootTaskButtons(newNode);
                bindRootToggleEvents(newNode);
            }
        }
    }
    rootRows.forEach((row, id) => {
        if (!seen.has(id)) {
            const node = row.closest('.task-mgmt-node');
            if (node) node.remove();
        }
    });

    // 递归原位更新已加载的子任务容器
    for (const task of tasks) {
        const row = container.querySelector(`:scope > .task-mgmt-node > .task-tree-toggle[data-task-id="${task.id}"]`);
        if (!row) continue;
        const childrenEl = row.nextElementSibling;
        if (childrenEl && childrenEl.dataset.loaded === 'true') {
            await patchChildrenContainer(task.id, childrenEl);
        }
    }
}

/** 原位更新根任务行的状态/进度/操作按钮 */
function patchRootRow(row, task) {
    const statusCol = row.querySelector('.col-status');
    if (statusCol) {
        const newStatus = `<span class="status-badge status-${task.status.toLowerCase()}">${getStatusText(task.status)}</span>`;
        if (statusCol.innerHTML !== newStatus) statusCol.innerHTML = newStatus;
    }
    const progCol = row.querySelector('.col-progress');
    if (progCol) {
        const newProg = `<div class="progress-bar" style="height: 6px;"><div class="progress-fill" style="width: ${taskProgressPct(task)}%"></div></div>${taskProgressText(task)}`;
        if (progCol.innerHTML !== newProg) progCol.innerHTML = newProg;
    }
    const actCol = row.querySelector('.col-actions');
    if (actCol) {
        const newActions = buildRootActionsHtml(task, parseInt(row.closest('.task-mgmt-node')?.dataset.depth || '0', 10)).trim();
        if (actCol.innerHTML.trim() !== newActions) {
            actCol.innerHTML = newActions;
            bindRootTaskButtons(actCol);
        }
    }
}

/** 递归原位更新已加载的子任务容器 */
async function patchChildrenContainer(parentId, containerEl) {
    let children;
    try {
        const response = await fetch(`${API_BASE}/tasks/${parentId}/children`);
        if (!response.ok) return;
        children = await response.json();
    } catch (e) {
        return;
    }

    const childRows = new Map();
    containerEl.querySelectorAll(':scope > .task-node > .task-child-toggle').forEach(row => {
        childRows.set(String(row.dataset.taskId), row);
    });

    const seen = new Set();
    for (const task of children) {
        const id = String(task.id);
        seen.add(id);
        const row = childRows.get(id);
        if (row) {
            patchChildRow(row, task);
        } else {
            containerEl.insertAdjacentHTML('beforeend', renderTaskChildNode(task));
            const newNode = containerEl.querySelector(':scope > .task-node:last-child');
            if (newNode) {
                bindChildToggleEvents(newNode);
                bindTaskChildActions(newNode);
            }
        }
    }
    childRows.forEach((row, id) => {
        if (!seen.has(id)) {
            const node = row.closest('.task-node');
            if (node) node.remove();
        }
    });

    // 继续深入已加载的下一层
    for (const task of children) {
        const row = containerEl.querySelector(`:scope > .task-node > .task-child-toggle[data-task-id="${task.id}"]`);
        if (!row) continue;
        const nested = row.nextElementSibling;
        if (nested && nested.classList.contains('task-children') && nested.dataset.loaded === 'true') {
            await patchChildrenContainer(task.id, nested);
        }
    }
}

/** 原位更新子任务行的进度/状态/操作按钮 */
function patchChildRow(row, task) {
    const progSpan = row.querySelector('.task-progress');
    if (progSpan && progSpan.textContent !== taskProgressText(task)) {
        progSpan.textContent = taskProgressText(task);
    }
    const statusSpan = row.querySelector('.task-status');
    if (statusSpan) {
        const newStatus = getStatusText(task.status);
        if (statusSpan.textContent !== newStatus) {
            statusSpan.textContent = newStatus;
            statusSpan.className = `task-status status-${task.status.toLowerCase()}`;
        }
    }
    const actionsSpan = row.querySelector('span[style*="margin-left: auto"]');
    if (actionsSpan) {
        const newActions = buildChildActionsHtml(task).trim();
        if (actionsSpan.innerHTML.trim() !== newActions) {
            actionsSpan.innerHTML = newActions;
        }
    }
}

/** 任务管理页自动刷新（扫描进行时保持状态/进度最新） */
let tasksAutoRefreshTimer = null;

function startTasksAutoRefresh() {
    stopTasksAutoRefresh();
    tasksAutoRefreshTimer = setInterval(() => {
        const active = document.querySelector('.nav-item.active');
        if (!active || active.dataset.page !== 'tasks') {
            stopTasksAutoRefresh();
            return;
        }
        refreshTasksInPlace();
    }, 5000);
}

function stopTasksAutoRefresh() {
    if (tasksAutoRefreshTimer) {
        clearInterval(tasksAutoRefreshTimer);
        tasksAutoRefreshTimer = null;
    }
}

/** 绑定根任务行操作按钮事件（整个容器或单个行均可作为 scope） */
function bindRootTaskButtons(scope) {
    scope.querySelectorAll('.view-task-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            viewTaskDetail(e.target.dataset.id);
        });
    });
    scope.querySelectorAll('.pause-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            pauseTask(e.target.dataset.id);
        });
    });
    scope.querySelectorAll('.resume-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            resumeTaskWithChildren(e.target.dataset.id);
        });
    });
    scope.querySelectorAll('.batch-pause-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            pauseTaskWithChildren(e.target.dataset.id);
        });
    });
    scope.querySelectorAll('.batch-resume-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            resumeTaskWithChildren(e.target.dataset.id);
        });
    });
    scope.querySelectorAll('.cancel-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            cancelTask(e.target.dataset.id);
        });
    });
    scope.querySelectorAll('.delete-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            deleteTask(e.target.dataset.id);
        });
    });
    scope.querySelectorAll('.restart-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            restartTask(e.target.dataset.id);
        });
    });
}

/** 绑定根任务行展开/收起事件（可针对单个新行或整个容器） */
function bindRootToggleEvents(scope) {
    scope.querySelectorAll('.task-tree-toggle').forEach(header => {
        header.addEventListener('click', (e) => {
            e.stopPropagation();
            e.preventDefault();
            const nodeId = header.dataset.taskId;

            const isExpanded = header.classList.toggle('expanded');
            const icon = header.querySelector('i');
            const children = header.nextElementSibling;

            if (isExpanded) {
                if (icon) icon.className = 'fas fa-chevron-down';
                if (children) {
                    children.style.display = 'block';
                    if (children.dataset.loaded !== 'true') {
                        loadChildTasks(nodeId, children, header);
                    }
                }
            } else {
                if (icon) icon.className = 'fas fa-chevron-right';
                if (children) children.style.display = 'none';
            }
        });
    });
}

function bindTaskManagementEvents(container) {
    bindRootTaskButtons(container);
    bindRootToggleEvents(container);
}

/** 绑定子任务行展开/收起事件（可针对单个新行或整个容器） */
function bindChildToggleEvents(scope) {
    scope.querySelectorAll('.task-child-toggle').forEach(h => {
        h.addEventListener('click', (e) => {
            e.stopPropagation();
            const nodeId = h.dataset.taskId;
            const exp = h.classList.toggle('expanded');
            const ic = h.querySelector('i');
            const ch = h.nextElementSibling;
            if (exp) {
                if (ic) ic.className = 'fas fa-chevron-down';
                if (ch) {
                    ch.style.display = 'block';
                    if (ch.dataset.loaded !== 'true') {
                        loadChildTasks(nodeId, ch, h);
                    }
                }
            } else {
                if (ic) ic.className = 'fas fa-chevron-right';
                if (ch) ch.style.display = 'none';
            }
        });
    });
}

async function loadChildTasks(parentId, childrenContainer, header) {
    const icon = header.querySelector('i');
    const originalIcon = icon ? icon.className : '';
    if (icon) {
        icon.className = 'fas fa-spinner fa-spin';
    }
    
    try {
        const response = await fetch(`${API_BASE}/tasks/${parentId}/children`);
        if (response.ok) {
            const children = await response.json();
            childrenContainer.dataset.loaded = 'true';
            
            if (children.length > 0) {
                // 使用紧凑布局显示子任务，与项目详情页面一致
                childrenContainer.innerHTML = children.map(child => renderTaskChildNode(child)).join('');
                // 绑定新加载子任务的展开事件
                bindChildToggleEvents(childrenContainer);
                // 绑定操作按钮事件
                bindTaskChildActions(childrenContainer);
            } else {
                childrenContainer.innerHTML = '<div style="padding:10px 20px; color:#888; font-size:12px;">暂无子任务</div>';
            }
        } else {
            childrenContainer.innerHTML = '<div style="padding:10px 20px; color:#ff4444; font-size:12px;">加载子任务失败</div>';
        }
    } catch (e) {
        console.error('加载子任务出错:', e);
        childrenContainer.innerHTML = '<div style="padding:10px 20px; color:#ff4444; font-size:12px;">加载子任务出错</div>';
    } finally {
        if (icon) {
            icon.className = originalIcon || 'fas fa-chevron-right';
        }
    }
}

function renderTaskChildNode(task) {
    const typeLabel = getTaskTypeText(task.taskType);

    return `
        <div class="task-node" style="margin-left: 20px;" data-depth="1">
            <div class="task-node-header task-child-toggle" data-task-id="${task.id}">
                <i class="fas fa-chevron-right task-icon"></i>
                <span class="task-name">${escapeHtml(task.taskName)}</span>
                <span class="task-type-tag">${typeLabel}</span>
                <span class="task-progress">${taskProgressText(task)}</span>
                <span class="task-status status-${task.status.toLowerCase()}">${getStatusText(task.status)}</span>
                ${task.llmConfigName ? `<span class="task-llm-model">🤖 ${escapeHtml(task.llmConfigName)}</span>` : ''}
                <span style="margin-left: auto; display: flex; gap: 4px;">
                    ${buildChildActionsHtml(task)}
                </span>
            </div>
            <div class="task-children" style="display: none;" data-loaded="false">
                <div style="padding:8px 16px; color:#888; font-size:12px;">点击展开加载子任务</div>
            </div>
        </div>
    `;
}

/** 子任务行操作按钮 HTML（渲染与原位刷新共用） */
function buildChildActionsHtml(task) {
    // 重启按钮：已完成/失败/取消的任务可以重启
    const restartBtn = (task.status === 'COMPLETED' || task.status === 'FAILED' || task.status === 'CANCELLED')
        ? `<button class="btn btn-sm btn-success restart-child-btn" data-id="${task.id}">重启</button>`
        : '';

    return `
        <button class="btn btn-sm btn-secondary view-child-task-btn" data-id="${task.id}">查看</button>
        ${restartBtn}
        ${task.status === 'RUNNING' ? `<button class="btn btn-sm btn-warning pause-child-btn" data-id="${task.id}">暂停</button>` : ''}
        ${task.status === 'PAUSED' ? `<button class="btn btn-sm btn-primary resume-child-btn" data-id="${task.id}">继续</button>` : ''}
        ${task.status === 'RUNNING' || task.status === 'PAUSED' ? `<button class="btn btn-sm btn-danger cancel-child-btn" data-id="${task.id}">取消</button>` : ''}
        ${task.status !== 'RUNNING' ? `<button class="btn btn-sm btn-danger delete-child-btn" data-id="${task.id}">删除</button>` : ''}
    `;
}

function bindTaskChildActions(container) {
    container.querySelectorAll('.view-child-task-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            viewTaskDetail(parseInt(btn.dataset.id));
        });
    });
    container.querySelectorAll('.pause-child-btn').forEach(btn => {
        btn.addEventListener('click', async (e) => {
            e.stopPropagation();
            await pauseTask(parseInt(btn.dataset.id));
            refreshTasksInPlace();
        });
    });
    container.querySelectorAll('.resume-child-btn').forEach(btn => {
        btn.addEventListener('click', async (e) => {
            e.stopPropagation();
            await resumeTaskWithChildren(parseInt(btn.dataset.id));
            refreshTasksInPlace();
        });
    });
    container.querySelectorAll('.cancel-child-btn').forEach(btn => {
        btn.addEventListener('click', async (e) => {
            e.stopPropagation();
            if (confirm('确定要取消此任务吗？')) {
                await cancelTask(parseInt(btn.dataset.id));
                refreshTasksInPlace();
            }
        });
    });
    container.querySelectorAll('.delete-child-btn').forEach(btn => {
        btn.addEventListener('click', async (e) => {
            e.stopPropagation();
            const taskId = parseInt(btn.dataset.id);
            if (confirm('确定要删除此任务及其所有子任务吗？')) {
                try {
                    const response = await fetch(`${API_BASE}/tasks/${taskId}`, { method: 'DELETE' });
                    if (response.ok) {
                        refreshTasksInPlace();
                    } else {
                        alert('删除失败');
                    }
                } catch (err) {
                    alert('删除出错: ' + err.message);
                }
            }
        });
    });
    container.querySelectorAll('.restart-child-btn').forEach(btn => {
        btn.addEventListener('click', async (e) => {
            e.stopPropagation();
            await restartTask(parseInt(btn.dataset.id));
            refreshTasksInPlace();
        });
    });
}

function renderTaskManagementNode(task, depth = 0) {
    const padding = depth * 24;
    const typeLabel = getTaskTypeText(task.taskType);
    // 子任务不显示项目名，避免重复
    const projectDisplay = depth > 0 ? '-' : escapeHtml(task.projectName || '-');

    const actions = buildRootActionsHtml(task, depth);

    return `
        <div class="task-mgmt-node" style="margin-left: ${padding}px;" data-depth="${depth}">
            <div class="task-mgmt-row task-tree-toggle" data-task-id="${task.id}">
                <div class="task-mgmt-col col-toggle">
                    <i class="fas fa-chevron-right task-toggle-icon"></i>
                </div>
                <div class="task-mgmt-col col-project" title="${escapeHtml(task.projectName || '')}">${projectDisplay}</div>
                <div class="task-mgmt-col col-name" title="${escapeHtml(task.taskName)}">${escapeHtml(task.taskName)}</div>
                <div class="task-mgmt-col col-type" title="${typeLabel}">${typeLabel}</div>
                <div class="task-mgmt-col col-model" title="${escapeHtml(task.llmConfigName || '')}">${escapeHtml(task.llmConfigName || '-')}</div>
                <div class="task-mgmt-col col-status"><span class="status-badge status-${task.status.toLowerCase()}">${getStatusText(task.status)}</span></div>
                <div class="task-mgmt-col col-progress">
                    <div class="progress-bar" style="height: 6px;">
                        <div class="progress-fill" style="width: ${taskProgressPct(task)}%"></div>
                    </div>
                    ${taskProgressText(task)}
                </div>
                <div class="task-mgmt-col col-time">${formatDate(task.startedAt)}</div>
                <div class="task-mgmt-col col-actions">${actions}</div>
            </div>
            <div class="task-mgmt-children" style="display: none;" data-loaded="false">
                <div style="padding:10px 20px; color:#888; font-size:12px;">点击展开加载子任务</div>
            </div>
        </div>
    `;
}

/** 根任务行操作按钮 HTML（渲染与原位刷新共用） */
function buildRootActionsHtml(task, depth = 0) {
    // 父任务（depth=0）显示批量暂停/继续按钮
    const batchPauseBtn = (depth === 0 && (task.status === 'RUNNING' || task.status === 'PENDING'))
        ? `<button class="btn btn-sm btn-warning batch-pause-btn" data-id="${task.id}">全部暂停</button>`
        : '';
    const batchResumeBtn = (depth === 0 && task.status === 'PAUSED')
        ? `<button class="btn btn-sm btn-primary batch-resume-btn" data-id="${task.id}">全部继续</button>`
        : '';

    // 重启按钮：已完成/失败/取消的任务可以重启
    const restartBtn = (task.status === 'COMPLETED' || task.status === 'FAILED' || task.status === 'CANCELLED')
        ? `<button class="btn btn-sm btn-success restart-btn" data-id="${task.id}">重启</button>`
        : '';

    return `
        <button class="btn btn-sm btn-secondary view-task-btn" data-id="${task.id}">查看</button>
        ${restartBtn}
        ${batchPauseBtn}
        ${batchResumeBtn}
        ${depth > 0 && task.status === 'RUNNING' ? `<button class="btn btn-sm btn-warning pause-btn" data-id="${task.id}">暂停</button>` : ''}
        ${depth > 0 && task.status === 'PAUSED' ? `<button class="btn btn-sm btn-primary resume-btn" data-id="${task.id}">继续</button>` : ''}
        ${task.status === 'RUNNING' || task.status === 'PAUSED' ? `<button class="btn btn-sm btn-danger cancel-btn" data-id="${task.id}">取消</button>` : ''}
        ${task.status !== 'RUNNING' ? `<button class="btn btn-sm btn-danger delete-btn" data-id="${task.id}">删除</button>` : ''}
    `;
}

function getTaskTypeText(type) {
    const map = {
        DEPENDENCY_SCAN: '依赖扫描',
        INTERFACE_SCAN: '接口扫描',
        HIGH_RISK_OPERATION_SCAN: '高危操作扫描',
        SUB_DEPENDENCY_SCAN: '依赖子任务',
        SUB_INTERFACE_SCAN: '接口子任务',
        SUB_HIGH_RISK_SCAN: '高危子任务',
        SUB_INTERFACE_VULNERABILITY_SCAN: '漏洞分析'
    };
    return map[type] || type;
}

async function viewTaskDetail(taskId) {
    const response = await fetch(`${API_BASE}/tasks/${taskId}`);
    if (!response.ok) return;
    const task = await response.json();
    
    document.getElementById('task-detail-title').textContent = task.taskName;
    
    // 构建漏洞分析结果显示
    let analysisResultHtml = '';
    if (task.hasVulnerability !== undefined && task.hasVulnerability !== null) {
        const isVul = task.hasVulnerability;
        const resultClass = isVul ? 'danger' : 'success';
        const resultText = isVul ? '发现安全风险' : '接口安全';
        const resultIcon = isVul ? '⚠️' : '✅';
        analysisResultHtml = `
            <div class="info-item" style="margin-top:12px;padding:10px;background:${isVul ? '#2d1f1f' : '#1f2d1f'};border:1px solid ${isVul ? '#dc3545' : '#28a745'};border-radius:6px;">
                <label style="color:${isVul ? '#dc3545' : '#28a745'};">接口分析结果</label>
                <span style="color:${isVul ? '#ff6b6b' : '#51cf66'};font-weight:bold;">${resultIcon} ${resultText}</span>
                ${task.analysisResult ? `<div style="margin-top:8px;color:#ccc;font-size:12px;white-space:pre-wrap;">${escapeHtml(task.analysisResult)}</div>` : ''}
            </div>
        `;
    }
    
    document.getElementById('task-detail-info').innerHTML = `
        <div class="info-grid">
            <div class="info-item"><label>项目</label><span>${escapeHtml(task.projectName || '-')}</span></div>
            <div class="info-item"><label>状态</label><span class="status-badge status-${task.status.toLowerCase()}">${getStatusText(task.status)}</span></div>
            <div class="info-item"><label>进度</label><span>${taskProgressText(task)}</span></div>
            <div class="info-item"><label>类型</label><span>${getTaskTypeText(task.taskType)}</span></div>
            <div class="info-item"><label>使用模型</label><span>${escapeHtml(task.llmConfigName || '-')}</span></div>
            <div class="info-item"><label>开始时间</label><span>${formatDate(task.startedAt)}</span></div>
            <div class="info-item"><label>完成时间</label><span>${formatDate(task.completedAt)}</span></div>
        </div>
        ${analysisResultHtml}
        ${task.taskDescription ? `<div class="info-item" style="margin-top:16px;"><label>任务描述</label><span>${escapeHtml(task.taskDescription)}</span></div>` : ''}
    `;
    
    // 思考过程 tab - 从日志中提取思考过程
    const thinkingDisplay = document.getElementById('task-thinking-display');
    const thinkingParts = [];
    
    // 从日志中提取所有思考过程
    if (task.logs && task.logs.length > 0) {
        const thinkingLogs = task.logs.filter(log => 
            log.logType === 'LLM_THINKING' || 
            (log.message && log.message.startsWith('思考过程'))
        );
        
        for (const log of thinkingLogs) {
            // 提取思考过程内容（去除标题部分）
            let content = log.message;
            const match = content.match(/^思考过程\d+\s*\n([\s\S]*)$/);
            if (match) {
                content = match[1];
            }
            // 跳过只有长度信息的日志
            if (content.includes('长度:') && content.length < 100) {
                continue;
            }
            thinkingParts.push(`
                <div style="margin:10px 0;padding:10px;border:1px solid #f59e0b;border-radius:4px;">
                    <pre style="margin:0;white-space:pre-wrap;word-break:break-all;color:#f59e0b;">${escapeHtml(content)}</pre>
                </div>
            `);
        }
    }
    
    // 如果日志中没有思考过程，使用任务的thinkingProcess（按轮次解析）
    if (thinkingParts.length === 0 && task.conversationHistory && task.conversationHistory.length > 0) {
        for (const entry of task.conversationHistory) {
            if (entry.thinkingProcess) {
                const thinkingSections = parseConversationSections(entry.thinkingProcess, '思考过程');
                for (const section of thinkingSections) {
                    thinkingParts.push(`
                        <div style="margin:10px 0;padding:10px;border:1px solid #f59e0b;border-radius:4px;">
                            <h4 style="color:#f59e0b;margin:0 0 10px 0;font-size:14px;">
                                <span style="color:#888;">[${escapeHtml(getTaskTypeText(entry.taskType))}]</span> ${escapeHtml(entry.taskName)}
                            </h4>
                            <h5 style="color:#f59e0b;margin:5px 0;font-size:13px;">${section.title}</h5>
                            <pre style="margin:0;white-space:pre-wrap;word-break:break-all;color:#f59e0b;background:#3d2b1f;">${escapeHtml(section.content)}</pre>
                        </div>
                    `);
                }
            }
        }
    }
    
    if (thinkingParts.length === 0) {
        thinkingDisplay.innerHTML = '<p class="waiting-message">暂无思考记录</p>';
    } else {
        thinkingDisplay.innerHTML = thinkingParts.join('');
    }
    
    // 对话记录 tab: 使用JSON格式显示每轮对话的完整历史
    const chatDisplay = document.getElementById('task-chat-display');
    const chatParts = [];
    
    // 收集所有需要显示的对话记录
    const allChatEntries = [];
    
    if (task.conversationHistory && task.conversationHistory.length > 0) {
        for (const entry of task.conversationHistory) {
            if (entry.llmRequest || entry.thinkingProcess || entry.llmResponse) {
                allChatEntries.push({
                    taskName: entry.taskName,
                    taskType: entry.taskType,
                    llmRequest: entry.llmRequest || '',
                    thinkingProcess: entry.thinkingProcess || '',
                    llmResponse: entry.llmResponse || ''
                });
            }
        }
    } else if (task.llmRequest || task.thinkingProcess || task.llmResponse) {
        allChatEntries.push({
            taskName: task.taskName,
            taskType: task.taskType,
            llmRequest: task.llmRequest || '',
            thinkingProcess: task.thinkingProcess || '',
            llmResponse: task.llmResponse || ''
        });
    }
    
    // 显示每个任务的对话记录，按轮次组合（大模型输入/大模型输出）
    for (const entry of allChatEntries) {
        const roundsHtml = renderChatEntryRounds(entry);

        if (roundsHtml) {
            chatParts.push(`
                <div style="margin:15px 0;padding:10px;border:1px solid #333;border-radius:4px;">
                    <h4 style="color:#a78bfa;margin:0 0 10px 0;font-size:14px;">
                        <span style="color:#888;">[${escapeHtml(getTaskTypeText(entry.taskType))}]</span> ${escapeHtml(entry.taskName)}
                    </h4>
                    ${roundsHtml}
                </div>
            `);
        }
    }
    
    if (chatParts.length === 0) {
        chatDisplay.innerHTML = '<p class="waiting-message">暂无对话记录</p>';
    } else {
        chatDisplay.innerHTML = chatParts.join('');
    }
    
    // 加载任务日志
    renderTaskLogs(task.logs);
    
    document.getElementById('task-detail-modal').classList.remove('hidden');
    
    // 如果任务仍在运行或挂起，设置自动刷新机制
    if (task.status === 'RUNNING' || task.status === 'PENDING') {
        startTaskLogAutoRefresh(taskId);
    }
}

// 渲染任务日志
function renderTaskLogs(logs, preserveScrollPosition = false) {
    // 只保留关键类型：TASK_START / TASK_COMPLETE / ERROR / FINDING
    const ALLOWED_TYPES = ['TASK_START', 'TASK_COMPLETE', 'ERROR', 'FINDING'];
    const filteredLogs = logs ? logs.filter(l => ALLOWED_TYPES.includes(l.logType)) : [];
    
    if (filteredLogs.length > 0) {
        const container = document.getElementById('task-logs-display');
        const wasNearBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 50;
        
        container.innerHTML = filteredLogs.map(log => 
            `<div class="log-entry log-${log.logType.toLowerCase()}">
                ${log.taskName ? `<span class="log-task-tag">[${escapeHtml(log.taskName)}]</span> ` : ''}
                <span>${formatDate(log.createdAt)}</span> - ${escapeHtml(log.message)}
            </div>`
        ).join('');
        
        // 只有当用户之前在底部或未指定保持位置时才滚动到底部
        if (!preserveScrollPosition || wasNearBottom) {
            container.scrollTop = container.scrollHeight;
        }
    } else {
        document.getElementById('task-logs-display').innerHTML = '<p class="waiting-message">暂无执行日志</p>';
    }
}

// 任务日志自动刷新（每3秒刷新一次，最多持续10分钟）
let taskLogRefreshTimer = null;
function startTaskLogAutoRefresh(taskId) {
    // 清除之前的定时器
    if (taskLogRefreshTimer) {
        clearInterval(taskLogRefreshTimer);
    }
    
    let refreshCount = 0;
    const maxRefreshes = 200; // 200 * 3s = 10分钟
    
    taskLogRefreshTimer = setInterval(async () => {
        refreshCount++;
        if (refreshCount > maxRefreshes) {
            clearInterval(taskLogRefreshTimer);
            taskLogRefreshTimer = null;
            return;
        }
        
        try {
            const response = await fetch(`${API_BASE}/tasks/${taskId}`);
            if (!response.ok) {
                clearInterval(taskLogRefreshTimer);
                return;
            }
            const task = await response.json();
            
            // 更新日志 - 保持滚动位置
            renderTaskLogs(task.logs, true);
            
            // 更新思考过程 - 按轮次显示每轮的思考过程
            const thinkingDisplay = document.getElementById('task-thinking-display');
            const thinkingParts = [];
            
            const allThinkingEntries = [];
            if (task.conversationHistory && task.conversationHistory.length > 0) {
                for (const entry of task.conversationHistory) {
                    if (entry.thinkingProcess) {
                        allThinkingEntries.push({
                            taskName: entry.taskName,
                            taskType: entry.taskType,
                            thinkingProcess: entry.thinkingProcess
                        });
                    }
                }
            } else if (task.thinkingProcess) {
                allThinkingEntries.push({
                    taskName: task.taskName,
                    taskType: task.taskType,
                    thinkingProcess: task.thinkingProcess
                });
            }
            
            for (const entry of allThinkingEntries) {
                const thinkingSections = parseConversationSections(entry.thinkingProcess, '思考过程');
                if (thinkingSections.length > 0) {
                    const sectionHtml = thinkingSections.map(s => 
                        `<h5 style="color:#f59e0b;margin:10px 0 5px 0;font-size:13px;">${s.title}</h5><pre style="background:#3d2b1f;">${escapeHtml(s.content)}</pre>`
                    ).join('');
                    thinkingParts.push(`
                        <div style="margin:15px 0;padding:10px;border:1px solid #333;border-radius:4px;">
                            <h4 style="color:#a78bfa;margin:0 0 10px 0;font-size:14px;">
                                <span style="color:#888;">[${escapeHtml(getTaskTypeText(entry.taskType))}]</span> ${escapeHtml(entry.taskName)}
                            </h4>
                            ${sectionHtml}
                        </div>
                    `);
                }
            }
            
            if (thinkingParts.length === 0) {
                thinkingDisplay.innerHTML = '<p class="waiting-message">暂无思考记录</p>';
            } else {
                thinkingDisplay.innerHTML = thinkingParts.join('');
            }
            
            // 更新对话记录 - 使用JSON格式显示每轮对话的完整历史
            const chatDisplay = document.getElementById('task-chat-display');
            const chatParts = [];
            
            const allChatEntries = [];
            if (task.conversationHistory && task.conversationHistory.length > 0) {
                for (const entry of task.conversationHistory) {
                    if (entry.llmRequest || entry.thinkingProcess || entry.llmResponse) {
                        allChatEntries.push({
                            taskName: entry.taskName,
                            taskType: entry.taskType,
                            llmRequest: entry.llmRequest || '',
                            thinkingProcess: entry.thinkingProcess || '',
                            llmResponse: entry.llmResponse || ''
                        });
                    }
                }
            } else if (task.llmRequest || task.thinkingProcess || task.llmResponse) {
                allChatEntries.push({
                    taskName: task.taskName,
                    taskType: task.taskType,
                    llmRequest: task.llmRequest || '',
                    thinkingProcess: task.thinkingProcess || '',
                    llmResponse: task.llmResponse || ''
                });
            }
            
            for (const entry of allChatEntries) {
                const roundsHtml = renderChatEntryRounds(entry);

                if (roundsHtml) {
                    chatParts.push(`
                        <div style="margin:15px 0;padding:10px;border:1px solid #333;border-radius:4px;">
                            <h4 style="color:#a78bfa;margin:0 0 10px 0;font-size:14px;">
                                <span style="color:#888;">[${escapeHtml(getTaskTypeText(entry.taskType))}]</span> ${escapeHtml(entry.taskName)}
                            </h4>
                            ${roundsHtml}
                        </div>
                    `);
                }
            }
            
            if (chatParts.length === 0) {
                chatDisplay.innerHTML = '<p class="waiting-message">暂无对话记录</p>';
            } else {
                chatDisplay.innerHTML = chatParts.join('');
            }
            
            // 更新状态和进度
            const statusBadge = document.querySelector('.info-item:nth-child(2) span');
            if (statusBadge) {
                statusBadge.className = 'status-badge status-' + task.status.toLowerCase();
                statusBadge.textContent = getStatusText(task.status);
            }
            const progressSpan = document.querySelector('.info-item:nth-child(3) span');
            if (progressSpan) progressSpan.textContent = taskProgressText(task);
            
            // 如果任务已完成或失败，停止刷新
            if (task.status === 'COMPLETED' || task.status === 'FAILED' || 
                task.status === 'CANCELLED') {
                clearInterval(taskLogRefreshTimer);
                taskLogRefreshTimer = null;
                // 刷新任务列表
                if (typeof loadTasks === 'function') loadTasks(currentTaskFilter);
            }
        } catch (e) {
            // 静默忽略刷新错误
        }
    }, 3000);
    
    // 模态框关闭时清除定时器
    const modal = document.getElementById('task-detail-modal');
    if (modal) {
        const observer = new MutationObserver(() => {
            if (modal.classList.contains('hidden') && taskLogRefreshTimer) {
                clearInterval(taskLogRefreshTimer);
                taskLogRefreshTimer = null;
                observer.disconnect();
            }
        });
        observer.observe(modal, { attributes: true, attributeFilter: ['class'] });
    }
}

function stopTaskLogAutoRefresh() {
    if (taskLogRefreshTimer) {
        clearInterval(taskLogRefreshTimer);
        taskLogRefreshTimer = null;
    }
}

function startTaskStream(taskId) {
    const thinkingDisplay = document.getElementById('task-thinking-display');
    const chatDisplay = document.getElementById('task-chat-display');
    
    const eventSource = new EventSource(`${API_BASE}/tasks/${taskId}/stream`);
    
    eventSource.addEventListener('thinking', (e) => {
        const content = document.createElement('div');
        content.className = 'thinking-item';
        content.textContent = e.data;
        thinkingDisplay.appendChild(content);
        thinkingDisplay.scrollTop = thinkingDisplay.scrollHeight;
    });
    
    eventSource.addEventListener('complete', () => {
        eventSource.close();
    });
    
    eventSource.addEventListener('error', () => {
        eventSource.close();
    });
}

async function pauseTask(taskId) {
    const response = await fetch(`${API_BASE}/tasks/${taskId}/pause`, { method: 'POST' });
    if (response.ok) {
        showToast('任务已暂停');
        loadTasks(currentTaskFilter);
    }
}

async function pauseTaskWithChildren(taskId) {
    if (!confirm('确定要暂停此任务及其所有子任务吗？')) return;
    const response = await fetch(`${API_BASE}/tasks/${taskId}/pause-children`, { method: 'POST' });
    if (response.ok) {
        showToast('任务及其子任务已暂停');
        loadTasks(currentTaskFilter);
    } else {
        const err = await response.json().catch(() => ({}));
        alert('暂停失败: ' + (err.message || '未知错误'));
    }
}

async function resumeTaskWithChildren(taskId) {
    if (!confirm('确定要继续此任务及其所有子任务吗？')) return;
    const response = await fetch(`${API_BASE}/tasks/${taskId}/resume-children`, { method: 'POST' });
    if (response.ok) {
        showToast('任务及其子任务已恢复');
        loadTasks(currentTaskFilter);
    } else {
        const err = await response.json().catch(() => ({}));
        alert('继续失败: ' + (err.message || '未知错误'));
    }
}

async function restartTask(taskId) {
    if (!confirm('确定要重启此任务吗？\n\n将重置任务状态并清除执行结果，子任务也会一并重置。')) return;
    const response = await fetch(`${API_BASE}/tasks/${taskId}/restart`, { method: 'POST' });
    if (response.ok) {
        showToast('任务已重启，状态已重置');
        loadTasks(currentTaskFilter);
    } else {
        const err = await response.json().catch(() => ({}));
        alert('重启失败: ' + (err.message || '未知错误'));
    }
}

async function cancelTask(taskId) {
    if (!confirm('确定要取消此任务吗？\n\n其所有子任务和孙任务也会一并取消！')) return;
    const response = await fetch(`${API_BASE}/tasks/${taskId}/cancel`, { method: 'POST' });
    if (response.ok) {
        showToast('任务已取消');
        loadTasks(currentTaskFilter);
    }
}

async function deleteTask(taskId) {
    if (!confirm('确定要删除此任务吗？\n\n注意：删除父任务将同时删除其所有子任务和孙任务，此操作不可恢复！')) return;
    try {
        const response = await fetch(`${API_BASE}/tasks/${taskId}`, { method: 'DELETE' });
        if (response.ok) {
            showToast('任务及所有子任务已删除');
            loadTasks(currentTaskFilter);
        } else {
            const err = await response.json().catch(() => ({}));
            alert('删除失败: ' + (err.message || '未知错误'));
        }
    } catch (err) {
        alert('删除失败: ' + err.message);
    }
}

async function testLlmConnection(id, btn) {
    const originalText = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 测试中...';
    
    try {
        const response = await fetch(`${API_BASE}/llm-config/${id}/test-connection`, {
            method: 'POST'
        });
        if (response.ok) {
            const result = await response.json();
            if (result.success) {
                btn.innerHTML = '<i class="fas fa-check"></i> 连接成功';
                btn.style.borderColor = '#22c55e';
                btn.style.color = '#22c55e';
                showToast('连接测试成功');
            } else {
                btn.innerHTML = '<i class="fas fa-times"></i> 连接失败';
                btn.style.borderColor = '#ef4444';
                btn.style.color = '#ef4444';
                showToast('连接测试失败: ' + result.message);
            }
        } else {
            btn.innerHTML = '<i class="fas fa-times"></i> 测试失败';
            showToast('连接测试失败');
        }
    } catch (err) {
        btn.innerHTML = '<i class="fas fa-times"></i> 测试失败';
        showToast('连接测试失败: ' + err.message);
    }
    
    setTimeout(() => {
        btn.innerHTML = originalText;
        btn.style.borderColor = '';
        btn.style.color = '';
        btn.disabled = false;
    }, 3000);
}

function initCreateTaskModal() {
    const modal = document.getElementById('create-task-modal');
    
    document.getElementById('create-task-btn').addEventListener('click', async () => {
        const projectSelect = document.getElementById('task-project-select');
        const modelSelect = document.getElementById('task-model-select');
        
        projectSelect.innerHTML = '<option value="">加载中...</option>';
        modelSelect.innerHTML = '<option value="">加载中...</option>';
        
        try {
            const [projectsRes, modelsRes] = await Promise.all([
                fetch(`${API_BASE}/projects`),
                fetch(`${API_BASE}/llm-config/enabled`)
            ]);
            
            if (projectsRes.ok) {
                const projects = await projectsRes.json();
                if (!projects.length) {
                    projectSelect.innerHTML = '<option value="">暂无项目，请先创建项目</option>';
                } else {
                    projectSelect.innerHTML = projects.map(p => 
                        `<option value="${p.id}">${p.projectName} (${getStatusText(p.status)})</option>`
                    ).join('');
                }
            }
            
            if (modelsRes.ok) {
                const configs = await modelsRes.json();
                if (!configs.length) {
                    modelSelect.innerHTML = '<option value="">无可用模型，请先配置并启用</option>';
                } else {
                    modelSelect.innerHTML = configs.map(c => 
                        `<option value="${c.id}">${escapeHtml(c.configName || getProviderName(c.providerName))} (${getProviderName(c.providerName)}) - ${c.model}${c.isDefault ? ' (默认)' : ''}</option>`
                    ).join('');
                }
            }
        } catch (err) {
            projectSelect.innerHTML = '<option value="">加载失败</option>';
            modelSelect.innerHTML = '<option value="">加载失败</option>';
        }
        
        modal.classList.remove('hidden');
    });
    
    document.getElementById('create-task-close').addEventListener('click', () => {
        modal.classList.add('hidden');
    });
    
    document.getElementById('create-task-cancel').addEventListener('click', () => {
        modal.classList.add('hidden');
    });
    
    document.getElementById('create-task-confirm').addEventListener('click', async () => {
        const projectId = document.getElementById('task-project-select').value;
        const llmConfigId = document.getElementById('task-model-select').value;
        
        if (!projectId) {
            alert('请选择一个项目');
            return;
        }
        if (!llmConfigId) {
            alert('请选择一个大模型');
            return;
        }
        
        const confirmBtn = document.getElementById('create-task-confirm');
        confirmBtn.disabled = true;
        confirmBtn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 启动中...';
        
        try {
            const response = await fetch(`${API_BASE}/tasks`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    projectId: parseInt(projectId),
                    llmConfigId: parseInt(llmConfigId)
                })
            });
            
            if (response.ok) {
                modal.classList.add('hidden');
                showToast('任务已启动');
                loadTasks(currentTaskFilter);
            } else {
                const error = await response.json().catch(() => ({}));
                alert('启动失败: ' + (error.error || '未知错误'));
            }
        } catch (err) {
            alert('启动失败: ' + err.message);
        } finally {
            confirmBtn.disabled = false;
            confirmBtn.innerHTML = '启动扫描';
        }
    });
    
    const taskDetailModal = document.getElementById('task-detail-modal');
    document.getElementById('task-detail-close').addEventListener('click', () => {
        taskDetailModal.classList.add('hidden');
        resetTaskDetailFullscreen();
    });
    document.getElementById('task-detail-fullscreen').addEventListener('click', () => {
        const modal = taskDetailModal.querySelector('.modal');
        const icon = document.querySelector('#task-detail-fullscreen i');
        modal.classList.toggle('fullscreen');
        const isFull = modal.classList.contains('fullscreen');
        icon.className = isFull ? 'fas fa-compress' : 'fas fa-expand';
        document.getElementById('task-detail-fullscreen').title = isFull ? '退出全屏' : '全屏显示';
    });
    document.getElementById('task-detail-back').addEventListener('click', () => {
        taskDetailModal.classList.add('hidden');
        resetTaskDetailFullscreen();
    });
    taskDetailModal.addEventListener('click', (e) => {
        if (e.target === taskDetailModal) {
            taskDetailModal.classList.add('hidden');
            resetTaskDetailFullscreen();
        }
    });
    
    const taskDetailTabs = taskDetailModal.querySelectorAll('.tab-btn');
    taskDetailTabs.forEach(tab => {
        tab.addEventListener('click', () => {
            const tabName = tab.dataset.tab;
            taskDetailTabs.forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
            document.querySelectorAll('#task-detail-modal .tab-pane').forEach(p => p.classList.add('hidden'));
            // thinking / chat / logs 分别对应 task-{tabName}-tab 容器（ID 加前缀避免与项目详情弹窗的 logs-tab 重复）
            document.getElementById('task-' + tabName + '-tab').classList.remove('hidden');
        });
    });
}

function resetTaskDetailFullscreen() {
    const modal = document.querySelector('#task-detail-modal .modal');
    if (modal) {
        modal.classList.remove('fullscreen');
    }
    const icon = document.querySelector('#task-detail-fullscreen i');
    if (icon) {
        icon.className = 'fas fa-expand';
    }
    const btn = document.getElementById('task-detail-fullscreen');
    if (btn) {
        btn.title = '全屏显示';
    }
}

function showToast(message) {
    const toast = document.createElement('div');
    toast.style.cssText = `
        position: fixed;
        top: 20px;
        right: 20px;
        background: rgba(0, 20, 0, 0.95);
        border: 1px solid #00ff41;
        color: #00ff41;
        padding: 12px 24px;
        border-radius: 4px;
        box-shadow: 0 0 20px rgba(0, 255, 65, 0.4);
        z-index: 10000;
        animation: slideIn 0.3s ease;
        font-family: 'Courier New', monospace;
        text-shadow: 0 0 10px rgba(0, 255, 65, 0.5);
    `;
    toast.textContent = message;
    document.body.appendChild(toast);
    
    setTimeout(() => {
        toast.style.animation = 'slideOut 0.3s ease';
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}

// ==================== 提示词配置功能 ====================

let currentPromptPhaseFilter = 'ALL';

function initPromptConfig() {
    const modal = document.getElementById('prompt-config-modal');
    
    document.getElementById('add-prompt-config-btn').addEventListener('click', () => {
        openPromptConfigModal(null);
    });
    
    document.getElementById('prompt-config-close').addEventListener('click', () => {
        modal.classList.add('hidden');
    });
    
    document.getElementById('prompt-config-cancel').addEventListener('click', () => {
        modal.classList.add('hidden');
    });
    
    document.getElementById('prompt-config-submit').addEventListener('click', () => {
        submitPromptConfigForm();
    });
    
    const phaseSelect = document.getElementById('prompt-config-phase');
    if (phaseSelect) {
        phaseSelect.addEventListener('change', () => {
            updatePromptConfigFieldVisibility();
        });
    }
    
    modal.addEventListener('click', (e) => {
        if (e.target === modal) {
            modal.classList.add('hidden');
        }
    });
    
    document.querySelectorAll('#prompt-config-section .filter-tab').forEach(tab => {
        tab.addEventListener('click', () => {
            document.querySelectorAll('#prompt-config-section .filter-tab').forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
            currentPromptPhaseFilter = tab.dataset.phase;
            loadPromptConfigs();
        });
    });
}

async function loadPromptConfigs() {
    let url = `${API_BASE}/prompt-config`;
    if (currentPromptPhaseFilter !== 'ALL') {
        url = `${API_BASE}/prompt-config/phase/${currentPromptPhaseFilter}`;
    }
    
    const response = await fetch(url);
    if (!response.ok) return;
    
    const configs = await response.json();
    const grid = document.getElementById('prompt-config-grid');
    
    if (!configs.length) {
        grid.innerHTML = '<div style="grid-column: 1/-1; text-align:center; padding:40px; color:#00aa29;">暂无提示词配置，点击"新建配置"添加</div>';
        return;
    }
    
    const phaseColors = {
        GLOBAL_CONFIG: { bg: 'rgba(34, 197, 94, 0.2)', border: 'rgba(34, 197, 94, 0.5)', color: '#22c55e' },
        DEPENDENCY_SCAN: { bg: 'rgba(59, 130, 246, 0.2)', border: 'rgba(59, 130, 246, 0.5)', color: '#3b82f6' },
        INTERFACE_SCAN: { bg: 'rgba(168, 85, 247, 0.2)', border: 'rgba(168, 85, 247, 0.5)', color: '#a855f7' },
        HIGH_RISK_OPERATION_SCAN: { bg: 'rgba(249, 115, 22, 0.2)', border: 'rgba(249, 115, 22, 0.5)', color: '#f97316' }
    };
    
    grid.innerHTML = configs.map(config => {
        const colors = phaseColors[config.phase] || phaseColors.DEPENDENCY_SCAN;
        return `
            <div class="prompt-config-card" style="border-color: ${colors.border};">
                <div class="prompt-config-header" style="border-bottom-color: ${colors.border};">
                    <div class="prompt-config-title-row">
                        <h3 style="color: ${colors.color};">${escapeHtml(config.name)}</h3>
                        ${!config.enabled ? '<span class="disabled-badge">已禁用</span>' : ''}
                        ${config.phase === 'GLOBAL_CONFIG' && config.displayOrder > 0 ? `<span class="order-badge">顺序: ${config.displayOrder}</span>` : ''}
                    </div>
                    <div class="prompt-config-meta">
                        <span class="phase-tag" style="background: ${colors.bg}; border-color: ${colors.border}; color: ${colors.color};">
                            ${getPhaseText(config.phase)}
                        </span>
                    </div>
                </div>
                <div class="prompt-config-body">
                    ${config.systemPrompt ? `
                        <div class="prompt-item">
                            <label>系统提示词</label>
                            <div class="prompt-preview">${escapeHtml(truncateText(config.systemPrompt, 100))}</div>
                        </div>
                    ` : ''}
                    ${config.analysisPrompt ? `
                        <div class="prompt-item">
                            <label>分析提示词</label>
                            <div class="prompt-preview">${escapeHtml(truncateText(config.analysisPrompt, 150))}</div>
                        </div>
                    ` : ''}
                    ${config.prefixPrompt ? `
                        <div class="prompt-item">
                            <label>前缀提示词</label>
                            <div class="prompt-preview">${escapeHtml(truncateText(config.prefixPrompt, 100))}</div>
                        </div>
                    ` : ''}
                    ${config.suffixPrompt ? `
                        <div class="prompt-item">
                            <label>后缀提示词</label>
                            <div class="prompt-preview">${escapeHtml(truncateText(config.suffixPrompt, 100))}</div>
                        </div>
                    ` : ''}
                </div>
                <div class="prompt-config-footer">
                    <button class="btn btn-sm btn-secondary edit-prompt-btn" data-id="${config.id}">编辑</button>
                    <button class="btn btn-sm btn-danger delete-prompt-btn" data-id="${config.id}">删除</button>
                </div>
            </div>
        `;
    }).join('');
    
    document.querySelectorAll('.edit-prompt-btn').forEach(btn => {
        btn.addEventListener('click', (e) => openPromptConfigModal(e.target.dataset.id));
    });
    document.querySelectorAll('.delete-prompt-btn').forEach(btn => {
        btn.addEventListener('click', (e) => deletePromptConfig(e.target.dataset.id));
    });
}

function getPhaseText(phase) {
    const map = {
        GLOBAL_CONFIG: '全局配置',
        DEPENDENCY_SCAN: '依赖扫描',
        INTERFACE_SCAN: '接口扫描',
        HIGH_RISK_OPERATION_SCAN: '高危操作扫描'
    };
    return map[phase] || phase;
}

function truncateText(text, maxLen) {
    if (!text) return '-';
    if (text.length <= maxLen) return text;
    return text.substring(0, maxLen) + '...';
}

async function openPromptConfigModal(id) {
    const modal = document.getElementById('prompt-config-modal');
    const form = document.getElementById('prompt-config-form');
    const titleEl = document.getElementById('prompt-config-modal-title');
    
    form.reset();
    document.getElementById('prompt-config-id').value = '';
    
    if (id) {
        titleEl.textContent = '编辑提示词配置';
        try {
            const response = await fetch(`${API_BASE}/prompt-config/${id}`);
            if (response.ok) {
                const config = await response.json();
                document.getElementById('prompt-config-id').value = config.id;
                document.getElementById('prompt-config-name').value = config.name;
                document.getElementById('prompt-config-phase').value = config.phase;
                document.getElementById('prompt-config-order').value = config.displayOrder || 0;
                document.getElementById('prompt-config-system').value = config.systemPrompt || '';
                document.getElementById('prompt-config-analysis').value = config.analysisPrompt || '';
                document.getElementById('prompt-config-prefix').value = config.prefixPrompt || '';
                document.getElementById('prompt-config-suffix').value = config.suffixPrompt || '';
                document.getElementById('prompt-config-enabled').checked = config.enabled !== false;
            } else {
                showToast('加载配置失败');
                return;
            }
        } catch (err) {
            showToast('加载配置出错: ' + err.message);
            return;
        }
    } else {
        titleEl.textContent = '新建提示词配置';
        document.getElementById('prompt-config-enabled').checked = true;
        document.getElementById('prompt-config-order').value = 0;
    }
    
    updatePromptConfigFieldVisibility();
    modal.classList.remove('hidden');
}

async function submitPromptConfigForm() {
    const id = document.getElementById('prompt-config-id').value;
    const name = document.getElementById('prompt-config-name').value;
    const phase = document.getElementById('prompt-config-phase').value;
    const displayOrder = parseInt(document.getElementById('prompt-config-order').value) || 0;
    const systemPrompt = document.getElementById('prompt-config-system').value;
    const analysisPrompt = document.getElementById('prompt-config-analysis').value;
    const prefixPrompt = document.getElementById('prompt-config-prefix').value;
    const suffixPrompt = document.getElementById('prompt-config-suffix').value;
    const enabled = document.getElementById('prompt-config-enabled').checked;
    
    if (!name) {
        alert('请填写配置名称');
        return;
    }
    
    if (!phase) {
        alert('请选择扫描阶段');
        return;
    }
    
    const method = id ? 'PUT' : 'POST';
    const url = id ? `${API_BASE}/prompt-config/${id}` : `${API_BASE}/prompt-config`;
    
    const response = await fetch(url, {
        method: method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            name: name,
            phase: phase,
            displayOrder: displayOrder,
            systemPrompt: systemPrompt || null,
            analysisPrompt: analysisPrompt || null,
            prefixPrompt: prefixPrompt || null,
            suffixPrompt: suffixPrompt || null,
            enabled: enabled
        })
    });
    
    if (response.ok) {
        document.getElementById('prompt-config-modal').classList.add('hidden');
        loadPromptConfigs();
        showToast('配置保存成功');
    } else {
        const error = await response.json().catch(() => ({}));
        alert('保存失败: ' + (error.error || '未知错误'));
    }
}

function updatePromptConfigFieldVisibility() {
    const phase = document.getElementById('prompt-config-phase').value;
    const orderGroup = document.getElementById('display-order-group');
    const analysisGroup = document.getElementById('analysis-prompt-group');
    const prefixGroup = document.getElementById('prefix-prompt-group');
    const suffixGroup = document.getElementById('suffix-prompt-group');
    
    // 显示顺序只在全局配置时显示
    if (phase === 'GLOBAL_CONFIG') {
        orderGroup.style.display = 'block';
        // 全局配置时显示前缀和后缀，隐藏分析提示词
        analysisGroup.style.display = 'none';
        prefixGroup.style.display = 'block';
        suffixGroup.style.display = 'block';
    } else {
        orderGroup.style.display = 'none';
        // 非全局配置时显示分析提示词，隐藏前缀和后缀
        analysisGroup.style.display = 'block';
        prefixGroup.style.display = 'none';
        suffixGroup.style.display = 'none';
    }
}

async function deletePromptConfig(id) {
    if (!confirm('确定要删除这个提示词配置吗？')) return;
    
    const response = await fetch(`${API_BASE}/prompt-config/${id}`, {
        method: 'DELETE'
    });
    
    if (response.ok) {
        loadPromptConfigs();
        showToast('配置已删除');
    } else {
        const error = await response.json().catch(() => ({}));
        alert('删除失败: ' + (error.error || '未知错误'));
    }
}

const style = document.createElement('style');
style.textContent = `
    @keyframes slideIn {
        from { transform: translateX(100%); opacity: 0; }
        to { transform: translateX(0); opacity: 1; }
    }
    @keyframes slideOut {
        from { transform: translateX(0); opacity: 1; }
        to { transform: translateX(100%); opacity: 0; }
    }
    .project-link {
        color: #38bdf8;
        text-decoration: none;
    }
    .project-link:hover {
        text-decoration: underline;
    }
    .prompt-config-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(350px, 1fr));
        gap: 20px;
    }
    .prompt-config-card {
        background: rgba(0, 20, 0, 0.75);
        border: 1px solid rgba(0, 255, 65, 0.3);
        border-radius: 4px;
        padding: 20px;
        transition: all 0.3s ease;
        backdrop-filter: blur(10px);
        position: relative;
        overflow: hidden;
    }
    .prompt-config-card:hover {
        box-shadow: 0 0 20px rgba(0, 255, 65, 0.2);
    }
    .prompt-config-header {
        border-bottom: 1px solid rgba(0, 255, 65, 0.2);
        padding-bottom: 12px;
        margin-bottom: 16px;
    }
    .prompt-config-title-row {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 8px;
    }
    .prompt-config-title-row h3 {
        font-size: 15px;
        margin: 0;
    }
    .prompt-config-meta {
        display: flex;
        gap: 8px;
        flex-wrap: wrap;
    }
    .phase-tag {
        padding: 3px 10px;
        border-radius: 2px;
        font-size: 11px;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.5px;
        border: 1px solid;
    }
    .order-badge {
        padding: 2px 8px;
        border-radius: 2px;
        font-size: 10px;
        background: rgba(34, 197, 94, 0.2);
        border: 1px solid rgba(34, 197, 94, 0.4);
        color: #22c55e;
    }
    .disabled-badge {
        background: rgba(100, 116, 139, 0.3);
        border: 1px solid #64748b;
        color: #94a3b8;
        padding: 2px 8px;
        border-radius: 2px;
        font-size: 10px;
        text-transform: uppercase;
    }
    .prompt-config-body {
        display: flex;
        flex-direction: column;
        gap: 12px;
        margin-bottom: 16px;
    }
    .prompt-item {
        background: rgba(0, 10, 0, 0.5);
        padding: 10px 12px;
        border-radius: 2px;
        border-left: 3px solid rgba(0, 255, 65, 0.3);
    }
    .prompt-item label {
        display: block;
        font-size: 11px;
        color: #00aa29;
        margin-bottom: 6px;
        text-transform: uppercase;
        letter-spacing: 0.5px;
    }
    .prompt-preview {
        font-size: 12px;
        color: #00cc33;
        line-height: 1.5;
        max-height: 80px;
        overflow: hidden;
    }
    .prompt-config-footer {
        display: flex;
        gap: 8px;
        padding-top: 12px;
        border-top: 1px solid rgba(0, 255, 65, 0.2);
    }
`;
document.head.appendChild(style);