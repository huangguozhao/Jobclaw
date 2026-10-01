# CLAUDE.md

本文件为 Claude Code（claude.ai/code）在本仓库中工作时提供指导。

## 项目概述

求职派（JobClaw）是一个 OpenClaw 风格的多 Agent 求职系统。IM 渠道（微信/钉钉/飞书）接入共享的 Agent 内核，消息经过意图分类后路由到可插拔的业务 Agent（身份采集、岗位检索、岗位推荐）。`app` 模块负责组装所有模块，并承载管理后台、岗位数据流水线、支付和 MCP 服务。

**语言**：中文优先的代码库。注释、提交信息、文档和界面文案以中文为主。

## 常用命令

### 后端

```bash
./mvnw install -DskipTests                   # 首次构建（所有模块）
./mvnw spring-boot:run -pl app               # 启动开发服务器（H2，端口 8087）
./mvnw clean package -DskipTests             # 打包所有模块
./mvnw test                                  # 运行所有测试
./mvnw test -pl app -Dtest=ExcelLoadTest     # 运行单个测试类
```

### 前端（在 ui-react/ 目录下执行）

```bash
pnpm install        # 安装依赖
pnpm dev            # 开发服务器，端口 3000
pnpm build          # 静态导出到 out/
pnpm run deploy     # 构建并复制静态资源到 app/src/main/resources/static/
```

### 首次启动

```bash
cp .env.example .env                                              # 创建环境配置
cp workspace/datas/jobclaw.mv.db workspace/datas/jobclaw-my.mv.db # 复制种子数据库
# 编辑 .env：设置 JOBCLAW_DATABASE_NAME=jobclaw-my；模型 API Key 在管理后台配置
# macOS：设置 MCP_SERVERS_CONFIG=classpath:mcp-servers-mac.json
./mvnw install -DskipTests && ./mvnw spring-boot:run -pl app
```

## 架构

### 模块依赖

```
app ──► core（共享基础层）
    ──► channels/{wechat-clawbot, dingding, feishu}  ──► core
    ──► providers/{openai, anthropic, ali}             ──► core
    ──► plugins/{playwright, job-library}             ──► core
    ──► agents/{identity-collector, job-fetch, job-recommend} ──► core
```

新的业务 Agent 放在 `agents/` 下，不要放到 `app/` 里，除非它完全属于管理后台的岗位数据流水线。

### 消息流转（核心流水线）

```
IM 消息 → Channel.adaptToReceive() → ChannelReceiveMessage
  → ChannelEventPublisher（Spring ApplicationEvent）
  → MsgRouter.onMessageReceived():
      1. IIdentityAgent.triggerToCollectIdentity()   （自动采集用户画像）
      2. SystemCommandDispatcher                      （/help, /agent, /reset）
      3. SessionAgentBinder.needsIntentRecognition()
      4. IntentClassifier.classify()                  （关键词 + LLM 复合分类）
      5. AgentRouter.route()                          （意图 → Agent）
      6. SessionAgentBinder.bind()
      7. BizAgent.process()/stream()
  → ChannelEventPublisher.publishMessageResponse()
  → Channel.responseToUser()
```

### 模型解析

通过 `ModelProviders.getModel(userId, modelType)` 按用户解析模型。偏好格式为 `provider#modelName`（如 `deepseek#deepseek-flash`）。供应商配置在 `application.yml` 的 `agent.ai.providers` 下维护。每个供应商模块实现 `ModelProvider` 接口，通过 `apiStyle` 标识协议风格。

### App 模块业务域

`app/src/main/java/com/git/hui/jobclaw/`：
- `agents/` — LangGraph4J 岗位数据工作流（TaskClassify → TaskGather → DraftWasher → DraftPublish）
- `gather/` — AI 驱动的岗位数据采集（GatherAiAgent、OfferGatherService）
- `oc/` — 岗位信息增删改查、草稿管理、MCP 服务端点
- `user/` — 用户管理、微信扫码登录、会员、支付
- 分层模式：`dao/entity/` → `dao/repository/` → `service/` → `convert/`（BO/Entity 映射）

### 身份系统（三层用户画像）

- **Soul**（`workspace/users/{id}/soul/`）— 性格特征、职业愿景（AI 提取）
- **Identity**（`workspace/users/{id}/user/`）— 技能、教育、工作经历
- **Info**（`workspace/users/{id}/info/`）— 基本人口信息

当用户画像不完整时，由 `MsgRouter` 中的 `IIdentityAgent.triggerToCollectIdentity()` 自动触发采集。

### 运行时数据路径

- `workspace/datas/` — H2 数据库文件
- `workspace/users/{userId}/` — 用户画像（soul.md、identity.md、info.md）
- `workspace/conversations/{userId}/` — 聊天会话历史（YAML）
- `logs/oc.log` — 主应用日志

## 技术栈

- Java 21（record、模式匹配、虚拟线程）
- Spring Boot 4.0.5、Spring AI 2.0.0-M4、Spring Modulith
- LangGraph4J 1.6.0-rc4（`app/agents/` 中的多 Agent 工作流）
- JPA/Hibernate，开发环境用 H2，测试/生产用 MySQL + Liquibase
- JobRunr 后台任务调度（管理面板端口 8099）
- React 19 / Next.js 15（静态导出）/ TailwindCSS / shadcn/ui

## 配置

运行时配置来源于 `.env`（不提交到仓库）或管理后台的全局配置。要点：
- LLM 供应商的 API Key 和 Base URL 在管理后台的「LLM 供应商」页面配置，不通过环境变量覆盖
- `JOBCLAW_DATABASE_NAME` — H2 数据库文件名（默认：`jobclaw`）
- `AGENT_AI_PREFERENCE_TEXT` / `AGENT_AI_PREFERENCE_VISION` — 可选的默认模型覆盖
- `MCP_SERVERS_CONFIG` — `classpath:mcp-servers.json`（Windows）或 `classpath:mcp-servers-mac.json`（macOS/Linux）

Maven Profile：`dev`（默认，H2）、`test`（MySQL + Liquibase）、`prod`（MySQL + Liquibase）。环境相关配置在 `app/src/main/resources-env/{dev,test,prod}/` 下。

本地覆盖配置：`application-private.yml`（已加入 .gitignore，自动导入）。

## 当前模型版本（2026 年 7 月）

写文章、文档和注释时引用模型名称，必须使用当前版本，不能用旧版本号。

### 各供应商最新主力模型

- **Anthropic**：Opus 5（Claude 3 / Claude 3.5 已过时）
- **OpenAI**：GPT-5.6（GPT-4o / GPT-4 已过时）
- **DeepSeek**：DeepSeek V4.1 Flash（本项目默认模型，文本 + 多模态，配置 id `deepseek-flash`）/ DeepSeek V4 Pro（旗舰文本模型，配置 id `deepseek-v4-pro`）
- **通义千问（阿里）**：最新一代 Qwen 3.7 系列——旗舰 qwen3.7-max、均衡 qwen3.7-plus、轻量 qwen3.7-flash；多模态推荐 qwen3.5-omni-flash。百炼（bailian.console.aliyun.com）是阿里的 MaaS 平台，不是模型品牌；模型品牌是通义千问（Qwen）。Spring AI 配置键 `spring.ai.dashscope` 对应百炼平台的 SDK（DashScope）

### 本项目配置的模型

- 文本 + 视觉默认：`deepseek-flash`（DeepSeek V4.1 Flash，`deepseek#deepseek-flash`，多模态）
- 旗舰文本：`deepseek-v4-pro`（`deepseek#deepseek-v4-pro`）
- 其他内置供应商：`silicon`（硅基流动，VISION/IMAGE/EMBEDDING/ASR）、`ali`（阿里百炼 DashScope）、`step`
- 通义千问文本：`qwen-plus`（传统别名，指向 Qwen 2.5 时代模型，推荐升级到 `qwen3.7-plus`）
- 通义千问多模态：`qwen-omni-turbo`（已停止更新，推荐升级到 `qwen3.5-omni-flash`）
- 已下线：智谱（zhipu）的 provider 模块、starter 依赖与供应商配置均已从仓库移除

### 文章引用规则

正文中泛指"当前主流模型"时，必须用上面的最新版本号。引用项目配置中的具体模型名时如实写。

## 编码规范

### 后端（Java）

- Agent 注册：实现 `BizAgent` 接口并标注 `@Component`，由 `AgentRegistry` 自动发现
- LLM 调用：使用 `LlmCaller` 或 `BizAgentLlmCaller`，不要直接使用 Spring AI 的原始客户端 API
- 记忆管理：`SmartWindowChatMemory`，上下文窗口通过 `agent.context.window.*` 配置
- 全局使用 Lombok（`@Data`、`@Slf4j`、`@Builder`）
- 可使用 Hutool（`cn.hutool`）工具库
- Service 类不单独抽接口，直接实现
- 术语规范：Java 的 constructor 在中文文档和注释中统一使用"构造方法"，不使用"构造器"
- 术语规范：模型的 provider 统一使用"供应商"，不使用"提供商"

### 前端（TypeScript/React）

- 只用函数组件 + Hooks
- 组件 Props 用 `interface XxxProps`，不使用 `any`
- `useState<T>` 显式指定泛型
- 用联合类型 + `as const` 代替 `enum`
- 样式：TailwindCSS 配合 `cn()`（clsx + tailwind-merge）

### 锚点注释

- AI 生成的代码标注 `AI-GENERATED`
- 使用前缀：`AIDEV-NOTE:`、`AIDEV-TODO:`、`AIDEV-QUESTION:`
- 不要删除已有的 `AIDEV-NOTE` 注释
