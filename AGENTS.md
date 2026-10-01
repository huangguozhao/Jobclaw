# AGENTS.md

本文件是 JobClaw 仓库给 AI 助手使用的协作说明。后续在本仓库里做代码、文档、文章、启动排障、页面验证时，优先遵守这里的规则。

## 项目定位

JobClaw（求职派）是一个面向求职场景的 OpenClaw 风格多 Agent 实战项目。不要把它理解成单一的“岗位采集系统”，它的核心是一个可替换、可扩展的多 Agent 运行时：

- `channels/`：把微信、钉钉、飞书、WebSocket 等入口统一适配成内部消息模型。
- `core/`：负责事件总线、消息路由、意图识别、会话 Agent 绑定、Agent 注册、模型解析、记忆和共享契约。
- `agents/`：承载身份采集、岗位抓取、岗位推荐等业务 Agent。
- `providers/`：隔离 DeepSeek、OpenAI 兼容、阿里、Anthropic 等模型接入。
- `plugins/`：提供 Playwright 浏览器、职位库检索、计划笔记等工具能力。
- `app/`：组装运行时，并保留 Web 管理后台、职位数据、草稿、用户、支付、MCP 服务端等应用域。

技术栈：Java 21、Spring Boot 4.0.5、Spring AI 2.0.0-M4、Spring Modulith、LangGraph4J、JPA/Hibernate、H2/MySQL、React 19、Next.js 15、TailwindCSS、shadcn/ui。

## 协作原则

- 先读真实文件、真实日志和真实运行结果，再给结论。不要只凭印象回答 JobClaw 的启动、架构、文章内容或配置问题。
- 默认做小而明确的改动。只改用户要求范围内的内容，不顺手重构无关模块。
- 遇到已有未提交改动，先判断是否与当前任务相关；不要回滚用户改动。
- 用户只问“有没有明显错误”“有没有原则性错误”时，先给问题点、风险和建议写法，不要直接改稿。
- 如果用户明确说“不要直接改我的稿子，只告诉我哪里有问题”，后续保持 suggestion-only，直到用户明确同意修改。
- 处理中文技术文章时，保留具体技术名词和实现抓手，例如 Spring AI、LangGraph4J、ReAct、Prompt、context window、SmartWindowChatMemory、Liquibase，不要抹平成空泛表达。
- 非代码中文正文优先使用中文全角标点。只修标点或引号时，限定在正文内容，不动代码块、JSON、URL、配置键、命令和字面量。

## 常用命令

后端命令必须从仓库根目录执行，不要进入 `app/` 目录启动。

```bash
./mvnw clean package -DskipTests
./mvnw test
./mvnw test -Dtest=ExcelLoadTest
```

H2 本地开发（默认 `dev` profile）：

```bash
./mvnw spring-boot:run -pl app
```

MySQL 本地开发（`test` profile）：

```bash
./mvnw -Ptest -pl app spring-boot:run
```

如果 `-pl app` 启动时报本地模块 `0.0.1-SNAPSHOT` 找不到，例如 `jobclaw-websocket` 解析失败，先安装本仓库所有本地模块：

```bash
./mvnw -U -Ptest -DskipTests install
./mvnw -Ptest -pl app spring-boot:run
```

前端命令在 `ui-react/` 下执行：

```bash
pnpm install
pnpm dev
pnpm build
pnpm run deploy
```

不要主动执行 `pnpm run deploy`，也不要提前把静态产物复制到 `app/src/main/resources/static/`；等用户确认本地页面效果后再部署。

## 配置与启动排障

- 本地启动分 H2 和 MySQL 两条路径，数据库模式由 Maven profile 决定，不是只改 `.env`。
- H2 默认路径使用 `dev` profile，资源来自 `app/src/main/resources-env/dev`，表结构由 Hibernate `ddl-auto=update` 维护。
- MySQL 路径必须显式加 `-Ptest`，资源来自 `app/src/main/resources-env/test`，表结构由 Liquibase 维护。
- `.env` 只提供数据库名、账号、密码、端口、workspace、开关等变量；不能单独决定 H2/MySQL 模式。
- LLM Provider API Key 和 Base URL 主要在后台“LLM供应商”页面配置；不要默认要求用户把所有模型 Key 都写进 `.env`。
- 外部 IM、支付、AI Key 等属于能力开关，不是最小本地启动前置条件。
- 启动验证优先看 `http://localhost:8087/actuator/health`，再看首页 `http://localhost:8087`。
- MySQL 模式下如果只出现 JobRunr 表或业务表没建出来，优先检查 Liquibase 启动依赖、`DATABASE_LIQUIBASE_ENABLED`、`db/changelog/db.changelog-master.xml` 和 profile 资源复制，不要先归因到 `.env` 密码。

首次启动参考 `docs/getting-started.md`。H2 场景要避免污染种子数据，通常复制一份数据库：

```bash
cp workspace/datas/jobclaw.mv.db workspace/datas/jobclaw-my.mv.db
# 然后在 .env 中设置 JOBCLAW_DATABASE_NAME=jobclaw-my
```

运行时目录：

- `workspace/datas/`：H2 数据库文件。
- `workspace/users/{userId}/`：用户画像文档，例如 `soul.md`、`identity.md`、`info.md`。
- `workspace/conversations/{userId}/`：会话历史 YAML。

IM 聊天系统命令：`/help`、`/agents`、`/current`、`/agent <id>`、`/plan`、`/reset`。

## 模块边界

```text
JobClaw/
├── app/              Spring Boot 主应用，组装所有模块
├── core/             Agent/Channel 抽象、路由、事件、模型、记忆
├── channels/         微信、钉钉、飞书、WebSocket 等入口
├── providers/        大模型 Provider 接入
├── plugins/          工具插件
├── agents/           业务 Agent
├── ui-react/         Next.js 前端
└── docs/             项目文档与文章
```

- `app` 依赖所有其他模块，是装配层，不要把共享抽象和通用 Agent 逻辑塞进 `app`。
- `core` 是共享基础，channels、providers、plugins、agents 都依赖它。
- 新增或调整业务 Agent 时，优先放在 `agents/`；工具能力放在 `plugins/`；模型接入放在 `providers/`。
- 只有 Web/Admin/职位数据流水线强相关逻辑才放进 `app/`。
- Prompt 模板通常在各模块自己的 `src/main/resources/prompts/` 下，不要假设仓库根目录存在统一 `resources/prompts/`。

## 消息主链路

分析 IM 到 Agent 的问题时，优先沿这条链路读代码：

```text
IM Message
  -> AbsChannel.processMessage()
  -> adaptToReceive()
  -> ChannelReceiveMessage
  -> ChannelEventPublisher.publishMessageReceived()
  -> MessageReceivedEvent
  -> MsgRouter.onMessageReceived()
  -> IIdentityAgent.triggerToCollectIdentity()
  -> SystemCommandDispatcher
  -> SessionAgentBinder.needsIntentRecognition()
  -> IntentClassifier.classify()
  -> AgentRouter.route()
  -> SessionAgentBinder.bind()
  -> BizAgent.process()/stream()
  -> ChannelEventPublisher.publishMessageResponse()
  -> MessageResponseEvent
  -> Channel.responseToUser()
```

核心抽象：

| 抽象 | 路径 | 作用 |
|---|---|---|
| `Channel` | `core/channel/Channel.java` | IM 渠道接口 |
| `AbsChannel` | `core/channel/AbsChannel.java` | 渠道基类，负责心跳、事件发布、自动启动 |
| `BizAgent` | `core/agent/BizAgent.java` | 业务 Agent 接口 |
| `AbsBizAgent` | `core/agent/impl/AbsBizAgent.java` | Agent 基类，封装 LLM 调用和记忆 |
| `AgentRegistry` | `core/router/intent/AgentRegistry.java` | Spring Agent 发现与注册 |
| `AgentRouter` | `core/router/intent/AgentRouter.java` | 意图到 Agent 的路由 |
| `IntentClassifier` | `core/router/intent/IntentClassifier.java` | 用户消息意图识别 |
| `ModelProviders` | `core/providers/ModelProviders.java` | 按用户偏好解析模型 |
| `LlmCaller` | `core/agent/llm/LlmCaller.java` | 同步和流式 LLM 调用抽象 |
| `ChannelEventPublisher` | `core/bus/ChannelEventPublisher.java` | Channel 与 Agent 解耦事件总线 |

## 模型与用户画像

- 模型通过 `ModelProviders.getModel(userId, modelType)` 按用户解析。
- 用户偏好格式为 `provider#modelName`，例如 `deepseek#deepseek-flash`。
- Provider 配置位于 `agent.ai.providers`，每个 Provider 模块实现 `ModelProvider` 并声明对应 `apiStyle`。
- 普通工作台用户不应该被迫手选模型；终端或 IM 会话可以通过命令或用户偏好切换模型。

身份采集 Agent 维护三层用户画像：

- Soul：个性、价值观、职业偏好，通常由 AI 提炼。
- Identity：技能、教育、经历，规则和 AI 采集结合。
- Info：基础人口学或渠道元信息，从用户 Agent/聊天元数据提取。

画像不完整时，`MsgRouter` 会通过 `IIdentityAgent.triggerToCollectIdentity()` 自动触发采集。

## App 业务域

`app/src/main/java/com/git/hui/jobclaw/` 下主要业务域：

- `agents/`：LangGraph4J 职位数据工作流，例如 TaskClassify、TaskGather、DraftWasher、DraftPublish。
- `gather/`：AI 职位采集流水线，例如 GatherAiAgent、OfferGatherService。
- `oc/`：职位信息增删改查、草稿管理、MCP 服务端点。
- `user/`：用户、微信登录、会员、支付。
- `configs/`：全局字典、环境配置。
- `openapi/`：跨平台账号集成。
- `constants/`：各业务域枚举。
- `components/`：AsyncUtil、ID 生成、`.env` 处理等横切组件。

常见分层：`dao/entity/` -> `dao/repository/` -> `service/` -> `convert/`。Service 默认按现有风格直接实现，不为了形式新增接口。

## 后端编码规则

- 使用 Java 21 能力，但不要为了炫技牺牲可读性。
- Agent Bean 使用 `@Component` 并实现 `BizAgent`，通过 `AgentRegistry` 自动注册。
- 后端调用大模型优先使用 `LlmCaller` 或 `BizAgentLlmCaller`，不要绕开项目封装直接散落 Spring AI 原生调用。
- 记忆能力优先沿用 `SmartWindowChatMemory` 及 `agent.context.window.*` 配置。
- Lombok、Hutool 是现有常用依赖，可以按项目风格使用。
- 复杂逻辑、重要业务规则、潜在缺陷或超过 30 行的 AI 生成代码，需要添加简短锚点注释。

锚点注释规则：

- AI 生成代码使用 `AI-GENERATED` 标记。
- 可用前缀：`AIDEV-NOTE:`、`AIDEV-TODO:`、`AIDEV-QUESTION:`。
- 注释要短，尽量 50 字以内。
- 不删除已有 `AIDEV-NOTE`，修改相关代码时同步更新锚点。

## 前端规则

- 前端位于 `ui-react/`，是静态导出的 Next.js 应用，部署到 Spring Boot `static/` 目录。
- 用户通常已经运行 `pnpm dev`。除非用户要求或确认服务不可用，不要主动重启前端开发服务。
- 前端源码改动后优先验证 `http://localhost:3000`，Next.js 热更新会直接反映 `ui-react` 源码。
- 使用函数组件和 Hooks，不写类组件。
- Props 使用 `interface XxxProps`，避免 `any`。
- State 使用 `useState<T>` 显式类型。
- 避免 `enum`，优先使用联合类型和 `as const`。
- 样式使用 TailwindCSS 和 `cn()`，保持现有 shadcn/ui 风格。
- 做 UI 改动时打开真实页面检查布局和交互，不要只看 TypeScript 编译结论。

## 文档与文章工作流

- 文章和教程工作以当前 Markdown 源文件为准，配合 Markdown Preview 或浏览器渲染结果检查，不要只在对话里生成一段“看起来完成”的文字。
- `sucai.md` 或客户简报是角度和覆盖要求，不是事实来源。事实判断要回到代码、官方文档、真实页面或可验证资料。
- 如果是文章审查，先列出具体问题行、为什么有风险、建议改法；只有用户要求时再落地修改。
- 如果是配图、截图或封面相关任务，要检查 `docs/imgs/`、页面预览和文章实际插入位置，避免只补文字不看最终效果。
- 文件名级别的“修引号/标点/润一下”默认是窄范围正文清理，不做结构重写，不改代码块和命令。
- 解释 JobClaw 或 AI Agent 概念时，保留 CoT、ReAct、Prompt、context management 等具体机制，不要写成泛泛的 AI 鸡汤。

## 简历和项目包装

- 写 JobClaw 到简历时，优先说明“用了什么技术栈、解决了什么问题、实现了什么业务、有哪些量化数据”。
- 可保留对外可识别技术栈：Java 21、Spring Boot 4、Spring AI、LangGraph4J、Reactor、YAML、H2/MySQL、WebSocket、Liquibase。
- 最终简历文案避免暴露内部自定义类名；内部类名可以作为核验依据，但不要直接塞进面向招聘者的表达。
- “智能上下文管理”“三级 LLM 调用链”“长对话摘要压缩”“身份文档注入”是 JobClaw 的有效亮点，包装时不要淹没在普通功能清单里。

## 验证标准

- 后端代码改动至少跑相关模块编译或定向测试。
- 启动类、配置、数据库变更要用实际启动或健康检查验证。
- MySQL/Liquibase 相关改动要验证 changelog 执行结果和业务表，不只看应用进程是否起来。
- 前端改动要用浏览器验证实际页面状态；只改样式时也要检查按钮、表格、弹窗和长文本是否溢出。
- 文档改动要读 diff，并尽量检查渲染后的段落是否通顺、命令是否仍可复制。
