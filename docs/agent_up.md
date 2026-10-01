# JobClaw Agent 能力升级方案

> 参照 [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) 核心实现，结合 JobClaw 当前架构制定的渐进式改进计划。

---

## 一、现状分析

### 1.1 JobClaw 当前 Agent 架构

```
IM Message → AbsChannel → ChannelEventPublisher → MsgRouter
  → IIdentityAgent (身份采集)
  → SystemCommandDispatcher (/help, /agent, /reset)
  → SessionAgentBinder (会话绑定)
  → IntentClassifier (意图分类)
  → AgentRouter (路由)
  → BizAgent.process() / stream()
  → ChannelEventPublisher → Channel.responseToUser()
```

**核心抽象：**
- `BizAgent` 接口：定义 `process()` / `stream()` / `getAgentIntro()`
- `AbsBizAgent`：持有 `ModelProviders`、`ChatMemory`、`BizAgentLlmCaller`
- `SmartWindowChatMemory`：基于 token 计数 + 消息数量 + 短消息过滤的窗口管理
- `MsgRouter`：消息路由中枢，串联身份采集 → 命令分发 → 意图识别 → Agent 路由

**当前不足：**
1. ~~Agent 内部没有推理-行动循环，工具调用为一次性 Fire-and-Forget~~ ✅ Phase 1: ReActAdvisor 已实现 ReAct 循环
2. ~~记忆管理仅有滑动窗口，缺少摘要压缩和长期记忆~~ ✅ Phase 3: 三层记忆架构已实现（Working + Episodic + Long-term）
3. ~~Agent 生命周期无拦截机制，无法注入横切关注点（日志、审计、限流）~~ ✅ Phase 2: ReActMiddleware 已实现生命周期 hooks
4. 无结构化任务分解能力，复杂任务只能由单一 Agent 一口气完成
5. Agent 能力无法沉淀复用，每次对话结束后经验丢失
6. 缺少 Human-in-the-Loop 控制，无法对敏感操作进行人工确认

### 1.2 AgentScope Java 核心设计提炼

| 模块 | 核心思想 | JobClaw 差距 |
|------|---------|-------------|
| **ReAct Loop** | reasoning → acting → reasoning 迭代循环，直到任务完成或达到上限 | ✅ 已实现：ReActAdvisor (Advisor 模式，collectList + runReactLoop) |
| **Middleware** | 洋葱模型拦截 Agent/Reasoning/Acting/ModelCall 四个阶段 | ✅ 已实现：ReActMiddleware (观察者模式 hooks，可扩展为洋葱模型) |
| **AgentState** | 统一的 `AgentState` 对象持有 context、toolContext、permissionContext，通过 Session 持久化 | 状态分散在 ChatMemory + SessionAgentBinder |
| **Memory 分层** | 短期 Memory (InMemory) + 长期 LongTermMemory (语义检索) + 记忆压缩 | ✅ 已实现：SmartWindow 压缩 + EpisodicMemory + IIdentityAgent 长期记忆 |
| **PlanNotebook** | 结构化任务分解为 SubTask，状态追踪 todo/in_progress/done | ✅ 已实现：AutoDiscoveredTool 插件 + 用户隔离持久化 |
| **SkillBox** | Markdown 技能文件 + 动态加载/卸载 + 工具组联动 | 无能力沉淀 |
| **Permission & HITL** | PermissionEngine + ConfirmResult 机制实现工具级权限控制和人工确认 | 无确认机制 |
| **Structured Output** | 自纠正输出解析，失败时自动重试并引导模型 | 无结构化输出 |
| **Graceful Shutdown** | 中断保护 + 状态保存 + 恢复执行 | 无中断保护 |

---

## 二、升级方案总览

### 阶段路线图

```
Phase 1: ReAct 推理循环 ✅ (核心引擎升级 — ReActAdvisor)
  ↓
Phase 2: ReActMiddleware 生命周期拦截 ✅ (观察者模式 hooks)
  ↓
Phase 3: 记忆压缩与分层管理 ✅ (上下文增强)
  ↓
Phase 4: PlanNotebook 任务分解 (复杂任务编排)
  ↓
Phase 5: Skill 能力沉淀 (经验复用)
  ↓
Phase 6: HITL 人工确认 + 权限控制 (生产安全)
```

---

## 三、Phase 1：ReAct 推理循环 ✅ 已完成

### 3.1 设计目标

将 JobClaw 当前"单次 LLM 调用 + 工具调用"模式升级为 ReAct（Reasoning-Acting）迭代循环。Agent 在每轮中：
1. **Reasoning**：基于上下文和工具 schema 调用 LLM，产出思考和工具调用计划
2. **Acting**：执行工具调用，收集结果
3. **判断**：是否完成（无工具调用）、达到最大迭代次数、或需要继续推理

### 3.2 核心设计

#### 3.2.1 ReActAdvisor — 基于 Spring AI Advisor 的 ReAct 实现

**方案选型**：未采用原计划的 `ReActBizAgent` 子类方案，而是参照 Spring AI 内置的 `ToolCallAdvisor`，实现了一个自定义的 `ReActAdvisor`（同时实现 `CallAdvisor` + `StreamAdvisor`）。这样做的优势：

- **零侵入**：现有 Agent 无需改动，只需在 `ChatClient` 构建时注册 `ReActAdvisor` 即可
- **Advisor Chain 融合**：与 `MessageChatMemoryAdvisor`、`SimpleLoggerAdvisor` 等 Advisor 自然协作
- **Spring AI 原生**：直接操作 `ChatClientRequest` / `ChatClientResponse`，无需额外抽象层

```
核心文件：core/agent/react/ReActAdvisor.java
```

**ReActAdvisor 执行流程：**

```
ChatClient.prompt(...).stream()
  │
  ▼
Advisor Chain: [ReActAdvisor → SimpleLoggerAdvisor → MessageChatMemoryAdvisor]
  │
  ▼
ReActAdvisor.adviseStream(request, chain)
  │
  ├─ Step 1: chain.nextStream(request).collectList()    ← 第一次推理走 Advisor Chain（保留 Memory 等效果）
  │
  ├─ Step 2: aggregateChunks(chunks)                    ← 手动聚合流式 chunks（不能用 ChatClientMessageAggregator）
  │           → 合并文本、toolCalls、metadata
  │
  ├─ Step 3: 检查 toolCalls
  │           ├── 空 → 直接发射原始 chunks（无工具调用 = 完成）
  │           └── 非空 → 进入 ReAct 循环
  │
  ├─ Step 4: runReactLoop(messages, toolCalls, request)  ← 同步循环（工具调用本质是阻塞的）
  │           for (iter = 1; iter < maxIterations; iter++):
  │             ├── executeTools(toolCalls, request)      ← 手动执行工具
  │             ├── messages.add(toolResponses)           ← 工具结果写入上下文
  │             ├── callModelDirect(messages, request)    ← 直接调用 ChatModel（不经过 Advisor Chain）
  │             └── 检查新 toolCalls → 继续循环 or 返回最终结果
  │
  └─ Step 5: 发射 [原始chunks + toolResultChunk + finalResponseChunk]
```

#### 3.2.2 关键设计决策

| 决策点 | 选型 | 原因 |
|--------|------|------|
| 实现方式 | Advisor（非 BizAgent 子类） | 零侵入，现有 Agent 无需改动 |
| 第一次推理 | 走 Advisor Chain | 保留 Memory、Logger 等前置 Advisor 效果 |
| 后续迭代 | 直接调用 ChatModel | 避免重复触发 Memory 写入，减少开销 |
| 流式聚合 | 手动 `collectList()` + `aggregateChunks()` | `ChatClientMessageAggregator` 是 pass-through，`blockFirst()` 只返回第一个原始 chunk |
| 工具执行 | `ToolCallback.call(args, toolContext)` 手动调用 | 参照 `DefaultToolCallingManager`，始终传入 ToolContext |
| 工具结果传递 | 合成 `ChatClientResponse` + `toolResult` 元数据标记 | 使其流经 `LlmRspCell::of` 被下游正确识别 |
| 工具禁用 | `internalToolExecutionEnabled(false)` | 防止 Spring AI 自动执行工具，由 ReActAdvisor 自行控制 |

#### 3.2.3 工具执行 — 参照 DefaultToolCallingManager

```java
// 参照 DefaultToolCallingManager.buildToolContext()
private ToolContext buildToolContext(Prompt prompt) {
    Map<String, Object> toolContextMap = Map.of();
    ChatOptions options = prompt.getOptions();
    if (options instanceof ToolCallingChatOptions tco) {
        if (!CollectionUtils.isEmpty(tco.getToolContext())) {
            toolContextMap = new HashMap<>(tco.getToolContext());
        }
    }
    return new ToolContext(toolContextMap);
}

// 始终传入 ToolContext，确保工具可访问上下文信息
String result = callback.call(toolArgs, toolContext);
```

#### 3.2.4 与 BizAgentLlmCaller 的集成

```java
// BizAgentLlmCaller.getClient() 中
var reactBuilder = ReActAdvisor.builder()
    .chatModel(chatModel)
    .autoInjectMiddleware();  // 自动从 Spring 容器注入 ReActMiddleware

var builder = ChatClient.builder(chatModel)
    .defaultOptions(
        ToolCallingChatOptions.builder()
            .internalToolExecutionEnabled(false)  // 禁用 Spring AI 自动工具执行
            .build()
    )
    .defaultAdvisors(
        reactBuilder.build(),                            // ReActAdvisor (order=100)
        SimpleLoggerAdvisor.builder().build(),           // 日志
        MessageChatMemoryAdvisor.builder(chatMemory).build()  // 记忆
    );
```

**Advisor 优先级**：`ReActAdvisor(order=100)` 最先处理请求、最后处理响应，确保 Memory 在 ReAct 循环前完成上下文注入。

#### 3.2.5 流式响应与 IM 渠道展示

`LlmRspCell` 新增了 `tool` 和 `toolResult` 字段，IM 渠道（DingDing/FeiShu）已适配展示：

```java
// LlmRspCell record
public record LlmRspCell(String thinking, String content, String tool, String toolResult) { ... }

// 钉钉渠道：工具信息拼接到卡片正文前部展示
// 飞书渠道：通过 TOOL_REQ / TOOL_RSP elementId 独立更新卡片区域
```

### 3.3 落地文件清单

| 文件 | 状态 | 说明 |
|------|------|------|
| `core/agent/react/ReActAdvisor.java` | ✅ 已完成 | ReAct 循环核心 Advisor |
| `core/agent/react/ReActMiddleware.java` | ✅ 已完成 | 中间件生命周期接口 |
| `core/agent/react/LoggingReActMiddleware.java` | ✅ 已完成 | 日志中间件实现 |
| `core/agent/models/LlmRspCell.java` | ✅ 已修改 | 新增 tool/toolResult 字段 |
| `core/agent/llm/BizAgentLlmCaller.java` | ✅ 已修改 | 集成 ReActAdvisor + Middleware |
| `channels/dingding/DingDingBotChannel.java` | ✅ 已修改 | 展示工具调用和执行结果 |
| `channels/feishu/FeiShuBotChannel.java` | ✅ 已修改 | 展示工具调用和执行结果 |

---

## 四、Phase 2：ReActMiddleware 生命周期拦截 ✅ 已完成

### 4.1 设计目标

为 ReAct 推理循环引入生命周期拦截机制，覆盖 Reasoning 和 Acting 两个核心阶段，支持日志记录、审计追踪、工具审批、限流等横切关注点。

### 4.2 核心设计

#### 4.2.1 ReActMiddleware 接口 — 观察者模式

**方案选型**：未采用原计划的洋葱模型（`Function<I, O>` 链式拦截），而是采用更简洁的**观察者模式**（lifecycle hooks）。所有方法提供默认空实现，按需覆写即可。

```java
// core/agent/react/ReActMiddleware.java
public interface ReActMiddleware {

    /** Reasoning 阶段前：LLM 即将进行推理 */
    default void beforeReasoning(List<Message> messages, int iter) {}

    /** Reasoning 阶段后：LLM 完成一次推理 */
    default void afterReasoning(AssistantMessage assistantMessage, int iter) {}

    /** Acting 阶段前：即将执行工具 */
    default void beforeActing(List<AssistantMessage.ToolCall> toolCalls, int iter) {}

    /** Acting 阶段后：工具执行完毕 */
    default void afterActing(ToolResponseMessage toolResponses, int iter) {}

    /** 循环结束时：ReAct 推理循环完成 */
    default void onComplete(int totalIters, String finalResponse) {}

    /** 异常时：循环中发生错误 */
    default void onError(Exception error, int iter) {}
}
```

**洋葱模型 vs 观察者模式对比：**

| 维度 | 洋葱模型 (原计划) | 观察者模式 (实际采用) |
|------|------------------|---------------------|
| 复杂度 | 高（需要 MiddlewareChain 构建器） | 低（接口 + 默认方法） |
| 拦截能力 | 可修改输入/输出、短路返回 | 仅观察，不修改执行流 |
| 适用场景 | 需要转换数据流的场景 | 日志、监控、审计等旁路关注点 |
| Spring 集成 | 需要自定义 Chain 构建 | `@Component` 注册，`autoInjectMiddleware()` 自动注入 |

**选择原因**：当前阶段的核心需求是日志和监控，不需要修改执行流。观察者模式更简单、更容易理解和调试。未来如需洋葱模型能力，可在 `ReActMiddleware` 基础上扩展。

#### 4.2.2 Middleware 自动注入

ReActAdvisor 通过 Builder 的 `autoInjectMiddleware()` 方法自动从 Spring 容器发现并注入所有 `ReActMiddleware` Bean：

```java
// ReActAdvisor.Builder
public Builder autoInjectMiddleware() {
    var middlewareBeans = SpringUtil.getContext().getBeansOfType(ReActMiddleware.class);
    middlewareBeans.values().forEach(this::addMiddleware);
    return this;
}
```

#### 4.2.3 内置 Middleware — LoggingReActMiddleware

```java
// core/agent/react/LoggingReActMiddleware.java
@Component
public class LoggingReActMiddleware implements ReActMiddleware {

    @Override
    public void beforeReasoning(List<Message> messages, int iter) {
        log.info("[ReAct-{}] Reasoning阶段开始，当前消息数: {}", iter, messages.size());
    }

    @Override
    public void afterReasoning(AssistantMessage msg, int iter) {
        log.info("[ReAct-{}] 推理结果文本: {}", iter, msg.getText());
        // 记录工具调用详情：ID、名称、参数
    }

    @Override
    public void afterActing(ToolResponseMessage toolResponses, int iter) {
        // 记录工具执行结果
    }

    @Override
    public void onComplete(int totalIters, String finalResponse) {
        log.info("[ReAct] 推理循环完成，总迭代次数: {}", totalIters);
    }
}
```

#### 4.2.4 扩展 Middleware 规划

| Middleware | 用途 | 状态 | 对应 AgentScope |
|-----------|------|------|----------------|
| `LoggingReActMiddleware` | 每轮推理和工具调用的日志 | ✅ 已完成 | - |
| `MemoryReActMiddleware` | 情景记忆注入与记录 | ✅ 已完成 (Phase 3) | `LongTermMemory` |
| `TokenCountMiddleware` | Token 使用量统计 | 待实现 | - |
| `RateLimitMiddleware` | 推理调用限流 | 待实现 | - |
| `AuditMiddleware` | 工具调用审计追踪 | 待实现 | - |
| `PermissionMiddleware` | 工具调用审批 / 人工确认 | 待实现 (Phase 6) | `PermissionEngine` |
| `TaskReminderMiddleware` | 注入 PlanNotebook 进度提示 | 待实现 (Phase 4) | `TaskReminderMiddleware` |

### 4.3 与 MsgRouter 的集成

当前 `ReActMiddleware` 在 ReActAdvisor 内部触发，无需 MsgRouter 层面的改动。Middleware 通过 Spring `@Component` 注册后，由 `autoInjectMiddleware()` 自动注入到 ReActAdvisor 中。

### 4.4 落地文件清单

| 文件 | 状态 | 说明 |
|------|------|------|
| `core/agent/react/ReActMiddleware.java` | ✅ 已完成 | 生命周期钩子接口 |
| `core/agent/react/LoggingReActMiddleware.java` | ✅ 已完成 | 日志中间件 `@Component` |

---

## 五、Phase 3：记忆压缩与分层管理 ✅ 已完成

### 5.1 设计目标

将 JobClaw 当前的 `SmartWindowChatMemory`（纯滑动窗口）升级为三层记忆架构：

```
┌─────────────────────────────────┐
│       Working Memory            │  ← 当前对话窗口（SmartWindow 升级）
│   短期上下文，滑动窗口 + 压缩    │
├─────────────────────────────────┤
│       Episodic Memory           │  ← 会话级记忆（新增）
│   对话摘要、关键决策、工具结果    │
├─────────────────────────────────┤
│       Long-term Memory          │  ← 跨会话持久记忆（新增）
│   用户偏好、技能画像、经验知识    │
└─────────────────────────────────┘
```

### 5.2 核心设计

#### 5.2.1 Working Memory 增强 — 摘要压缩

参照 AgentScope 的 `InMemoryMemory` + 摘要机制，在窗口管理中引入"先压缩后丢弃"策略：

```java
// core/agent/memory/CompressingWindowMemory.java
public class CompressingWindowMemory {

    private final SmartWindowChatMemory windowMemory;   // 现有窗口管理
    private final LlmCaller summarizer;                  // 摘要 LLM

    /**
     * 升级策略：
     * 1. 当消息数超过 softLimit（如 20 条），对最早的 N 条进行摘要压缩
     * 2. 将摘要作为 system message 注入后续对话
     * 3. 只在摘要也放不下时，才执行硬截断
     */
    public List<Message> manage(List<Message> messages) {
        if (messages.size() <= softLimit) {
            return messages;
        }

        // Step 1: 对早期消息进行摘要压缩
        List<Message> earlyMessages = messages.subList(0, messages.size() - keepRecent);
        String summary = summarize(earlyMessages);

        // Step 2: 构建压缩后的消息列表
        List<Message> result = new ArrayList<>();
        result.add(Message.system("[历史对话摘要]\n" + summary));
        result.addAll(messages.subList(messages.size() - keepRecent, messages.size()));
        return result;
    }

    private String summarize(List<Message> messages) {
        String prompt = "请将以下对话内容压缩为简洁的摘要，保留关键信息、决策和结论：\n"
            + formatMessages(messages);
        return summarizer.call(prompt);
    }
}
```

**对比现状：** 当前 `SmartWindowChatMemory` 只做 token 截断和短消息过滤，信息丢失严重。引入摘要压缩后，关键上下文得以保留。

#### 5.2.2 Episodic Memory — 会话级记忆

参照 AgentScope 的 `LongTermMemory.record()` / `retrieve()` 模式：

```java
// core/agent/memory/EpisodicMemory.java
public interface EpisodicMemory {

    /**
     * 记录本次对话的关键信息（对话结束后自动调用）
     * 参照 AgentScope LongTermMemory.record()
     */
    Mono<Void> record(String userId, List<Message> conversation);

    /**
     * 检索与当前查询相关的历史记忆（推理前自动调用）
     * 参照 AgentScope LongTermMemory.retrieve()
     */
    Mono<String> retrieve(String userId, Message query);
}
```

**实现方案：**
- 基于 H2/MySQL 存储，每次对话结束后提取关键信息（意图、结论、工具结果）
- 使用 Embedding 做语义检索，或简单的关键词匹配作为起步
- 集成方式：作为 Middleware 在 `onAgent` 阶段自动 record/retrieve

#### 5.2.3 Long-term Memory — 跨会话持久记忆

这是 JobClaw 已有的 `identity-collector-agent`（soul.md / user.md / info.md）的自然延伸：

```java
// core/agent/memory/LongTermMemoryService.java
public class LongTermMemoryService {

    /**
     * 从对话中自动提取并更新长期记忆
     * - 用户偏好变化
     * - 新的技能/经验
     * - 求职状态更新
     */
    public Mono<Void> updateFromConversation(String userId, List<Message> conversation);

    /**
     * 获取用户完整画像（整合 soul + identity + info + episodic）
     */
    public String getUserProfile(String userId);
}
```

**与现有系统的整合：** JobClaw 的 `workspace/users/{userId}/` 目录已经存储了 soul.md、identity.md、info.md，LongTermMemoryService 在此基础上增加自动更新和检索能力。

### 5.3 记忆管理 Middleware

```java
// 扩展现有 ReActMiddleware，新增记忆拦截器
@Component
public class MemoryReActMiddleware implements ReActMiddleware {

    @Override
    public void beforeReasoning(List<Message> messages, int iter) {
        // 仅在首次推理时检索记忆（iter == 1，因为 iter 0 走 Advisor Chain 已注入 Memory）
        if (iter == 1) {
            String userId = extractUserId(messages);
            String memory = episodicMemory.retrieve(userId, getLastMessage(messages));
            if (memory != null) {
                messages.add(Message.system("[相关历史记忆]\n" + memory));
            }
        }
    }

    @Override
    public void onComplete(int totalIters, String finalResponse) {
        // 循环结束后记录本次对话
        episodicMemory.record(userId, messages);
    }
}
```

### 5.4 落地改动清单

| 文件 | 状态 | 说明 |
|------|------|------|
| `core/agent/memory/SmartWindowChatMemory.java` | ✅ 已修改 | 新增 `compressOldMessages()` — 先压缩后丢弃策略 |
| `core/agent/memory/EpisodicMemory.java` | ✅ 新增 | 情景记忆接口（record/retrieve/getAll/clear） |
| `core/agent/memory/EpisodicFact.java` | ✅ 新增 | record(category, content, createdAt, sourceId) |
| `core/agent/memory/FileSystemEpisodicMemory.java` | ✅ 新增 | 文件系统情景记忆实现（LLM 提取 + YAML 存储 + 关键词检索） |
| `core/agent/memory/MemoryReActMiddleware.java` | ✅ 新增 | 记忆管理中间件（setContext 提取 userId + beforeReasoning 注入 + onComplete 记录） |
| `core/agent/memory/ContextWindowProperties.java` | ✅ 已修改 | 新增 compressBeforeDrop / episodicEnabled 等配置项 |
| `core/agent/memory/FileSystemChatMemoryRepository.java` | ✅ 已修改 | 集成情景记忆注入和记录 |
| `core/agent/react/ReActMiddleware.java` | ✅ 已修改 | 新增 `setContext(ChatClientRequest)` 默认方法 |
| `core/agent/react/ReActAdvisor.java` | ✅ 已修改 | 循环前调用 `notifySetContext(request)` |
| `core/pom.xml` | ✅ 已修改 | 新增 hutool-json 依赖 |
| `prompts/episodic-extract-prompt.md` | ✅ 新增 | LLM 提取情景记忆的提示词模板 |

### 5.5 实际架构 vs 原计划对比

| 维度 | 原计划 | 实际实现 |
|------|--------|---------|
| Working Memory | 新建 `CompressingWindowMemory` | 直接在 `SmartWindowChatMemory` 中增加 `compressOldMessages()` |
| Episodic Memory 存储 | JDBC (`JdbcEpisodicMemory`) | 文件系统 (`FileSystemEpisodicMemory` + YAML) |
| Long-term Memory | 新建 `LongTermMemoryService` | 复用已有 `IIdentityAgent`（soul.md / identity.md / info.md） |
| 记忆检索 | Embedding 语义检索 | 关键词匹配 + 时间衰减 + 类别权重（简单起步） |
| 上下文传递 | 未明确 | 新增 `ReActMiddleware.setContext()` + `ReActAdvisor.notifySetContext()` |
| 去重机制 | 未明确 | Jaccard bigram 相似度（阈值 0.7） |

---

## 六、Phase 4：PlanNotebook 任务分解 ✅ 已完成

### 6.1 设计目标

参照 AgentScope 的 `PlanNotebook`，为 JobClaw Agent 引入结构化任务分解能力。当面对复杂任务时，Agent 可以：
1. 创建计划（Plan），将目标分解为有序子任务（SubTask）
2. 追踪子任务状态（todo → in_progress → done / abandoned）
3. 每轮推理自动注入计划进度提示（Hint）

### 6.2 核心设计

#### 6.2.1 PlanNotebook

```java
// core/agent/plan/PlanNotebook.java
public class PlanNotebook {

    private Plan currentPlan;

    /** 创建计划 */
    @Tool(description = "创建一个结构化任务计划")
    public String createPlan(
        @ToolParam(name = "name") String name,
        @ToolParam(name = "subtasks") List<String> subtaskDescriptions) {
        this.currentPlan = Plan.builder()
            .name(name)
            .subtasks(subtaskDescriptions.stream()
                .map(desc -> SubTask.of(desc, SubTaskState.TODO))
                .toList())
            .build();
        return "计划已创建：" + formatPlan(currentPlan);
    }

    /** 更新子任务状态 */
    @Tool(description = "更新当前子任务状态")
    public String updateSubtaskState(
        @ToolParam(name = "index") int index,
        @ToolParam(name = "state") String state) {
        currentPlan.getSubtasks().get(index).setState(SubTaskState.valueOf(state));
        return "子任务 " + index + " 状态已更新为 " + state;
    }

    /** 完成当前子任务 */
    @Tool(description = "标记当前子任务为完成")
    public String finishSubtask(@ToolParam(name = "index") int index) {
        return updateSubtaskState(index, "DONE");
    }

    /** 生成计划进度提示 */
    public String generateHint() {
        if (currentPlan == null) return "";
        return "<system-hint>\n当前计划: " + currentPlan.getName()
            + "\n进度: " + formatProgress(currentPlan)
            + "\n下一步: " + getNextAction(currentPlan)
            + "\n</system-hint>";
    }
}
```

#### 6.2.2 PlanHintMiddleware

参照 AgentScope 的 `PlanHintMiddleware`，在每轮推理前自动注入计划进度：

```java
// 扩展现有 ReActMiddleware
@Component
public class PlanHintReActMiddleware implements ReActMiddleware {

    @Override
    public void beforeReasoning(List<Message> messages, int iter) {
        String hint = planNotebook.generateHint();
        if (!hint.isEmpty()) {
            messages.add(Message.system(hint));
        }
    }
}
```

### 6.3 JobClaw 场景适配

将 PlanNotebook 应用到 JobClaw 的具体业务场景：

| 场景 | Plan 示例 |
|------|----------|
| 岗位数据采集 | Plan: 采集 XX 公司校招 → [确定目标公司, 搜索岗位, 提取详情, 清洗入库] |
| 个性化推荐 | Plan: 为 XX 推荐岗位 → [分析用户画像, 搜索匹配岗位, 排序推荐, 生成推荐语] |
| 公众号发文 | Plan: 发布校招推文 → [汇总最新岗位, 筛选亮点, 撰写推文, 审核发布] |

### 6.4 落地改动清单

| 文件 | 改动 |
|------|-----|
| `plugins/plan-notebook/PlanNotebook.java` | ✅ 已新增 - 用户隔离、文件持久化 |
| `plugins/plan-notebook/PlanNotebookTool.java` | ✅ 已新增 - AutoDiscoveredTool 工具入口 |
| `plugins/plan-notebook/model/Plan.java` | ✅ 已新增 |
| `plugins/plan-notebook/model/SubTask.java` | ✅ 已新增 |
| `plugins/plan-notebook/model/SubTaskState.java` | ✅ 已新增 |
| `plugins/plan-notebook/PlanHintReActMiddleware.java` | ✅ 已新增 - 计划进度中间件 |
| `core/agent/impl/PlanBizAgent.java` | ✅ 已新增 - 计划模式业务 Agent |
| `core/cli/PlanModeCommandHandler.java` | ✅ 已新增 - `/plan` 进入计划模式 |

---

## 七、Phase 5：Skill 能力沉淀

### 7.1 设计目标

参照 AgentScope 的 `SkillBox`，为 JobClaw 引入技能沉淀机制。Agent 在完成任务后，可以将成功的执行策略沉淀为可复用的技能文件（Markdown），后续遇到类似任务时自动加载。

### 7.2 核心设计

#### 7.2.1 Skill 定义

```java
// core/agent/skill/AgentSkill.java
@Data
@Builder
public class AgentSkill {
    private String skillId;          // 唯一标识
    private String name;             // 技能名称
    private String description;      // 技能描述
    private String content;          // Markdown 内容（执行步骤、提示词模板等）
    private List<String> tags;       // 标签（用于检索）
    private String triggerPattern;   // 触发模式（关键词/意图匹配）
    private LocalDateTime createdAt;
    private int usageCount;          // 使用次数
}
```

#### 7.2.2 SkillBox — 技能管理

```java
// core/agent/skill/SkillBox.java
public class SkillBox {

    private final Map<String, AgentSkill> skills = new ConcurrentHashMap<>();
    private final Path skillDir;  // workspace/skills/

    /** 注册技能 */
    public void registerSkill(AgentSkill skill) {
        skills.put(skill.getSkillId(), skill);
        persistSkill(skill); // 写入 workspace/skills/ 目录
    }

    /** 根据用户消息检索相关技能 */
    public List<AgentSkill> findRelevantSkills(String userMessage) {
        return skills.values().stream()
            .filter(skill -> matchesSkill(skill, userMessage))
            .sorted(Comparator.comparingInt(AgentSkill::getUsageCount).reversed())
            .limit(3)
            .toList();
    }

    /** 获取技能系统提示词（注入到 Agent 的系统提示中） */
    public String getSkillPrompt(List<AgentSkill> relevantSkills) {
        if (relevantSkills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("你可以参考以下已有技能来辅助完成任务：\n");
        for (AgentSkill skill : relevantSkills) {
            sb.append("## ").append(skill.getName()).append("\n");
            sb.append(skill.getContent()).append("\n\n");
        }
        return sb.toString();
    }
}
```

#### 7.2.3 SkillSedimentMiddleware

```java
// 扩展现有 ReActMiddleware
@Component
public class SkillSedimentReActMiddleware implements ReActMiddleware {

    @Override
    public void beforeReasoning(List<Message> messages, int iter) {
        // 仅在首次推理时检索并注入相关技能
        if (iter == 1) {
            String lastMsg = getLastMessage(messages).getText();
            List<AgentSkill> skills = skillBox.findRelevantSkills(lastMsg);
            if (!skills.isEmpty()) {
                String skillPrompt = skillBox.getSkillPrompt(skills);
                messages.add(Message.system(skillPrompt));
            }
        }
    }
}
```

#### 7.2.4 能力沉淀工具

为 Agent 提供一个 `@Tool` 方法，允许 Agent 主动沉淀成功经验：

```java
@Tool(description = "将当前成功的执行策略沉淀为可复用技能")
public String saveSkill(
    @ToolParam(name = "name") String skillName,
    @ToolParam(name = "description") String description,
    @ToolParam(name = "steps") String executionSteps) {
    AgentSkill skill = AgentSkill.builder()
        .skillId(generateId())
        .name(skillName)
        .description(description)
        .content(executionSteps)
        .tags(extractTags(description))
        .build();
    skillBox.registerSkill(skill);
    return "技能已沉淀：" + skillName;
}
```

### 7.3 与现有 workspace/skills 整合

JobClaw 已有 `workspace/skills/skill-creator/` 目录，SkillBox 在此基础上：
- 统一技能文件的存储和检索
- 支持技能的动态加载和热更新
- 提供使用频率统计和淘汰机制

### 7.4 落地改动清单

| 文件 | 改动 |
|------|-----|
| `core/agent/skill/AgentSkill.java` | **新增** |
| `core/agent/skill/SkillBox.java` | **新增** |
| `core/agent/skill/SkillToolFactory.java` | **新增** - 将技能注册为 Agent 工具 |
| `core/agent/react/SkillSedimentReActMiddleware.java` | **新增** - 技能沉淀中间件 |

---

## 八、Phase 6：HITL 人工确认与权限控制

### 8.1 设计目标

参照 AgentScope 的 `PermissionEngine` + `ConfirmResult` 机制，为 JobClaw Agent 的工具调用引入人工确认流程。

### 8.2 核心设计

#### 8.2.1 ToolPermission 注解

```java
// core/agent/tool/annotation/RequireConfirm.java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireConfirm {
    String message() default "此操作需要您的确认";
    ConfirmLevel level() default ConfirmLevel.NORMAL;
}

public enum ConfirmLevel {
    NORMAL,    // 普通确认
    SENSITIVE, // 敏感操作（如数据删除、发布）
    CRITICAL   // 关键操作（如支付、对外发送）
}
```

#### 8.2.2 确认流程

参照 AgentScope 的 `ConfirmResult` + `applyConfirmResults()` 模式：

```
Agent 推理产出工具调用
  ↓
PermissionEngine 评估每个工具调用
  ↓
├── ALLOW → 直接执行
├── ASK   → 暂停 Agent，向用户发送确认请求
└── DENY  → 拒绝执行，返回拒绝结果
  ↓
用户确认/拒绝
  ↓
Agent 恢复执行（携带确认结果）
```

#### 8.2.3 IM 渠道集成

利用 JobClaw 现有的 IM 渠道（WeChat/DingDing/FeiShu）发送确认卡片：

```java
// 基于 ReActMiddleware 实现的权限中间件
@Component
public class PermissionReActMiddleware implements ReActMiddleware {

    @Override
    public void beforeActing(List<AssistantMessage.ToolCall> toolCalls, int iter) {
        // 评估每个工具调用是否需要人工确认
        for (var toolCall : toolCalls) {
            if (requiresConfirm(toolCall)) {
                channel.sendConfirmCard(userId, ConfirmCard.builder()
                    .title("操作确认")
                    .description("Agent 希望执行：" + toolCall.name())
                    .toolName(toolCall.name())
                    .parameters(parseArgs(toolCall.arguments()))
                    .build());
                // 等待用户确认后继续执行
            }
        }
    }
}
```

### 8.4 落地改动清单

| 文件 | 改动 |
|------|-----|
| `core/agent/tool/annotation/RequireConfirm.java` | **新增** |
| `core/agent/permission/PermissionEngine.java` | **新增** |
| `core/agent/permission/ConfirmResult.java` | **新增** |
| `core/channel/Channel.java` | **修改** - 增加 `sendConfirmCard()` |
| `core/agent/react/PermissionReActMiddleware.java` | **新增** - 基于 ReActMiddleware 的权限确认中间件 |

---

## 九、AgentState 统一状态管理

### 9.1 设计目标

参照 AgentScope 的 `AgentState`，将 JobClaw 当前分散的状态（ChatMemory、SessionAgentBinder、用户偏好）统一到单一状态对象中。

### 9.2 核心设计

```java
// core/agent/state/AgentState.java
@Data
@Builder
public class AgentState {
    private String sessionId;
    private String agentId;

    /** 对话上下文（替代 ChatMemory 的内部存储） */
    private List<Message> context;

    /** 工具上下文（已激活的工具组、pending 工具调用） */
    private ToolContext toolContext;

    /** 权限上下文 */
    private PermissionContext permissionContext;

    /** 记忆引用（EpisodicMemory + LongTermMemory 的检索结果缓存） */
    private Map<String, String> memoryCache;

    /** 当前计划引用 */
    private Plan currentPlan;
}
```

### 9.3 持久化策略

参照 AgentScope 的 `Session.save(sessionKey, "agent_state", state)` 模式：

```java
// 每次 Agent 执行结束后自动保存状态
private Mono<Void> saveStateToSession() {
    return Mono.fromRunnable(() ->
        session.save(sessionKey, "agent_state", state));
}
```

**与现有 Session 的整合：** JobClaw 的 `SessionAgentBinder` 已经维护了 session-agent 映射关系，在此基础上将 AgentState 纳入 Session 管理。

### 9.4 落地改动清单

| 文件 | 改动 |
|------|-----|
| `core/agent/state/AgentState.java` | **新增** |
| `core/agent/state/ToolContext.java` | **新增** |
| `core/agent/state/SessionStateStore.java` | **新增** |
| `core/router/SessionAgentBinder.java` | **修改** - 集成 AgentState 持久化 |

---

## 十、Structured Output 结构化输出

### 10.1 设计目标

参照 AgentScope 的 `StructuredOutputCapableAgent` + `StructuredOutputHook`，使 Agent 能够返回类型安全的结构化数据。

### 10.2 核心设计

```java
// core/agent/output/StructuredOutput.java
public interface StructuredOutput<T> {
    /** 期望输出的 Java 类型 */
    Class<T> outputType();

    /** JSON Schema 描述 */
    String jsonSchema();

    /** 解析 LLM 输出为结构化对象，失败时返回 empty */
    Optional<T> parse(String llmOutput);
}

// 使用示例：通过 ReActMiddleware 实现结构化输出校验
public class JobRecommendAgent extends AbsBizAgent {
    // 注册 ReActAdvisor 时配置 StructuredOutputReActMiddleware
}
```

### 10.3 自纠正机制

参照 AgentScope 的 `StructuredOutputHook`，当 LLM 输出不符合格式时自动重试：

```java
// 扩展现有 ReActMiddleware
@Component
public class StructuredOutputReActMiddleware implements ReActMiddleware {

    @Override
    public void afterReasoning(AssistantMessage msg, int iter) {
        if (structuredOutput != null && !structuredOutput.parse(msg.getText()).isPresent()) {
            // 解析失败 → 标记需要重新推理
            needsRetry = true;
            retryHint = "输出格式不正确，请按照以下 JSON Schema 重新输出：\n"
                + structuredOutput.jsonSchema();
        }
    }
}
```

---

## 十一、Event 事件系统

### 11.1 设计目标

参照 AgentScope 的 `AgentEvent` 细粒度事件流，为 JobClaw Agent 提供完整的执行可观测性。

### 11.2 事件类型

```java
// core/agent/event/AgentEvent.java (sealed interface)
public sealed interface AgentEvent {
    // Agent 级事件
    record AgentStartEvent(String replyId, String agentName) implements AgentEvent {}
    record AgentEndEvent(String replyId) implements AgentEvent {}

    // 推理级事件
    record ReasoningStartEvent(String replyId) implements AgentEvent {}
    record ReasoningChunkEvent(String replyId, String text) implements AgentEvent {}
    record ReasoningEndEvent(String replyId) implements AgentEvent {}

    // 工具级事件
    record ToolCallStartEvent(String replyId, String toolId, String toolName) implements AgentEvent {}
    record ToolCallChunkEvent(String replyId, String toolId, String chunk) implements AgentEvent {}
    record ToolResultEndEvent(String replyId, String toolId, String result) implements AgentEvent {}

    // 记忆级事件
    record MemoryRetrievedEvent(String userId, String memory) implements AgentEvent {}
    record MemoryRecordedEvent(String userId, int messageCount) implements AgentEvent {}

    // 计划级事件
    record PlanCreatedEvent(String planName, int subtaskCount) implements AgentEvent {}
    record SubtaskCompletedEvent(String planName, int index) implements AgentEvent {}
}
```

### 11.3 用途

- **前端流式展示**：细粒度的事件流可以让前端实时展示 Agent 的思考过程
- **日志审计**：完整记录每轮推理和工具调用
- **调试追踪**：定位 Agent 执行中的问题

---

## 十二、实施优先级与依赖关系

```
                    ┌─────────────────────┐
                    │  Phase 1: ReAct     │ ✅ ReActAdvisor (Advisor 模式)
                    │  推理循环           │    collectList + runReactLoop
                    └────────┬────────────┘
                             │
                    ┌────────▼────────────┐
                    │  Phase 2: ReAct      │ ✅ 观察者模式 hooks
                    │  Middleware         │    autoInjectMiddleware()
                    └────────┬────────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
     ┌────────▼──────┐ ┌────▼───────┐ ┌───▼──────────┐
     │ Phase 3:      │ │ Phase 4:   │ │ Phase 5:     │
     │ 记忆压缩  ✅  │ │ 任务分解   │ │ 能力沉淀     │  ← 可并行推进
     │ 与分层管理    │ │ PlanNote   │ │ Skill        │
     └───────────────┘ └────────────┘ └──────────────┘
                             │
                    ┌────────▼────────────┐
                    │  Phase 6: HITL      │ ← 生产部署前必须
                    │  人工确认           │
                    └─────────────────────┘
```

### 推荐实施节奏

| 阶段 | 预估工时 | 依赖 | 核心产出 | 状态 |
|------|---------|------|---------|------|
| Phase 1: ReAct | 5-7 天 | 无 | `ReActAdvisor` + 工具调用循环 | ✅ 已完成 |
| Phase 2: ReActMiddleware | 3-4 天 | Phase 1 | `ReActMiddleware` 接口 + `LoggingReActMiddleware` | ✅ 已完成 |
| Phase 3: 记忆 | 4-5 天 | Phase 2 | 摘要压缩 + EpisodicMemory + `MemoryReActMiddleware` | ✅ 已完成 |
| Phase 4: Plan | 3-4 天 | Phase 2 | PlanNotebook + `PlanHintReActMiddleware` | ✅ 已完成 |
| Phase 5: Skill | 3-4 天 | Phase 2 | SkillBox + `SkillSedimentReActMiddleware` | 待实施 |
| Phase 6: HITL | 3-4 天 | Phase 1 | PermissionEngine + 确认卡片 + `PermissionReActMiddleware` | 待实施 |

**已完成：Phase 1 + Phase 2 + Phase 3（约 12-16 天工作量）**
**剩余：约 9-12 天**

---

## 十三、核心对比：AgentScope vs JobClaw 改进方案

| 维度 | AgentScope Java | JobClaw 当前 | JobClaw 改进后 |
|------|----------------|-------------|---------------|
| 推理模式 | ReAct 迭代循环 | ReActAdvisor (Advisor 模式) ✅ | ReAct 迭代循环 ✅ |
| 生命周期 | Middleware 洋葱模型 | ReActMiddleware (观察者模式) ✅ | ReActMiddleware + 按需扩展洋葱模型 |
| 记忆 | InMemory + LongTermMemory + 语义检索 | SmartWindow 滑动窗口 | 三层记忆 + 摘要压缩 ✅ (Working + Episodic + Long-term) |
| 任务分解 | PlanNotebook + SubTask | 无 | PlanNotebook + SubTask ✅ |
| 能力沉淀 | SkillBox + Markdown 技能文件 | 无 | SkillBox + workspace/skills (待实施) |
| 权限控制 | PermissionEngine + ConfirmResult | 无 | @RequireConfirm + 确认卡片 (待实施) |
| 输出格式 | StructuredOutput + 自纠正 | 自由文本 | StructuredOutput + 自纠正 (待实施) |
| 状态管理 | AgentState + Session | 分散状态 | 统一 AgentState (待实施) |
| 事件流 | 细粒度 AgentEvent | 无 | sealed AgentEvent (待实施) |
| 响应式 | Project Reactor | Spring AI Flux | Spring AI Flux ✅ |
| 工具执行 | 内置 ToolCallingManager | ToolCallback.call(args, toolContext) ✅ | 手动执行 + ToolContext ✅ |
| IM 展示 | N/A | LlmRspCell (tool + toolResult) ✅ | 钉钉/飞书卡片展示工具信息 ✅ |

---

## 十四、注意事项

1. **向后兼容**：所有改进以新增为主，现有 `BizAgent` 接口和实现不变，ReAct 能力通过 `ReActAdvisor` 注入
2. **Advisor 模式**：ReAct 实现为 Spring AI Advisor（非 BizAgent 子类），与现有 Advisor Chain 自然协作
3. **渐进式迁移**：现有 Agent（IdentityCollectorAgent、JobFetchAgent 等）注册 ReActAdvisor 后即获得 ReAct 能力
4. **技术栈一致**：继续使用 Spring AI 的 `ChatModel` / `StreamingChatModel` 作为底层模型调用，不引入 AgentScope 的 Model 抽象
5. **Spring 生态融合**：ReActMiddleware 注册为 Spring `@Component`，通过 `autoInjectMiddleware()` 自动注入
6. **配置驱动**：所有新增功能通过 `application.yml` 配置开关控制，默认关闭
7. **Java 21 特性**：充分利用 Record（`ReactLoopResult`、`LlmRspCell`）、Pattern Matching、Virtual Thread
8. **Middleware 扩展**：当前 `ReActMiddleware` 为观察者模式，后续如需洋葱模型拦截能力，可在此基础上扩展
