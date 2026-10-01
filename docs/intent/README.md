# README

## 意图识别与Agent路由方案

### 一、背景与目标

#### 1.1 问题背景

JobClaw 系统目前有多个业务Agent（如岗位信息收集Agent、岗位推荐Agent、岗位推送Agent等），需要一个统一入口来：
1. 识别用户意图
2. 自动路由到合适的业务Agent
3. 保持会话的Agent状态，避免每次都需要重新识别

#### 1.2 核心需求

| 需求 | 说明 |
|-----|------|
| **意图识别** | 根据用户消息内容，识别用户想要执行的操作 |
| **Agent路由** | 将消息路由到合适的业务Agent |
| **状态保持** | 选中Agent后，会话应保持对该Agent的聚焦，避免意图识别跳跃 |
| **可配置** | 支持通过配置扩展意图关键词和对应的Agent |
| **可干预** | 用户可以主动切换Agent（如输入 `/agent xxx`） |

#### 1.3 设计约束

- 基于现有的 `MsgRouter` 作为入口（事件驱动架构）
- 复用现有的 `BizAgent` 接口
- 会话状态需要持久化（避免重启后丢失）

---

### 二、架构设计

#### 2.1 整体流程

```
用户消息
    │
    ▼
┌─────────────────┐
│   MsgRouter     │ ◄─── 入口，接收 MessageReceivedEvent
└─────────────────┘
    │
    ▼
┌─────────────────┐
│ IntentClassifier│ ◄─── 意图识别模块
│  (意图分类器)    │
└─────────────────┘
    │
    ├─── 识别成功 ───┐
    │               │
    ▼               ▼
┌─────────┐   ┌─────────┐
│ AgentRouter │ │ Fallback │ 
│ (路由)    │   │ (兜底)   │
└─────────┘   └─────────┘
    │
    ▼
┌─────────────────┐
│  BizAgent       │ ◄─── 业务Agent执行
│  (执行业务)     │
└─────────────────┘
    │
    ▼
┌─────────────────┐
│ ChannelResponse │ ◄─── 返回响应
└─────────────────┘
```

#### 2.2 模块职责

| 模块 | 职责 |
|-----|------|
| `IntentClassifier` | 意图识别：分析用户消息，输出意图类型 |
| `AgentRouter` | Agent路由：根据意图类型，选择合适的BizAgent |
| `SessionAgentBinder` | 会话状态管理：记录当前会话绑定的Agent |
| `IntentResolutionStrategy` | 意图解析策略：关键词匹配 / 模型识别 / 混合模式 |

---

### 三、核心接口设计

#### 3.1 IntentType 意图类型枚举

```java
public enum IntentType {
    // 业务意图
    COLLECT("collect", "岗位信息收集"),
    RECOMMEND("recommend", "岗位推荐"),
    SUBSCRIBE("subscribe", "订阅推送"),
    QUERY("query", "信息查询"),
    PROFILE("profile", "用户画像管理"),
    
    // 系统意图
    HELP("help", "帮助"),
    SWITCH_AGENT("switch_agent", "切换Agent"),
    RESET("reset", "重置会话"),
    UNKNOWN("unknown", "未知意图"),
    ;
}
```

#### 3.2 Intent Classification Result

```java
public record IntentClassification(
    IntentType intentType,           // 识别到的意图类型
    double confidence,              // 置信度 0.0-1.0
    String reasoning,              // 识别理由
    Map<String, Object> context    // 额外上下文
) {}
```

#### 3.3 Session Agent Binder

```java
public interface SessionAgentBinder {
    
    /**
     * 绑定会话到指定Agent
     */
    void bind(String jobClawUserId, String sessionId, String agentId);
    
    /**
     * 获取当前会话绑定的Agent
     */
    Optional<String> getBoundAgent(String jobClawUserId, String sessionId);
    
    /**
     * 解除绑定
     */
    void unbind(String jobClawUserId, String sessionId);
    
    /**
     * 检查是否需要重新意图识别
     * - 用户明确指定Agent时：不需要
     * - 绑定Agent存在且用户未明确切换时：不需要
     * - 其他情况：需要
     */
    boolean needsIntentRecognition(String jobClawUserId, String sessionId, String userMessage);
}
```

#### 3.4 Intent Classifier

```java
public interface IntentClassifier {
    
    /**
     * 识别用户意图
     * @param message 用户消息
     * @param conversationHistory 对话历史（用于上下文）
     * @return 识别结果
     */
    IntentClassification classify(String message, List<String> conversationHistory);
    
    /**
     * 判断是否为明确的Agent切换指令
     */
    boolean isAgentSwitchCommand(String message);
    
    /**
     * 解析Agent切换指令
     */
    Optional<String> parseAgentSwitchCommand(String message);
}
```

#### 3.5 Agent Registry

```java
public interface AgentRegistry {
    
    /**
     * 注册业务Agent
     */
    void register(BizAgent agent);
    
    /**
     * 根据AgentId获取Agent
     */
    Optional<BizAgent> getAgent(String agentId);
    
    /**
     * 根据意图类型获取Agent
     */
    List<BizAgent> getAgentsForIntent(IntentType intentType);
    
    /**
     * 获取所有已注册的Agent
     */
    List<BizAgent> getAllAgents();
    
    /**
     * Agent是否存在
     */
    boolean hasAgent(String agentId);
}
```

#### 3.6 Agent Router

```java
public interface AgentRouter {
    
    /**
     * 根据意图路由到Agent
     * @param classification 意图识别结果
     * @param currentBoundAgent 当前绑定的Agent（可选）
     * @return 路由结果
     */
    RouterResult route(IntentClassification classification, 
                       Optional<String> currentBoundAgent);
    
    /**
     * 强制路由到指定Agent
     */
    RouterResult routeTo(String agentId);
    
    /**
     * 路由记录
     */
    record RouterResult(
        String agentId,
        boolean isNewSession,    // 是否新会话（需要初始化）
        String reason           // 路由原因
    ) {}
}
```

---

### 四、状态管理设计

#### 4.1 会话状态存储

会话状态需要持久化，存储结构设计：

```
workspace/sessions/
└── {jobClawUserId}/
    └── session-{sessionId}.yaml
```

```yaml
# Session状态存储文件
sessionId: "session-20260101-120000"
jobClawUserId: "user-12345"
boundAgent:
  agentId: "collect-agent"
  boundAt: "2026-01-01T12:00:00Z"
  expiresAt: "2026-01-01T18:00:00Z"
intentHistory:
  - intentType: "COLLECT"
    confidence: 0.95
    timestamp: "2026-01-01T12:00:00Z"
conversation:
  - role: "user"
    content: "我想投递字节跳动的岗位"
    timestamp: "2026-01-01T12:00:00Z"
  - role: "assistant"
    content: "好的，请问您想投递什么岗位？"
    timestamp: "2026-01-01T12:00:05Z"
```

#### 4.2 状态过期策略

| 场景 | 过期时间 |
|-----|---------|
| 默认 | 6小时 |
| 用户活跃（30分钟内发送消息） | 续期6小时 |
| 用户明确切换Agent | 立即生效 |
| 用户发送 `/reset` | 解除绑定 |

#### 4.3 状态判断逻辑

```
消息到达
    │
    ├── 用户明确指定 /agent xxx ──► 强制��由到指定Agent
    │
    ├── 用户发送 /reset ───────────► 解除绑定，重新意图识别
    │
    ├── 存在已绑定的Agent ──────────┐
    │   且用户未明确切换      │   │
    │                       │   ▼
    │                   需要IntentRecognition? ──No──► 继续使用绑定Agent
    │                       │
    │                    Yes
    │                       │
    └── 不存在绑定 ───────────┘
              │
              ▼
        意图识别
              │
              ▼
        路由到Agent + 绑定状态
```

---

### 五、意图识别策略

#### 5.1 三层识别策略

| 层级 | 策略 | 优先级 | 适用场景 |
|-----|------|--------|---------|
| **L0 命令匹配** | 预定义命令词 | 最高 | `/agent collect`, `/help`, `/reset` |
| **L1 关键词匹配** | 关键词 + TF-IDF | 高 | 明确业务关键词（"投简历"、"推荐岗位"） |
| **L2 模型识别** | LLM意图分类 | 中 | 模糊、多义性消息 |

#### 5.2 命令匹配 (L0)

```java
public class CommandIntentClassifier implements IntentClassifier {
    
    private static final Map<String, IntentType> SYSTEM_COMMANDS = Map.of(
        "/help", IntentType.HELP,
        "/reset", IntentType.RESET,
        "/collect", IntentType.COLLECT,
        "/recommend", IntentType.RECOMMEND,
        "/subscribe", IntentType.SUBSCRIBE,
        "/query", IntentType.QUERY,
        "/profile", IntentType.PROFILE
    );
    
    @Override
    public boolean isAgentSwitchCommand(String message) {
        return message.trim().startsWith("/agent ");
    }
    
    @Override
    public Optional<String> parseAgentSwitchCommand(String message) {
        if (isAgentSwitchCommand(message)) {
            return Optional.of(message.substring(7).trim());
        }
        return Optional.empty();
    }
}
```

#### 5.3 关键词匹配 (L1)

```java
public class KeywordIntentClassifier implements IntentClassifier {
    
    // 意图 -> 关键词列表（按权重排序）
    private static final Map<IntentType, List<KeywordWeight>> KEYWORDS = Map.of(
        IntentType.COLLECT, List.of(
            new KeywordWeight("投递", 1.0),
            new KeywordWeight("投简历", 1.0),
            new KeywordWeight("岗位", 0.8),
            new KeywordWeight("面试", 0.7),
            new KeywordWeight("校招", 0.6)
        ),
        IntentType.RECOMMEND, List.of(
            new KeywordWeight("推荐", 1.0),
            new KeywordWeight("帮我看看", 0.8),
            new KeywordWeight("有什么", 0.5)
        ),
        IntentType.SUBSCRIBE, List.of(
            new KeywordWeight("订阅", 1.0),
            new KeywordWeight("通知", 0.8),
            new KeywordWeight("提醒", 0.7)
        )
    );
    
    record KeywordWeight(String keyword, double weight) {}
}
```

#### 5.4 模型识别 (L2)

```java
public class LLMIntentClassifier implements IntentClassifier {
    
    private final LlmCaller llmCaller;
    
    private static final String PROMPT_TEMPLATE = """
        您是一个意图分类器，请分析用户的意图。

        可分类的意图类型：
        - COLLECT: 投递简历、收集岗位信息
        - RECOMMEND: 推荐岗位、帮我看看有什么工作
        - SUBSCRIBE: 订阅通知、提醒
        - QUERY: 查询信息
        - HELP: 请求帮助
        - UNKNOWN: 无法确定

        用户消息：{{message}}

        请返回JSON格式的分类结果：
        ```json
        {
          "intentType": "COLLECT",
          "confidence": 0.95,
          "reasoning": "用户提到了投递岗位"
        }
        ```
        """;
}
```

#### 5.5 混合策略编排

```java
public class CompositeIntentClassifier implements IntentClassifier {
    
    private final CommandIntentClassifier commandClassifier;
    private final KeywordIntentClassifier keywordClassifier;
    private final LLMIntentClassifier llmClassifier;
    
    @Override
    public IntentClassification classify(String message, List<String> history) {
        // L0: 命令匹配（最高优先级）
        IntentType commandType = commandClassifier.matchCommand(message);
        if (commandType != null) {
            return new IntentClassification(commandType, 1.0, "命令匹配", Map.of());
        }
        
        // L1: 关键词匹配
        IntentClassification keywordResult = keywordClassifier.classify(message, history);
        if (keywordResult.confidence() >= 0.9) {
            return keywordResult;
        }
        
        // L2: 模型识别（置信度门槛）
        if (keywordResult.confidence() < 0.7) {
            return llmClassifier.classify(message, history);
        }
        
        // L1结果作为兜底
        return keywordResult;
    }
}
```

---

### 六、Agent路由策略

#### 6.1 意图到Agent的映射

```java
public class DefaultAgentRegistry implements AgentRegistry {
    
    // 意图类型 -> Agent ID列表（按优先级）
    private static final Map<IntentType, List<String>> INTENT_AGENT_MAPPING = Map.of(
        IntentType.COLLECT, List.of("collect-agent", "default-agent"),
        IntentType.RECOMMEND, List.of("recommend-agent", "default-agent"),
        IntentType.SUBSCRIBE, List.of("subscribe-agent", "default-agent"),
        IntentType.QUERY, List.of("query-agent", "default-agent"),
        IntentType.PROFILE, List.of("profile-agent"),
        IntentType.HELP, List.of("help-agent"),
        IntentType.UNKNOWN, List.of("default-agent")
    );
    
    @Override
    public List<BizAgent> getAgentsForIntent(IntentType intentType) {
        return INTENT_AGENT_MAPPING.getOrDefault(intentType, List.of("default-agent"))
                .stream()
                .map(this::getAgent)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }
}
```

#### 6.2 路由选择逻辑

```java
public class IntentBasedAgentRouter implements AgentRouter {
    
    private final AgentRegistry agentRegistry;
    private final SessionAgentBinder sessionBinder;
    
    @Override
    public RouterResult route(IntentClassification classification,
                            Optional<String> currentBoundAgent) {
        IntentType intentType = classification.intentType();
        
        // 如果存在绑定Agent，且意图类型一致，继续使用
        // AIDEV-NOTE: 这里的意图类型判断是简化的，实际可以根据Agent支持的意图类型来判断
        if (currentBoundAgent.isPresent()) {
            String boundAgentId = currentBoundAgent.get();
            BizAgent boundAgent = agentRegistry.getAgent(boundAgentId).orElse(null);
            
            if (boundAgent != null && isAgentSuitable(boundAgent, intentType)) {
                return new RouterResult(boundAgentId, false, "继续使用绑定Agent");
            }
        }
        
        // 路由到新Agent
        List<BizAgent> suitableAgents = agentRegistry.getAgentsForIntent(intentType);
        if (suitableAgents.isEmpty()) {
            return new RouterResult("default-agent", true, "未找到合适的Agent，使用默认");
        }
        
        BizAgent selectedAgent = suitableAgents.get(0);
        return new RouterResult(selectedAgent.getAgentId(), true, 
                "根据意图类型 " + intentType + " 路由到 " + selectedAgent.getAgentId());
    }
    
    private boolean isAgentSuitable(BizAgent agent, IntentType intentType) {
        // Agent支持该意图类型
        // AIDEV-NOTE: BizAgent接口需要添加支持意图类型的方法
        return true;
    }
}
```

---

### 七、集成MsgRouter

#### 7.1 修改MsgRouter流程

```java
@Slf4j
@Component
public class MsgRouter {
    
    private final IntentClassifier intentClassifier;
    private final AgentRouter agentRouter;
    private final SessionAgentBinder sessionBinder;
    private final AgentRegistry agentRegistry;
    // ... 现有依赖
    
    @Async
    @EventListener
    public void onMessageReceived(MessageReceivedEvent event) {
        var msg = event.getOriginalMessage();
        String jobClawUserId = msg.getJobClawUserId();
        String sessionId = getSessionId(msg);
        String fromUserId = msg.getFromUserId();
        String channel = msg.getChannel();
        String userMessage = msg.getMessage();
        
        // Step 1: 身份信息收集（现有逻辑）
        if (identityAgent.triggerToCollectIdentity(...)) {
            return;
        }
        
        // Step 2: 判断是否需要意图识别
        if (!sessionBinder.needsIntentRecognition(jobClawUserId, sessionId, userMessage)) {
            // 继续使用绑定的Agent
            String agentId = sessionBinder.getBoundAgent(jobClawUserId, sessionId)
                    .orElse(null);
            routeToAgent(agentId, msg);
            return;
        }
        
        // Step 3: 意图识别
        IntentClassification classification = intentClassifier.classify(
                userMessage, getConversationHistory(jobClawUserId, sessionId));
        
        // Step 4: Agent切换指令
        if (intentClassifier.isAgentSwitchCommand(userMessage)) {
            String agentId = intentClassifier.parseAgentSwitchCommand(userMessage)
                    .orElse(null);
            if (agentId != null && agentRegistry.hasAgent(agentId)) {
                sessionBinder.bind(jobClawUserId, sessionId, agentId);
                routeToAgent(agentId, msg);
                return;
            }
        }
        
        // Step 5: 重置指令
        if (classification.intentType() == IntentType.RESET) {
            sessionBinder.unbind(jobClawUserId, sessionId);
            // 重新意图识别
            classification = intentClassifier.classify(userMessage, 
                    getConversationHistory(jobClawUserId, sessionId));
        }
        
        // Step 6: 路由到Agent
        RouterResult routeResult = agentRouter.route(classification, 
                sessionBinder.getBoundAgent(jobClawUserId, sessionId));
        
        // Step 7: 绑定会话状态
        if (routeResult.isNewSession()) {
            sessionBinder.bind(jobClawUserId, sessionId, routeResult.agentId());
        }
        
        // Step 8: 执行Agent
        routeToAgent(routeResult.agentId(), msg);
    }
    
    private void routeToAgent(String agentId, ChannelReceiveMessage msg) {
        BizAgent agent = agentRegistry.getAgent(agentId)
                .orElse(agentRegistry.getAgent("default-agent")
                .orElseThrow(() -> new IllegalStateException("未找到Agent: " + agentId)));
        
        // 执行Agent处理
        // AIDEV-NOTE: BizAgent接口需要定义处理方法
        agent.process(msg, channelEventPublisher);
    }
}
```

---

### 八、持久化存储实现

#### 8.1 Session状态存储

```java
@Component
public class FileSystemSessionAgentBinder implements SessionAgentBinder {
    
    private static final Path SESSION_BASE = Path.of("workspace/sessions");
    
    @Override
    public void bind(String jobClawUserId, String sessionId, String agentId) {
        Path sessionFile = getSessionFile(jobClawUserId, sessionId);
        SessionState state = loadOrCreate(sessionFile);
        
        state.setBoundAgent(new BoundAgentInfo(
                agentId,
                Instant.now(),
                Instant.now().plus(Duration.ofHours(6))
        ));
        
        save(sessionFile, state);
    }
    
    @Override
    public Optional<String> getBoundAgent(String jobClawUserId, String sessionId) {
        Path sessionFile = getSessionFile(jobClawUserId, sessionId);
        SessionState state = load(sessionFile);
        
        if (state == null || state.getBoundAgent() == null) {
            return Optional.empty();
        }
        
        BoundAgentInfo bound = state.getBoundAgent();
        // 检查是否过期
        if (bound.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        
        return Optional.of(bound.agentId());
    }
    
    @Override
    public boolean needsIntentRecognition(String jobClawUserId, String sessionId, String userMessage) {
        // 明确的重置指令
        if (userMessage.startsWith("/reset")) {
            return true;
        }
        
        // 不存在绑定
        if (getBoundAgent(jobClawUserId, sessionId).isEmpty()) {
            return true;
        }
        
        // 检查绑定是否过期
        return getBoundAgent(jobClawUserId, sessionId).isEmpty();
    }
    
    private Path getSessionFile(String jobClawUserId, String sessionId) {
        return SESSION_BASE.resolve(jobClawUserId)
                .resolve("session-" + sessionId + ".yaml");
    }
}
```

#### 8.2 Session状态DTO

```java
public record SessionState(
        String sessionId,
        String jobClawUserId,
        BoundAgentInfo boundAgent,
        List<IntentHistoryItem> intentHistory
) {}

public record BoundAgentInfo(
        String agentId,
        Instant boundAt,
        Instant expiresAt
) {}

public record IntentHistoryItem(
        IntentType intentType,
        double confidence,
        Instant timestamp
) {}
```

---

### 九、可扩展设计

#### 9.1 Agent定义示例

```java
@Component
public class CollectAgent implements BizAgent {
    
    @Override
    public String getAgentId() {
        return "collect-agent";
    }
    
    @Override
    public IntentType[] getSupportedIntents() {
        return new IntentType[]{IntentType.COLLECT};
    }
    
    @Override
    public void process(ChannelReceiveMessage message, 
                     ChannelEventPublisher publisher) {
        // 岗位信息收集逻辑
    }
}
```

#### 9.2 配置化Intent Mapping

```yaml
# application-intent.yml
intent:
  mapping:
    COLLECT:
      - collect-agent
      - default-agent
    RECOMMEND:
      - recommend-agent
      - default-agent
    SUBSCRIBE:
      - subscribe-agent
      - default-agent
  
  keywords:
    COLLECT:
      - 投递: 1.0
      - 投简历: 1.0
      - 岗位: 0.8
    RECOMMEND:
      - 推荐: 1.0
      - 帮我看看: 0.8
    SUBSCRIBE:
      - 订阅: 1.0
      - 通知: 0.8
```

---

### 十、API设计

#### 10.1 Agent切换API

支持用户通过消息切换Agent：

| 命令 | 说明 |
|-----|------|
| `/agent collect` | 切换到收集Agent |
| `/agent recommend` | 切换到推荐Agent |
| `/agent` | 查看当前Agent |
| `/reset` | 重置会话状态 |

#### 10.2 意图识别调试API

```java
@RestController
@RequestMapping("/api/intent")
public class IntentDebugController {
    
    @GetMapping("/classify")
    public IntentClassification classify(@RequestParam String message) {
        return intentClassifier.classify(message, List.of());
    }
    
    @GetMapping("/bound")
    public Map<String, Object> getBoundAgent(
            @RequestParam String jobClawUserId,
            @RequestParam String sessionId) {
        return sessionBinder.getBoundAgent(jobClawUserId, sessionId)
                .map(agent -> Map.of(
                        "agentId", agent,
                        "timestamp", Instant.now().toString()
                ))
                .orElse(Map.of("bound", false));
    }
    
    @PostMapping("/bind")
    public void bindAgent(
            @RequestParam String jobClawUserId,
            @RequestParam String sessionId,
            @RequestParam String agentId) {
        sessionBinder.bind(jobClawUserId, sessionId, agentId);
    }
    
    @PostMapping("/unbind")
    public void unbindAgent(
            @RequestParam String jobClawUserId,
            @RequestParam String sessionId) {
        sessionBinder.unbind(jobClawUserId, sessionId);
    }
}
```

---

### 十一、实现清单

#### 11.1 接口定义

| 文件 | 说明 |
|-----|------|
| `core/agent/intent/IntentType.java` | 意图类型枚举 |
| `core/agent/intent/IntentClassification.java` | 意图识别结果 |
| `core/agent/intent/IntentClassifier.java` | 意图分类器接口 |
| `core/agent/intent/SessionAgentBinder.java` | 会话状态管理接口 |
| `core/agent/intent/AgentRegistry.java` | Agent注册中心接口 |
| `core/agent/intent/AgentRouter.java` | Agent路由接口 |

#### 11.2 实现类

| 文件 | 说明 |
|-----|------|
| `core/agent/intent/impl/CompositeIntentClassifier.java` | 混合意图分类器 |
| `core/agent/intent/impl/KeywordIntentClassifier.java` | 关键词意图分类器 |
| `core/agent/intent/impl/LLMIntentClassifier.java` | 大模型意图分类器 |
| `core/agent/intent/impl/FileSystemSessionAgentBinder.java` | 文件系统会话状态管理 |
| `core/agent/intent/impl/DefaultAgentRegistry.java` | 默认Agent注册中心 |
| `core/agent/intent/impl/IntentBasedAgentRouter.java` | 基于意图的Agent路由 |

#### 11.3 修改文件

| 文件 | 说明 |
|-----|------|
| `core/agent/BizAgent.java` | 扩展BizAgent接口定义 |
| `core/router/MsgRouter.java` | 集成意图识别和路由 |
| `core/agent/BizAgent.java` | 添加Agent处理入口 |

---

### 十二、总结

本方案设计了一个完整的意图识别和Agent路由系统，核心特点：

1. **三层识别策略**：命令匹配 → 关键词匹配 → 大模型识别，优先级依次降低
2. **会话状态持久化**：基于文件系统的会话状态存储，支持会话恢复
3. **灵活的路由机制**：意图类型到Agent的映射，支持配置化扩展
4. **用户干预能力**：支持 `/agent xxx` 强制切换，支持 `/reset` 重置
5. **状态过期策略**：6小时默认过期，用户活跃自动续期