# ✅求职派业务 Agent 体系设计与实现实战

> `MsgRouter` 完成路由决策后，消息交到谁手上？一个意图可能对应多个 Agent 实现，Agent 之间如何组织、如何调用 LLM、如何注入用户身份、如何注册工具？当系统从单一 Agent 进化到多 Agent 协作时，这些问题从"不需要考虑"变成了"必须标准化"。本文完整记录求职派的 BizAgent 体系——从接口抽象、LLM 调用封装、五类内置 Agent，到独立业务 Agent 的完整实现链。

---

## 一、为什么需要 BizAgent？

### 1.1 没有标准化 Agent 接口会怎样？

**场景还原**：

```
用户（发送消息）："帮我找北京的国企岗位"
  → MsgRouter 路由 → 交给 JobRecommendAgent
  → JobRecommendAgent 调用 LLM 处理
  → 但 LLM 调用、记忆管理、工具注册代码与其他 Agent 重复
```

**没有 BizAgent 的系统**：

```java
// 反例：每个"Agent"自己管理一切
public class JobRecommendAgent {
    // 每个 Agent 自己创建 ChatClient
    private ChatClient buildClient() {
        return ChatClient.builder(chatModel)
            .defaultSystem("...")
            .defaultTools(tool)
            .build();
    }
    // 每个 Agent 自己管理记忆
    private void saveMemory() { /* ... */ }
    // 每个 Agent 自己处理身份注入
    private String buildIdentityPrompt() { /* ... */ }
}
```

**核心问题**：

| 问题 | 后果 |
|------|------|
| **代码重复** | 每个 Agent 都要写 ChatClient 创建、记忆管理、身份注入逻辑 |
| **行为不一致** | 有的 Agent 注入身份信息，有的不注入；有的用 ReAct，有的不用 |
| **无法统一管理** | 无法集中注册、按权限过滤、按优先级排序 |
| **扩展成本高** | 增加一个新 Agent 需要复制一整套基础设施代码 |
| **LLM 调用无法兜底** | Agent 处理失败时没有统一的 fallback 机制 |

### 1.2 V2 的设计目标

> **如何标准化业务 Agent 的创建、注册、调用、工具管理，让新增一个 Agent 只需要关注"业务逻辑"和"系统提示词"，而不需要关心 LLM 调用、记忆管理、身份注入等基础设施？**

五个核心诉求：

1. **统一的接口契约**：所有 Agent 实现 `BizAgent`，保证 process/stream/权限/优先级等能力一致
2. **可复用的 LLM 调用封装**：内置 Agent 不直接操作 `ChatClient`，而是通过 `LlmCaller` 层统一封装
3. **两级 LLM Caller 适应不同场景**：专业业务 Agent 不需要用户身份注入（用 `BizAgentLlmCaller`），用户交互 Agent 需要完整身份信息（用 `UserPreferenceBasedLlmCaller`）
4. **自动注册与路由**：`@Component` + `AgentRegistry` 自动发现，无需手工维护 Agent 列表
5. **配置热更新**：用户偏好配置变更后，Agent 的 LLM 调用器自动清除缓存，下次调用生效

### 1.3 为什么选择模板方法 + 组合模式？

`AbsBizAgent` 采用模板方法模式定义骨架：`init()` → `getSystemPrompt()` → `getTools()` → `process()`/`stream()`，子类只需重写业务特定方法。同时通过组合持有 `IIdentityAgent` 和 `ChatMemory`，避免在 Agent 内部管理这些基础设施的生命周期。

这个选择解决了 1.1 中的**代码重复**和**行为不一致**问题——所有 Agent 共享同一个 `BizAgentLlmCaller` 构建逻辑和 `ReActAdvisor` 配置，但每个 Agent 可以自由选择使用哪个 Caller。

---

## 二、整体架构

```
┌──────────────────────────────────────────────────────────────────┐
│                       BizAgent 体系分层                            │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  接口层  BizAgent ←─── AgentIntro (子接口)                         │
│          IIdentityAgent (身份注入契约)                              │
│          LlmCaller (LLM 调用契约)                                  │
│                                                                   │
│  抽象层  AbsBizAgent (模板方法，持有依赖)                           │
│          ├─ chatMemory / modelProviders / identityAgent            │
│          ├─ llmCaller (BizAgentLlmCaller)                         │
│          ├─ init() → 构建 llmCaller                               │
│          ├─ getSystemPrompt() ← 子类重写                          │
│          └─ getTools() ← 子类重写                                 │
│                                                                   │
│  调用层  SimpleLlmCaller                                          │
│             └─ BizAgentLlmCaller (含记忆 + 身份 + 工具)            │
│                    └─ UserPreferenceBasedLlmCaller (含偏好 + 全工具) │
│                                                                   │
│  实现层  ┌─ 内置 Agent ──────────────────────────────────────────┐ │
│          │  SimpleDefaultBizAgent (兜底, 最低优先级)              │ │
│          │  PlanBizAgent (计划模式, 依赖 PlanNotebookCapability)   │ │
│          │  TaskBizAgent (任务提醒, 使用 TaskTool)                │ │
│          │  CustomChatBizAgent (通用聊天)                         │ │
│          │  PreferenceSettingBizAgent (偏好设置, 提供@Tool)        │ │
│          └───────────────────────────────────────────────────────┘ │
│          ┌─ 独立业务 Agent ───────────────────────────────────────┐ │
│          │  JobFetchAgent (岗位采集, ADMIN 权限)                   │ │
│          │  JobRecommendAgent (岗位推荐, TOTAL 权限)               │ │
│          └───────────────────────────────────────────────────────┘ │
│                                                                   │
│  路由层  PresetAgentIntro (枚举) → AgentRegistry (注册中心)        │
│          → AgentRouter (路由决策) → MsgRouter (集成入口)            │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

### 2.1 两条 Agent 实现路径

内置 Agent 和独立业务 Agent 都继承 `AbsBizAgent`，但有两条不同的创建路径：

| 对比维度 | 内置 Agent（core 模块） | 独立业务 Agent（agents 模块） |
|----------|------------------------|------------------------------|
| 注册方式 | `@Component` 自动扫描 | `@Component` 自动扫描 |
| LLM Caller 选择 | 自选（或继承默认） | 自选（或继承默认） |
| 工具来源 | 内置工具 / 自动发现 | 模块内部的 Service + Tool |
| 系统 Prompt | 简短，偏向引导 | 详细，包含完整工作流 |
| 权限 | 多为 TOTAL | 视业务敏感度定 |

**一个关键的架构决策**：为什么独立业务 Agent 不放在 `core` 中？因为 `core` 是基础设施层，不应该依赖业务领域逻辑。`agents/` 是业务层，可以让每个 Agent 模块拥有独立的 Service、Entity、Repository。这是"关注点分离"原则的体现。

### 2.2 核心依赖链

```
BizAgent
  │
  ├─ 依赖
  │    ModelProviders  → 解析用户偏好模型
  │    ChatMemory      → 对话上下文持久化
  │    IIdentityAgent   → 用户身份/灵魂注入
  │
  ├─ 构建
  │    BizAgentLlmCaller (或不含偏好的 UserPreferenceBasedLlmCaller)
  │      ├─ ChatMemory (记忆管理)
  │      ├─ IIdentityAgent (身份注入)
  │      ├─ ModelProviders (模型解析)
  │      ├─ System Prompt (业务定义)
  │      └─ ToolCallback[] (工具注册)
  │
  └─ 执行
        process() / stream() → ChatClient → ReActAdvisor → Tool Execution
```

---

## 三、核心抽象：BizAgent 接口

### 3.1 为什么选择接口 + 默认方法的组合？

`BizAgent.java:22-119` 定义了一个"少而精"的接口——只有 4 个必须实现的方法，其余均提供默认实现。

```java
public interface BizAgent {
    // ── 必须实现 ──
    AgentPermission permission();          // 权限模型
    AgentIntro getAgentIntro();             // Agent 身份标识
    String process(...);                    // 同步处理
    Flux<LlmRspCell> stream(...);           // 流式处理

    // ── 默认实现（可按需重写） ──
    default List<AgentIntro> getSupportedIntents() { ... }  // 默认返回自身
    default boolean supportsIntent(AgentIntro intent) { ... }  // 遍历判断
    default int getPriority() { return 0; }
    default boolean isAvailable() { return true; }
    default void initialize() { }
    default void destroy() { }
}
```

**为什么这样设计？**

1. **最少接口原则**：一个 Agent 最终核心就是"处理消息并返回结果"。权限、身份、支持意图都是辅助元数据。把必须实现的方法控制在 4 个，降低新 Agent 的接入成本。
2. **默认方法提供扩展点**：大部分 Agent 不需要生命周期管理（`initialize`/`destroy`），不需要重写 `isAvailable`。提供默认空实现，让"99% 的 Agent 不需要重写这些方法"。

**与此前对比**：如果是一个传统设计，我们可能会把 `process` 和 `stream` 合并成一个方法（返回 `Mono<String>` 或 `Flux<String>`），然后由调用方决定用同步还是流式。但求职派的设计选择了两个独立方法——因为同步和流式的调用端处理逻辑完全不同：同步有阻塞等待，流式有 `LlmRspCell` 增量推送。两个方法让 Agent 可以针对两种模式做不同的处理（如 `JobFetchAgent` 对文件上传用同步流程，对文本对话用流式）。

### 3.2 AgentIntro：身份契约

`BizAgent.AgentIntro` 是嵌入在 `BizAgent` 内部的子接口：

```java
interface AgentIntro {
    String getAgentId();     // 唯一标识
    String getIntro();       // 一句话描述
    String getDescription(); // 详细描述
}
```

**接口嵌套接口的设计意图**：AgentIntro 与 BizAgent 的生命周期完全绑定——没有 BizAgent 就不需要 AgentIntro。嵌套接口让命名空间更紧凑，调用方只需 `BizAgent.AgentIntro` 即可引用。

实际使用时，`PresetAgentIntro` 枚举实现了 `AgentIntro`。这意味着：

- 枚举值自带 agentId/intro/description 三要素
- Agent 返回 `getAgentIntro()` 本质上返回枚举常量
- 路由层只需要比较 `agentId` 字符串即可完成匹配

**为什么不用 String 直接做 agentId？** 因为 AgentIntro 承载了比 ID 更多的信息。当用户通过 `/agents` 查看可用 Agent 时，intro 和 description 直接作为展示文本，无需额外查询。如果只用 String，就得多一次查询映射。

### 3.3 process / stream：双模式处理

```java
String process(UserConversationInfo user, ChannelReceiveMessage message);
Flux<LlmRspCell> stream(UserConversationInfo user, ChannelReceiveMessage message);
```

**为什么同时保留同步和流式？**

1. **工具调用场景下流式有优势**：真正的流式（非 SSE 模拟）可以让工具执行结果和 AI 文本交错推送，用户看到"AI 已经理解需求 → AI 调用工具 → AI 返回结果"的完整过程，体验更实时。
2. **同步是兜底**：有些通道（如 微信机器人）不支持流式响应，只能用同步。同步实现也相对简单，调试起来更直接。
3. **`LlmRspCell` 封装了流式单元**：每条流式消息包含 `id`、`content`、`toolResult` 三个字段，让前端或通道层能区分"工具执行结果"和"AI 文本增量"。

### 3.4 权限与优先级

```java
AgentPermission permission();    // TOTAL / VIP / ADMIN
default int getPriority() { return 0; }
```

**权限模型**决定了"谁能用这个 Agent"。`AgentPermission` 枚举与 `UserRoleEnum` 配合：

- `TOTAL`：所有用户可用（SimpleDefault、Plan、Chat...）
- `VIP`：仅付费用户（预留，暂未使用）
- `ADMIN`：仅管理员（JobFetchAgent 的岗位抓取功能涉及数据写入）

**优先级模型**决定"多个 Agent 支持同一意图时谁先上"。`DefaultAgentRegistry.getAgentsForIntent()` 返回的 Agent 列表按优先级降序排列。

`SimpleDefaultBizAgent` 的优先级是 `Integer.MIN_VALUE`——这是故意的。它的角色是"最后一道防线"：没有任何 Agent 能处理时，兜底 Agent 接管。优先级最低确保它不会抢在其他 Agent 前面。

---

## 四、通用底座：AbsBizAgent 抽象类

`AbsBizAgent.java:19-51` 是连接接口和实现的桥梁。

### 4.1 构造注入 vs 字段注入

```java
public abstract class AbsBizAgent implements BizAgent {
    protected final ModelProviders modelProviders;
    protected final ChatMemory chatMemory;
    protected BizAgentLlmCaller llmCaller;
    protected IIdentityAgent identityAgent;

    public AbsBizAgent(ModelProviders modelProviders,
                       ChatMemory chatMemory,
                       IIdentityAgent identityAgent) {
        this.modelProviders = modelProviders;
        this.chatMemory = chatMemory;
        this.identityAgent = identityAgent;
    }
}
```

**全部使用构造注入**，且 `modelProviders` 和 `chatMemory` 声明为 `final`。这意味着一旦 Agent 创建，它引用的基础设施实例不可变，但 `llmCaller` 不是 final——因为 `init()` 方法可以替换它。

**为什么 `llmCaller` 不通过构造注入？**

因为子类需要在 `@PostConstruct init()` 中决定使用哪个 Caller。如果通过构造注入，caller 在父类构造时就已经创建，子类没有机会替换。来看两种模式：

**模式 A**：使用默认的 `BizAgentLlmCaller`（`TaskBizAgent`、`PreferenceSettingBizAgent`、`JobFetchAgent`）——不需要重写 `init()`，直接用父类的 `@PostConstruct` 构建。

**模式 B**：使用 `UserPreferenceBasedLlmCaller`（`SimpleDefaultBizAgent`、`PlanBizAgent`、`CustomChatBizAgent`、`JobRecommendAgent`）——必须重写 `init()` 在父类 `@PostConstruct` 执行后替换 `llmCaller`。

```java
// 模式 B 示例：SimpleDefaultBizAgent
@PostConstruct
public void init() {
    this.llmCaller = new UserPreferenceBasedLlmCaller(
        modelProviders, identityAgent, chatMemory, taskManager,
        autoDiscoveredTools, getSystemPrompt()
    );
    this.llmCaller.setWorkspace(workspace);
}
```

注意到子类 `init()` 与父类 `init()` 同名——都是 `@PostConstruct`。子类方法会覆盖父类方法（Java 实例方法的动态绑定），所以父类的 `init()` 不会被执行。这就是为什么子类必须自己调用 `UserPreferenceBasedLlmCaller` 的构造函数，而不能再依赖父类构建的 `BizAgentLlmCaller`。

**如果希望父类自动区分这两种模式**，可以引入一个 `boolean useUserPreferenceBasedLlmCaller()` 方法，但这样做会增加抽象类的复杂度。求职派的方案更直接——子类完全掌控 `init()`，灵活但需要自行维护。

### 4.2 模板方法：getSystemPrompt() + getTools()

```java
public abstract String getSystemPrompt();
public ToolCallback[] getTools() {
    return new ToolCallback[0];  // 默认无工具
}
```

模板方法模式的精髓在于：**子类决定"做什么"，父类决定"怎么做"**。

- `getSystemPrompt()` 必须重写——每个 Agent 的系统提示词是其业务核心
- `getTools()` 可选重写——大部分 Agent 至少需要一个工具，但 ChatGPT 风格的纯对话 Agent 不需要

父类的 `init()` 用这两个方法构建 `llmCaller`：

```java
@PostConstruct
public void init() {
    this.llmCaller = new BizAgentLlmCaller(
        chatMemory, identityAgent, modelProviders,
        getSystemPrompt(), getTools()
    );
}
```

这种设计的巧妙之处在于：**创建 `llmCaller` 时不需要子类主动调用**。子类只需要定义 `getSystemPrompt()` 和 `getTools()` 的返回值，父类自动完成组装。

### 4.3 配置热更新

```java
@EventListener
public void refreshLlmCache(PropertiesRefreshedEvent event) {
    if (AiUserPreferenceProperties.class.equals(event.getPropertiesClz())) {
        llmCaller.refreshCache();  // 清除 ChatClient 缓存
    }
}
```

**工作原理**：当管理员在后台修改用户偏好配置时，`ConfigurationManager` 发布 `PropertiesRefreshedEvent`。所有 `AbsBizAgent` 实例收到事件后，调用 `llmCaller.refreshCache()` 清除内部的 `chatClientMap`。

下次用户发消息时，`getClient()` 发现缓存不存在，重新创建 `ChatClient`，此时 `modelProviders` 会从最新的 `AiUserPreferenceProperties` 中解析用户的模型配置。

**为什么不在 Agent 上监听，而是在 AbsBizAgent 上监听？** 因为配置热更新是所有 Agent 都需要的能力，放在抽象层避免每个 Agent 重复监听。

---

## 五、LLM 调用层设计

LLM 调用层是 BizAgent 体系中最精密的部分。它从 `SimpleLlmCaller` 到 `BizAgentLlmCaller` 再到 `UserPreferenceBasedLlmCaller`，逐级叠加能力。

### 5.1 三层结构

```
SimpleLlmCaller (core/agent/llm/SimpleLlmCaller.java)
  │  基础 LLM 调用，调用者自行管理上下文
  │  - call() / stream() / call(entity)
  │  - workspace 注入
  │  - 默认 system prompt（含 INFO.md）
  │  - 使用 ReActAdvisor
  │
  └─ BizAgentLlmCaller (core/agent/llm/BizAgentLlmCaller.java)
       增加 BizAgent 需要的上下文管理
       - ChatClient 缓存（按 userId + modelType 缓存）
       - ChatMemory 集成（MessageChatMemoryAdvisor）
       - 身份注入（identityAgent.buildSoulPrompt）
       - 工具注册（ToolCallback[]）
       - 媒体消息支持（图片/文件 → Spring AI Media）
       - refreshCache()
       │
       └─ UserPreferenceBasedLlmCaller (core/agent/llm/UserPreferenceBasedLlmCaller.java)
             增加用户偏好注入和全工具注册
             - buildSoulPrompt()：三份系统提示（soul.md + identity.md + info.md）
             - 自动发现工具注入（AutoDiscoveredTool）
             - 内置工具注入（SkillsTool / CheckListTool / TaskTool / McpTool）
             - Shell / FileSystem / WebFetch 工具注册
             - ChatClient 按 userId + channel + modelType 缓存
```

**关键设计决策**：`SimpleLlmCaller` 标注了 `@Component`，可以在模块中没有 Agent 时直接注入使用。而 `BizAgentLlmCaller` 和 `UserPreferenceBasedLlmCaller` 没有 `@Component`，它们通过 `AbsBizAgent` 的 `init()` 手动创建。

**为什么后两者不标注 `@Component`？** 因为它们的构造函数依赖 `IIdentityAgent`、`ChatMemory`、`SystemPrompt`、`ToolCallback[]` 等参数，这些参数在每个 Agent 中不同。如果标注 `@Component`，Spring 无法推断如何实例化——不同 Agent 需要不同的 systemPrompt 和 tools。

### 5.2 ChatClient 缓存策略

`BizAgentLlmCaller` 使用 `ConcurrentHashMap<String, ChatClient>` 做缓存，key 的生成策略是：

```java
// BizAgentLlmCaller: userId + modelType
String key = user.jobClawUserId() + model.name();

// UserPreferenceBasedLlmCaller: userId + channel + modelType (+ "_m" for multimodal)
String key = multiModal ? userId + "_" + channel + "_m" : userId + "_" + channel;
```

**为什么 BizAgentLlmCaller 的 key 不包含 channel？** 因为 `BizAgentLlmCaller` 用于**专业业务 Agent**（如 TaskBizAgent、PreferenceSettingBizAgent），这些 Agent 不依赖用户偏好身份注入，同一个用户在不同通道使用同一个 Agent 时，system prompt 和 tools 完全相同，无需按 channel 区分。

**为什么 UserPreferenceBasedLlmCaller 的 key 包含 channel？** 因为 `buildSoulPrompt()` 可能（未来）根据 channel 差异调整身份提示词。同时，跨通道的模型偏好也可能不同。

**refreshCache() 怎么做？** 直接 `chatClientMap.clear()` — 简单粗暴但有效。下次调用 `getClient()` 时重新创建 `ChatClient`，新创建时 `modelProviders.getModel()` 会使用最新的 `AiUserPreferenceProperties`。

### 5.3 身份注入差异

两个 Caller 在身份注入上存在根本差异：

**BizAgentLlmCaller.getClient()**：

```java
// 只注入 soul 信息
String identity = identityAgent.buildSoulPrompt(user.jobClawUserId());
if (StringUtils.isBlank(identity)) {
    sys = buildSystemPrompt(systemPrompt);
} else {
    sys = buildSystemPrompt(identity + "\n\n" + systemPrompt);
}
```

**UserPreferenceBasedLlmCaller.initClient() / buildSoulPrompt()**：

```java
// 注入完整的系统提示（soul.md + identity.md + info.md）
String agentPrompt = identityAgent.buildSystemPrompt(userId);
// 再加上 Agent 自己的 system prompt
```

`buildSoulPrompt()` 只注入灵魂信息（性格、价值观、职业愿景），比较轻量。`buildSystemPrompt()` 注入完整的身份信息（包含技能、教育、经验），比较重量。

**专业业务 Agent 只需要灵魂**。比如职位抓取 Agent，它需要知道用户的基本偏好来辅助判断"这个岗位是否对用户有价值"，但不应该访问用户的详细简历。这是信息最小化原则的体现。

### 5.4 工具注册差异

**BizAgentLlmCaller**：只注册子类通过 `getTools()` 提供的工具。典型如 `TaskBizAgent` 只注册 `TaskTool`。

```java
if (tools != null && tools.length > 0) {
    builder.defaultToolCallbacks(tools);
}
```

**UserPreferenceBasedLlmCaller**：注册全套工具链：

```java
.defaultTools(
    SkillsTool, CheckListTool, TaskTool, McpTool,
    ShellTools, FileSystemTools, SmartWebFetchTool
)
// + 自动发现工具
if (autoDiscoveredTools != null) {
    autoDiscoveredTools.forEach(t -> builder.defaultTools(t.tool()));
}
// + Agent 自定义工具
if (super.tools != null) {
    builder.defaultToolCallbacks(super.tools);
}
```

**为什么通用 Agent 需要这么多工具？** 因为 `SimpleDefaultBizAgent` 和 `CustomChatBizAgent` 面向用户不确定的所有场景——用户可能问"帮我写一个文件"、"帮我执行一个脚本"、"帮我查一个网页"。只有注册全工具链才能覆盖这些不可预知的用户请求。

而专业业务 Agent 的场景是确定的——"帮我抓取一个职位"、"帮我设置偏好"。注册太多工具反而会干扰 LLM 的选择。

---

## 六、五大内置 Agent 实现

`core` 模块内置了五个 Agent 实现，覆盖了系统运行所需的全部基础场景。

### 6.1 SimpleDefaultBizAgent：最后的防线

**位置**：`core/.../agent/impl/SimpleDefaultBizAgent.java`

```java
@Component
public class SimpleDefaultBizAgent extends AbsBizAgent {
    @PostConstruct
    public void init() {
        this.llmCaller = new UserPreferenceBasedLlmCaller(...);
    }

    @Override
    public AgentPermission permission() { return AgentPermission.TOTAL; }

    @Override
    public AgentIntro getAgentIntro() { return PresetAgentIntro.DEFAULT; }

    @Override
    public int getPriority() { return Integer.MIN_VALUE; }

    @Override
    public String process(...) {
        return switch (userMessage.toLowerCase()) {
            case "help", "/help" -> formatHelp();
            default -> llmCaller.call(userConversationInfo, message);
        };
    }
}
```

**为什么选择在兜底 Agent 中处理 `/help`？**

`MsgRouter` 的 Step 2（系统命令分发）只处理以 `/` 开头的命令。但用户可能发送不带 `/` 的 "help"——这时候系统正常走意图识别，最终路由到兜底 Agent。兜底 Agent 的 `process()` 检查消息是否为 "help" 或 "/help"，返回帮助信息。

这种"双入口"设计覆盖了两个场景：
- `/help`：系统命令快马加鞭，Step 2 直接处理
- "help"：自然语言意图识别，兜底 Agent 处理

**为什么 system prompt 那么详细（200+ 行）？** 因为兜底 Agent 是所有"不知道该找谁"请求的最后处理者。它的 system prompt 定义了多种场景（求职相关 / 个人设置 / 通用聊天 / 情绪化表达等），指导 LLM 识别用户意图并引导到正确的专业 Agent。这是"引导型" Agent 的特点——它不自己做业务处理，而是帮用户找到正确的处理者。

### 6.2 PlanBizAgent：复杂任务的执行者

**位置**：`core/.../agent/impl/PlanBizAgent.java`

```java
@Component
public class PlanBizAgent extends AbsBizAgent {
    @Override
    public boolean isAvailable() {
        return autoDiscoveredTools.stream()
                .anyMatch(PlanNotebookCapability.class::isInstance);
    }

    @Override
    public AgentIntro getAgentIntro() { return PresetAgentIntro.PLAN; }
}
```

**为什么 PlanBizAgent 是内置 Agent 而不是独立 Agent？** 因为计划模式是 Agent 框架的核心能力——它不依赖任何业务数据（岗位库、用户信息等），只依赖 `PlanNotebookCapability` 这个纯工具。放在 core 模块中，任何业务场景都可以复用。

**`isAvailable()` 的动态检查**：PlanBizAgent 只在 `PlanNotebookCapability` 工具可用时才可用。如果配置中禁用了 plan-notebook 工具，这个 Agent 虽然已注册但不会在路由时被选中。这是**条件可用性**模式，避免"Agent 已注册但关键依赖缺失"的运行时报错。

System prompt 定义了"计划模式工作流"：创建计划 → 更新子任务状态 → 完成子任务 → 放弃子任务 → 查看计划。这是一个非常结构化的 ReAct 流程，要求 LLM 严格遵循工具调用的顺序。

### 6.3 TaskBizAgent：任务提醒

**位置**：`core/.../agent/impl/TaskBizAgent.java`

```java
@Component
public class TaskBizAgent extends AbsBizAgent {
    // 使用父类的默认 init()，自动构建 BizAgentLlmCaller

    @Override
    public ToolCallback[] getTools() {
        return ToolCallbacks.from(TaskTool.builder().taskManager(taskManager).build());
    }

    @Override
    public String process(...) {
        return llmCaller.call(userConversationInfo, new Prompt(message.getMessage()));
    }
}
```

**最精简的 Agent 实现**：只提供了 `TaskTool` 这一个工具，没有重写 `init()`。这是"专业 Agent"的典型模式——专注、轻量、不注入用户偏好。

注意到 `process()` 直接传 `new Prompt(message.getMessage())`，而其他 Agent 用 `llmCaller.call(userConversationInfo, message)`。前者跳过 `BizAgentLlmCaller.buildUserMessage()` 的媒体处理逻辑。因为 TaskBizAgent 不需要处理图片/文件，跳过媒体处理节省了一次对象构建。

### 6.4 CustomChatBizAgent：通用对话

**位置**：`core/.../agent/impl/CustomChatBizAgent.java`

最标准的"通用 Agent"实现——使用 `UserPreferenceBasedLlmCaller`，支持全部自动发现工具，没有额外的业务逻辑。

```java
@Override
public AgentIntro getAgentIntro() { return PresetAgentIntro.CHAT; }

@Override
public List<AgentIntro> getSupportedIntents() {
    return List.of(PresetAgentIntro.CHAT);
}
```

**明确声明只支持 CHAT 意图**：这意味着它不会被路由去处理"职位推荐"或"任务管理"等业务意图。但如果用户意图识别为 CHAT，它就是首选。

### 6.5 PreferenceSettingBizAgent：偏好设置

**位置**：`core/.../agent/impl/PreferenceSettingBizAgent.java`

**最复杂的 Agent 实现**，因为它通过 `@Tool` 注解提供了三个工具方法：

```java
@Tool(description = "修改用户的默认模型")
public String updateActiveModel(
    @JsonPropertyDescription("模型提供方") String provider,
    @JsonPropertyDescription("模型名称") String model,
    @JsonPropertyDescription("模型类型") ModelConfig.ModelType type,
    ToolContext toolContext) { ... }

@Tool(description = "添加大模型访问的APIKEY")
public String addModelApiKey(
    @JsonPropertyDescription("模型提供方") String provider,
    @JsonPropertyDescription("模型APIKEY") String apiKey,
    ToolContext toolContext) { ... }

@Tool(description = "查询显示用户的当前个人偏好设置")
public AiUserPreferenceProperties.UserPreferenceEntry showMyPreference(ToolContext toolContext) { ... }
```

**为什么工具方法放在 Agent 类内部？** 因为这些工具操作的是 Agent 直接依赖的 `AiUserPreferenceService` 和 `AiUserPreferenceProperties`。如果放在外部工具类中，就需要额外传递这些依赖，增加复杂度。Spring AI 的 `ToolCallbacks.from(this)` 可以直接将当前对象的方法暴露为工具，这是非常便捷的做法。

**安全性考虑**：`showMyPreference()` 内部调用了 `securityReturn()` 方法，对 API Key 做脱敏处理：

```java
private AiUserPreferenceProperties.UserPreferenceEntry securityReturn(AiUserPreferenceProperties.UserPreferenceEntry entry) {
    // 只复制基本字段，provider.apiKey 通过 SensitiveUtil 脱敏
    ...
    provider.setApiKey(SensitiveUtil.securityReturn(config.getApiKey()));
    ...
}
```

任何通过 LLM 返回给用户的数据都经过敏感信息过滤，这是 AI 应用中"数据泄露防护"的典型做法。

**关键的用户校验**：

```java
if (!NumberUtil.isNumber(userConversationInfo.jobClawUserId())) {
    return "仅绑定求职派账号的用户才可以使用服务偏好配置Agent哦~";
}
```

为什么需要这个校验？因为偏好设置操作需要通过 `AiUserPreferenceService` 持久化，而 `AiUserPreferenceService.saveOrUpdate()` 需要数字类型的 userId。未绑定账号的匿名用户没有数字 ID，无法完成持久化。

### 6.6 内置 Agent 对比

| Agent | 意图 ID | LLM Caller | 工具 | 权限 | 优先级 | 特殊点 |
|-------|---------|-----------|------|------|--------|--------|
| SimpleDefaultBizAgent | default | UserPreferenceBased | 全自动发现 | TOTAL | MIN_VALUE | 兜底 + help 处理 |
| PlanBizAgent | plan | UserPreferenceBased | 全 + PlanNotebook | TOTAL | 0 | 条件可用性 |
| TaskBizAgent | task | BizAgentLlmCaller | TaskTool | TOTAL | 0 | 最精简 |
| CustomChatBizAgent | chat | UserPreferenceBased | 全自动发现 | TOTAL | 0 | 通用对话 |
| PreferenceSettingBizAgent | preferenceAgent | BizAgentLlmCaller | 内部 @Tool | TOTAL | 0 | 内置工具方法 |

---

## 七、独立业务 Agent 实现

`agents/` 目录下的业务 Agent 是独立的 Maven 模块，拥有自己的 Service、Entity、Repository。

### 7.1 JobFetchAgent：职位抓取

**位置**：`agents/job-fetch-agent/.../JobFetchAgent.java`

```java
@Component
public class JobFetchAgent extends AbsBizAgent {
    @Override
    public AgentPermission permission() { return AgentPermission.ADMIN; }

    @Override
    public AgentIntro getAgentIntro() { return PresetAgentIntro.COLLECT; }

    @Override
    public List<AgentIntro> getSupportedIntents() {
        return List.of(PresetAgentIntro.COLLECT, PresetAgentIntro.CHAT);
    }
}
```

**ADMIN 权限**: 为什么抓取 Agent 需要管理员权限？因为它涉及数据写入（将抓取的职位入库）。在求职派的设计中，任何涉及"写入操作"的 Agent 都默认为 ADMIN 权限，防止未授权用户注入恶意数据。

**File/Media 的特殊处理**：`process()` 和 `stream()` 都做了"是否包含附件"的判断：

```java
if (CollectionUtils.isEmpty(message.getFiles()) && CollectionUtils.isEmpty(message.getMedias())) {
    return llmCaller.call(userConversationInfo, message);  // 走 LLM 对话
} else {
    // 直接调用 JobFetchService 处理文件，不走 LLM
    JobFetchTaskResponse taskResponse = jobFetchService.fetchFromTextOrLocalFile(...);
    return buildTaskCreatedMessage(taskResponse, "文件/图片");
}
```

**为什么附件不走 LLM？** 因为附件（PDF、Word、图片）中的职位信息提取是一个"确定性"任务——把文件内容解析成结构化的职位数据。不需要 LLM 对话，直接通过 OCR/解析管道处理更高效、更可靠。这也展示了 BizAgent 的灵活性：`process()` 可以完全不调用 `llmCaller`，而是直接调用业务 Service。

### 7.2 JobRecommendAgent：职位推荐

**位置**：`agents/job-recommend-agent/.../JobRecommendAgent.java`

```java
@Component
public class JobRecommendAgent extends AbsBizAgent {
    @PostConstruct
    public void init() {
        this.llmCaller = new UserPreferenceBasedLlmCaller(
            modelProviders, identityAgent, chatMemory, taskManager,
            autoDiscoveredTools, getSystemPrompt()
        );
        this.llmCaller.setWorkspace(workspace);
    }

    @Override
    public ToolCallback[] getTools() {
        return ToolCallbacks.from(jobLibraryTool);  // 职位库搜索工具
    }
}
```

**为什么 RecommendAgent 使用 UserPreferenceBasedLlmCaller？** 职位推荐的核心是"理解用户需求并匹配岗位"。这需要完整的用户身份信息（职位偏好、行业倾向、薪资期望等），identityAgent.buildSystemPrompt() 注入的用户画像刚好提供了这些信息。

**为什么它注册了所有自动发现工具却没有 PlanNotebook 的问题？** 实际上 UserPreferenceBasedLlmCaller 会注册全工具链，但 JobRecommendAgent 的 System prompt 明确限定了使用范围："必须使用工具从数据库检索"。即使有 ShellTools、FileSystemTools 等通用工具，LLM 在 system prompt 的约束下不会随意使用它们——System Prompt 就是 LLM 的行为准则。

---

## 八、路由集成

BizAgent 不是孤立运行的，它通过三个层次与路由系统集成。

### 8.1 PresetAgentIntro → AgentRegistry → AgentRouter 映射链

```
PresetAgentIntro (枚举)
  │  agentId = "JobFetchAgent"
  │  intro = "JobFetchAgent是专门用于获取职业信息的Agent..."
  │  description = "收集和管理岗位信息..."
  │
  ▼
AgentRegistry (DefaultAgentRegistry)
  │  INTENT_AGENT_MAPPING:
  │    COLLECT → ["JobFetchAgent", "default"]
  │    RECOMMEND → ["JobRecommendAgent", "default"]
  │    ...
  │  agents: ConcurrentHashMap<String, BizAgent>
  │
  ▼
AgentRouter (IntentBasedAgentRouter)
  │  route(classification, currentBoundAgent)
  │    1. 系统意图 → 不路由
  │    2. 保持当前 Agent（如果支持新意图）
  │    3. 根据意图从 AgentRegistry 获取候选列表
  │    4. 取第一个候选（按优先级排序）
  │    5. 兜底到默认 Agent
  │    6. 所有失败 → "__unknown__"
```

**这个映射链的核心理念**：意图（PresetAgentIntro）是抽象的，Agent 注册中心维护"意图 → Agent ID 列表"的映射，路由层使用这个映射做决策。

**为什么 COLLECT 映射到 ["JobFetchAgent", "default"]？** 这是"首选 + 兜底"模式。当用户意图被识别为 COLLECT 时，优先路由到 `JobFetchAgent`。但如果 `JobFetchAgent` 未注册或不可用（例如 agents 模块未部署），则回退到 `default` 兜底 Agent。

### 8.2 MsgRouter 中的 Agent 调用

`MsgRouter.onMessageReceived()` 的最后一步：

```java
// Step 5: 路由到 Agent
AgentRouter.RouterResult routeResult = agentRouter.route(classification, currentBoundAgent);

// Step 6: 绑定会话状态（如果是新会话）
if (routeResult.isNewSession()) {
    sessionBinder.bind(jobClawUserId, conversationId, routeResult.agentId());
}

// Step 7: 执行 Agent
routeToAgent(routeResult.agentId(), msg, conversationInfo);
```

`routeToAgent()` 是 BizAgent 调用的入口：

```java
private void routeToAgent(String agentId, ChannelReceiveMessage msg, UserConversationInfo conversationInfo) {
    BizAgent agent = agentRegistry.getAgent(agentId)
        .orElseGet(() -> agentRegistry.getDefaultAgent().orElse(null));

    if (agent == null) {
        sendTextResponse(msg, "没有找到可用的Agent哦，请稍后再试。");
        return;
    }

    conversationInfo.setAgent(agent.getAgentIntro().getAgentId());

    try {
        String response = null;
        Flux<LlmRspCell> streamRes = null;
        if (msg.isStream()) {
            streamRes = agent.stream(conversationInfo, msg);
        } else {
            response = agent.process(conversationInfo, msg);
        }

        if (response == null && streamRes == null) {
            fallbackToLlm(msg, conversationInfo);  // Agent 处理失败 → LLM 兜底
            return;
        }

        sendTextResponse(msg, response, streamRes);
    } catch (Exception e) {
        fallbackToLlm(msg, conversationInfo);  // Agent 异常 → LLM 兜底
    }
}
```

**两步兜底机制**：
1. **Agent 不可用**：`agentRegistry.getAgent(agentId).orElseGet(getDefaultAgent)` — 如果指定 Agent 不存在，用默认 Agent 替代
2. **Agent 执行失败**：`fallbackToLlm()` — 如果 Agent 返回 null 或抛出异常，直接调用 LLM 响应，确保用户永远能收到回复

**为什么会话绑定在路由后、执行前？** 如果执行成功，会话已经绑定，后续消息直接路由到同一个 Agent（不再走意图识别）。如果执行失败，`fallbackToLlm()` 不感知绑定——它只是返回 LLM 生成的文本，不会修改绑定状态。这是"执行出错不破坏状态"的设计。

---

## 九、扩展指南：如何新增一个 BizAgent

### 9.1 新增内置 Agent（core 模块）

**步骤**：

1. 在 `PresetAgentIntro` 枚举中新增一个枚举值，定义 agentId/intro/description
2. 在 `DefaultAgentRegistry.INTENT_AGENT_MAPPING` 中添加意图 → Agent ID 映射
3. 创建 Agent 类，继承 `AbsBizAgent`，标注 `@Component`
4. 根据是否需要用户偏好，决定使用 `BizAgentLlmCaller` 还是 `UserPreferenceBasedLlmCaller`
5. 实现 `getSystemPrompt()`、`getAgentIntro()`、`permission()`、`process()`/`stream()`

**示例模板**（使用默认 BizAgentLlmCaller）：

```java
@Component
public class MyNewAgent extends AbsBizAgent {
    public MyNewAgent(ModelProviders modelProviders,
                      IIdentityAgent identityAgent,
                      ChatMemory chatMemory) {
        super(modelProviders, chatMemory, identityAgent);
    }

    @Override
    public AgentPermission permission() { return AgentPermission.TOTAL; }

    @Override
    public AgentIntro getAgentIntro() { return PresetAgentIntro.MY_NEW; }

    @Override
    public String getSystemPrompt() { return "你是..."; }

    @Override
    public String process(...) {
        return llmCaller.call(userConversationInfo, message);
    }

    @Override
    public Flux<LlmRspCell> stream(...) {
        return llmCaller.stream(userConversationInfo, message, LlmRspCell::of);
    }
}
```

### 9.2 新增独立业务 Agent（agents 模块）

**步骤**：

1. 在 `PresetAgentIntro` 中新增枚举值（或复用现有枚举）
2. 在 `DefaultAgentRegistry.INTENT_AGENT_MAPPING` 中添加映射
3. 在 `agents/` 下创建新的 Maven 子模块（参考 job-fetch-agent 的 pom.xml）
4. 创建 Agent 类，通过构造注入注入业务 Service
5. 注册工具（`ToolCallbacks.from(toolService)`）

**与传统 Spring Service 的关系**：`AbsBizAgent` 不需要实现 `InitializingBean` 或 `ApplicationListener`，因为 `@PostConstruct init()` 提供了初始化点，`@EventListener` 提供了事件监听。Agent 可以像普通 Spring Bean 一样使用构造注入。

---

## 十、架构思考：为什么这样设计 BizAgent？

### 10.1 为什么不在 BizAgent 中直接管理会话？

BizAgent 的 `process()` 和 `stream()` 接收 `UserConversationInfo` 作为参数，但它不主动管理会话绑定。会话绑定由 `SessionAgentBinder` 在 `MsgRouter` 中维护。

**理由**：会话绑定是"消息路由"的职责，不是"业务处理"的职责。如果一个 Agent 能主动解绑或切换会话，会造成路由混乱——消息发送给 Agent A，Agent A 在内部把绑定改成了 Agent B，后续消息都到了 Agent B 但用户不知情。

### 10.2 为什么不在 BizAgent 中处理消息的响应发送？

`process()` 和 `stream()` 的返回值是 `String` 和 `Flux<LlmRspCell>`，而不是直接发送消息到通道。

**理由**：Agent 不知道消息来自哪里（微信/钉钉/飞书），也不应该知道。通道选择是 `MsgRouter.onMessageResponse()` 通过 `ChannelEventPublisher` 和 `ChannelRegistry` 完成的。Agent 只专注于生成响应内容，通道路由是分离的关注点。

### 10.3 为什么 BizAgent 体系能应对"100 个 Agent"的扩展？

求职派的核心抽象链：`PresetAgentIntro`（意图枚举） → `INTENT_AGENT_MAPPING`（映射表） → `DefaultAgentRegistry`（注册中心）可以实现多对多映射——一个意图可以映射到多个 Agent（按优先级），一个 Agent 可以支持多个意图。

当 Agent 数量增长时：
- 注册：`@Component` 自动发现，零配置
- 路由：`AgentRouter` 按意图 + 优先级 + 权限 + 可用性过滤，O(n) 遍历
- 权限：`AgentPermission` + `UserRoleEnum` 分层控制
- 隔离：`agents/` 模块独立部署互不影响

每个 Agent 只依赖不变的 `core` 接口，不依赖其他 Agent 的实现。这种"依赖倒置"让 Agent 成为可替换的插件，而不是耦合的业务逻辑。

---

:::success
相关代码：
- BizAgent 接口：`core/src/main/java/com/git/hui/jobclaw/core/agent/BizAgent.java`
- AbsBizAgent 抽象类：`core/src/main/java/com/git/hui/jobclaw/core/agent/impl/AbsBizAgent.java`
- LlmCaller 接口：`core/src/main/java/com/git/hui/jobclaw/core/agent/llm/LlmCaller.java`
- SimpleLlmCaller（基础实现）：`core/src/main/java/com/git/hui/jobclaw/core/agent/llm/SimpleLlmCaller.java`
- BizAgentLlmCaller（带记忆/身份的 LLM 调用）：`core/src/main/java/com/git/hui/jobclaw/core/agent/llm/BizAgentLlmCaller.java`
- UserPreferenceBasedLlmCaller（带用户偏好的 LLM 调用）：`core/src/main/java/com/git/hui/jobclaw/core/agent/llm/UserPreferenceBasedLlmCaller.java`
- SimpleDefaultBizAgent（兜底）：`core/src/main/java/com/git/hui/jobclaw/core/agent/impl/SimpleDefaultBizAgent.java`
- PlanBizAgent（计划模式）：`core/src/main/java/com/git/hui/jobclaw/core/agent/impl/PlanBizAgent.java`
- TaskBizAgent（任务提醒）：`core/src/main/java/com/git/hui/jobclaw/core/agent/impl/TaskBizAgent.java`
- CustomChatBizAgent（通用聊天）：`core/src/main/java/com/git/hui/jobclaw/core/agent/impl/CustomChatBizAgent.java`
- PreferenceSettingBizAgent（偏好设置）：`core/src/main/java/com/git/hui/jobclaw/core/agent/impl/PreferenceSettingBizAgent.java`
- ReActAdvisor（自定义 ReAct 循环）：`core/src/main/java/com/git/hui/jobclaw/core/agent/react/ReActAdvisor.java`
- IIdentityAgent（身份注入接口）：`core/src/main/java/com/git/hui/jobclaw/core/agent/IIdentityAgent.java`
- UserConversationInfo：`core/src/main/java/com/git/hui/jobclaw/core/agent/models/UserConversationInfo.java`
- PresetAgentIntro（意图枚举）：`core/src/main/java/com/git/hui/jobclaw/core/router/intent/PresetAgentIntro.java`
- AgentRegistry（Agent注册中心）：`core/src/main/java/com/git/hui/jobclaw/core/router/intent/AgentRegistry.java`
- DefaultAgentRegistry（注册中心实现）：`core/src/main/java/com/git/hui/jobclaw/core/router/intent/impl/DefaultAgentRegistry.java`
- AgentRouter（Agent路由接口）：`core/src/main/java/com/git/hui/jobclaw/core/router/intent/AgentRouter.java`
- IntentBasedAgentRouter（路由实现）：`core/src/main/java/com/git/hui/jobclaw/core/router/intent/impl/IntentBasedAgentRouter.java`
- AgentPermission（权限模型）：`core/src/main/java/com/git/hui/jobclaw/core/apis/permission/AgentPermission.java`
- MsgRouter（消息路由集成）：`core/src/main/java/com/git/hui/jobclaw/core/router/MsgRouter.java`
- JobFetchAgent（职位抓取 Agent）：`agents/job-fetch-agent/src/main/java/.../JobFetchAgent.java`
- JobRecommendAgent（职位推荐 Agent）：`agents/job-recommend-agent/src/main/java/.../JobRecommendAgent.java`
- SmartWindowChatMemory（智能窗口记忆）：`core/src/main/java/com/git/hui/jobclaw/core/agent/memory/SmartWindowChatMemory.java`
:::

---

> 相关文档：
> - [消息路由与多轮对话状态管理](./08-✅求职派消息路由与多轮对话状态管理实战.md)
> - [用户身份采集与 Agent 灵魂塑造](./09-✅求职派用户身份采集与Agent灵魂塑造实战.md)
> - [用户偏好设置与多用户隔离](./10-✅求职派用户偏好设置与多用户隔离实战.md)
> - [系统命令与 CLI 交互机制](./11-✅求职派系统命令与CLI交互机制实战.md)
> - [Plugin 插件系统与自动工具发现](./07-✅求职派Plugin插件系统与自动工具发现机制实战.md)
> - [V2 重构总览](./00-求职派V2重构：从单体应用到多Agent运行时.md)
