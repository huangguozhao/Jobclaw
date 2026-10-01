# 04-✅求职派ReActAdvisor实现ReAct范式与Middleware机制实战

> ReAct（Reasoning + Acting）是大模型工具调用的核心范式。本文完整记录求职派如何基于Spring AI的Advisor机制自定义ReActAdvisor，实现完整的ReAct循环，并提供可扩展的Middleware拦截机制，为大模型监控、工具审批、记忆注入等能力奠定基础。

---

## 一、为什么需要自定义ReActAdvisor？

### 1.1 Spring AI的ToolCallAdvisor有什么问题？

**问题场景**：

用户使用JobFetchAgent查询岗位信息：

```
用户："帮我找北京的开发岗位"
  ↓
大模型思考：需要调用searchJobs工具
  ↓
ToolCallAdvisor自动执行工具 → 返回岗位列表
  ↓
大模型总结：为您找到以下岗位...
```

**ToolCallAdvisor的工作方式**：

```java
// Spring AI内置的ToolCallAdvisor
public class ToolCallingAdvisor implements CallAdvisor, StreamAdvisor {
    private final ToolCallingManager toolCallingManager;
    
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // 1. 调用模型
        ChatClientResponse response = chain.nextCall(request);
        
        // 2. 检查是否有工具调用
        AssistantMessage output = response.chatResponse().getResult().getOutput();
        List<ToolCall> toolCalls = output.getToolCalls();
        
        // 3. 如果有工具调用，自动执行（内部循环）
        if (toolCalls != null && !toolCalls.isEmpty()) {
            // 自动执行工具，但无法拦截
            toolCallingManager.executeToolCalls(...);
        }
        
        return response;
    }
}
```

**问题分析**：

| 问题 | 说明 | 影响 |
|------|------|------|
| **无法拦截工具执行** | 工具自动执行，没有前置/后置钩子 | 无法实现工具审批、权限控制 |
| **无法监控推理过程** | 没有暴露Reasoning/Acting阶段 | 无法记录大模型思考过程 |
| **无法注入中间逻辑** | 没有扩展点 | 无法实现记忆注入、日志审计 |
| **流式响应不完整** | 工具执行结果不发射到Flux | 前端无法实时显示工具调用 |

### 1.2 ReActAdvisor的设计目标

> **如何让业务层能够完全控制ReAct循环的每个阶段，并支持灵活的扩展机制？**

答案就是：
1. **自定义ReActAdvisor**：替代ToolCallAdvisor，完全控制Reasoning→Acting循环
2. **Middleware机制**：提供6个生命周期钩子，支持灵活扩展
3. **流式支持**：工具执行结果实时发射到Flux，前端可实时显示

---

## 二、ReAct范式详解：什么是Reasoning + Acting？

### 2.1 ReAct的核心思想

ReAct（Reasoning + Acting）是一种让大模型"边思考边行动"的模式：

```
用户："帮我找北京月薪20k以上的Java开发岗位"
  ↓
【Reasoning 1】大模型思考：
  - 需要调用搜索工具
  - 参数：location=北京, type=Java开发, salary=20k
  ↓
【Acting 1】执行工具：searchJobs(location="北京", type="Java开发", salary=20k)
  ↓
工具返回：[{id: 1, title: "Java高级开发", salary: "25k"}, ...]
  ↓
【Reasoning 2】大模型思考：
  - 收到了5个岗位
  - 需要筛选出符合要求的
  - 生成总结回复
  ↓
【Final Response】"为您找到以下5个岗位..."
```

### 2.2 ReAct vs 传统工具调用

| 方式 | 特点 | 适用场景 |
|------|------|----------|
| **传统工具调用** | 大模型生成ToolCall → 框架自动执行 → 返回结果 | 简单工具调用，无需中间处理 |
| **ReAct模式** | 大模型显式生成ToolCall → 框架拦截 → 执行工具 → 再次推理 | 复杂工具链、需要中间处理、需要监控 |

### 2.3 ReAct循环的完整流程

```
┌────────────────────────────────────────────────────────┐
│                    ReAct循环流程                         │
│                                                        │
│  用户消息                                                │
│    ↓                                                    │
│  【Reasoning 0】第一次推理（走Advisor Chain）             │
│    - 触发Memory Advisor加载历史对话                       │
│    - 触发System Prompt注入身份信息                        │
│    - 大模型生成回复或ToolCall                            │
│    ↓                                                    │
│  检查是否有ToolCall？                                    │
│    ├─ 无 → 直接返回最终回复                              │
│    └─ 有 → 进入ReAct循环                                 │
│         ↓                                               │
│      【Acting 1】执行工具                                 │
│        - 查找ToolCallback                               │
│        - 构建ToolContext                                │
│        - 执行工具调用                                    │
│        - 捕获异常                                       │
│        ↓                                               │
│      【Reasoning 1】再次推理（直接调用Model）             │
│        - 不经过Advisor Chain（避免重复触发Memory）        │
│        - 将工具结果添加到消息列表                          │
│        - 大模型基于工具结果生成新回复                      │
│        ↓                                               │
│      检查是否有ToolCall？                                │
│        ├─ 无 → 返回最终回复                              │
│        └─ 有 → 继续循环（最多maxIterations次）            │
└────────────────────────────────────────────────────────┘
```

---

## 三、ReActAdvisor核心实现：如何控制ReAct循环？

### 3.1 ReActAdvisor的架构设计

```java
@Slf4j
public class ReActAdvisor implements CallAdvisor, StreamAdvisor {
    
    private final ChatModel chatModel;           // 模型实例
    private final List<ReActMiddleware> middlewares;  // 中间件列表
    private final int maxIterations;             // 最大迭代次数
    private final int order;                     // Advisor优先级
    
    // 实现两个接口
    // CallAdvisor   → adviseCall()   同步调用
    // StreamAdvisor → adviseStream() 流式调用
}
```

**设计要点**：

**1. 为什么同时实现CallAdvisor和StreamAdvisor？**

因为ChatClient支持两种调用方式：

```java
// 同步调用
String response = chatClient.prompt(prompt).call().content();
  ↓
触发 adviseCall()

// 流式调用
Flux<String> stream = chatClient.prompt(prompt).stream().content();
  ↓
触发 adviseStream()
```

**ReActAdvisor需要同时支持两种模式**，确保工具调用在同步和流式场景下都能正常工作。

**2. 为什么需要maxIterations？**

防止大模型陷入无限工具调用循环：

```java
// 反例：不限制迭代次数
while (toolCalls != null && !toolCalls.isEmpty()) {
    executeTools(toolCalls);
    response = callModel(messages);
    toolCalls = response.getToolCalls();  // ← 可能永远不为空
}
```

**问题**：如果大模型一直生成ToolCall，会导致无限循环。

**设计价值**：设置maxIterations=10，超过10次迭代后强制退出。

### 3.2 adviseCall()：同步调用的ReAct循环

```java
@Override
public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    String chatId = "S-" + UUID.randomUUID();
    
    // Step 1: 通知中间件 - 设置上下文
    notifySetContext(request, chatId);
    notifyBeforeReasoning(initialMessages, 0, chatId);
    
    // Step 2: 第一次推理（走Advisor Chain）
    ChatClientResponse firstResponse = chain.nextCall(request);
    AssistantMessage output = firstResponse.chatResponse().getResult().getOutput();
    List<ToolCall> toolCalls = output.getToolCalls();
    
    notifyAfterReasoning(chatResponse, 0, chatId);
    
    // Step 3: 无工具调用 → 直接返回
    if (toolCalls == null || toolCalls.isEmpty()) {
        notifyComplete(1, output.getText(), chatId);
        return firstResponse;
    }
    
    // Step 4: 进入ReAct循环
    List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
    messages.add(output);
    ReactLoopResult loopResult = runReactLoop(messages, toolCalls, request, 1, chatId);
    
    // Step 5: 返回最终结果
    if (loopResult != null) {
        notifyComplete(loopResult.iterations(), 
                loopResult.finalResponse.chatResponse().getResult().getOutput().getText(),
                chatId);
        return firstResponse.mutate()
                .chatResponse(loopResult.finalResponse.chatResponse())
                .build();
    }
    
    return firstResponse;
}
```

**设计要点**：

**为什么第一次推理走Advisor Chain？**

```java
// 第一次推理：走Advisor Chain
ChatClientResponse firstResponse = chain.nextCall(request);
  ↓
触发 Memory Advisor → 加载历史对话
触发 System Prompt Advisor → 注入身份信息
触发其他前置Advisor → ...
```

**后续迭代为什么直接调用Model？**

```java
// 后续迭代：直接调用Model
ChatResponse nextResponse = callModelDirect(messages, request, chatId);
  ↓
不经过Advisor Chain
  ↓
避免重复触发Memory Advisor（历史对话已经包含在messages中）
避免重复触发System Prompt Advisor（系统提示词已经包含在messages中）
```

**设计价值**：
- 第一次推理：完整走Advisor Chain，确保Memory等前置Advisor生效
- 后续迭代：直接调用Model，避免重复触发Advisor，提升性能

### 3.3 runReactLoop()：同步ReAct循环核心

```java
private ReactLoopResult runReactLoop(List<Message> messages,
                                     List<ToolCall> toolCalls,
                                     ChatClientRequest request,
                                     int startIter, String chatId) {
    List<ToolResponseMessage> allToolResponses = new ArrayList<>();
    
    for (int iter = startIter; iter < maxIterations; iter++) {
        // ---- Acting: 执行工具 ----
        ToolResponseMessage toolResponses = executeTools(toolCalls, request, iter, chatId);
        if (toolResponses == null) return null;  // 工具执行失败
        allToolResponses.add(toolResponses);
        messages.add(toolResponses);
        
        // ---- Reasoning: 再次推理 ----
        notifyBeforeReasoning(messages, iter, chatId);
        ChatResponse nextResponse = callModelDirect(messages, request, chatId);
        if (nextResponse == null) return null;
        
        AssistantMessage output = nextResponse.getResult().getOutput();
        notifyAfterReasoning(nextResponse, iter, chatId);
        messages.add(output);
        
        toolCalls = output.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            // 无工具调用 → 循环结束
            return new ReactLoopResult(finalResponse, allToolResponses, iter + 1);
        }
    }
    
    log.warn("[ReAct] Max iterations ({}) reached", maxIterations);
    return null;
}
```

**ReAct循环的本质**：

```
Reasoning → 检查ToolCall → Acting → Reasoning → 检查ToolCall → ...
```

**循环终止条件**：
1. 大模型不再生成ToolCall（正常结束）
2. 达到maxIterations上限（强制退出）
3. 工具执行失败（异常退出）

### 3.4 executeTools()：如何执行工具？

```java
private ToolResponseMessage executeTools(List<ToolCall> toolCalls,
                                         ChatClientRequest request,
                                         int iter, String chatId) {
    notifyBeforeActing(toolCalls, iter, chatId);  // ← 通知中间件
    
    ToolCallback[] callbacks = resolveToolCallbacks(request);
    ToolContext toolContext = buildToolContext(request.prompt());
    List<ToolResponse> responses = new ArrayList<>();
    
    for (var toolCall : toolCalls) {
        String toolName = toolCall.name();
        String toolArgs = toolCall.arguments();
        String toolCallId = toolCall.id();
        
        ToolCallback callback = findCallback(toolName, callbacks);
        if (callback == null) {
            responses.add(new ToolResponse(toolCallId, toolName, 
                    "Tool '" + toolName + "' not found"));
            continue;
        }
        
        try {
            // 始终传入ToolContext
            String result = callback.call(toolArgs, toolContext);
            responses.add(new ToolResponse(toolCallId, toolName, result));
        } catch (Exception e) {
            responses.add(new ToolResponse(toolCallId, toolName, 
                    "Tool execution failed: " + e.getMessage()));
        }
    }
    
    ToolResponseMessage toolResponse = ToolResponseMessage.builder()
            .responses(responses)
            .build();
    
    notifyAfterActing(toolResponse, iter, chatId);  // ← 通知中间件
    return toolResponse;
}
```

**设计要点**：

**1. 为什么始终传入ToolContext？**

参照Spring AI的`DefaultToolCallingManager`实现：

```java
// 正例：始终构建并传入ToolContext
ToolContext toolContext = buildToolContext(request.prompt());
String result = callback.call(toolArgs, toolContext);

// 反例：通过反射判断是否需要ToolContext
if (needsToolContext(callback)) {
    callback.call(toolArgs, toolContext);
} else {
    callback.call(toolArgs);  // ← 不传
}
```

**设计价值**：
- 工具函数可以通过`ToolContext`获取用户信息、原始消息等上下文
- 保持一致性，避免某些工具拿到上下文，某些拿不到

**2. ToolContext包含什么？**

```java
private ToolContext buildToolContext(Prompt prompt) {
    Map<String, Object> toolContextMap = Map.of();
    ChatOptions options = prompt.getOptions();
    if (options instanceof ToolCallingChatOptions tcOptions) {
        if (!CollectionUtils.isEmpty(tcOptions.getToolContext())) {
            toolContextMap = new HashMap<>(tcOptions.getToolContext());
        }
    }
    return new ToolContext(toolContextMap);
}
```

**BizAgentLlmCaller设置ToolContext**：

```java
.toolContext(Map.of(
    "jobClawUserId", user.jobClawUserId(),
    "user", user,           // ← 工具中可以访问用户信息
    "msg", msg              // ← 工具中可以访问原始消息
))
```

**工具函数使用示例**：

```java
@Tool(description = "搜索岗位")
public String searchJobs(String keyword, ToolContext toolContext) {
    UserConversationInfo user = (UserConversationInfo) 
        toolContext.getContext().get("user");
    String userId = user.jobClawUserId();
    
    ChannelReceiveMessage msg = (ChannelReceiveMessage) 
        toolContext.getContext().get("msg");
    String channel = msg.getChannel();
    
    // ... 搜索逻辑
}
```

---

## 四、流式ReAct实现：如何支持实时工具调用显示？

### 4.1 为什么流式实现更复杂？

**问题**：同步模式下，工具执行完成后一次性返回结果。但流式模式下，用户希望实时看到：

```
[思考中...] 正在为您搜索北京的Java开发岗位...
[工具调用] 🔧 执行工具: searchJobs(location="北京", type="Java开发")
[工具结果] ✅ 找到 5 个岗位
[思考中...] 正在为您总结岗位信息...
[最终回复] 为您找到以下5个岗位...
```

### 4.2 adviseStream()：流式ReAct循环

```java
@Override
public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, 
                                              StreamAdvisorChain chain) {
    String chatId = "A-" + UUID.randomUUID();
    
    notifySetContext(request, chatId);
    notifyBeforeReasoning(initialMessages, 0, chatId);
    
    return streamWithReAct(request, chain, initialMessages, 0, chatId);
}
```

### 4.3 streamWithReAct()：非阻塞流式ReAct核心

```java
private Flux<ChatClientResponse> streamWithReAct(
        ChatClientRequest request,
        StreamAdvisorChain chain,
        List<Message> messages,
        int iteration,
        String chatId) {
    
    // Step 1: 获取推理流
    Flux<ChatClientResponse> responseFlux;
    if (iteration == 0) {
        responseFlux = chain.nextStream(request);  // 第一次走Advisor Chain
    } else {
        responseFlux = callModelDirectStream(messages, request, chatId);  // 后续直接调用Model
    }
    
    AtomicReference<ChatClientResponse> aggregatedRef = new AtomicReference<>();
    
    // Step 2: 使用publish()分支处理
    return responseFlux
            .publish(shared -> {
                // 分支1: 实时发射chunks（pass-through）
                ChatClientMessageAggregator aggregator = new ChatClientMessageAggregator();
                Flux<ChatClientResponse> streamingBranch = aggregator.aggregateChatClientResponse(
                        shared, aggregatedRef::set);
                
                // 分支2: 流结束后检查工具调用，递归下一次推理
                Flux<ChatClientResponse> recursionBranch = Flux.defer(() ->
                        handleAggregatedResponse(aggregatedRef.get(), request, chain, 
                                messages, iteration, chatId)
                ).subscribeOn(Schedulers.boundedElastic());
                
                // 先发射streamingBranch，再发射recursionBranch
                return streamingBranch.concatWith(recursionBranch);
            });
}
```

**设计要点**：

**为什么使用publish()分支处理？**

```
推理流：chunk1 → chunk2 → chunk3 → ... → complete
  ↓
publish(shared) 将流分成两个分支：
  ├─ streamingBranch: 实时发射chunks到下游（用户实时看到内容）
  └─ recursionBranch: 等待流完成后，检查工具调用，递归下一次推理
```

**关键点**：
1. **streamingBranch**：实时发射chunks，不阻塞
2. **recursionBranch**：使用`Flux.defer()`延迟执行，等streamingBranch完成后才执行
3. **concatWith()**：先发射streamingBranch，再发射recursionBranch

### 4.4 handleAggregatedResponse()：处理工具调用递归

```java
private Flux<ChatClientResponse> handleAggregatedResponse(
        ChatClientResponse aggregated,
        ChatClientRequest request,
        StreamAdvisorChain chain,
        List<Message> messages,
        int iteration,
        String chatId) {
    
    AssistantMessage output = aggregated.chatResponse().getResult().getOutput();
    List<ToolCall> toolCalls = output.getToolCalls();
    
    notifyAfterReasoning(aggregated.chatResponse(), iteration, chatId);
    
    // 无工具调用 → 完成
    if (toolCalls == null || toolCalls.isEmpty()) {
        notifyComplete(iteration + 1, output.getText(), chatId);
        return Flux.empty();
    }
    
    // 检查最大迭代次数
    int nextIter = iteration + 1;
    if (nextIter >= maxIterations) {
        notifyComplete(nextIter, output.getText(), chatId);
        return Flux.empty();
    }
    
    // 执行工具
    ToolResponseMessage toolResponses = executeTools(toolCalls, request, iteration, chatId);
    if (toolResponses == null) {
        return Flux.empty();
    }
    
    // 构建工具执行结果合成响应（供LlmRspCell识别）
    ChatClientResponse toolResultResp = buildToolResultResponse(List.of(toolResponses), request);
    
    // 构建下一次推理的消息列表
    List<Message> nextMessages = new ArrayList<>(messages);
    nextMessages.add(output);
    nextMessages.add(toolResponses);
    
    notifyBeforeReasoning(nextMessages, nextIter, chatId);
    
    // 递归进入下一次流式推理
    Flux<ChatClientResponse> nextFlux = streamWithReAct(request, chain, nextMessages, nextIter, chatId);
    
    // 先发射工具结果，再发射下一次推理流
    return Flux.just(toolResultResp).concatWith(nextFlux);
}
```

**流式ReAct的完整流程**：

```
用户："帮我找北京的开发岗位"
  ↓
streamWithReAct(iteration=0)
  ↓
发射推理chunks: "正在为您搜索..." → "北京的..." → "Java开发岗位..."
  ↓
流完成 → 检查到ToolCall: searchJobs(location="北京")
  ↓
执行工具 → 构建toolResultResp（带"toolResult"标记）
  ↓
发射toolResultResp: "[searchJobs]: 找到5个岗位"
  ↓
streamWithReAct(iteration=1)
  ↓
发射推理chunks: "为您找到以下5个岗位..." → "1. Java高级开发..." → ...
  ↓
流完成 → 无ToolCall → 结束
```

### 4.5 buildToolResultResponse()：如何构建工具结果响应？

```java
private ChatClientResponse buildToolResultResponse(List<ToolResponseMessage> toolResponses,
                                                   ChatClientRequest request) {
    var sb = new StringBuilder();
    for (var tr : toolResponses) {
        for (var resp : tr.getResponses()) {
            if (sb.length() > 0) sb.append("\n---\n");
            sb.append("[").append(resp.name()).append("]: ");
            String data = resp.responseData();
            sb.append(data != null && data.length() > 500 ? data.substring(0, 500) + "..." : data);
        }
    }
    
    AssistantMessage msg = AssistantMessage.builder()
            .content(sb.toString())
            .properties(Map.of("toolResult", true))  // ← 关键：设置标记
            .build();
    
    ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
    return ChatClientResponse.builder()
            .chatResponse(chatResponse)
            .context(request.context())
            .build();
}
```

**设计要点**：

**为什么设置`"toolResult": true`标记？**

因为`LlmRspCell`需要识别工具执行结果：

```java
public static LlmRspCell of(ChatResponse chatResponse) {
    AssistantMessage output = chatResponse.getResult().getOutput();
    
    // 检查是否是工具执行结果
    if (output.getProperties() != null && 
        Boolean.TRUE.equals(output.getProperties().get("toolResult"))) {
        return LlmRspCell.builder()
                .toolResult(output.getText())  // ← 识别为工具结果
                .build();
    }
    
    // 普通内容
    return LlmRspCell.builder()
            .content(output.getText())
            .build();
}
```

**LlmRspCell支持4个字段**：

| 字段 | 用途 | 识别方式 |
|------|------|----------|
| `thinking` | 大模型思考过程 | 特定格式解析 |
| `content` | 普通文本内容 | 默认 |
| `tool` | 工具调用请求 | 检测ToolCall |
| `toolResult` | 工具执行结果 | 检测`"toolResult"`标记 |

---

## 五、ReActMiddleware机制：如何扩展ReAct循环？

### 5.1 为什么需要Middleware？

**问题场景**：

1. **日志审计**：记录每次工具调用的请求和响应
2. **工具审批**：敏感工具（如删除操作）需要用户确认
3. **性能监控**：记录每次推理的Token消耗和耗时
4. **记忆注入**：在Reasoning阶段前注入相关记忆
5. **限流控制**：限制工具调用频率

**如果没有Middleware会怎样？**

```java
// 反例：在ReActAdvisor中硬编码所有逻辑
private ToolResponseMessage executeTools(...) {
    // 日志记录
    log.info("执行工具: {}", toolName);
    
    // 权限检查
    if (!checkPermission(toolName)) {
        throw new SecurityException("无权执行");
    }
    
    // 性能监控
    long start = System.currentTimeMillis();
    String result = callback.call(toolArgs, toolContext);
    long duration = System.currentTimeMillis() - start;
    metrics.record(toolName, duration);
    
    // 记忆注入
    injectMemory(messages);
    
    // ... 还有很多其他逻辑
}
```

**问题**：
- ReActAdvisor职责过重，违反单一职责原则
- 新增功能需要修改ReActAdvisor代码
- 无法灵活组合不同功能

### 5.2 ReActMiddleware接口定义

```java
public interface ReActMiddleware {
    
    /**
     * 在ReAct循环开始前设置请求上下文
     */
    default void setContext(ChatClientRequest request, String chatId) {}
    
    /**
     * Reasoning阶段前：LLM即将进行推理
     */
    default void beforeReasoning(List<Message> messages, int iter, String chatId) {}
    
    /**
     * Reasoning阶段后：LLM完成一次推理
     */
    default void afterReasoning(ChatResponse response, int iter, String chatId) {}
    
    /**
     * Acting阶段前：即将执行工具
     */
    default void beforeActing(List<ToolCall> toolCalls, int iter, String chatId) {}
    
    /**
     * Acting阶段后：工具执行完毕
     */
    default void afterActing(ToolResponseMessage toolResponses, int iter, String chatId) {}
    
    /**
     * 循环结束时：ReAct推理循环完成
     */
    default void onComplete(int totalIters, String finalResponse, String chatId) {}
    
    /**
     * 异常时：循环中发生错误
     */
    default void onError(Exception error, int iter, String chatId) {}
}
```

### 5.3 Middleware生命周期

```
┌────────────────────────────────────────────────────────────┐
│                  Middleware生命周期                          │
│                                                            │
│  adviseCall/adviseStream开始                                │
│    ↓                                                       │
│  setContext(request, chatId)                                │
│    - 提取userId、conversationId等                           │
│    - 初始化中间件内部状态                                    │
│    ↓                                                       │
│  beforeReasoning(messages, iter=0, chatId)                  │
│    - 可注入记忆、修改消息                                    │
│    ↓                                                       │
│  [第一次推理：走Advisor Chain]                               │
│    ↓                                                       │
│  afterReasoning(response, iter=0, chatId)                   │
│    - 可记录Token消耗、推理结果                               │
│    ↓                                                       │
│  检查是否有ToolCall？                                        │
│    ├─ 无 → onComplete(totalIters, finalResponse, chatId)    │
│    └─ 有 → 进入ReAct循环                                    │
│         ↓                                                  │
│      beforeActing(toolCalls, iter=1, chatId)                │
│        - 可审批工具调用、权限控制                            │
│        ↓                                                  │
│      [执行工具]                                              │
│        ↓                                                  │
│      afterActing(toolResponses, iter=1, chatId)             │
│        - 可后处理工具结果、记录日志                          │
│        ↓                                                  │
│      beforeReasoning(messages, iter=1, chatId)              │
│        ↓                                                  │
│      [再次推理：直接调用Model]                               │
│        ↓                                                  │
│      afterReasoning(response, iter=1, chatId)               │
│        ↓                                                  │
│      检查是否有ToolCall？                                    │
│        ├─ 无 → onComplete(totalIters, finalResponse, chatId)│
│        └─ 有 → 继续循环（最多maxIterations次）               │
│                                                            │
│  异常时：onError(error, iter, chatId)                        │
└────────────────────────────────────────────────────────────┘
```

### 5.4 ReActAdvisor如何通知Middleware？

```java
private void notifyBeforeReasoning(List<Message> messages, int iter, String chatId) {
    for (var mw : middlewares) {
        try {
            mw.beforeReasoning(messages, iter, chatId);
        } catch (Exception e) {
            log.warn("Middleware error", e);  // ← 中间件异常不影响主流程
        }
    }
}

private void notifyBeforeActing(List<ToolCall> toolCalls, int iter, String chatId) {
    for (var mw : middlewares) {
        try {
            mw.beforeActing(toolCalls, iter, chatId);
        } catch (Exception e) {
            log.warn("Middleware error", e);
        }
    }
}

// ... 其他notify方法类似
```

**设计要点**：

**为什么中间件异常不影响主流程？**

```java
try {
    mw.beforeReasoning(messages, iter, chatId);
} catch (Exception e) {
    log.warn("Middleware error", e);  // ← 仅记录日志，不抛出异常
}
```

**设计价值**：
- 中间件是可选的扩展点，不应影响核心ReAct循环
- 某个中间件失败，不影响其他中间件和主流程

### 5.5 LoggingReActMiddleware：日志记录实现

```java
@Component
public class LoggingReActMiddleware implements ReActMiddleware {
    
    private static final Logger log = LoggerFactory.getLogger(LoggingReActMiddleware.class);
    
    @Override
    public void beforeReasoning(List<Message> messages, int iter, String chatId) {
        log.info("[ReAct-{}] chatId={} Reasoning阶段开始，当前消息数: {}", 
                iter, chatId, messages.size());
        if (log.isDebugEnabled() && !messages.isEmpty()) {
            log.debug("[ReAct-{}] 最后一条消息内容: {}", 
                    iter, messages.get(messages.size() - 1).getText());
        }
    }
    
    @Override
    public void afterReasoning(AssistantMessage assistantMessage, int iter, String chatId) {
        log.info("[ReAct-{}] chatId={} Reasoning阶段完成", iter, chatId);
        if (assistantMessage != null) {
            log.info("[ReAct-{}] 推理结果文本: {}", iter, assistantMessage.getText());
            if (assistantMessage.getToolCalls() != null && !assistantMessage.getToolCalls().isEmpty()) {
                log.info("[ReAct-{}] 工具调用数量: {}", iter, assistantMessage.getToolCalls().size());
                assistantMessage.getToolCalls().forEach(toolCall -> 
                    log.info("[ReAct-{}] 工具调用 - ID: {}, 名称: {}, 参数: {}", 
                            iter, toolCall.id(), toolCall.name(), toolCall.arguments())
                );
            }
        }
    }
    
    @Override
    public void beforeActing(List<ToolCall> toolCalls, int iter, String chatId) {
        log.info("[ReAct-{}] chatId={} Acting阶段开始，即将执行工具", iter, chatId);
        toolCalls.forEach(toolCall -> 
            log.info("[ReAct-{}] 准备执行工具 - ID: {}, 名称: {}, 参数: {}", 
                    iter, toolCall.id(), toolCall.name(), toolCall.arguments())
        );
    }
    
    @Override
    public void afterActing(ToolResponseMessage toolResponses, int iter, String chatId) {
        log.info("[ReAct-{}] chatId={} Acting阶段完成", iter, chatId);
        toolResponses.getResponses().forEach(response -> 
            log.info("[ReAct-{}] 工具执行结果 - ID: {}, 内容: {}", 
                    iter, response.id(), response.responseData())
        );
    }
    
    @Override
    public void onComplete(int totalIters, String finalResponse, String chatId) {
        log.info("[ReAct] chatId={} 推理循环完成，总迭代次数: {}", chatId, totalIters);
        log.info("[ReAct] 最终回答: {}", finalResponse);
    }
    
    @Override
    public void onError(Exception error, int iter, String chatId) {
        log.error("[ReAct-{}] chatId={} 发生错误: {}", iter, chatId, error.getMessage(), error);
    }
}
```

**日志输出示例**：

```
[ReAct-0] chatId=A-xxx Reasoning阶段开始，当前消息数: 2
[ReAct-0] chatId=A-xxx Reasoning阶段完成
[ReAct-0] 推理结果文本: 正在为您搜索北京的Java开发岗位...
[ReAct-0] 工具调用数量: 1
[ReAct-0] 工具调用 - ID: call_xxx, 名称: searchJobs, 参数: {"location": "北京", "type": "Java开发"}
[ReAct-1] chatId=A-xxx Acting阶段开始，即将执行工具
[ReAct-1] 准备执行工具 - ID: call_xxx, 名称: searchJobs, 参数: {"location": "北京", "type": "Java开发"}
[ReAct-1] chatId=A-xxx Acting阶段完成
[ReAct-1] 工具执行结果 - ID: call_xxx, 内容: [{"id": 1, "title": "Java高级开发"}, ...]
[ReAct-1] chatId=A-xxx Reasoning阶段开始，当前消息数: 4
[ReAct-1] chatId=A-xxx Reasoning阶段完成
[ReAct-1] 推理结果文本: 为您找到以下5个岗位...
[ReAct] chatId=A-xxx 推理循环完成，总迭代次数: 2
[ReAct] 最终回答: 为您找到以下5个岗位...
```

### 5.6 自动注入Middleware

```java
public Builder autoInjectMiddleware() {
    try {
        var middlewareBeans = SpringUtil.getContext().getBeansOfType(ReActMiddleware.class);
        middlewareBeans.values().forEach(this::addMiddleware);
    } catch (Exception e) {
        log.debug("No ReActMiddleware beans found", e);
    }
    return this;
}
```

**使用方式**：

```java
var reactBuilder = ReActAdvisor.builder()
        .chatModel(chatModel)
        .autoInjectMiddleware();  // ← 自动注入所有ReActMiddleware Bean
```

**Spring自动扫描**：

```java
@Component  // ← Spring自动扫描
public class LoggingReActMiddleware implements ReActMiddleware {
    // ...
}

@Component  // ← 可以创建多个Middleware
public class MonitorReActMiddleware implements ReActMiddleware {
    // ...
}
```

---

## 六、ReActAdvisor vs ToolCallAdvisor：对比分析

### 6.1 核心差异对比

| 特性 | ToolCallAdvisor（Spring AI内置） | ReActAdvisor（求职派自定义） |
|------|--------------------------------|---------------------------|
| **工具执行** | 自动执行，无法拦截 | 手动执行，支持Middleware拦截 |
| **生命周期钩子** | 无 | 6个钩子（setContext/beforeReasoning/afterReasoning/...） |
| **第一次推理** | 直接调用Model | 走Advisor Chain（保留Memory等效果） |
| **后续迭代** | 自动循环 | 直接调用Model（避免重复触发Advisor） |
| **流式支持** | 工具结果不发射到Flux | 工具结果实时发射到Flux |
| **ToolContext** | 内部构建 | 显式构建并传入（参照DefaultToolCallingManager） |
| **扩展性** | 无法扩展 | Middleware机制，灵活扩展 |
| **最大迭代** | 内置限制 | 可配置maxIterations |

### 6.2 代码对比

**ToolCallAdvisor的执行流程**：

```java
// Spring AI内置（简化版）
public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    // 1. 调用模型
    ChatClientResponse response = chain.nextCall(request);
    
    // 2. 检查工具调用
    AssistantMessage output = response.chatResponse().getResult().getOutput();
    List<ToolCall> toolCalls = output.getToolCalls();
    
    // 3. 自动执行工具（内部循环，无法拦截）
    if (toolCalls != null && !toolCalls.isEmpty()) {
        // 内部调用toolCallingManager.executeToolCalls()
        // 没有暴露钩子，无法拦截
    }
    
    return response;
}
```

**ReActAdvisor的执行流程**：

```java
// 求职派自定义
public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    // 1. 通知中间件
    notifySetContext(request, chatId);
    notifyBeforeReasoning(messages, 0, chatId);
    
    // 2. 第一次推理（走Advisor Chain）
    ChatClientResponse firstResponse = chain.nextCall(request);
    
    // 3. 通知中间件
    notifyAfterReasoning(chatResponse, 0, chatId);
    
    // 4. 检查工具调用
    List<ToolCall> toolCalls = output.getToolCalls();
    if (toolCalls == null || toolCalls.isEmpty()) {
        notifyComplete(1, output.getText(), chatId);
        return firstResponse;
    }
    
    // 5. 进入ReAct循环（可拦截）
    ReactLoopResult loopResult = runReactLoop(messages, toolCalls, request, 1, chatId);
    
    // 6. 通知中间件
    notifyComplete(loopResult.iterations(), finalResponse, chatId);
    
    return firstResponse.mutate()
            .chatResponse(loopResult.finalResponse.chatResponse())
            .build();
}
```

### 6.3 使用方式对比

**使用ToolCallAdvisor**：

```java
// Spring AI默认配置
ChatClient client = ChatClient.builder(chatModel)
        .defaultAdvisors(
                new ToolCallingAdvisor(toolCallingManager)  // ← 内置
        )
        .build();
```

**使用ReActAdvisor**：

```java
// 求职派自定义配置
ChatClient client = ChatClient.builder(chatModel)
        .defaultOptions(
                ToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)  // ← 禁用自动执行
                        .build()
        )
        .defaultAdvisors(
                ReActAdvisor.builder()
                        .chatModel(chatModel)
                        .autoInjectMiddleware()  // ← 自动注入Middleware
                        .maxIterations(10)
                        .build()
        )
        .defaultToolCallbacks(tools)
        .build();
```

**关键差异**：
- 必须设置`internalToolExecutionEnabled(false)`，否则Spring AI会自动执行工具
- 使用ReActAdvisor替代ToolCallingAdvisor
- 支持autoInjectMiddleware()自动注入中间件

---

## 七、如何扩展自定义Middleware？

### 7.1 扩展步骤

**Step 1：创建Middleware类**

```java
@Component
public class MonitorReActMiddleware implements ReActMiddleware {
    
    private final LlmMonitorService monitorService;
    
    @Override
    public void setContext(ChatClientRequest request, String chatId) {
        // 提取用户信息
        UserConversationInfo user = (UserConversationInfo) 
            request.prompt().getOptions().getToolContext().get("user");
        
        // 初始化监控上下文
        monitorService.startMonitoring(chatId, user.jobClawUserId());
    }
    
    @Override
    public void afterReasoning(ChatResponse response, int iter, String chatId) {
        if (response != null && response.getMetadata() != null) {
            // 记录Token消耗
            Long inputTokens = response.getMetadata().getUsage().getPromptTokens();
            Long outputTokens = response.getMetadata().getUsage().getCompletionTokens();
            
            monitorService.recordTokens(chatId, iter, inputTokens, outputTokens);
        }
    }
    
    @Override
    public void beforeActing(List<ToolCall> toolCalls, int iter, String chatId) {
        // 工具调用前：权限检查
        for (var toolCall : toolCalls) {
            if (!checkPermission(toolCall.name())) {
                throw new SecurityException("无权执行工具: " + toolCall.name());
            }
        }
    }
    
    @Override
    public void onComplete(int totalIters, String finalResponse, String chatId) {
        // 循环结束：记录完整监控数据
        monitorService.completeMonitoring(chatId, totalIters, finalResponse);
    }
    
    @Override
    public void onError(Exception error, int iter, String chatId) {
        // 异常时：记录错误
        monitorService.recordError(chatId, iter, error);
    }
}
```

**Step 2：注册为Spring Bean**

```java
@Component  // ← Spring自动扫描
public class MonitorReActMiddleware implements ReActMiddleware {
    // ...
}
```

**Step 3：自动注入**

```java
var reactBuilder = ReActAdvisor.builder()
        .chatModel(chatModel)
        .autoInjectMiddleware();  // ← 自动注入LoggingReActMiddleware + MonitorReActMiddleware
```

### 7.2 Middleware应用场景

| 场景 | Middleware实现 | 生命周期钩子 |
|------|---------------|-------------|
| **日志记录** | LoggingReActMiddleware | 所有钩子 |
| **性能监控** | MonitorReActMiddleware | afterReasoning（记录Token）、onComplete（记录总耗时） |
| **工具审批** | ApprovalReActMiddleware | beforeActing（拦截敏感工具） |
| **记忆注入** | MemoryReActMiddleware | beforeReasoning（注入相关记忆） |
| **限流控制** | RateLimitReActMiddleware | beforeActing（检查调用频率） |
| **审计追踪** | AuditReActMiddleware | 所有钩子（记录完整流程） |

---

## 八、常见问题：深度解答

### 8.1 为什么不直接用Spring AI的ToolCallAdvisor？

**问题**：Spring AI已经提供了ToolCallAdvisor，为什么还要自定义？

**答案**：

| 需求 | ToolCallAdvisor | ReActAdvisor |
|------|----------------|--------------|
| 记录工具调用日志 | ❌ 无法拦截 | ✅ beforeActing/afterActing |
| 工具调用权限控制 | ❌ 无法拦截 | ✅ beforeActing抛出异常 |
| 记录Token消耗 | ❌ 无钩子 | ✅ afterReasoning获取response |
| 流式显示工具调用 | ❌ 工具结果不发射 | ✅ buildToolResultResponse发射到Flux |
| 注入记忆到推理过程 | ❌ 无钩子 | ✅ beforeReasoning修改messages |

**设计价值**：ReActAdvisor提供了完整的生命周期钩子，支持灵活扩展。

### 8.2 Middleware异常会影响主流程吗？

**问题**：如果Middleware抛出异常，ReAct循环会中断吗？

**答案**：不会。ReActAdvisor捕获了所有Middleware异常：

```java
try {
    mw.beforeReasoning(messages, iter, chatId);
} catch (Exception e) {
    log.warn("Middleware error", e);  // ← 仅记录日志，不抛出
}
```

**设计价值**：中间件是可选的扩展点，不应影响核心ReAct循环。

### 8.3 为什么第一次推理走Advisor Chain？

**问题**：为什么不让所有推理都直接调用Model？

**答案**：第一次推理需要触发Memory等前置Advisor：

```java
// 第一次推理：走Advisor Chain
chain.nextCall(request)
  ↓
触发 MessageChatMemoryAdvisor → 加载历史对话
触发 System Prompt Advisor → 注入身份信息
触发其他Advisor → ...
```

**如果所有推理都直接调用Model**：

```java
// 反例：所有推理都直接调用Model
ChatResponse response = chatModel.call(prompt);
  ↓
不经过Advisor Chain
  ↓
历史对话未加载 → 大模型不知道之前的对话内容
身份信息未注入 → 大模型不了解用户画像
```

**设计价值**：第一次推理走Advisor Chain，后续迭代直接调用Model，兼顾功能和性能。

### 8.4 流式ReAct如何保证非阻塞？

**问题**：工具执行是阻塞的，流式ReAct如何保证不阻塞？

**答案**：使用`publish()`分支处理 + `subscribeOn()`异步调度：

```java
return responseFlux
        .publish(shared -> {
            // 分支1: 实时发射chunks（非阻塞）
            Flux<ChatClientResponse> streamingBranch = aggregator.aggregateChatClientResponse(
                    shared, aggregatedRef::set);
            
            // 分支2: 流结束后检查工具调用（阻塞，但使用异步调度）
            Flux<ChatClientResponse> recursionBranch = Flux.defer(() ->
                    handleAggregatedResponse(...)
            ).subscribeOn(Schedulers.boundedElastic());  // ← 异步调度
            
            return streamingBranch.concatWith(recursionBranch);
        });
```

**设计价值**：streamingBranch实时发射chunks，recursionBranch在后台线程执行，不阻塞主线程。

---

## 九、小结：设计思想总结

ReActAdvisor是求职派大模型工具调用的核心引擎，背后的设计思想是：

1. **完全控制ReAct循环**：替代ToolCallAdvisor，手动控制Reasoning→Acting的完整流程
2. **Middleware扩展机制**：提供6个生命周期钩子，支持日志、监控、审批、记忆等扩展
3. **第一次推理走Advisor Chain**：保留Memory等前置Advisor的效果
4. **后续迭代直接调用Model**：避免重复触发Advisor，提升性能
5. **流式工具调用支持**：工具执行结果实时发射到Flux，前端可实时显示
6. **始终传入ToolContext**：确保工具可访问用户信息、原始消息等上下文

**核心设计原则**：
- **关注点分离**：ReActAdvisor控制循环，Middleware处理扩展逻辑
- **开闭原则**：对扩展开放（新增Middleware），对修改封闭（ReActAdvisor无需改动）
- **单一职责**：每个Middleware只负责一件事（日志、监控、审批等）
- **容错设计**：Middleware异常不影响主流程

**Middleware机制的价值**：
- ✅ 日志审计：完整记录ReAct循环的每个阶段
- ✅ 性能监控：记录Token消耗、推理耗时
- ✅ 工具审批：敏感工具需要用户确认
- ✅ 记忆注入：在推理阶段前注入相关记忆
- ✅ **大模型监控**：下一篇文章将详细介绍如何基于Middleware实现完整的大模型交互监控方案... 🔜

---

:::success
相关代码：
- ReActAdvisor核心实现：`core/src/main/java/com/git/hui/jobclaw/core/agent/react/ReActAdvisor.java`
- Middleware接口定义：`core/src/main/java/com/git/hui/jobclaw/core/agent/react/ReActMiddleware.java`
- 日志Middleware实现：`core/src/main/java/com/git/hui/jobclaw/core/agent/react/LoggingReActMiddleware.java`
- BizAgentLlmCaller集成：`core/src/main/java/com/git/hui/jobclaw/core/agent/llm/BizAgentLlmCaller.java`

:::

---

> 相关文档：
> - [Channel 通道层设计](./01-✅求职派Channel通道层设计与实现实战.md)
> - [消息模型与事件总线](./02-✅求职派消息模型与事件总线设计实战.md)
> - [LLM大模型层设计](./03-✅求职派LLM大模型层设计与实现实战.md)
> - [V2 重构总览](../07.V2重构/00-求职派V2重构：从单体应用到多Agent运行时.md)
> - [🔜 大模型交互监控方案](./05-✅求职派基于Middleware实现大模型监控实战.md)（下一篇）
