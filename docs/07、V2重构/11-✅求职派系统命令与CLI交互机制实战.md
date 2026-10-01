# ✅求职派系统命令与CLI交互机制实战

> 用户发来一条 `/help` 或 `/agents`，系统不应该走完整的意图识别和 Agent 路由流程——这些命令有明确的语义，应该被立即识别并快速响应。但如果直接硬编码 if-else 判断，每新增一个命令就要改 MsgRouter，扩展性差。怎么设计一套可扩展的 CLI 机制，既能快速响应命令，又能与意图分类器协同工作？本文完整记录求职派的系统命令体系——从命令识别、策略分发、权限管控到与正常对话流程的衔接。

---

## 一、为什么需要系统命令？

### 1.1 没有系统命令会怎样？

**场景还原**：

```
用户（首次使用）："/help"
用户（想切换 Agent）："/agent JobRecommendAgent"
用户（想重置）："/reset"
```

**没有系统命令的系统**：

```java
// 反例：所有消息都走意图识别 + Agent 路由
IntentClassificationRes intent = intentClassifier.classify(msg, userMessage);
AgentRouter.RouterResult route = agentRouter.route(intent, boundAgent);
routeToAgent(route.agentId(), msg);
```

**问题**：

| 问题 | 后果 |
|------|------|
| **延迟高** | `/help` 这种简单请求也要走一遍 LLM 意图识别，浪费时间和费用 |
| **不确定** | LLM 可能把 `/reset` 误解为"重新设置"而非"重置会话" |
| **扩展难** | 新增命令要同时改命令处理和意图分类，耦合度高 |
| **无法覆盖** | `/agent xxx` 这种精确切换，意图分类器很难 100% 识别 |

### 1.2 V2 的设计目标

> **如何在消息路由中建立一条"快车道"，让系统命令以最短路径响应，同时与意图分类器协同配合，确保两条路径不打架？**

四个核心诉求：

1. **快速响应**：命令消息不经过 LLM 意图识别，直接匹配执行
2. **策略可扩展**：新增命令只需添加一个 Handler，不改 MsgRouter
3. **与意图识别协同**：命令也能被意图分类器识别，自然语言触发和命令触发互通
4. **权限管控**：部分命令（如 `/agent xxx`）需要校验用户权限

### 1.3 为什么选择 `/` 前缀而非自然语言触发？

考虑过"自然语言触发"方案——用户说"帮我看看帮助"就触发 HelpHandler。但这会模糊系统命令和业务对话的边界，带来两个根本性问题：

1. **误触发无法避免**：用户说"这个任务需要帮助"和"帮帮我"都可能触发 HELP 意图，但用户本意是正常的任务对话。在一个 Agent 系统中，消息要么走命令处理，要么走意图识别，如果边界模糊，消息可能被两条路径同时处理。
2. **通道层和路由层职责冲突**：自然语言触发本质是意图识别的范畴，应该由 `IntentClassifier` 处理。如果在 Channel 层（消息进入的地方）做自然语言匹配，Channel 就承担了它不该承担的职责——它只负责消息适配和身份注入。

`/` 前缀的好处是提供了**确定的语义边界**：以 `/` 开头的就是命令，不以 `/` 开头的就是业务对话。这条规则简单到不需要 AI 判断，0 误判率，且 MsgRouter 在 Step 2 就可以用 O(1) 的复杂度完成决策。

**架构上的意义**：`/` 前缀不是"UI 设计"，而是"架构契约"。它让系统命令在事件驱动架构中成为一条独立的处理流，不污染意图分类器和 Agent 路由的设计。

---

## 二、整体架构

```
MsgRouter.onMessageReceived()
  │
  ├─ Step 1: 身份采集（略）
  │
  ├─ Step 2: 系统命令处理 ← 快车道
  │     commandDispatcher.isSystemCommand(message)
  │     └─ true → commandDispatcher.executeCommand()
  │           ├─ /help    → HelpCommandHandler
  │           ├─ /agents  → ListAgentsCommandHandler
  │           ├─ /current → CurrentAgentCommandHandler
  │           ├─ /agent   → SwitchAgentCommandHandler  (权限校验)
  │           ├─ /reset   → ResetCommandHandler
  │           └─ /plan    → PlanModeCommandHandler
  │     return（不再走后续流程）
  │
  ├─ Step 2.5: /agentId 快捷切换 ← 备用通道
  │     startsWith("/agentId") → 直接 routeToAgent()
  │     （当命令调度器无法处理时才进入此分支）
  │
  └─ Step 3+: 意图识别 + Agent 路由 ← 慢车道
        （系统命令已在 Step 2 被拦截，不进入此流程）
```

### 2.1 命令 vs 自然语言：双入口覆盖

```
用户输入 "/help"
  │
  ├─ 入口 A（CLI 快车道）
  │     Step 2: isSystemCommand → true → executeCommand
  │     响应："您好！我是求职派助手..."
  │
  └─ 入口 B（自然语言慢车道）
        用户输入 "帮帮我" → 意图识别 → HELP 意图 → HelpCommandHandler
        （同一 Handler 可被两种入口触发）
```

```
用户输入 "/agent JobRecommendAgent"
  │
  ├─ Step 2: /agent → SwitchAgentCommandHandler（权限校验后切换）
  │
  └─ Step 2.5: /JobRecommendAgent → 直接路由到 JobRecommendAgent
        （当命令没有对应 Handler 时，尝试按 Agent ID 匹配）
```

### 2.2 架构层面的设计权衡

**为什么系统命令处理放在 Step 2（身份采集之后、意图识别之前），而不是在 MsgRouter 入口的第一个位置？**

这个位置是刻意选择的。MsgRouter 的 Step 顺序是：Step 1 身份采集（中断型） → Step 2 系统命令（快车道） → Step 3+ 意图识别（全链路）。系统命令放在 Step 2 而不是 Step 1 之前的原因是：

1. **身份采集的优先级高于一切**：如果用户还没有完成身份采集（soul.md / user.md 缺失），此时 `/help` 返回的帮助信息是没有意义的——用户根本还没到"使用系统"的阶段。身份采集先处理，采集完成后再放行到 Step 2。
2. **命令响应需要身份上下文**：`/current` 需要 `jobClawUserId` 去查会话绑定，`/agent` 需要 `jobClawUserId` 去做权限校验。如果放在 Step 1 之前，用户身份尚未注入，部分命令会因缺少上下文而无法执行。

**为什么采用策略模式而非职责链模式？**

策略模式是"选择一条路径执行"，职责链模式是"依次尝试直到有一条处理"。两者在实现上差别不大（都是 List 遍历），但语义上策略模式更符合系统命令的特征：

- **策略模式**：命令与 Handler 是 1:1 映射，每条命令只能由一个 Handler 处理
- **职责链模式**：消息沿着链传递，直到某个节点处理；允许多个节点处理同一消息

系统命令是一对一的——`/help` 只应该由 `HelpCommandHandler` 处理，不应该有第二个 Handler 插进来。策略模式的语义清晰表达了"一个命令对应一个处理器"的设计约束。

**为什么不把命令处理放在 Channel 层以追求更快的响应速度？**

Channel 层是消息的入口，在 Channel 层做 `/` 检测速度当然更快（省去了事件总线的传递）。但这样做会在每个 Channel 实现中重复命令处理逻辑——钉钉处理一份、飞书处理一份、微信处理一份。而系统命令的语义与渠道无关，放在 MsgRouter（所有 Channel 共享）是最合理的位置，一次实现处处生效。

---



## 三、核心抽象

### 3.1 SystemCommandHandler 接口

定义在 `core/.../cli/SystemCommandHandler.java`：

```java
public interface SystemCommandHandler {

    /** 判断是否支持处理该命令（默认实现：命令以指定前缀开头） */
    default boolean supports(String command) {
        return command.startsWith(getIntentType().getCommand());
    }

    /** 执行命令处理 */
    boolean handle(ChannelReceiveMessage msg, UserConversationInfo conversationInfo,
                   String command, Function<String, Boolean> process);

    /** 获取命令对应的意图类型 */
    PresetAgentIntro getIntentType();

    /** 获取命令描述（用于帮助文档） */
    default String getDescription() {
        return getIntentType().getDescription();
    }
}
```

**接口设计的三个要点**：

| 点 | 说明 |
|----|------|
| `supports()` 默认实现 | 基于 `startsWith()` 前缀匹配，大多数 Handler 不需要覆盖 |
| `handle()` 的 process 回调 | Handler 不负责发送响应，只负责生成内容，由调用方（MsgRouter）决定怎么发送 |
| `getIntentType()` | 每个 Handler 绑定一个 PresetAgentIntro，实现命令 ↔ 意图的双向映射 |

#### 3.1.1 接口设计的思考过程

**为什么 supports() 默认用 startsWith 而不是 equals？**

这是由系统命令的参数语义决定的。`/agent JobRecommendAgent` 是一个带参数的命令，Handler 匹配的是命令前缀 `/agent`，具体参数由 Handler 自行解析。如果用 `equals`，`/agent` 后面必须没有空格和参数，那所有带参数的命令都无法匹配。

`startsWith` 的问题是可能误匹配——`/agent-special` 也会匹配前缀 `/agent`。但在实际中，所有系统命令的定义在 `PresetAgentIntro` 中是固定的（`/help`、`/agents`、`/current`、`/agent`、`/reset`、`/plan`），不存在 `agent-special` 这样的命令名。对于需要精确匹配的场景，`PlanModeCommandHandler` 通过覆盖 `supports()` 切换到了 `equals` 模式。

**为什么 handle() 的 process 回调是 Function<String, Boolean> 而不是直接返回响应字符串？**

这是"职责分离"原则在接口设计中的体现。Handler 的职责是**生成响应内容**，不关心怎么发送；MsgRouter 的职责是**发送响应**，不关心内容怎么生成。

如果 Handler 直接返回字符串，MsgRouter 拿到字符串后需要判断"是否要发送"以及"发送给谁"——这又回到了 MsgRouter。如果 Handler 直接调用 `channel.responseToUser()`，Handler 就依赖了 Channel 层的具体实现，不方便测试也无法独立部署。

`process` 回调的 `Function<String, Boolean>` 设计是"依赖倒置"的一种轻量实现——Handler 依赖的是"一个能处理字符串的函数"这个抽象，而不是具体的 Channel 实现。这也是 `handle()` 方法签名中没有返回值的原因：处理结果通过回调返回，而不是通过返回值。

### 3.2 SystemCommandDispatcher

定义在 `core/.../cli/SystemCommandDispatcher.java`，是命令处理的中枢：

```java
@Component
public class SystemCommandDispatcher {

    private final List<SystemCommandHandler> handlers;

    // 1. 命令识别 — 通过前缀检测
    public boolean isSystemCommand(String message) {
        if (message == null || message.isBlank()) return false;
        return message.trim().toLowerCase().startsWith("/");
    }

    // 2. 意图识别 — 找到命令对应的 PresetAgentIntro
    public Optional<PresetAgentIntro> recognizeIntent(String message) {
        if (!isSystemCommand(message)) return Optional.empty();
        for (SystemCommandHandler handler : handlers) {
            if (handler.supports(normalized)) {
                return Optional.of(handler.getIntentType());
            }
        }
        return Optional.empty();
    }

    // 3. 命令执行 — 路由到对应 Handler，设置 CLI Agent 上下文
    public boolean executeCommand(msg, conversationInfo, message, process) {
        for (SystemCommandHandler handler : handlers) {
            if (handler.supports(normalized)) {
                conversationInfo.setAgent("CLI-" + handler.getIntentType().getAgentId());
                return handler.handle(msg, conversationInfo, normalized, process);
            }
        }
        return false;
    }

    // 4. 帮助文档 — 收集所有已注册命令的描述
    public String getAllCommandDescriptions() { ... }
}
```

#### 3.2.1 Dispatcher 设计的思考过程

**为什么 getAllCommandDescriptions 返回 String 而不是 List？**

Http API 场景下返回 List 然后前端渲染是合理的。但在 IM 对话场景中，输出是纯文本（Markdown），Handler 需要的是拼接好的字符串，而不是一个数组。`getAllCommandDescriptions()` 直接返回格式化好的字符串，Handler 不需要再做拼接。

这个决策本质是"调用方优先"原则的体现：系统的唯一调用方是 `HelpCommandHandler`，它需要的是字符串而不是 List，那就返回字符串。不被"通用性"绑架，只服务真实场景。当然返回List也是比较推荐的做法，后续的扩展性更强

**为什么用 @Component 自动注入而不是手动注册？**

`SystemCommandDispatcher` 通过 Spring 的依赖注入自动收集所有 `SystemCommandHandler` 实现。这意味着新增一个命令只需要写一个 `@Component` 类，不需要修改任何注册代码。

这是策略模式的典型优势：**策略的发现机制与策略的执行机制分离**。`SystemCommandDispatcher` 不需要知道有哪些 Handler 存在，Spring 容器负责发现，Dispatcher 只负责按需调用。

---

## 四、命令处理器详解

### 4.1 六种内置命令

| 命令 | Handler | PresetAgentIntro | 功能 |
|------|---------|------------------|------|
| `/help` | `HelpCommandHandler` | HELP | 显示帮助信息 |
| `/agents` | `ListAgentsCommandHandler` | LIST_AGENTS | 列出所有可用 Agent |
| `/current` | `CurrentAgentCommandHandler` | CURRENT_AGENT | 查看当前绑定的 Agent |
| `/agent <name>` | `SwitchAgentCommandHandler` | SWITCH_AGENT | 切换到指定 Agent（含权限校验） |
| `/reset` | `ResetCommandHandler` | RESET | 重置会话绑定 |
| `/plan` | `PlanModeCommandHandler` | PLAN | 进入计划模式 |

### 4.2 /help — 帮助命令

最简单的 Handler——从 `SystemCommandDispatcher` 获取所有已注册命令的描述，拼接后返回：

```java
@Component
public class HelpCommandHandler implements SystemCommandHandler {

    @Override
    public boolean handle(..., Function<String, Boolean> process) {
        String helpText = SpringUtil.getBean(SystemCommandDispatcher.class)
                .getAllCommandDescriptions();
        return process.apply(String.format("""
                您好！我是求职派助手，请问有什么可以帮助您的？

                可用命令：

                %s

                我可以帮您：
                - 推荐岗位 - 根据您的偏好推荐合适的岗位
                - 订阅推送 - 订阅您感兴趣的岗位推送通知
                - 查询状态 - 查看您的投递记录和面试状态
                - 收集信息 - 帮您收集和整理岗位信息
                """, helpText));
    }

    @Override
    public PresetAgentIntro getIntentType() { return PresetAgentIntro.HELP; }
}
```

**关键设计**：`HelpCommandHandler` 自己不维护命令列表，而是通过 `SystemCommandDispatcher.getAllCommandDescriptions()` 动态获取。新增命令时，帮助文档自动更新，不需要改 HelpCommandHandler。

### 4.3 /agents — 列出 Agent

```java
@Component
public class ListAgentsCommandHandler implements SystemCommandHandler {

    private final AgentRegistry agentRegistry;

    @Override
    public boolean handle(...) {
        var agents = agentRegistry.getAllAgents(conversationInfo.jobClawUserId());
        // 遍历 agentList，格式化输出每个 Agent 的 ID 和介绍
        return process.apply(sb.toString());
    }

    @Override
    public PresetAgentIntro getIntentType() { return PresetAgentIntro.LIST_AGENTS; }
}
```

**关键设计**：通过 `AgentRegistry.getAllAgents(userId)` 查询——不同用户根据权限看到不同的 Agent 列表。调用 `getAllAgents(userId)` 而不是静态列表，是因为后续可能基于用户角色做 Agent 可见性控制。

### 4.4 /current — 查看当前 Agent

```java
@Component
public class CurrentAgentCommandHandler implements SystemCommandHandler {

    private final SessionAgentBinder sessionBinder;
    private final AgentRegistry agentRegistry;

    @Override
    public boolean handle(...) {
        Optional<BoundAgentInfo> boundAgent = sessionBinder.getBoundAgent(
                conversationInfo.jobClawUserId(),
                conversationInfo.conversationId());

        if (boundAgent.isPresent()) {
            // 显示绑定的 Agent ID、描述、绑定时间、过期时间
        } else {
            // 显示"未绑定"，提示可用操作
        }
        return process.apply(sb.toString());
    }
}
```

**关键设计**：不仅能看绑定了哪个 Agent，还能看到**绑定时间和过期时间**。这对调试多轮对话绑定问题非常有用——用户可以自己看到"这个绑定还有效吗？"。

### 4.5 /agent <name> — 切换 Agent（含权限校验）

最复杂的 Handler，因为它涉及**权限管控**：

```java
@Component
public class SwitchAgentCommandHandler implements SystemCommandHandler {

    @Override
    public boolean handle(...) {
        Optional<String> targetAgentId = parseAgentSwitchCommand(msg.getMessage());

        if (targetAgentId.isPresent()
                && agentRegistry.hasAgent(targetAgentId.get())) {
            var agent = agentRegistry.getAgent(targetAgentId.get()).get();

            // 权限管控
            if (agent.permission().enabled(getUserRole(conversationInfo.jobClawUserId()))) {
                sessionBinder.bind(userId, conversationId, targetAgentId.get());
                return process.apply("已为您切换到 " + agentId + "...");
            }
        }

        // 无效的 Agent ID → 显示可用列表让用户重新选择
        return process.apply(showAgentList(userId));
    }

    private UserRoleEnum getUserRole(String jobClawUserId) {
        if (NumberUtil.isNumber(jobClawUserId)) {
            return SpringUtil.getBean(IUserService.class).getRole(jobClawUserId);
        }
        return UserRoleEnum.NORMAL;
    }
}
```

**关键设计**：

1. **权限校验**：通过 `agent.permission().enabled(userRole)` 判断——不是所有用户都能切换到所有 Agent
2. **容错提示**：Agent ID 无效时不报错，而是显示可用 Agent 列表
3. **角色查询**：匿名用户（非数字 ID）默认 NORMAL 权限

### 4.6 /reset — 重置会话

```java
@Component
public class ResetCommandHandler implements SystemCommandHandler {

    private final SessionAgentBinder sessionBinder;

    @Override
    public boolean handle(...) {
        sessionBinder.unbind(conversationInfo.jobClawUserId(),
                conversationInfo.conversationId());
        return process.apply("会话状态已重置，请告诉我您想要做什么？");
    }
}
```

**关键设计**：`/reset` 只解除了会话-Agent 绑定，不清除对话历史。目的是让用户"换一个 Agent 重试"，而不是"彻底清空聊天记录"。

**为什么权限校验在 SwitchAgentCommandHandler 而不是在 MsgRouter 或统一的权限拦截层？**

这是一个关于"权限应该在哪里拦截"的架构决策。权限是**特定命令的约束**，不是所有命令都有权限问题。六条内置命令中只有 `/agent` 和 `/plan` 需要权限校验，`/help`、`/agents`、`/current`、`/reset` 对所有用户开放。

如果把权限校验放在 MsgRouter 的统一拦截层，MsgRouter 需要理解每个命令的权限语义——`/help` 不校验、`/agent` 要校验、`/plan` 也要校验但校验逻辑不同。MsgRouter 变成了"命令权限路由器"，职责膨胀。

放在 Handler 内部的好处是：**权限逻辑与业务逻辑在一起**，哪个 Handler 需要权限，自己负责。`SwitchAgentCommandHandler` 知道它需要查用户角色，`PlanModeCommandHandler` 知道它需要查插件是否可用。这是"高内聚"原则的体现。

### 4.7 /plan — 进入计划模式

计划模式是 PlanNotebook 插件提供的功能，所以 `PlanModeCommandHandler` 需要先检查插件是否可用：

```java
@Component
public class PlanModeCommandHandler implements SystemCommandHandler {

    @Override
    public boolean supports(String command) {
        // 覆盖默认实现：要求完全匹配 /plan，而不是前缀匹配
        return PresetAgentIntro.PLAN.getCommand().equals(command.trim());
    }

    @Override
    public boolean handle(...) {
        boolean available = agentRegistry
                .getAgent(PresetAgentIntro.PLAN.getAgentId())
                .filter(agent -> agent.isAvailable())
                .isPresent();

        if (!available) {
            return process.apply("计划模式当前不可用，请确认 PlanNotebook 插件已启用。");
        }

        sessionBinder.bind(userId, convId, PresetAgentIntro.PLAN.getAgentId());
        return process.apply("已进入计划模式。请告诉我需要完成的复杂目标...");
    }
}
```

**关键设计**：

1. **覆盖 `supports()`**：要求完全匹配 `/plan` 而非前缀匹配——避免 `/planToday` 等误触发
2. **能力检查**：通过 `agent.isAvailable()` 检查插件是否启用，未启用时给出明确的错误提示
3. **与插件联动**：`/plan` 命令在插件启用后才可用，体现了 CLI 机制与插件系统的集成

### 4.8 命令体系的"宏"设计

回看六个 Handler，它们实际上覆盖了三个层面的用户需求：

| 层面 | 命令 | 解决的问题 |
|------|------|-----------|
| **信息获取** | `/help` `/agents` `/current` | "当前系统是什么状态？" |
| **状态变更** | `/agent` `/reset` | "我想改变系统的工作模式" |
| **功能入口** | `/plan` | "我想使用一个特殊功能" |

这个分层不是刻意设计的，而是在开发过程中自然形成的。每个层面对应的命令模式不同：

- **信息获取型**：只读不写，不需要权限校验，响应固定
- **状态变更型**：写入会话绑定状态，需要权限校验，响应受用户身份影响
- **功能入口型**：触发插件或 Agent，需要前置条件检查

这种分层体现了**命令体系本身就是一种"元路由"**——它不直接处理业务，而是告诉系统"接下来该怎么处理"。

---

## 五、与消息路由的集成

### 5.1 MsgRouter 中的三步路由

```java
@EventListener
public void onMessageReceived(MessageReceivedEvent event) {
    // Step 1: 身份采集（略）

    // Step 2: 系统命令处理 — 快车道
    if (commandDispatcher.isSystemCommand(userMessage)) {
        if (commandDispatcher.executeCommand(msg, conversationInfo,
                userMessage, str -> sendTextResponse(msg, str))) {
            return; // 命令已处理，不再走后续流程
        }
    }

    // Step 2.5: /agentId 快捷切换 — 备用通道
    if (userMessage.startsWith("/")) {
        for (BizAgent bizAgent : agentRegistry.getAllAgents(jobClawUserId)) {
            if (userMessage.startsWith("/" + bizAgent.getAgentIntro().getAgentId())) {
                routeToAgent(bizAgent.getAgentIntro().getAgentId(), msg, conversationInfo);
                return;
            }
        }
    }

    // Step 3+: 意图识别 + Agent 路由（略）
}
```

### 5.2 流程决策

```
消息到达 MsgRouter
  │
  ├─ 以 "/" 开头？
  │     ├─ Step 2: 有对应 Handler？      → 执行命令，返回
  │     └─ Step 2.5: 匹配 Agent ID？     → 直接路由到 Agent，返回
  │
  └─ 不以 "/" 开头？
        └─ Step 3+: 正常意图识别 + Agent 路由
```

**三种可能的结果**：

| 路径 | 条件 | 行为 |
|------|------|------|
| Step 2 命中 | `/help`、`/agents` 等已注册命令 | 执行 Handler，返回响应 |
| Step 2.5 命中 | `/JobRecommendAgent`（未注册但匹配 Agent ID） | 直接路由到对应 Agent |
| 均未命中 | 无效命令 `/xxx` | 不拦截，进入正常意图识别（LLM 可能理解） |

### 5.2.1 三步路由的递进逻辑

Step 2 → Step 2.5 → Step 3+ 形成了一个**命中率递减、处理能力递增**的递进结构：

| 步骤 | 命中率 | 处理能力 | 
|------|--------|----------|
| Step 2（命令） | ⭐⭐⭐ | ⭐ | 固定响应，快速返回 |
| Step 2.5（Agent） | ⭐⭐ | ⭐⭐ | 路由到 Agent，可能带上下文 |
| Step 3+（意图） | ⭐ | ⭐⭐⭐ | 全链路 LLM 处理 |


**为什么 Handler 优先于 Agent ID 匹配？**

如果用户输入 `/help`，Step 2.5 也能匹配到名为 `help` 的 Agent（如果存在的话）。但 Handler 是专门为 `/help` 设计的第一方处理逻辑，响应速度快、内容确定、不需要 LLM 调用。应该优先使用 Handler。

**但如果一个 Agent 注册了与命令相同的 ID 呢？**

不会冲突。有 Handler 的命令在 Step 2 就已经被拦截并 return 了，永远不会走到 Step 2.5。Step 2.5 只处理 Step 2  未匹配的命令。这意味着 Step 2 的命令优先级高于 Agent ID，这是设计意图——轻量、快速、确定的路径优先。

**为什么 Step 2 执行失败后（executeCommand 返回 false）不走意图识别而是继续 Step 2.5？**

Step 2 返回 `false` 有两种情况：
1. **没有匹配的 Handler**：用户输入了一个不在注册列表中的 `/xxx` 命令
2. **Handler 执行异常**：代码抛了异常被 `try-catch` 捕获

对于情况 1，继续走 Step 2.5 尝试匹配 Agent ID 是合理的——用户可能想通过 `/JobRecommendAgent` 直接切换到某个 Agent，而这是一个合理的快捷用法，不应该被拒绝。

对于情况 2，如果 Handler 执行出错，返回 `false` 后 Step 2.5 大概率也匹配不到，最终进入 Step 3+ 让 LLM 兜底解释。这种"层层降级"的设计保证了异常不会阻塞消息处理。

### 5.3 命令与意图的双向映射

命令体系不仅仅是 CLI，它实际上是"意图的确定性映射"。

**命令行映射**：用户输入 `/help` → `PresetAgentIntro.HELP`
**自然语言映射**：用户输入"帮帮我" → 意图分类器 → `PresetAgentIntro.HELP`

两条路径最终汇聚到同一个 `PresetAgentIntro` 上。这意味着：

- 命令处理器和意图分类器共享同一套意图定义（`PresetAgentIntro` 枚举）
- 新增一个命令等于新增一个意图，意图分类器自动支持该意图（通过 `KeywordIntentClassifier` 的关键词映射）
- 用户可以根据习惯选择"打字快用命令"或"自然对话用自然语言"，系统给出一致的响应

**为什么不是两套独立的体系？**

如果命令一种体系、意图另一种体系，就会出现"`/help` 能用但说‘帮帮我’不行"的割裂体验。让命令和意图共享 `PresetAgentIntro`，从架构层面保证了体验的一致性。

### 5.4 与意图分类器的协同

系统命令不仅能在 Step 2 被拦截，还能通过 `SystemCommandDispatcher.recognizeIntent()` 被意图分类器识别：

```java
// CompositeIntentClassifier 或 KeywordIntentClassifier
// 先检查是否是已知的 /command
var systemIntent = commandDispatcher.recognizeIntent(message);
if (systemIntent.isPresent()) {
    return new IntentClassificationRes(systemIntent.get(), 1.0f, "SYSTEM_COMMAND");
}
```

这意味着：

- 用户输入 `/help` → Step 2 直接响应
- 用户输入 "帮帮我" → 意图分类器识别为 HELP，路由到帮助流程
- 两条路径共享同一套 `PresetAgentIntro` 意图定义

### 5.5 在 SimpleDefaultBizAgent 中的使用

兜底 Agent `SimpleDefaultBizAgent` 在无法理解用户意图时，会告诉用户可用命令：

```java
// 从 commandDispatcher 动态获取
String helpText = SpringUtil.getBean(SystemCommandDispatcher.class)
        .getAllCommandDescriptions();
```

这样用户在任何 Agent 中都可以知道 `/help` 等命令的存在。

---

## 六、扩展指南

### 6.1 新增系统命令

五步完成：

**Step 1：在 PresetAgentIntro 添加意图**

```java
FEEDBACK("feedback", "用户反馈", "提交使用反馈", "/feedback"),
```

**Step 2：实现 Handler**

```java
@Component
public class FeedbackCommandHandler implements SystemCommandHandler {

    @Override
    public boolean handle(..., Function<String, Boolean> process) {
        return process.apply("感谢您的反馈！我们会认真对待每一位用户的意见...");
    }

    @Override
    public PresetAgentIntro getIntentType() {
        return PresetAgentIntro.FEEDBACK;
    }
}
```

**Step 3：Spring 自动注册**（无需额外配置）

Handler 使用 `@Component` 注解，Spring 自动将其注入 `SystemCommandDispatcher.handlers` 列表。

**Step 4：/help 自动更新**

`HelpCommandHandler` 通过 `getAllCommandDescriptions()` 动态获取命令列表，新命令的 `getDescription()` 自动出现在帮助文档中。

**Step 5（可选）：在意图分类器中注册**

如果希望自然语言也能触发该命令，在 `KeywordIntentClassifier` 的 keyword-intent 映射中添加关键词。

### 6.2 覆盖默认 supports() 逻辑

如果命令需要精确匹配而非前缀匹配，覆盖 `supports()` 方法：

```java
@Override
public boolean supports(String command) {
    return "/feedback".equals(command.trim());
}
```

### 6.3 命令带参数决策

目前只有 `/agent` 和 `/plan` 等命令带参数或需要条件判断。未来的命令可能：

```java
// 命令 + 参数：/feedback 这个功能很好用
// Handler 通过 msg.getMessage() 获取完整消息，自行解析参数
```

不需要改动框架层面的任何代码。

---

## 七、小结
 
求职派的系统命令机制是一条**轻量级快车道**，策略模式的设计让它：

1. **新增无侵入**：一个 `@Component` 类 + 一个 `PresetAgentIntro` 枚举值 = 一条新命令
2. **与意图识别协同**：命令既能被 CLI 快车道拦截，也能被意图分类器识别
3. **权限按命令管控**：不统一的全局拦截，而是在需要的地方精确校验

命令处理位于 MsgRouter 的 Step 2，介于身份采集和意图识别之间，形成了一个**递减复杂度**的三层路由：

```
Step 1: 身份采集（中断型，返回 boolean）
Step 2: 系统命令（快车道，直接响应）
Step 2.5: /agentId（快捷通道，直接路由）
Step 3+: 意图识别 + Agent 路由（全链路处理）
```

---

:::success
相关代码：
- 命令调度器：`core/src/main/java/com/git/hui/jobclaw/core/cli/SystemCommandDispatcher.java`
- 命令处理器接口：`core/src/main/java/com/git/hui/jobclaw/core/cli/SystemCommandHandler.java`
- 帮助命令：`core/src/main/java/com/git/hui/jobclaw/core/cli/HelpCommandHandler.java`
- 列出 Agent：`core/src/main/java/com/git/hui/jobclaw/core/cli/ListAgentsCommandHandler.java`
- 当前 Agent：`core/src/main/java/com/git/hui/jobclaw/core/cli/CurrentAgentCommandHandler.java`
- 切换 Agent：`core/src/main/java/com/git/hui/jobclaw/core/cli/SwitchAgentCommandHandler.java`
- 重置会话：`core/src/main/java/com/git/hui/jobclaw/core/cli/ResetCommandHandler.java`
- 计划模式：`core/src/main/java/com/git/hui/jobclaw/core/cli/PlanModeCommandHandler.java`
- 意图枚举（含命令定义）：`core/src/main/java/com/git/hui/jobclaw/core/router/intent/PresetAgentIntro.java`
- 消息路由集成：`core/src/main/java/com/git/hui/jobclaw/core/router/MsgRouter.java`
- 兜底 Agent 中的命令提示：`core/src/main/java/com/git/hui/jobclaw/core/agent/impl/SimpleDefaultBizAgent.java`
- 关键词分类器（系统命令感知）：`core/src/main/java/com/git/hui/jobclaw/core/router/intent/classifier/KeywordIntentClassifier.java`
- 组合分类器（系统命令感知）：`core/src/main/java/com/git/hui/jobclaw/core/router/intent/classifier/CompositeIntentClassifier.java`
- Agent 注册中心：`core/src/main/java/com/git/hui/jobclaw/core/router/intent/AgentRegistry.java`
- 会话绑定：`core/src/main/java/com/git/hui/jobclaw/core/router/intent/SessionAgentBinder.java`
- 权限模型：`core/src/main/java/com/git/hui/jobclaw/core/apis/permission/AgentPermission.java`
- 用户角色枚举：`core/src/main/java/com/git/hui/jobclaw/core/apis/context/UserRoleEnum.java`
:::

---

> 相关文档：
> - [消息路由与多轮对话状态管理](./08-✅求职派消息路由与多轮对话状态管理实战.md)
> - [Plugin 插件系统与自动工具发现](./07-✅求职派Plugin插件系统与自动工具发现机制实战.md)
> - [消息模型与事件总线](./02-✅求职派消息模型与事件总线设计实战.md)
> - [V2 重构总览](./00-求职派V2重构：从单体应用到多Agent运行时.md)
> - [用户偏好设置与多用户隔离](./10-✅求职派用户偏好设置与多用户隔离实战.md)
