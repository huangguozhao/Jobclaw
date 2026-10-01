# getting-started

## JobClaw 首次启动指南

> **文档说明**: 本文档提供 JobClaw V2 版本的完整启动指引，帮助新用户快速配置和运行系统。
>
> **最后更新**: 2026-04-27

---

### 一、环境配置

#### 1.1 安装 Java 21

项目要求 **Java 21**，请先确认已安装并设为默认版本：

```bash
java -version
# 确认输出包含 "21" 版本号
```

**使用 jenv 管理多版本（推荐）**：

```bash
# 安装 Java 21
brew install openjdk@21

# 通过 jenv 注册并设为全局默认
jenv add /opt/homebrew/Cellar/openjdk@21/21.0.9/libexec/openjdk.jdk/Contents/Home
jenv global 21

# 确认 JAVA_HOME 正确指向 Java 21
echo $JAVA_HOME
# 应输出类似: /Users/<you>/.jenv/versions/21
```

> **注意**: 即使 `java -version` 显示 21，也要确认 `JAVA_HOME` 指向 21。如果 `JAVA_HOME` 仍指向旧版本，jenv 的 export 插件可能未生效。检查方式：
> ```bash
> jenv enable-plugin export   # 确保 export 插件已启用
> eval "$(jenv init -)"       # 重新加载 jenv
> echo $JAVA_HOME             # 确认输出为 21 的路径
> ```

#### 1.2 复制环境变量文件

```bash
# 将示例配置文件复制为实际配置文件
cp .env.example .env
```

#### 1.3 配置数据库（避免污染初始数据）

```bash
# 拷贝初始化数据库文件
cp workspace/datas/jobclaw.mv.db workspace/datas/jobclaw-my.mv.db

# 修改 .env 文件中的数据库名称
JOBCLAW_DATABASE_NAME=jobclaw-my
```

**说明**:
- 项目提供了一份初始化数据 `workspace/datas/jobclaw.mv.db`
- 为避免污染初始数据，建议拷贝一份并重命名
- 修改 `.env` 中的 `JOBCLAW_DATABASE_NAME` 指向新文件名

#### 1.4 配置大模型 API Key

**默认模型**: DeepSeek V4.1 Flash（文本和视觉统一使用，`deepseek#deepseek-flash`，多模态）

大模型供应商统一在后台配置，不再通过 `.env`、IDEA 启动参数或修改 `application.yml` 配置 API Key。

启动项目后进入后台 **LLM供应商** 页面，编辑内置的 `deepseek` 供应商，填入从 DeepSeek 开放平台获取的 API Key，并确认 Base URL、API 风格和模型清单后保存。文本对话和图片理解默认都走它。

如果希望使用其他供应商，也在这个页面新增或编辑供应商，再到用户侧模型偏好里选择对应模型。

#### 1.5 配置 MCP 客户端（跨平台）

项目使用 Spring AI MCP 客户端，配置文件中包含 Windows 特定命令。macOS/Linux 用户需切换配置：

```bash
# 在 .env 文件中修改 MCP_SERVERS_CONFIG
# Windows 用户保持默认：
MCP_SERVERS_CONFIG=classpath:mcp-servers.json

# macOS/Linux 用户改为：
MCP_SERVERS_CONFIG=classpath:mcp-servers-mac.json
```

**说明**：
- `mcp-servers.json` — Windows 环境的 MCP 服务配置（包含 `cmd /c` 等 Windows 命令）
- `mcp-servers-mac.json` — macOS/Linux 环境的 MCP 服务配置（默认为空，可按需添加）

---

### 二、启动项目

#### 2.1 首次构建

首次运行或代码变更后，需先构建项目：

```bash
./mvnw install -DskipTests
```

> 如果 `mvnw` 没有执行权限，先运行：`chmod +x mvnw`

#### 2.2 启动后端

```bash
./mvnw spring-boot:run -pl app
```

**说明**:
- `-pl app` 指定只运行 app 模块（Spring Boot 主应用所在模块）
- 启动成功后自动打开浏览器，访问项目主页
- 应用运行在 `http://localhost:8087`

> **macOS 用户提示**: 如果启动时报 `不支持发行版本 21` 错误，说明 `JAVA_HOME` 未指向 Java 21，请参考 [1.1 节](#11-安装-java-21) 排查。也可在启动命令中显式指定：
> ```bash
> JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run -pl app
> ```

#### 2.3 前端启动（可选）

前端基于 Next.js 15 + React 19 + TailwindCSS + shadcn/ui，静态导出后部署到 Spring Boot 的 `static/` 目录。

**环境要求**: Node.js 18+、pnpm

```bash
cd ui-react

# 安装依赖
pnpm install

# 启动开发服务器（热更新）
pnpm dev
```

前端应用将运行在 `http://localhost:3000`，修改代码后会自动刷新。

**构建并部署到后端**：

```bash
# 构建 + 清理旧文件 + 复制到 Spring Boot static 目录
pnpm run deploy
```

执行后需重启后端才能看到前端变更。或者开发时前后端同时运行（后端 8087、前端 3000），通过前端 dev server 代理 API 请求。

> **说明**: 前端默认以静态导出模式运行（`next.config.js` 中 `output: 'export'`），不支持 Next.js 服务端特性（如 API Routes、动态服务端渲染）。

---

### 三、首次登录

#### 3.1 访问系统

打开浏览器访问：`http://localhost:8087`

#### 3.2 选择登录方式

系统提供两种登录方式：

- **游客登录**: 快速体验基础功能
- **管理员登录**: 获取完整权限，可配置机器人和管理系统

---

### 四、配置 IM 渠道

进入「个人中心」→「渠道配置」，添加机器人。

#### 选项 1: 微信 ClawBot（推荐新手）

- ✅ **优点**: 
  - 成本最低
  - 配置简单
  - 无需额外注册第三方平台
- ❌ **缺点**: 
  - 不支持流式输出
  - 消息格式受限
- 📝 **配置步骤**:
  1. 在系统中直接绑定微信 ClawBot 账号
  2. 扫描二维码完成绑定
  3. 即可开始对话

#### 选项 2: 钉钉机器人

- ✅ **优点**: 
  - 支持流式输出
  - 支持富文本展示
  - 企业级稳定性
- 📝 **配置步骤**:
  1. 在钉钉开放平台创建机器人
  2. 获取 AppId 和 AppSecret
  3. 在系统中添加机器人配置
  4. **强烈建议**: 绑定钉钉账号（获得更好的个性化体验）

#### 选项 3: 飞书机器人

- ✅ **优点**: 
  - 支持流式输出
  - 支持富文本展示
  - 支持图片/文件上传
  - 现代化的交互体验
- 📝 **配置步骤**:
  1. 在飞书开放平台创建机器人
  2. 获取 AppId 和 AppSecret
  3. 在系统中添加机器人配置
  4. **强烈建议**: 绑定飞书账号（获得更好的个性化体验）

---

### 五、配置个人偏好

登录系统后，进入「个人中心」→「偏好设置」，您可以配置个人的模型偏好和 API Key。

#### 5.1 为什么需要配置个人偏好？

- **使用自己的 API Key**: 避免依赖系统默认的 API Key，更加灵活可控
- **个性化模型选择**: 根据不同任务选择合适的模型（如文本对话用 `deepseek-flash`，OCR 场景用 `PaddleOCR-VL`）
- **成本优化**: 可以混合使用不同提供商的模型，平衡性能和成本
- **隐私保护**: 使用自己的 API Key，对话数据不会经过第三方账号

#### 5.2 配置步骤

##### 步骤 1: 进入偏好设置页面

1. 登录后点击右上角头像
2. 选择「个人中心」
3. 点击左侧菜单「偏好设置」

##### 步骤 2: 添加模型提供商

在「模型提供商」区域，点击「添加提供商」：

**示例：添加硅基流动（SiliconFlow）**

- **提供商名称**: `silicon`（自定义，建议使用英文）
- **API Style**: `openai`（硅基流动兼容 OpenAI 接口）
- **Base URL**: `https://api.siliconflow.cn`
- **API Key**: 从硅基流动控制台获取
- **Completions Path**: `/v1/chat/completions`
- **Embeddings Path**: `/v1/embeddings`
- **Images Path**: `/v1/images/embeddings`

**支持的模型列表**:

| 模型名称 | 类型 | 多模态 | 说明 |
|---------|------|--------|------|
| Qwen/Qwen3-8B | TEXT | false | 文本对话模型 |
| deepseek-ai/DeepSeek-OCR | VISION | true | 视觉理解模型 |
| BAAI/bge-m3 | EMBEDDING | false | 文本向量模型 |

##### 步骤 3: 设置默认模型

在「默认模型」区域，选择您希望使用的模型：

- **文本对话模型**: 用于普通聊天、问答、任务管理等
  - 推荐：`silicon#Qwen/Qwen3-8B`
  
- **视觉理解模型**: 用于图片识别、OCR、文档解析等
  - 推荐：`silicon#deepseek-ai/DeepSeek-OCR`

##### 步骤 4: 配置消息推送渠道优先级

在「推送渠道」区域，设置后台主动推送消息时的渠道顺序：

例如：`[wechat-clawbot, dingding, feishu]`

- 系统会按此顺序尝试推送
- 如果第一个渠道失败，自动尝试下一个
- 建议将最常用的渠道放在前面

#### 5.3 常用模型提供商配置参考

##### DeepSeek

```yaml
供应商: deepseek
API Style: openai
Base URL: https://api.deepseek.com
Completions Path: /chat/completions
API Key: 在后台 LLM供应商 页面保存
```

**推荐模型**:
- 文本 + 视觉：`deepseek-flash`（DeepSeek V4.1 Flash，本项目默认模型，多模态）
- 文本：`deepseek-v4-pro`（旗舰模型）

##### 硅基流动（SiliconFlow）

```yaml
提供商名称: silicon
API Style: openai
Base URL: https://api.siliconflow.cn
API Key: 从硅基流动控制台获取
```

**推荐模型**:
- 文本：`Qwen/Qwen3-8B`（性能好，成本低）
- 视觉：`deepseek-ai/DeepSeek-OCR`（OCR 能力强）
- 向量：`BAAI/bge-m3`（中文效果好）

##### OpenAI

```yaml
提供商名称: openai
API Style: openai
Base URL: https://api.openai.com/v1
API Key: 从 OpenAI 平台获取
```

**推荐模型**:
- 文本：`gpt-4o-mini`（性价比高）
- 视觉：`gpt-4o`（最强的多模态能力）

#### 5.4 注意事项

1. **API Key 安全**:
   - 不要将 API Key 分享给他人
   - 定期轮换 API Key
   - 建议在提供商平台设置使用限额

2. **模型选择建议**:
   - 日常对话：选择速度快、成本低的模型
   - 复杂任务：选择性能更强的模型
   - 视觉任务：确保模型支持多模态

3. **成本控制**:
   - 监控 API 使用情况
   - 设置预算提醒
   - 优先使用免费额度

4. **测试验证**:
   - 配置完成后，发送一条测试消息
   - 确认模型响应正常
   - 检查是否有错误提示

---

### 六、开始对话

#### 6.1 通过机器人与 JobClaw 进行问答

1. 在对应的 IM 应用（微信/钉钉/飞书）中找到已配置的机器人
2. 发送消息给机器人
3. 系统会自动识别意图并路由到合适的 Agent
4. 接收机器人的回复

#### 6.2 首次 1v1 沟通触发信息收集

当首次与机器人进行 1v1 私聊时，系统会触发用户信息收集流程：

- **收集方式**: 一问一答的交互式对话
- **收集内容**:
  - 求职意向（目标岗位、行业偏好）
  - 专业背景（学历、专业、技能）
  - 工作经验（年限、过往经历）
  - 偏好设置（工作地点、薪资期望等）
- **用途**: 构建个性化的用户画像，用于智能推荐和精准匹配

#### 6.3 查看可用命令

在对话中发送以下命令查看系统功能：

```
/help           - 查看所有可用命令及说明
/plan           - 进入计划模式，拆解并执行复杂目标
/agents         - 查看可用的 Agent 列表及其能力
/current        - 查看当前会话绑定的 Agent
/agent <id>     - 切换到指定的 Agent
/reset          - 重置当前会话，清除上下文
```

---

### 七、常见问题

#### Q1: 启动报错 "不支持发行版本 21"

**A**: `JAVA_HOME` 指向了旧版本的 Java。即使 `java -version` 显示 21，Maven 编译使用的是 `JAVA_HOME` 指向的 JDK。

排查步骤：
1. 检查 `echo $JAVA_HOME`，确认路径包含 `21` 而非 `17` 或其他版本
2. 如果使用 jenv，确认 export 插件已启用：`jenv enable-plugin export`
3. 重新加载 shell：`eval "$(jenv init -)"` 或新开终端
4. 临时方案：启动时显式指定 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run -pl app`

#### Q2: 启动报错 "Failed to start process with command: [cmd, /c, npx...]"

**A**: MCP 客户端加载了 Windows 特定的 `mcp-servers.json`。macOS/Linux 用户需在 `.env` 中设置：

```bash
MCP_SERVERS_CONFIG=classpath:mcp-servers-mac.json
```

Windows 用户保持默认 `MCP_SERVERS_CONFIG=classpath:mcp-servers.json` 即可。

#### Q3: 为什么建议使用自己的数据库副本？

**A**: 避免在开发过程中污染初始数据，方便随时重置环境。如果测试过程中数据混乱，可以直接删除 `jobclaw-my.mv.db` 并重新拷贝一份。

#### Q4: 如何切换大模型提供商？

**A**: 有两种方式：
1. **界面配置**: 在「个人中心」→「偏好设置」中修改模型配置
2. **配置文件**: 直接修改 `application-private.yml` 中的 `agent.ai.preference` 配置

支持的提供商包括：DeepSeek、硅基流动、阿里云、OpenAI、豆包等。

#### Q5: 为什么要绑定钉钉/飞书账号？

**A**: 绑定后可以基于 JobClawUserId 构建个性化的用户偏好和会话历史。未绑定时，系统只能基于 OpenId 存储数据，功能受限且无法跨设备同步。

**绑定后的优势**:
- 个性化的用户画像管理
- 完整的会话历史记录
- 跨设备的偏好同步
- 更精准的职位推荐

#### Q6: 微信 ClawBot 为什么不支持流式输出？

**A**: 这是微信平台的限制。微信的消息接口不支持流式传输，因此无法实现打字机效果。钉钉和飞书支持更丰富的消息格式和流式输出，提供更好的用户体验。

#### Q7: 启动时遇到数据库错误怎么办？

**A**: 检查以下几点：
1. 确认已拷贝 `jobclaw.mv.db` 为 `jobclaw-my.mv.db`
2. 确认 `.env` 文件中 `JOBCLAW_DATABASE_NAME=jobclaw-my`
3. 确认数据库文件路径正确
4. 如果仍有问题，删除 `workspace/datas/` 下的所有 `.db` 文件，重新启动让系统自动创建

#### Q8: 如何查看系统日志？

**A**: 日志文件位于 `logs/` 目录：
- `logs/oc.log`: 主应用日志
- `logs/arch/`: 归档日志
- `logs/req-oc.log`: 请求日志

#### Q9: 如何重置用户配置？

**A**:
1. 删除 `workspace/users/{userId}/` 目录下的所有文件
2. 删除 `workspace/conversations/{userId}/` 目录下的会话文件
3. 重启应用或重新登录
4. 下次对话时会重新触发信息收集流程

---

### 八、下一步

完成首次启动后，您可以：

1. **探索系统功能**: 使用 `/help` 查看所有可用命令
2. **配置个人偏好**: 在「个人中心」设置您的求职偏好和模型配置
3. **体验多 Agent 协作**: 尝试不同的业务 Agent（任务管理、职位推荐等）
4. **查看研发计划**: 阅读 [plan.md](plan.md) 了解系统架构和未来规划
5. **贡献代码**: 欢迎提交 Issue 和 Pull Request

---

### 九、技术支持

如遇到问题，可以通过以下方式寻求帮助：

- 📖 查看 [plan.md](plan.md) 了解系统架构
- 🐛 提交 GitHub Issue
- 💬 在社区中提问

---

**祝您使用愉快！** 🎉
