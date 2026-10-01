# 大模型应用开发：GitHub Actions + AI 实现 PR 自动代码审核

> 每次提交 PR 都要等人来 Review？如果团队只有你一个人写代码呢？如果凌晨两点提交的 PR 想快速合并呢？
>
> 本文将手把手教你，用 **GitHub Actions + 大模型**，搭建一套全自动的 AI 代码审核流水线。PR 一提交，AI 立刻帮你审，有问题直接拦住，没问题自动放行。

---

## 一、为什么需要 AI 代码审核？

做过开源或者团队协作的同学都知道，Code Review 是一件**重要但容易被拖延**的事情。

传统 CI 能做的事情有限：编译、跑测试、Lint 检查。但更深层的问题——**安全漏洞、逻辑缺陷、资源泄漏、前端 XSS**——这些静态分析工具往往无能为力。

而大模型恰好擅长理解代码语义。它能读懂上下文，能发现"这里有个 NPE 必现"，能告诉你"这个 `dangerouslySetInnerHTML` 有 XSS 风险"。

那能不能让大模型直接参与 PR 审核，**像真人 Reviewer 一样**在 PR 下面留评论、打回修改？

答案是：**完全可以**。

## 二、效果预览：AI 审核长什么样？

先看最终效果。当你提交一个 PR 后，GitHub Actions 会自动触发，几分钟后你的 PR 下面就会出现这样的评论：

```markdown
### 🤖 AI 代码审核结论 ✅ 审核通过

## 必须修改
无

## 建议优化
- [代码质量] 变量命名 userIdStr 不够语义化 | 建议改为 rawUserId 更贴切
- [性能优化] 循环内重复创建 DateFormat 实例 | 建议提取为 static final 常量

---
*本评论由 GitHub Actions CI 自动生成，基于 GitHub App 鉴权 + deepseek-reasoner 模型审核*
```

如果 AI 发现了严重问题，评论会变成这样：

```markdown
### 🤖 AI 代码审核结论 ❌ 审核失败

## 必须修改
- [安全漏洞] 第 42 行直接拼接 SQL 字符串，存在 SQL 注入风险 | 建议使用参数化查询 PreparedStatement

## 建议优化
- [代码规范] catch 块中仅打印堆栈，未做异常恢复 | 建议添加重试或降级逻辑

---
*本评论由 GitHub Actions CI 自动生成*
```

并且，**Job 会标记为失败**，配合分支保护规则，可以直接阻止合并。

这就是我们要搭建的东西。下面开始动手。

---

## 三、整体架构设计

在写代码之前，先理清整体流程。我们的方案分为 **4 个核心环节**：

![mr_1.png](https://imgbed.ppai.top/file/1781487755105_mr_1.png)

```mermaid
graph TD
    A[开发者提交 PR] --> B[GitHub Actions 触发工作流]
    B --> C[GitHub App 生成短期 Token]
    C --> D[拉取 PR Diff 内容]
    D --> E[调用大模型 API 审核]
    E --> F{解析审核结果}
    F -->|有必须修改| G[提交 REQUEST_CHANGES 并失败 Job]
    F -->|仅建议优化| H[提交 COMMENT 并正常结束]
    G --> I[PR 评论展示审核结论]
    H --> I
```

**关键设计决策：**

| 决策点 | 我们的选择 | 为什么 |
|--------|-----------|--------|
| 触发方式 | `pull_request_target` | 比 `pull_request` 更安全，fork PR 也能拿到 Secrets |
| 鉴权方式 | GitHub App + 短期 Token | 最小权限原则，Token 1 小时自动过期 |
| 脚本语言 | Python 3 + `urllib` 标准库 | Runner 自带 Python 3，零依赖，不需要 pip install |
| 模型调用 | OpenAI 兼容接口 | 通用性强，DeepSeek/通义/GPT 都能接 |
| 结果判定 | 正则解析"必须修改"章节 | 结构化输出 + 自动化判定，不依赖模型格式完美 |

---

## 四、前置准备：你需要什么？

### 4.1 一个 GitHub 仓库

你的代码得在 GitHub 上。如果是私有仓库也没问题，流程完全一样。

### 4.2 一个大模型 API

需要 OpenAI 兼容接口的模型服务。推荐几个选择：

| 模型服务商 | 推荐模型 | 特点 |
|-----------|---------|------|
| DeepSeek | `deepseek-reasoner` | 性价比高，自带推理链，**本项目默认使用** |
| 阿里百炼 | `qwen-plus` | 中文理解强 |
| OpenAI | `gpt-4o-mini` | 综合能力最强，但贵 |

你只需要两样东西：
- **API Key**：模型服务的密钥
- **API URL**：模型的 chat completions 端点地址

### 4.3 一个 GitHub App（重点）

这是整个方案中最关键的准备步骤。我们需要创建一个 GitHub App 来鉴权，而不是直接用 Personal Access Token（PAT）。

**为什么不用 PAT？**

- PAT 权限范围大，一旦泄漏风险高
- PAT 绑定个人账号，人走了 Token 就失效
- GitHub App 可以精确控制权限到**只读写 PR**，且生成的 Token **1 小时自动过期**

---

## 五、手把手创建 GitHub App

这一步很多同学会卡住，我们详细走一遍。

### 5.1 进入创建页面

GitHub → 点击你的头像 → **Settings** → 左侧最下面 **Developer settings** → **GitHub Apps** → **New GitHub App**

![mr_2.PNG](https://imgbed.ppai.top/file/1781487887374_mr_2.PNG)

### 5.2 填写基本信息

| 字段 | 填写内容                      |
|------|---------------------------|
| GitHub App name | `AiCodeReview`（起个名字，全局唯一） |
| Description | AI Code Review Bot        |
| Homepage URL | 你的仓库地址                    |
| Webhook → Active | **取消勾选**（我们不需要 Webhook）   |

![mr_3.PNG](https://imgbed.ppai.top/file/1781487894512_mr_3.PNG)

### 5.3 配置权限（Permissions）

只给最小必要权限：

| 权限 | 级别 | 用途 |
|------|------|------|
| **Pull requests** | **Read & Write** | 读取 PR 内容 + 写入审核评论 |
| **Contents** | **Read** | 读取代码文件 |

### 5.4 创建并安装

点击 **Create GitHub App**，然后：

1. 记录下页面上的 **App ID**（一串数字），这就是后面的 `GH_APP_ID`
2. 滑到最下面，点击 **Generate a private key**，会下载一个 `.pem` 文件
3. 左侧点 **Install App**，选择你的目标仓库，点击 Install

![mr_4.jpg](https://imgbed.ppai.top/file/1781488146717_mr_4.jpg)

> **⚠️ 踩坑提醒：关于私钥格式**
>
> 下载的 `.pem` 文件内容大致长这样：
> ```
> -----BEGIN RSA PRIVATE KEY-----
> MIIEpAIBAAKCAQEA0Z3VS5JJcds3xfn/yGaX...
> ...多行 base64 内容...
> -----END RSA PRIVATE KEY-----
> ```
>
> 把这个**完整内容**（包括 BEGIN/END 行）存到 GitHub Secrets 中。
>
> 如果你把私钥内容直接粘贴到 Secrets 输入框，换行符可能被吞掉。我们的工作流程已经内置了自动修复逻辑，支持以下格式：
> - 完整 PEM 内容（推荐，直接复制 .pem 文件）
> - 换行符被转义为 `\n` 的 PEM
> - Base64 编码后的 PEM
> - 单行拼接的 PEM
>
> **但请注意：** 一定要包含 `-----BEGIN ...-----` 和 `-----END ...-----` 标记，不能只存中间的 base64 内容。

---

## 六、配置 GitHub Secrets

进入仓库 → **Settings** → **Secrets and variables** → **Actions**

![mr_5.jpg](https://imgbed.ppai.top/file/1781488524566_mr_5.jpg)

### 6.1 必须配置的 Secrets（4 个）

| Secret 名称 | 值 | 说明                      |
|-------------|---|-------------------------|
| `GH_APP_ID` | 你的 GitHub App ID（数字） | 在 App 设置页面获取            |
| `GH_APP_PRIVATE_KEY` | PEM 私钥完整内容 | 下载的 .pem 文件内容           |
| `MR_OPENAI_API_KEY` | 模型 API Key | 你的大模型服务密钥               |
| `MR_OPENAI_API_URL` | 模型 API 地址 | 完整的 chat/completions 端点 |

### 6.2 可选配置（Repository Variables）

| Variable 名称 | 默认值 | 说明 |
|---------------|--------|------|
| `AI_REVIEW_MODEL` | `deepseek-reasoner` | 审核使用的模型名称 |
| `AI_REVIEW_MAX_DIFF_CHARS` | `40000` | Diff 最大字符数，超出会截断 |
| `AI_REVIEW_PROMPT`（Secret） | 内置提示词 | 自定义审核提示词模板 |

---

## 七、工作流文件详解

核心就是 `.github/workflows/code-review-ci.yml` 这一个文件。我们来逐段拆解。

### 7.1 触发条件

```yaml
on:
  pull_request_target:
    types:
      - opened          # PR 新建
      - synchronize     # PR 有新 push
      - reopened        # PR 重新打开
      - ready_for_review # PR 从 Draft 变为 Ready
    branches:
      - main
      - v2
```

**几个关键点：**

- 使用 `pull_request_target` 而不是 `pull_request`。区别在于：`pull_request` 在 fork PR 上**拿不到 Secrets**（出于安全考虑），而 `pull_request_target` 始终运行在**目标分支的上下文**中，可以安全地使用 Secrets
- 只审核目标分支为 `main` 或 `v2` 的 PR
- Draft PR 不会触发（后面有 `if` 判断）

### 7.2 权限与并发控制

```yaml
permissions:
  contents: read
  pull-requests: write

concurrency:
  group: ai-code-review-${{ github.event.pull_request.number || github.run_id }}
  cancel-in-progress: true
```

- `permissions` 声明最小权限：只读代码，只写 PR
- `concurrency` 保证同一个 PR 同时只跑一次审核，新的 push 会自动取消旧的审核，避免浪费 Token

### 7.3 Job 过滤

```yaml
jobs:
  ai_code_review_for_pr:
    runs-on: ubuntu-latest
    if: ${{ github.event.pull_request.draft == false }}
```

Draft PR 直接跳过，不打扰开发中的 PR。

### 7.4 Step 1：环境诊断

```yaml
- name: Check environment
  shell: bash
  run: |
    echo "=== Environment Diagnostics ==="
    echo -n "python3 available: "; command -v python3 && python3 --version || echo "NOT FOUND"
    echo -n "curl    available: "; command -v curl && curl --version | head -1 || echo "NOT FOUND"
    echo "=== End Diagnostics ==="
```

这一步纯粹是打印环境信息，方便出问题时排查。GitHub Actions 的 `ubuntu-latest` Runner 自带 Python 3，但万一呢？

### 7.5 Step 2：私钥格式自动修复（精妙之处）

```yaml
- name: Normalize GitHub App private key
  id: app-key
  shell: bash
  env:
    GH_APP_PRIVATE_KEY_RAW: ${{ secrets.GH_APP_PRIVATE_KEY }}
  run: |
    python3 - <<'PY'
    # 自动处理换行符转义、Base64 解码、引号剥离
    # ...（完整逻辑见源文件）
    PY
```

**这一步是整个工作流中最"接地气"的设计。**

实际操作中，PEM 私钥存到 GitHub Secrets 时，格式很容易出问题：
- 有人直接粘贴，换行符变成了 `\n` 字面量
- 有人用 Base64 编码后存储
- 有人不小心多了一对引号

这段 Python 脚本会**自动检测并修复**所有常见格式问题，输出一个标准化的 PEM 私钥，确保下一步 GitHub App Token 生成不会出错。

### 7.6 Step 3：生成 GitHub App Token

```yaml
- name: Create GitHub App token
  id: app-token
  uses: actions/create-github-app-token@v1
  with:
    app-id: ${{ secrets.GH_APP_ID }}
    private-key: ${{ steps.app-key.outputs.private-key }}
    owner: ${{ github.repository_owner }}
    repositories: ${{ github.event.repository.name }}
```

使用 GitHub 官方提供的 Action，用上一步处理好的私钥，生成一个**有效期 1 小时的 installation token**。这个 Token 会被用于：
1. 调用 GitHub API 获取 PR 的 Diff
2. 在 PR 下发布审核评论

### 7.7 Step 4：核心审核逻辑

这是最长也是最核心的一步——一个内嵌的 Python 脚本，大约 360 行。我们把它拆成几个模块来看。

#### 模块 A：获取 PR Diff

```python
def fetch_pull_request_diff(repo, pr_number, token):
    # 优先使用 diff 端点，一次性拿到完整 diff
    diff_url = f"https://api.github.com/repos/{repo}/pulls/{pr_number}"
    diff_text = request_text(diff_url, headers=github_headers(token, "application/vnd.github.v3.diff"))

    # 如果 diff 端点失败，降级为 files API，逐页获取 patch
    # ...
```

**两级降级策略**：先试 diff 端点（快且完整），不行再退到 files API（稳定但需分页）。

获取到的 Diff 如果太大，会被截断：

```python
max_diff_chars = int(getenv("MAX_DIFF_CHARS", "40000"))
diff_content = clamp_text(diff_content, max_diff_chars)
```

默认 4 万字符，大约对应 2-3 万行代码变更，够覆盖绝大多数 PR 了。

#### 模块 B：内置审核提示词

工作流内置了一套经过实战验证的审核提示词，核心结构如下：

**必须修改（严格限定范围）：**

| 类别 | 具体场景 |
|------|---------|
| 安全类-高危 | SQL 注入、硬编码密钥、权限绕过、XSS、命令注入、路径遍历 |
| 前端安全类 | `dangerouslySetInnerHTML` 渲染用户输入、前端硬编码 API Key、`eval()` 执行用户输入 |
| 逻辑类-致命 | 必现 NPE、死循环/无限递归、资源泄漏、数据丢失风险 |
| 前端逻辑类 | `useEffect` 依赖错误导致无限渲染、渲染路径中直接调用副作用 |

**建议优化（所有其他问题）：**

- 中低风险安全问题（HTTP、日志打印敏感信息等）
- 代码质量（命名、注释、重复代码、设计模式）
- 前端质量（useEffect 依赖、key 使用、any 类型、组件过度渲染）

**核心原则只有一条：宁可少报也不要误报。**

> 如果你不确定是否属于"必须修改"，那就归入"建议优化"。

这个原则极其重要。AI 审核最怕的不是漏报，而是**误报**。一旦频繁误报，开发者就会失去信任，直接忽略审核结果。

#### 模块 C：调用大模型

```python
def call_llm(api_url, api_key, model, prompt_text):
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": "你是一位资深的代码审核专家..."},
            {"role": "user", "content": prompt_text},
        ],
        "stream": False,
    }
    response = request_json(api_url, method="POST", headers=headers, body=payload, timeout=180)
```

几个细节：

1. **超时设为 180 秒**——大模型分析代码变更需要较长时间，默认 60 秒往往不够
2. **支持推理模型**——如果使用 `deepseek-reasoner` 这类推理模型，返回中会包含 `reasoning_content` 字段，脚本会自动提取推理链并在日志中输出
3. **兜底逻辑**——如果 `content` 字段为空，会尝试用 `reasoning_content` 作为审核结果

#### 模块 D：结果判定

```python
def is_must_fix(content):
    # 用正则匹配 "## 必须修改" 章节（支持中英文标题）
    match = re.search(r"##\s*(?:必须修改|Must Fix)\s*\n(.*?)(?=\n##\s*|$)", content, re.DOTALL)

    section = match.group(1).strip()
    if section == "无" or section == "none":
        return False

    # 检查是否有以 "-" 开头的实际条目
    for line in section.splitlines():
        if line.startswith("-") and "none" not in line.lower():
            return True
    return False
```

不依赖模型输出完美的 JSON 格式，而是用**正则 + 简单规则**来判定。这种方式更鲁棒——即使模型的格式稍有偏差，也能正确判断。

#### 模块 E：提交审核结果

```python
review_event = "REQUEST_CHANGES" if must_fix else "COMMENT"
post_review(repo, pr_number, token, review_body, review_event)
```

- 有"必须修改"→ 提交 `REQUEST_CHANGES`（打回），同时 `sys.exit(1)` 让 Job 失败
- 仅"建议优化"→ 提交 `COMMENT`（评论），Job 正常结束

如果 Review API 调用失败（比如权限不够），会**自动降级为普通评论**：

```python
try:
    post_review(...)
except Exception:
    post_comment(...)  # 降级为 Issue Comment
```

---

## 八、完整执行流程

用一张图总结整个执行过程：

![mr_6.jpg](https://imgbed.ppai.top/file/1781489016130_mr_6.jpg)

```mermaid
graph TD
    A[PR 事件触发] --> B{Draft PR?}
    B -->|是| Z[跳过审核]
    B -->|否| C{目标分支是 main/v2?}
    C -->|否| Z
    C -->|是| D[环境诊断: 检查 python3/curl]
    D --> E[私钥格式自动修复]
    E --> F[生成 GitHub App Token]
    F --> G{Token 生成成功?}
    G -->|否| Z
    G -->|是| H[获取 PR Diff]
    H --> I{Diff 为空?}
    I -->|是| Z
    I -->|否| J[截断 Diff + 填充提示词模板]
    J --> K[调用大模型 API]
    K --> L[正则解析审核结果]
    L --> M{有必须修改?}
    M -->|是| N[REQUEST_CHANGES + Job 失败]
    M -->|否| O[COMMENT + Job 成功]
```

---

## 九、自定义审核提示词

内置的提示词已经覆盖了大多数场景，但你可能想根据自己的项目定制。

### 9.1 修改方式

在 GitHub Secrets 中新增一个 `AI_REVIEW_PROMPT`，写入你的自定义提示词。

### 9.2 提示词模板要求

自定义提示词必须遵守两个规则：

1. **必须包含 `{code_diff}` 占位符** —— 工作流会把它替换成实际的 Diff 内容
2. **必须包含 `## 必须修改` 和 `## 建议优化` 两个章节标题** —— 脚本依赖这两个标题来自动判定审核结果

### 9.3 自定义示例

比如你想让 AI 更关注数据库相关的变更：

```text
作为资深后端审核专家，重点审核以下 Git PR 变更中的数据库操作和并发安全性：
{code_diff}

审核维度：
1. SQL 注入和参数化查询
2. 事务隔离级别是否正确
3. 连接池配置是否合理
4. 并发场景下的数据一致性

## 必须修改
- [问题类型] 具体描述 | 修复建议

## 建议优化
- [问题类型] 具体描述 | 优化建议
```

---

## 十、接入不同的大模型

工作流使用 OpenAI 兼容接口，这意味着几乎所有主流国产大模型都能无缝接入。

### 10.1 DeepSeek（推荐，本项目默认）

```
MR_OPENAI_API_URL = https://api.deepseek.com/v1/chat/completions
AI_REVIEW_MODEL   = deepseek-reasoner
```

优势：自带推理链，审核质量高，性价比极高。

### 10.2 阿里百炼 通义千问

```
MR_OPENAI_API_URL = https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
AI_REVIEW_MODEL   = qwen-plus
```

### 10.3 OpenAI GPT

```
MR_OPENAI_API_URL = https://api.openai.com/v1/chat/completions
AI_REVIEW_MODEL   = gpt-4o-mini
```

> 注意：`AI_REVIEW_MODEL` 配置在 Repository Variables 中，不是 Secrets。

---

## 十一、踩坑记录

搭建过程中我们踩了不少坑，这里列出最常见的几个，帮你避坑。

### 坑 1：私钥格式不对，Token 生成失败

**现象：** `create-github-app-token` 步骤报错 `secretOrPrivateKey could not be found`

**原因：** PEM 私钥粘贴到 Secrets 时换行符丢失，变成了单行字符串。

**解决：** 我们的工作流已经内置了 `Normalize GitHub App private key` 步骤，会自动修复 `\n` 转义、Base64 编码等常见格式问题。你只需要确保存进去的内容**包含 BEGIN/END 标记**即可。

### 坑 2：模型返回内容为空

**现象：** 日志中显示 `LLM returned empty content`

**原因：** 部分推理模型（如 `deepseek-reasoner`）会把答案放在 `reasoning_content` 而不是 `content` 字段中。

**解决：** 工作流已内置兜底逻辑：如果 `content` 为空，会自动尝试读取 `reasoning_content` 和 `thinking` 字段。

### 坑 3：PR Review 提交失败

**现象：** 日志显示 `review submission failed`

**原因：** GitHub App 的权限不够，或者 App 没有安装到当前仓库。

**解决：**
1. 检查 App 权限：`Pull requests` 必须设为 `Read & Write`
2. 检查 App 是否安装到了目标仓库
3. 工作流会自动降级为 Issue Comment，不会完全静默失败

### 坑 4：审核没有触发

**现象：** 提交了 PR 但没有看到 AI 审核评论

**排查清单：**
- [ ] PR 目标分支是不是 `main` 或 `v2`？
- [ ] PR 是不是还是 Draft 状态？
- [ ] Workflow 文件是不是在目标分支上？（`pull_request_target` 读取的是目标分支的 workflow 文件）
- [ ] GitHub App 是不是安装到了这个仓库？

### 坑 5：Diff 太大导致模型超时或报错

**现象：** 日志显示 LLM 调用超时或 token limit exceeded

**解决：** 调小 `AI_REVIEW_MAX_DIFF_CHARS` 的值，比如设为 `20000`。超大 PR 建议拆分成多个小 PR。

---

## 十二、进阶玩法

### 12.1 配合分支保护规则

光有 AI 审核还不够，你需要配合 GitHub 的**分支保护规则**才能真正拦住问题 PR：

1. 进入仓库 → Settings → Branches → Add rule
2. Branch name pattern 填 `main`
3. 勾选 **Require status checks to pass before merging**
4. 在搜索框中找到 `ai_code_review_for_pr`，添加它

这样，当 AI 审核发现"必须修改"的问题时，Job 会失败，PR 就**无法合并**，必须修复后重新提交。

### 12.2 与现有 CI 共存

这套 AI 审核工作流可以和你现有的 CI（编译、测试、Lint）完全共存。它们各自独立运行，互不干扰。建议的 CI 组合：

| 工作流 | 职责 | 失败影响 |
|--------|------|---------|
| Build & Test | 编译 + 单元测试 | 无法合并 |
| Lint Check | 代码风格检查 | 无法合并 |
| **AI Code Review** | **安全 + 逻辑审核** | **无法合并** |

### 12.3 成本控制

AI 审核是有成本的（API 调用费用）。几个控制成本的建议：

- 默认模型用 `deepseek-reasoner`，性价比最高
- `MAX_DIFF_CHARS` 不要设太大，4 万字符已经足够
- 只对 `main`/`v2` 分支触发审核，feature 分支之间互审不需要
- 开启 `concurrency` 取消重复审核，避免同一次 PR 多次推送产生多次调用

---

## 十三、快速上手清单

如果你想在**自己的项目**中快速跑起来，按照这个清单操作即可：

- [ ] 创建 GitHub App，权限设为 `Pull requests: Read & Write` + `Contents: Read-only`
- [ ] 记录 App ID，下载 PEM 私钥，安装 App 到目标仓库
- [ ] 在仓库 Secrets 中配置 `GH_APP_ID`、`GH_APP_PRIVATE_KEY`、`MR_OPENAI_API_KEY`、`MR_OPENAI_API_URL`
- [ ] 将 `.github/workflows/code-review-ci.yml` 复制到你的仓库
- [ ] 根据需要修改 `branches` 列表（默认为 `main` 和 `v2`）
- [ ] 提交一个测试 PR，等待 AI 审核结果

**最小配置只需要 4 个 Secrets**，其他全部使用默认值即可运行。

---

## 十四、总结

回顾一下我们搭建的这套 AI 代码审核系统：

| 特性 | 说明 |
|------|------|
| 触发方式 | PR 自动触发，无需人工干预 |
| 鉴权安全 | GitHub App 短期 Token，1 小时过期 |
| 模型选择 | OpenAI 兼容接口，可接任意模型 |
| 审核策略 | 必须修改 vs 建议优化，严格区分 |
| 容错设计 | Diff 获取降级、Review 降级为 Comment、私钥自动修复 |
| 成本控制 | 并发取消、Diff 截断、最小触发范围 |
| 零依赖 | 纯 Python 标准库，不需要 pip install |

这套方案的核心思想可以用一句话概括：

> **让 AI 做 Code Review 的第一道防线，而不是最后一道。**

AI 擅长快速扫描大量代码变更，发现潜在的安全漏洞和逻辑缺陷。但 AI 不能替代人工对业务逻辑的深入理解。最佳实践是：**AI 先审 → 过滤掉明显问题 → 人工再审 → 聚焦业务合理性**。

这样既提高了 Review 效率，又保证了代码质量。

如果你的项目也在用 GitHub，强烈建议试试。从零搭建到跑通，大概 **15 分钟**。而这 15 分钟的投入，可能会在某个凌晨帮你拦住一个 SQL 注入漏洞。

---

> 本文是**求职派**大模型应用开发实战系列的第 15 篇。如果你对 AI + 工程化实践感兴趣，欢迎关注后续更新。
