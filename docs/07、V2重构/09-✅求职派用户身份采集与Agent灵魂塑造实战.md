# ✅求职派用户身份采集与Agent灵魂塑造实战

> 当用户第一次通过 IM 发送消息，系统怎么知道用户是谁？期望的沟通风格是什么？该用什么策略推荐职位？如果不问清楚就走正常对话，Agent 对用户一无所知，体验就像"盲人摸象"。本文完整记录求职派的三阶段身份采集链路：从 AI 灵魂设定（soul.md）→ 用户画像（user.md）→ 智能体身份名片（info.md），以及运行时如何将这些身份信息注入系统提示词。

---

## 一、为什么需要身份采集？

### 1.1 没有身份采集会怎样？

**场景还原**：

```
用户（首次通过钉钉）："帮我找北京的 Java 开发岗位"
```

**没有身份采集的系统**：

```java
// 反例：所有新用户一视同仁
@EventListener
public void onMessage(MessageReceivedEvent event) {
    // 不知道用户是谁、什么背景、什么偏好
    String response = llm.call("找北京的 Java 开发岗位");
    sendResponse(response);
}
```

**问题**：

| 问题 | 后果 |
|------|------|
| **无个性化** | 所有用户得到相同的回复风格，有人喜欢幽默有人喜欢专业 |
| **无背景信息** | Agent 不知道用户是应届生还是社招，推荐策略一刀切 |
| **无身份记忆** | 每次对话都是"初次见面"，没有递进关系 |
| **无法精准推荐** | 不知道学校、专业、期望城市，推荐基本靠盲猜 |

### 1.2 V2 的设计目标

> **如何在新用户首次对话时，自动、自然、无感知地完成「AI 人格设定 → 用户画像收集 → 身份名片生成」三个阶段的身份构建，并在后续对话中增量更新？**

三个核心阶段：

1. **灵魂设定（Soul）**：让用户定义 AI 助手的名字、沟通风格、角色定位
2. **用户画像（User Identity）**：收集用户的学历、技能、求职偏好
3. **身份名片（Info）**：自动生成 AI 对自己的介绍文案（基于 Soul + User）

---

### 1.3 推理与设计决策

**为什么需要两个 Prompt 构建方法（buildSoulPrompt vs buildSystemPrompt）？**

开始时我们只有一个 `buildSystemPrompt()`，把所有身份文档拼在一起注入。但开发中发现两个问题：

1. **BizAgent（业务 Agent）有自己的 System Prompt**，如果身份信息和业务提示混在一起，Agent 的行为边界会模糊。比如 JobRecommendAgent 的 prompt 里已经定义了推荐策略，再去覆盖 identity 会导致角色冲突。
2. **soul.md 只描述人格特质**，适合注入到所有 LLM 调用；而 `user.md + info.md` 是具体的用户求职背景信息，更适合在业务 Agent 的上下文中出现。

所以最终拆成两个方法：
- `buildSoulPrompt()` → 人格设定，注入 BizAgent 的 System Prompt 之前
- `buildSystemPrompt()` → 完整身份，注入通用 LLM 调用的 System Prompt

**为什么设计成三阶段而非一次性采集？**

我们考虑过两种方案：

| 方案 | 做法 | 问题 |
|------|------|------|
| 一次性采集 | "请告诉我你的姓名、学校、专业、期望的 AI 名字和沟通风格..." | 用户刚进来说不了这么多，体验差 |
| 分阶段采集 | 先问 AI 人格，再问用户背景，自动生成名片 | 每阶段 3-5 轮对话，用户感觉像聊天 |

选择三阶段的根本原因是**问题域自然分离**：Soul 采集的是"你想要什么样的 AI"，User 采集的是"你是什么样的人"，Info 是"AI 怎么介绍自己"。这三类问题在用户心智中是不同的话题，混在一起问会让用户困惑。

**为什么 Soul 在前、User 在后？**

一开始我们认为应该先收集用户信息（学校、专业），再根据用户背景定制 AI 人格。但真实测试中发现：用户刚进来时最关心的是"你是谁、能帮我做什么"，而不是先被问学历。反过来，先让用户给 AI 起名字、定风格，用户会感觉在"定制专属助手"，参与感更强，后续回答用户信息时也更配合。

所以 Soul → User → Info 的顺序是**从感性到理性、从体验到效率**的过渡。

---

## 二、整体架构

```
用户首次发送消息
  │
  ▼
MsgRouter Step 1: identityAgent.triggerToCollectIdentity()
  │
  ▼
UnifiedIdentityInitializer.checkAndAdvance()
  │
  ├─ Phase 1: SOUL_COLLECTION  ──────────────────────────────┐
  │    UserAgentSoulCollector                                  │
  │    → AI 驱动的多轮对话，收集 agentName, 沟通风格等           │  (需要用户交互)
  │    → 完成后调用 SoulExtractor 生成 soul.md                  │
  │    → 保存到 workspace/users/{userId}/soul.md               │
  ├─ Phase 2: USER_ID_COLLECTION ─────────────────────────────┐
  │    IdentityCollectorSelector → 选择采集策略                 │
  │    ├─ AiBasedIdentityCollector  (默认)                     │  (需要用户交互)
  │    │   → AI 驱动的动态对话，适应性强                        │
  │    └─ RuleBasedIdentityCollector                          │  (需要用户交互)
  │        → 预制问题模板，确定性高，不依赖 AI                  │
  │    → 完成后调用 UserIdentityExtractor 生成 user.md          │
  │    → 保存到 workspace/users/{userId}/user.md               │
  ├─ Phase 3: INFO_GENERATION  ───────────────────────────────┐
  │    UserAgentInfoCollector                                  │  (无需用户交互)
  │    → 自动读取 soul.md + user.md                            │
  │    → 调用 InfoExtractor 生成 info.md                        │
  │    → 保存到 workspace/users/{userId}/info.md               │
  │    → 标记全部完成                                          │
  └─ 采集完成，放行消息到正常 Agent 流程 ─────────────────────┘
```

### 2.1 文件存储约定

```
workspace/
├── agent.md                          ← 全局操作手册（管理员手动维护）
└── users/{jobClawUserId}/
    ├── soul.md                       ← AI 灵魂设定（Phase 1 生成）
    ├── user.md                       ← 用户画像（Phase 2 生成）
    └── info.md                       ← AI 身份名片（Phase 3 自动生成）
```

---

## 三、核心抽象

### 3.1 IIdentityAgent 接口

定义在 `core/.../agent/IIdentityAgent.java`：

```java
public interface IIdentityAgent {

    // 消息入口：尝试触发用户信息采集，返回 true 表示进入采集模式
    boolean triggerToCollectIdentity(UserConversationInfo conversation, String userMessage);

    // 构建灵魂提示词（agent.md + soul.md），返回 "" 表示不需要注入
    String buildSoulPrompt(String jobClawUserId);

    // 构建完整系统提示词（agent.md + soul.md + info.md + user.md）
    String buildSystemPrompt(String jobClawUserId);

    // 异步增量更新用户画像（运行中从对话学习）
    void asyncUpdateUserIdentityAsync(UserConversationInfo conversation, List<Message> messages);
}
```

**四个方法的职责分界**：

| 方法 | 调用时机 | 返回结果 |
|------|----------|----------|
| `triggerToCollectIdentity()` | 每条用户消息（MsgRouter Step 1） | boolean（是否进入采集） |
| `buildSoulPrompt()` | LLM 调用前构建 Prompt | soul 提示文字 |
| `buildSystemPrompt()` | LLM 调用前构建 Prompt | 完整身份提示文字 |
| `asyncUpdateUserIdentityAsync()` | 对话持久化后（文件存储回调） | void（异步执行） |

#### 3.1.1 推理与设计决策

**为什么 IIdentityAgent 是接口而不是抽象类？**

身份采集系统的消费者有两个：`MsgRouter`（路由阶段触发采集）和 `LlmCaller`（运行时注入身份）。前者只需要 `triggerToCollectIdentity()`，后者只需要 `buildSoulPrompt/buildSystemPrompt`。接口比抽象类更灵活，不同调用方只需依赖自己需要的部分，测试时也可以轻松 Mock。

**为什么 triggerToCollectIdentity 返回 boolean 而不是 void？**

关键在**中断语义**。如果这个方法只是触发一个异步任务就返回，MsgRouter 无法知道当前消息是否应该继续走意图识别流程。返回 true/false 是最简单直接的方式：

- `true` → MsgRouter 直接 return，当前消息被"消费"了
- `false` → MsgRouter 继续后面的步骤

这种"中断-继续"模式避免了 MsgRouter 去理解身份采集的内部状态流转。

**为什么 asyncUpdateUserIdentityAsync 在持久化回调中触发，而不是在每条消息处理完就触发？**

考虑过两个触发点：

| 方案 | 问题 |
|------|------|
| Agent 执行完成后立即触发 | Agent 执行链路中混入非关键操作，增加失败风险 |
| 消息持久化后触发 | 持久化本身是异步的，且 identity 更新不阻塞主流程 |

选择持久化回调的原因是"稳定优先"：identity 更新不是核心链路的功能，不应该影响主流程。放在持久化回调中，即使更新失败也不影响用户继续聊天。

### 3.2 InfoCollector 接口

定义在 `agents/.../init/InfoCollector.java`，是三个 Phase 采集器的统一抽象：

```java
public interface InfoCollector {

    // 是否应该触发采集
    boolean shouldInitiateCollection(String jobClawUserId);

    // 开始采集（发送第一条消息给用户）
    void initiateCollection(UserConversationInfo userConversationInfo);

    // 处理用户的回答，completeCallback 是该阶段完成的回调
    void processAnswer(UserConversationInfo userConversationInfo, String userMessage, Runnable completeCallback);

    // 获取当前采集状态
    Optional<CollectionState> getCollectionState(String jobClawUserId);

    // 采集器类型：AI_BASED / RULE_BASED
    AiUserPreferenceProperties.CollectorType getCollectorType();
}
```

### 3.3 状态模型

**UnifiedCollectionState** — 全流程状态机：

```
NOT_STARTED → IN_PROGRESS → COMPLETED
              ├─ SOUL_COLLECTION
              ├─ USER_ID_COLLECTION
              └─ INFO_GENERATION
              （三个阶段串行推进）
```

**CollectionState** — 单个阶段的细节状态：

```
NOT_STARTED → IN_PROGRESS → COMPLETED / ABANDONED
```

每个 CollectionState 记录：
- 已问问题列表（askedQuestions）
- 待问问题列表（remainingQuestions）
- 当前问题（currentQuestion）
- 已收集答案（collectedAnswers）
- 活跃通道信息（channel, conversationId）

### 3.4 推理与设计决策

**为什么需要两层状态机？**

一开始我们只有一个 `UnifiedCollectionState` 管理所有阶段，发现两个问题：

1. **耦合过紧**：每个阶段的细节状态（已问问题、已收集答案）混在统一状态里，Phase 1 的代码会不小心读到 Phase 2 的字段
2. **复用困难**：`CollectionState` 的问题追踪逻辑（askedQuestions、collectedAnswers）在 Soul 和 User Identity 两个阶段都需要，但 Info 阶段不需要

所以拆成两层：
- **外层 `UnifiedCollectionState`**：只负责阶段切换，不关心具体采集了什么
- **内层 `CollectionState`**：管理单个阶段内的提问-回答循环

这样 Soul 和 User Identity 可以共享 `CollectionState` 的提问逻辑，而 Info 阶段只用 `UnifiedCollectionState` 做状态跟踪。

**为什么状态机用 AtomicReference 而不是 synchronized？**

身份采集是高频触发（每条消息都检查），但每个用户同时只在一个线程中处理（`@Async` + 事件驱动，同一用户的消息是串行的）。用 `AtomicReference` 比 `synchronized` 更轻量，且语义清晰——我们只需要保证状态读取的可见性，不需要复杂的事务保护。

---

## 四、三阶段采集详解

### Phase 1：灵魂设定 (Soul Collection)

#### 4.1 触发条件

在 `UnifiedIdentityInitializer.checkAndAdvance()` 中，首次进入时 `UnifiedCollectionState.OverallStatus` 为 `NOT_STARTED`，自动设置为 `IN_PROGRESS` 并进入 `SOUL_COLLECTION` 阶段。

**Soul Collector 的 shouldInitiateCollection 判断**：

```java
public boolean shouldInitiateCollection(String jobClawUserId) {
    if (soulManager.hasSoul(jobClawUserId)) return false;  // 已有 soul.md
    if (collectionStates.get(jobClawUserId)?.isInProgress()) return false; // 正在采集中
    return true;
}
```

#### 4.2 采集过程

`UserAgentSoulCollector` 使用 **AI 驱动**的流式对话：

1. **首次触发**：`initiateCollection()` 创建 `CollectionState`，调用 `startAiConversation()`
2. **AI 开场**：LLM 以 `agent-soul-collect-prompt.md` 作为 System Prompt，主动向用户提问（最多 3 个问题/次）
3. **用户回复**：`processAnswer()` 将用户消息加入对话历史，调用 `continueAiConversation()` 继续流式对话
4. **完成检测**：实时检测 AI 回复中是否包含 `[SOUL_COLLECTION_COMPLETE]` 标记
5. **最大轮数保护**：超过 `maxSoulCollectionTurns`（可配置）时强制完成

**需要收集的字段**：

| 类型 | 字段 | 必填 |
|------|------|------|
| 基本信息 | agentName | ✅ |
| 沟通风格 | communication_style | ✅ |
| 情感温度 | emotional_tone | ✅ |
| 主动性 | proactive_level | ✅ |
| 详细程度 | detail_orientation | ✅ |
| 关系定位 | relationship_type | ✅ |
| 正式程度 | formality_level | ❌ |
| 幽默频率 | humor_frequency | ❌ |
| 价值观 | values | ❌ |

#### 4.3 灵魂提取

对话完成后，`UserAgentSoulExtractor` 调用 LLM 从对话历史中提取结构化 soul.md：

```java
String soulMd = userAgentSoulExtractor.extract(userInfo, "", history, "");
// soulMd 被保存到 workspace/users/{userId}/soul.md
```

提取 prompt（`agent-soul-extraction-prompt.md`）的输入格式：
- 现有灵魂设定（本次为空）
- 用户画像（本次为空）
- 对话历史文本

输出遵循 `soul-template.md` 模板结构：

```markdown
# Agent Soul Profile

## Basic Info
- **userId**: {用户ID}
- **agentName**: {AI 助手名称}

## Personality (人格特质)
### Core Traits
- **communication_style**: [友好/专业/幽默/严肃]
- **emotional_tone**: [温暖/中性/热情]
...
```

#### 4.4 完成回调

Soul 采集完成后，回调 `UnifiedIdentityInitializer` 的 `markCurrentPhaseCompleted()` + `advanceToNextPhase()`，自动进入 Phase 2。

#### 4.5 推理与设计决策

**为啥 Soul 选择 AI 驱动而不是规则驱动？**

Soul 采集的字段（沟通风格、情感温度、关系定位）都是主观偏好，没有标准答案。如果用固定问题模板，问"你喜欢正式还是随意？"用户可能答不上来，或者答了"随意"但实际想要"友好"。AI 驱动的优势在于：

1. **动态调整**：用户回答"随意"时，AI 可以追问"那你是喜欢像朋友聊天那样，还是略带正式感？"——这是固定模板做不到的
2. **推断能力**：用户说"帮我找 Java 工作"时，AI 可以推断出用户偏直接沟通，而不是必须显式问"你想要直接回答吗？"
3. **自然过渡**：AI 可以在聊天气氛中自然过渡到下一个问题，用户不会感觉在被审问

**但 AI 驱动的代价是什么？**

最大的风险是不可控——AI 可能问超范围的问题、可能过早结束、也可能一直问不完。所以我们加了三个保护机制：

1. **System Prompt 严格约束**：限定只能问 9 个预定义字段，禁止越界
2. **完成标记检测**：`[SOUL_COLLECTION_COMPLETE]` 标记让 AI 自行决定何时结束，但代码会校验标记是否存在
3. **最大轮数兜底**：超过 `maxSoulCollectionTurns` 轮时强制完成，确保不会无限循环

**Streaming 的完成检测为什么在 doOnNext 而不是 doOnComplete？**

这是一个踩过坑的设计。最开始在 `doOnComplete` 检测标记，但发现一个问题：如果是长回复，用户要等完整内容流完才能进入下一步。改到 `doOnNext` 实时检测后，"标记一出现就触发完成回调"，用户几乎感觉不到等待。

---

### Phase 2：用户画像 (User Identity Collection)

#### 5.1 采集策略选择

`IdentityCollectorSelector` 根据用户偏好配置选择采集器：

```java
public InfoCollector getCollector(String jobClawUserId) {
    var preference = aiUserPreferenceProperties.getUserPreference(jobClawUserId);
    // 默认 AI_BASED
    return collectorMap.getOrDefault(preference?.collectorType, AI_BASED);
}
```

**两种策略对比**：

| 维度 | AiBasedIdentityCollector | RuleBasedIdentityCollector |
|------|------------------------|---------------------------|
| 驱动方式 | LLM 动态生成问题 | 预定义 8 个问题模板 |
| 适应性 | 可根据用户回答调整 | 固定流程，不可跳题 |
| 响应形式 | 流式推送（Streaming） | 一次性推送 |
| 完成检测 | `[IDENTITY_COLLECTION_COMPLETE]` 标记 | 问题列表耗尽 |
| AI 依赖 | 强依赖 | 无需 AI |
| 适用场景 | 默认推荐 | 测试、离线、低延迟场景 |

#### 5.2 AI 驱动采集（默认）

与 Soul Collection 流程完全对称：

1. System Prompt：`user-identity-collect-prompt.md` 定义 6 个必填字段 + 9 个选填字段
2. 流式对话：LLM 主动提问，每次一个问题
3. 完成标记：`[IDENTITY_COLLECTION_COMPLETE]`
4. 最大轮数保护：`maxIdentityCollectionTurns`
5. 完成后调用 `UserIdentityExtractor.extract()` 生成 user.md

**必填字段**：

```
graduationYear, university, major, location, jobType, internship
```

**选填字段**：

```
name, degree, industry, salary, technical, languages, internships, projects, awards
```

#### 5.3 规则式采集

`RuleBasedIdentityCollector` 使用固定 8 问题流程：

```
【1/8】姓名（选填）
【2/8】毕业年份（必填）
【3/8】学校（必填）
【4/8】专业（必填）
【5/8】期望城市（必填）
【6/8】岗位类型（必填）
【7/8】实习/正式（必填）
【8/8】技术技能（选填）
```

用户可回复"跳过"或"稍后"跳过当前问题。完成后直接拼接 `collectedAnswers` 生成 user.md（无需 LLM）。

#### 5.4 完成回调

与 Phase 1 相同，完成后自动推进到 Phase 3。

#### 5.5 推理与设计决策

**为什么在用户画像阶段提供两种采集策略？**

这个决策源自一个真实反馈：在早期版本中，所有用户都用 AI 驱动采集。但测试中发现两类问题：

1. **AI 不稳定时**：如果 LLM 服务超时或返回不符合格式的内容，采集流程会中断，用户卡住无法继续
2. **开发和测试时**：开发者频繁重建数据，每次都要走完整的 AI 对话，效率极低

规则式采集就是为了解决这两个问题而存在的。它不依赖 AI，问什么答什么，失败路径为零。

**为什么不把所有阶段都用规则式？**

Soul 采集的字段（沟通风格、情感温度）不适合用固定问题模板——用户可能不理解"formality_level"是什么，需要 AI 用自然语言解释。而用户画像（毕业年份、学校、专业）字段标准化程度高，固定模板完全够用。

**为什么 Soul 没有提供规则式方案？**

如果未来需要，也可以为 Soul 提供一个规则式 Collector。但 Soul 字段本身有 9 个之多，且大多是偏好型（不是事实型），用规则模板会导致问题列表过长。目前没有这个需求，保持简单。

#### 5.6 替代方案：将采集器外置为 Plugin

当前 `IdentityCollectorSelector` 通过 Spring DI 硬编码两种策略。如果未来采集策略越来越多（如 Hybrid、Stepwise），更合理的做法是将其做成 Plugin 式的扩展点：

```
InfoCollector  ← 插件标准接口
  ├─ AiBasedIdentityCollector    （内置，默认）
  ├─ RuleBasedIdentityCollector  （内置，备选）
  └─ ThirdPartyCollector         （第三方插件，通过 Plugin 发现机制注册）
```

这在当前阶段是过度设计，但保持了接口的可扩展性。

---

### Phase 3：身份名片生成 (Info Generation)

#### 6.1 触发条件

```java
public boolean shouldInitiateCollection(String jobClawUserId) {
    if (infoManager.hasInfo(jobClawUserId)) return false;  // 已有 info.md
    if (!soulManager.hasSoul(jobClawUserId)) return false;  // soul.md 就绪
    if (!userIdentityManager.hasIdentity(jobClawUserId)) return false; // user.md 就绪
    return true;
}
```

Phase 3 **只在前两个阶段都完成后**才会触发，且**不需要用户交互**。

#### 6.2 自动生成

`UserAgentInfoCollector.initiateCollection()` 异步调用 `UserAgentInfoExtractor`：

```java
infoExtractor.extractAsync(userInfo, "", userMd, soulMd)
    .thenAccept(infoMd -> infoManager.saveInfo(userId, infoMd));
```

info.md 生成基于两个输入：
- `user.md`：用户的学历、技能、求职偏好
- `soul.md`：AI 的人格设定

输出遵循 `info-template.md` 模板，包含：
```markdown
# Agent Identity Card
## Basic Info (名称、角色、版本)
## Introduction (个性化自我介绍)
## Expertise (专长领域 — 根据求职场景定制)
## Capabilities (能力范围)
## Personalization (基于 user.md 的个性化配置)
## Scenario Adaptation (场景适配策略)
```

#### 6.3 非阻塞设计

Phase 3 采用异步执行，**不阻塞用户消息**：

```java
// handleInfoGeneration() 返回 false，消息继续流到正常 Agent
// 用户无需等待 info.md 生成即可开始聊天
return false;
```

#### 6.4 推理与设计决策

**为什么 Phase 3 不需要用户交互？**

Phase 3 是前两个阶段产物的"合成产物"。soul.md 已经定义了 AI 的人格定位，user.md 已经定义了用户的背景信息。info.md 只是把这两者以"AI 身份名片"的形式表达出来，不需要新增信息。

这意味着：Phase 3 是一个纯计算过程，可以由 AI 自动完成。

**为什么 Phase 3 不阻塞用户消息？**

Soul 和 User Identity 采集需要用户交互，所以必须中断当前消息，引导用户进入采集对话。但 Info 生成只是"后台文件处理"，用户感知不到也无需等待。

如果阻塞用户消息，用户会在 Phase 2 完成后突然等几秒钟（AI 生成 info.md 的时间），体验很突兀。所以 `handleInfoGeneration()` 返回 `false`，让用户的消息直接进入正常 Agent 流程，info.md 在后台慢慢生成。

**如果 info.md 生成失败会怎样？**

`UserAgentInfoExtractor` 中有 fallback 机制：如果 AI 生成失败，保留空的 info.md。`buildSystemPrompt()` 在加载 info.md 时如果文件不存在或为空，直接跳过，不影响后续对话。空 info.md 只是在运行时少了一段自我介绍文本，不影响 Agent 的核心功能。

---

## 七、运行时身份注入

身份采集完成后，采集到的三份文档在运行时被注入到 LLM 的 System Prompt 中。

### 7.1 Prompt 构建链路

```
BizAgentLlmCaller.buildSystemPrompt()
  │
  ├─ identityAgent.buildSoulPrompt(userId)
  │    → agent.md + soul.md（灵魂设定）
  │
  └─ identityAgent.buildSystemPrompt(userId)
       → agent.md + soul.md + info.md + user.md（完整身份）
```

### 7.2 buildSoulPrompt vs buildSystemPrompt

| 方法 | 注入文档 | 调用方 | 用途 |
|------|----------|--------|------|
| `buildSoulPrompt()` | agent.md + soul.md | `BizAgentLlmCaller` | Agent 人格设定 |
| `buildSystemPrompt()` | agent.md + soul.md + info.md + user.md | `UserPreferenceBasedLlmCaller` | 完整上下文 |

### 7.3 加载优先级

```
agent.md      ← 全局操作手册（不可修改）
soul.md       ← AI 人格设定（Phase 1 生成）
info.md       ← AI 身份名片（Phase 3 生成）
user.md       ← 用户画像（Phase 2 生成）
```

如果对应文件不存在，则跳过注入，不报错。

### 7.4 推理与设计决策

**为什么硬拆两个方法 vs 一个方法加参数？**

考虑过统一为一个 `buildPrompt(type, userId)` 方法，但发现：
- `BizAgentLlmCaller` 只需要 soul 信息（人格设定）
- `UserPreferenceBasedLlmCaller` 需要完整身份（人格 + 背景）

如果用一个方法加参数，调用方需要知道传什么参数——这暴露了内部实现细节。两个方法各自提供清晰的职责边界，调用方不需要知道对方需要什么。

**为什么 agent.md 放在最前面？**

agent.md 是全局操作手册，定义了整个 Agent 系统的行为准则，比如"不要代替用户投递简历"、"回复不超过 200 字"等。这些是**系统级约束**，应该在任何个性化信息之前注入。soul.md/user.md 等信息可以在操作手册的框架内发挥作用，但不能覆盖操作手册的约束。

加载优先级就是按照"约束先于个性"的原则设计的：`agent.md > soul.md > info.md > user.md`。

---

## 八、异步增量更新

### 8.1 触发时机

在 `FileSystemChatMemoryRepository` 持久化对话历史后，调用：

```java
// FileSystemChatMemoryRepository.java:215
identityAgent.asyncUpdateUserIdentityAsync(conversation, messages);
```

### 8.2 更新策略

```java
public void asyncUpdateUserIdentityAsync(UserConversationInfo info, List<Message> messages) {
    if (info.group()) return; // 群聊不更新

    // 1. 判断是否应该触发（消息数达到更新频率）
    if (!userIdentityManager.shouldAutoUpdateIdentity(userId, messages)) return;

    // 2. 加载现有 identity 作为基线
    String currentIdentity = userIdentityManager.loadIdentity(userId);

    // 3. 异步提取增量信息
    userIdentityExtractor.extractAsync(info, currentIdentity, messages)
        .thenAccept(updated -> userIdentityManager.saveIdentity(userId, updated));
}
```

**更新频率控制**：消息数 >= 10 且为 `updateIdentityFrequency`（可配置）的整数倍时才触发。

### 8.3 提取规则

`UserIdentityExtractor`（使用 `user-identity-extraction-prompt.md`）遵循：

1. **增量更新**：保留已有，新增对话中提及的新信息
2. **冲突处理**：以最新信息为准
3. **隐私保护**：不提取身份证号、电话等敏感信息
4. **不确定性标记**：不确定的信息加 `?` 后缀

### 8.4 推理与设计决策

**为什么增量更新不用在线模式（每条消息都更新）？**

每条消息都调用 LLM 提取 identity，成本太高且大多数更新是无意义的（用户可能只是在闲聊）。选择"消息数达到 N 的倍数时触发"的策略，是为了在精度和成本之间取得平衡：

- 触发频率太高 → LLM 调用成本激增，用户画像频繁写盘
- 触发频率太低 → 用户改了偏好后很久才被感知

当前默认值是 10 条消息触发一次，后续可以根据用户活跃度动态调整。

**为什么群聊不更新？**

群聊中的用户行为与私聊不同——用户可能在群里发消息给其他人看，或者在群聊中讨论不相关话题。从群聊消息中提取的 identity 噪音太大，不如不做。

**为什么异步更新失败不影响主流程？**

核心原则：**身份信息是非关键数据**。即使更新失败，用户现有的 identity 还能继续使用，对话功能不受影响。所以整个 `asyncUpdateUserIdentityAsync` 方法被 `try-catch` 包裹，任何异常都只打日志不抛出。

---

## 九、与消息路由的集成

### 9.1 MsgRouter 中的调用

```java
// MsgRouter Step 1: 身份采集
if (!msg.isGroupTalk() && identityAgent.triggerToCollectIdentity(info, userMessage)) {
    return; // 进入采集流程，中断当前消息
}
```

**关键设计**：
- `triggerToCollectIdentity()` 返回 `true` → 中断正常对话流程，此时 MsgRouter 后续步骤（意图识别、Agent 路由）都不执行
- `triggerToCollectIdentity()` 返回 `false` → 采集已完成，正常进入后续流程
- 群聊消息跳过身份采集（`!msg.isGroupTalk()`）

### 9.2 三阶段中断与放行

```
checkAndAdvance() 返回值与消息流向：

Phase 1 (Soul):
  → handleSoulCollection()
    → 正在采集中：return true（中断消息）
    → 采集完成：advanceToNextPhase() → 递归 checkAndAdvance()

Phase 2 (User Identity):
  → handleUserIdentityCollection()
    → 正在采集中：return true（中断消息）
    → 采集完成：advanceToNextPhase() → 递归 checkAndAdvance()

Phase 3 (Info):
  → handleInfoGeneration()
    → 始终 return false（不中断消息）
    → 生成完成后 complete()
```

### 9.3 对用户透明

用户在整个采集过程中**不需要知道存在三个阶段**。体验上：
- Phase 1：AI 问"你希望我怎么称呼？喜欢什么风格？"
- Phase 2：AI 接着问"你是哪年毕业？什么专业？"
- Phase 3：完全无感知，用户继续正常对话

阶段切换时，AI 会自然过渡，例如：

```
AI: "太好了！我已经了解了你期望的AI人格设定。
     接下来我想进一步了解你的基本信息，以便更好地帮你推荐职位。
     你是哪一年毕业的呢？"
```

用户只觉得是在正常聊天，不知道背后发生了三个阶段的采集路由。

### 9.4 推理与设计决策

**为什么中断消息而不是异步触发采集？**

一开始我们尝试异步方案：MsgRouter 触发采集后立即返回，采集对话在另一个线程中异步推送给用户。但遇到一个竞态问题——如果用户在采集过程中又发了新消息，新消息会走到意图识别流程，而采集 Agent 也在同一时间发消息，用户会看到"两个 AI 同时在说话"。

所以改成中断模式：`triggerToCollectIdentity()` 返回 true 时，MsgRouter 直接 return，用户的这条消息不会进入后续流程。采集 Agent 接管对话，在采集完成后才放行后续消息。

**为什么用递归 checkAndAdvance 而不是循环？**

Phase 完成后的阶段推进逻辑：

```java
handleSoulCollection() → Phase 1 完成
    → checkAndAdvance(conversationInfo, userMessage)  // 递归
        → Phase 2 可以立即触发（无需新输入）
            → initiateCollection() → 发消息问用户
        → Phase 2 需要用户输入
            → return true，等用户下一条消息
```

如果不用递归而是循环，那么 Phase 1 → Phase 2 的推进中，如果 Phase 2 也可以立即触发（不需要等用户输入），循环逻辑就要写两层 switch-case。递归虽然调用栈深一点，但逻辑更清晰，而且最多递归 3 次（3 个 Phase），不会栈溢出。

**为什么 Phase 3 不参与中断逻辑？**

因为 Phase 3 不需要用户输入。如果 Phase 3 也返回 true，用户会傻等一个不需要他参与的过程完成。Phase 3 选择异步执行 + 放行消息，用户在正常聊天时，info.md 在后台悄悄生成。

---

## 十、扩展指南

### 10.1 新增采集字段

以 Soul 采集为例，修改 `agent-soul-collect-prompt.md`：

```markdown
### 6. 新增字段
- **new_field**: [描述]

### 必填字段
agentName, communication_style, ..., NEW_FIELD
```

### 10.2 新增采集策略

实现 `InfoCollector` 接口并在 `IdentityCollectorSelector` 注册：

```java
@Component
public class HybridIdentityCollector implements InfoCollector {
    // 同时使用 AI 和规则，互补采集
}
```

```java
// IdentityCollectorSelector 构造函数中注册
this.collectorMap = Map.of(
    AI_BASED, aiBased,
    RULE_BASED, ruleBased,
    HYBRID, hybridCollector   // 新增
);
```

### 10.3 自定义信息源

三个 Manager 类（`UserAgentSoulManager`、`UserAgentInfoManager`、`UserIdentityManager`）都基于文件系统。如需切换到数据库：

```java
@Component
@Primary
public class DbUserIdentityManager extends UserIdentityManager {
    @Override
    public String loadIdentity(String jobClawUserId) {
        // 从数据库加载
    }
}
```

### 10.4 采集状态持久化

当前 `UnifiedCollectionStateManager` 使用 `ConcurrentHashMap` 内存管理。重启后用户需重新采集。如需持久化，替换为 Redis：

```java
@Component
public class RedisCollectionStateManager extends UnifiedCollectionStateManager {
    private final StringRedisTemplate redis;

    @Override
    public UnifiedCollectionState getOrCreateState(String userId) {
        // 从 Redis 读取/写入
    }
}
```

---

## 十一、总结

| 阶段 | 采集器 | 产出 | 交互方式 | AI 依赖 |
|------|--------|------|----------|---------|
| Phase 1: Soul | `UserAgentSoulCollector` | soul.md | AI 驱动对话 | 强 |
| Phase 2: Identity | `AiBasedIdentityCollector` / `RuleBasedIdentityCollector` | user.md | AI 对话 / 规则问答 | 可选 |
| Phase 3: Info | `UserAgentInfoCollector` | info.md | 全自动异步 | 强 |

整个身份采集系统是求职派实现**个性化 Agent 体验的基础设施**，通过三阶段串行采集 + 运行时注入 + 异步增量更新的完整闭环，让 Agent 在首次对话就能了解用户，在后续对话中不断深化这种理解。

### 11.1 核心设计原则回顾

| 原则 | 体现在哪 | 为什么 |
|------|----------|--------|
| **中断-继续模式** | `triggerToCollectIdentity()` 返回 boolean | 避免采集与正常消息处理竞态 |
| **状态与逻辑分离** | `UnifiedCollectionState` + `CollectionState` 两层 | 阶段切换和阶段内采集各自独立演进 |
| **AI 驱动 + 规则兜底** | 双策略 `IdentityCollectorSelector` | 灵活性 + 可靠性的平衡 |
| **非关键异步** | Phase 3 异步 + identity 异步更新 | 主链路不受非关键功能影响 |
| **文件即数据库** | soul.md/user.md/info.md 明文存储 | 便于调试和手动修改 |

### 11.2 可以改进的方向

1. **采集状态持久化**：当前 `UnifiedCollectionStateManager` 用内存存储，服务重启后用户需重新采集。可以用 Redis 替代
2. **增量更新范围扩大**：当前只增量更新 user.md，可以扩展到 soul.md（从后续对话中学习用户偏好的变化）
3. **群聊场景支持**：当前群聊完全跳过身份采集。未来可以为群聊设计轻量级 Scope 模式——只采集"用户在群聊中的角色"，不采集完整画像
4. **采集进度通知**：当前 Phase 3 在后台静默执行，用户不知道"身份已完善"。可以在 info.md 生成完成后推送一条无打扰提示

---

:::success
相关代码：
- IIdentityAgent 接口：`core/src/main/java/com/git/hui/jobclaw/core/agent/IIdentityAgent.java`
- IdentityAgent 实现：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/IdentityAgent.java`
- IdentityConfiguration：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/IdentityConfiguration.java`
- 全流程调度器：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/init/UnifiedIdentityInitializer.java`
- 全流程状态：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/init/UnifiedCollectionState.java`
- 全流程状态管理器：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/init/UnifiedCollectionStateManager.java`
- 阶段内状态：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/init/CollectionState.java`
- 采集器接口：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/init/InfoCollector.java`
- Soul 采集器：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/soul/UserAgentSoulCollector.java`
- Soul 提取器：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/soul/UserAgentSoulExtractor.java`
- Soul 管理器：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/soul/UserAgentSoulManager.java`
- Info 采集器：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/info/UserAgentInfoCollector.java`
- Info 提取器：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/info/UserAgentInfoExtractor.java`
- Info 管理器：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/info/UserAgentInfoManager.java`
- 用户画像管理器：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/user/UserIdentityManager.java`
- 用户画像提取器：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/user/UserIdentityExtractor.java`
- 采集器选择器：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/user/IdentityCollectorSelector.java`
- AI 采集器：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/user/collector/AiBasedIdentityCollector.java`
- 规则采集器：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/user/collector/RuleBasedIdentityCollector.java`
- 全局操作手册管理器：`agents/identity-collector-agent/src/main/java/com/git/hui/jobclaw/agents/identity/global/AgentIdentityManager.java`
- 消息路由集成：`core/src/main/java/com/git/hui/jobclaw/core/router/MsgRouter.java`
- Prompt 模板（Soul 采集）：`agents/identity-collector-agent/src/main/resources/prompts/agent-soul-collect-prompt.md`
- Prompt 模板（Soul 提取）：`agents/identity-collector-agent/src/main/resources/prompts/agent-soul-extraction-prompt.md`
- Prompt 模板（Info 提取）：`agents/identity-collector-agent/src/main/resources/prompts/agent-info-extraction-prompt.md`
- Prompt 模板（用户采集）：`agents/identity-collector-agent/src/main/resources/prompts/user-identity-collect-prompt.md`
- Prompt 模板（用户提取）：`agents/identity-collector-agent/src/main/resources/prompts/user-identity-extraction-prompt.md`
- Soul 模板：`agents/identity-collector-agent/src/main/resources/templates/soul-template.md`
- Info 模板：`agents/identity-collector-agent/src/main/resources/templates/info-template.md`
- 自动装配：`agents/identity-collector-agent/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Prompt 注入（BizAgentLlmCaller）：`core/src/main/java/com/git/hui/jobclaw/core/agent/llm/BizAgentLlmCaller.java`
- Prompt 注入（UserPreferenceBasedLlmCaller）：`core/src/main/java/com/git/hui/jobclaw/core/agent/llm/UserPreferenceBasedLlmCaller.java`
- 异步更新触发：`core/src/main/java/com/git/hui/jobclaw/core/agent/memory/FileSystemChatMemoryRepository.java`
:::

---

> 相关文档：
> - [消息路由与多轮对话状态管理](./08-✅求职派消息路由与多轮对话状态管理实战.md)
> - [LLM 大模型层设计](./03-✅求职派LLM大模型层设计与实现实战.md)
> - [消息模型与事件总线](./02-✅求职派消息模型与事件总线设计实战.md)
> - [V2 重构总览](./00-求职派V2重构：从单体应用到多Agent运行时.md)
> - [用户偏好设置与多用户隔离](./10-✅求职派用户偏好设置与多用户隔离实战.md)
