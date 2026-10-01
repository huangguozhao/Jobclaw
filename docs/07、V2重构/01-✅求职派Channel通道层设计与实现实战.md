# ✅求职派Channel通道层设计与实现实战

> Channel 是求职派与外部 IM 平台通信的桥梁，是"多渠道即插即用"架构的核心抽象。本文完整记录 Channel 层的设计思路、实现细节与扩展指南。

---

## 一、为什么需要 Channel 抽象层？

### 1.1 V1 的痛点

在 V1 版本中，求职派只支持微信公众号登录，通信逻辑硬编码在业务代码中：

```
用户消息 → 微信 SDK → Controller → Service → 大模型 → 返回结果
```

这种方式的致命问题是：
- **耦合严重**：微信 SDK 调用散落各处，想加钉钉？改一堆代码
- **无法复用**：每个渠道的消息格式、认证方式、推送机制都不同
- **难以测试**：没有统一的消息模型，单元测试无从下手

### 1.2 V2 的设计目标

我们希望回答一个问题：

> **如何让求职派能够以统一的方式对接微信、钉钉、飞书等多种 IM 渠道，且新增渠道时无需改动核心业务逻辑？**

答案就是 **Channel 抽象层**。

---

## 二、Channel 架构总览

### 2.1 整体设计

```
┌──────────────────────────────────────────────────────────┐
│                   IM 渠道层 (channels/)                     │
│     微信 ClawBot │ 钉钉 (WebSocket) │ 飞书 (WebSocket)      │
├──────────────────────────────────────────────────────────┤
│           通道抽象层 (core/channel/)                        │
│     Channel 接口 │ AbsChannel 基类 │ AbsStreamChannel     │
│     ChannelRegistry（通道注册中心）                         │
├──────────────────────────────────────────────────────────┤
│              事件总线 (core/bus/)                           │
│     ChannelEventPublisher（详见消息模型与事件总线文档）      │
├──────────────────────────────────────────────────────────┤
│            消息路由 (core/router/)                          │
│     MsgRouter │ IntentClassifier │ AgentRouter            │
└──────────────────────────────────────────────────────────┘
```

### 2.2 消息处理主链路

这是理解 Channel 层最重要的链路：

```
IM 消息到达（微信/钉钉/飞书）
  │
  ▼
AbsChannel.processMessage()          ← 通道层：消息适配
  → adaptToReceive()                  ← 将 SDK 消息转为统一消息模型
  → reportToAgent()                   ← 通过事件总线发布
  │
  ▼
ChannelEventPublisher                ← 事件总线（详见消息模型与事件总线文档）
  → publishMessageReceived()
  → MessageReceivedEvent
  │
  ▼
MsgRouter.onMessageReceived()        ← 消息路由：意图识别 + Agent 路由
  → 身份采集 → 系统命令 → 意图识别 → Agent 执行
  │
  ▼
ChannelEventPublisher                ← 响应回写
  → publishMessageResponse()
  → MessageResponseEvent
  │
  ▼
MsgRouter.onMessageResponse()
  → ChannelRegistry.getChannel()     ← 找到对应通道
  → Channel.responseToUser()         ← 将结果推送回 IM
```

> 📌 **注意**：消息模型（`ChannelReceiveMessage`、`ChannelResponseMessage`）和事件总线（`ChannelEventPublisher`）的详细说明已迁移至独立文档：[消息模型与事件总线设计实战](./02-✅求职派消息模型与事件总线设计实战.md)

---

## 三、核心抽象设计

### 3.1 Channel 接口

`Channel` 是所有通道的顶层抽象，定义了通道的基本行为：

```java
public interface Channel {
    /**
     * 通道名称（如 "weixin-clawbot", "dingding", "feishu"）
     */
    default String name() {
        return channel().getChannel();
    }

    /**
     * 通道类型枚举
     */
    ChannelConfig.ChannelEnum channel();

    /**
     * 通道接收到消息，并向外发送给 Agent
     */
    void reportToAgent(ChannelReceiveMessage msg);

    /**
     * 向通道发送消息（Agent 处理后的响应）
     */
    boolean responseToUser(ChannelResponseMessage msg);

    /**
     * 新增一个用户账号（一个通道支持多个用户）
     */
    default <T extends ChannelConfig> void addAccount(T channelConfig) {
        throw new UnsupportedOperationException("不支持添加用户");
    }
}
```

**设计要点**：
- 一个 Channel 实例可以服务多个用户（通过 `addAccount` 动态添加）
- `reportToAgent` 和 `responseToUser` 是双向通信的核心方法
- 通道名称全局唯一，用于事件路由

### 3.2 AbsChannel 基类：解决什么问题？

#### 为什么需要 AbsChannel？

如果让每个通道直接实现 `Channel` 接口，会出现什么问题？

**问题 1：代码重复**

```java
// 如果没有 AbsChannel，每个通道都要重复实现这些逻辑
public class WeChatChannel implements Channel {
    @Override
    public void reportToAgent(ChannelReceiveMessage msg) {
        // 1. 设置通道名称
        if (StringUtils.isBlank(msg.getChannel())) {
            msg.setChannel("weixin-clawbot");
        }
        // 2. 发布事件
        channelEventPublisher.publishMessageReceived(msg.getChannel(), msg, true);
    }
    
    @Override
    public void processMessage(MsgWrapper msg) {
        // 3. 消息适配
        var r = adaptToReceive(msg);
        if (r == null) return;
        
        // 4. 心跳维护
        saveHeartBeatConfig(msg, ...);
        
        // 5. 异常处理
        try {
            reportToAgent(r);
        } catch (Exception e) {
            responseToUser(ChannelResponseMessage.builder()
                .content("系统异常")
                .build());
        }
    }
}

// 钉钉通道又要重复一遍...
// 飞书通道又要重复一遍...
```

**问题 2：容易遗漏关键步骤**

不同的开发者实现不同通道时，可能会遗漏心跳维护、异常处理等关键步骤，导致：
- 后台任务无法主动推送消息（忘记保存心跳）
- 通道异常时用户没有反馈（忘记异常处理）
- 事件发布参数不一致（忘记设置通道名称）

**问题 3：难以统一升级**

如果需要新增一个功能（比如消息日志记录），需要修改所有通道的实现。

#### AbsChannel 的设计价值

`AbsChannel` 通过**模板方法模式**解决了上述问题：

```java
public abstract class AbsChannel<T> implements Channel, ChannelMsgAdapter<T>, CommandLineRunner {
    
    protected final ChannelEventPublisher channelEventPublisher;
    protected final ChannelRegistry channelRegistry;
    protected final ConfigurationManager configurationManager;

    /**
     * 消息处理入口（所有通道的统一入口）
     * 
     * 【解决的问题】
     * 1. 统一消息处理流程：适配 → 心跳 → 发布 → 异常处理
     * 2. 避免子类遗漏关键步骤
     * 3. 集中处理异常，保证用户体验
     */
    public void processMessage(MsgWrapper<T> msg) {
        var r = adaptToReceive(msg);  // ← 子类实现：SDK消息 → 统一消息模型
        if (r == null) return;

        try {
            // 更新心跳信息（用于后台主动推送）
            var tag = this.saveHeartBeatConfig(msg, ...);
            if (tag) {
                var func = buildHeartBeatCallback(msg.getJobClawUserId());
                channelRegistry.refreshChannelHeartBeatInfoIgnoreNull(
                    msg.getJobClawUserId(), r.getChannel(), func);
            }
            reportToAgent(r);  // ← 发布到事件总线
        } catch (Exception e) {
            // 异常时直接返回错误消息
            responseToUser(ChannelResponseMessage.builder()
                .content("系统异常，请稍后再试")
                .build());
        }
    }

    /**
     * 【解决的问题】统一事件发布逻辑，避免子类重复实现
     */
    @Override
    public void reportToAgent(ChannelReceiveMessage msg) {
        if (StringUtils.isBlank(msg.getChannel())) {
            msg.setChannel(name());
        }
        channelEventPublisher.publishMessageReceived(msg.getChannel(), msg, true);
    }

    /**
     * 【抽象点 1】心跳配置保存
     * 
     * 【为什么需要心跳？】
     * 后台任务（定时提醒、岗位推荐）需要主动向用户推送消息，但不同通道的推送方式不同：
     * - 微信：需要 msgContentToken 定位回复上下文
     * - 钉钉：需要 aiCardId 更新流式卡片
     * - 飞书：需要 openId 和 messageId 指定接收人
     * 
     * 每个通道在用户主动发消息时，保存这些关键信息，后续后台任务就可以通过这些信息主动推送。
     */
    public abstract boolean saveHeartBeatConfig(MsgWrapper<T> wrapper, boolean force);

    /**
     * 【抽象点 2】构建主动推送的回调函数
     * 
     * 【为什么需要回调？】
     * 后台任务只知道 jobClawUserId 和 channelName，不知道具体怎么构建响应消息。
     * 通过回调函数，通道层自己决定如何构建响应（携带什么透传参数）。
     */
    public abstract Function<Object, ChannelResponseMessage> buildHeartBeatCallback(String jobClawUserId);

    /**
     * 【解决的问题】Spring Boot 启动时自动激活通道
     * 
     * 【为什么实现 CommandLineRunner？】
     * 通道需要在应用启动时自动加载配置、建立连接、开始侦听消息。
     * 通过 CommandLineRunner 接口，Spring Boot 会自动调用 run() 方法。
     */
    @Override
    public void run(String... args) throws Exception {
        activeChannelAccounts();
    }

    /**
     * 【抽象点 3】激活通道账号
     * 
     * 【为什么需要这个方法？】
     * 不同通道的激活方式不同：
     * - 微信：加载配置，启动轮询侦听
     * - 钉钉：建立 WebSocket 连接，注册消息回调
     * - 飞书：创建 EventDispatcher，启动客户端
     */
    public abstract void activeChannelAccounts();
}
```

#### AbsChannel 抽象了哪些功能？

| 抽象功能 | 解决问题 | 子类是否需要实现 |
|---------|---------|----------------|
| **消息处理流程** | 统一适配 → 心跳 → 发布 → 异常处理的流程 | ❌ 已实现 |
| **事件发布** | 统一设置通道名称、调用事件总线 | ❌ 已实现 |
| **异常处理** | 保证通道异常时用户收到反馈 | ❌ 已实现 |
| **心跳维护** | 不同通道保存的心跳信息不同 | ✅ 子类实现 |
| **主动推送回调** | 不同通道构建响应的方式不同 | ✅ 子类实现 |
| **通道激活** | 不同通道的初始化方式不同 | ✅ 子类实现 |

**设计要点**：
- `processMessage` 是模板方法，定义了消息处理的标准流程
- 心跳机制支持后台任务主动向用户推送消息（如定时提醒、岗位推荐）
- 实现 `CommandLineRunner`，Spring Boot 启动时自动激活通道

### 3.3 AbsStreamChannel 流式通道：为什么需要它？

#### 为什么需要区分流式和非流式通道？

**问题场景**：

钉钉和飞书支持**流式响应**（大模型逐步生成内容，实时推送到用户端），而微信 ClawBot 采用轮询方式，只能等全部内容生成后一次性发送。

这两种通道的核心差异是什么？

| 特性 | 非流式（微信） | 流式（钉钉/飞书） |
|------|--------------|------------------|
| 响应方式 | 一次性发送完整内容 | 逐步增量更新 |
| 用户体验 | 等待时间长，不知道进度 | 实时看到生成过程 |
| 技术实现 | 简单调用 sendText | 需要管理 AI 卡片状态 |

**流式通道的核心挑战**：

1. **如何标识当前活跃的流式卡片？**
   - 用户可能同时发起多个对话
   - 需要知道哪个卡片正在生成内容

2. **如何避免并发冲突？**
   - 用户快速发送两条消息，会不会创建两个卡片？
   - 如何保证同一时间只有一个卡片在更新？

3. **如何管理卡片生命周期？**
   - 初始化 → 正在回答 → 完成
   - 完成后如何清理状态？

#### AbsStreamChannel 的设计价值

```java
public abstract class AbsStreamChannel<T> extends AbsChannel<T> {
    /**
     * 【解决的问题】管理流式 AI 卡片的状态
     * 
     * 【为什么需要 ActiveAiCardCache？】
     * 流式通道需要知道：
     * 1. 当前用户是否有正在生成的卡片？
     * 2. 如果有，卡片 ID 是什么？
     * 3. 卡片处于什么状态（初始化/正在回答/完成）？
     */
    protected ActiveAiCardCache aiCardStatus = new ActiveAiCardCache();

    /**
     * AI 卡片状态枚举
     */
    public enum AiCardStatus {
        INIT,       // 初始化：卡片已创建，等待开始生成
        ANSWERING,  // 正在回答：大模型正在逐步生成内容
        COMPLETE    // 完成：内容生成完毕
    }

    public record AiCardState(AiCardStatus status, Long updateTime) {}

    /**
     * 活跃 AI 卡片缓存
     * 
     * 【数据结构设计】
     * key = robotId + "_" + jobClawUserId  （机器人 + 用户）
     * value = Map<cardId, AiCardState>     （可能有多个卡片）
     * 
     * 【为什么这样设计？】
     * 1. 一个机器人可以服务多个用户
     * 2. 一个用户可能同时有多个对话（但通常只有一个活跃）
     * 3. 通过状态判断哪个卡片是活跃的（INIT 状态）
     */
    public static class ActiveAiCardCache {
        private final Map<String, Map<String, AiCardState>> aiCardStatus = new ConcurrentHashMap<>();

        /**
         * 【使用场景】用户发送新消息时，创建新的流式卡片
         */
        public void startAiCard(String robotId, String jobClawUserId, String cardId) {
            aiCardStatus.computeIfAbsent(buildKey(robotId, jobClawUserId),
                            key -> new ConcurrentHashMap<>())
                    .put(cardId, new AiCardState(AiCardStatus.INIT, System.currentTimeMillis()));
        }

        /**
         * 【使用场景】大模型开始生成内容时，标记卡片为回答中
         */
        public void answerAiCard(String robotId, String jobClawUserId, String cardId) {
            String key = buildKey(robotId, jobClawUserId);
            var map = aiCardStatus.get(key);
            if (map != null) {
                map.put(cardId, new AiCardState(AiCardStatus.ANSWERING, System.currentTimeMillis()));
            }
        }

        /**
         * 【使用场景】内容生成完成后，清理卡片状态
         */
        public void finishAiCard(String robotId, String jobClawUserId, String cardId) {
            String key = buildKey(robotId, jobClawUserId);
            var map = aiCardStatus.get(key);
            if (map != null) {
                map.remove(cardId);  // ← 完成后移除，释放内存
            }
        }

        /**
         * 【使用场景】用户发送新消息时，查找是否有未完成的卡片
         * 
         * 【为什么查找 INIT 状态？】
         * 如果用户快速发送两条消息，第一条消息创建的卡片可能还在 INIT 状态，
         * 此时应该复用这个卡片，而不是创建新的。
         */
        public String getActiveAiCard(String robotId, String jobClawUserId) {
            String key = buildKey(robotId, jobClawUserId);
            var map = aiCardStatus.get(key);
            if (map == null) {
                return null;
            }
            // 查找 INIT 状态的卡片（最新的）
            for (Map.Entry<String, AiCardState> entry : map.entrySet()) {
                if (entry.getValue().status == AiCardStatus.INIT) {
                    return entry.getKey();
                }
            }
            return null;
        }
    }
}
```

**设计要点**：
- 流式通道需要管理 AI 卡片状态，支持增量更新
- `ActiveAiCardCache` 维护用户当前活跃的流式卡片
- 钉钉和飞书都使用 AI 卡片实现流式输出

---

## 四、ChannelRegistry 注册中心：为什么需要它？

### 4.1 应用场景

#### 场景 1：消息响应时需要找到对应的通道

当 Agent 处理完用户消息后，需要将响应推送回 IM：

```java
// MsgRouter.onMessageResponse()
public void onMessageResponse(MessageResponseEvent event) {
    // 【问题】如何知道应该通过哪个通道发送响应？
    // 答案：通过 event.getChannel() 从 ChannelRegistry 查找
    var channel = channelRegistry.getChannel(event.getChannel());
    if (channel == null) {
        log.error("找不到对应的通道，请确认这个通道是否正常注册：{}", event.getChannel());
        return;
    }
    channel.responseToUser(event.getResponseMessage());
}
```

**如果没有 ChannelRegistry 会怎样？**

```java
// 反例：硬编码通道查找逻辑
public void onMessageResponse(MessageResponseEvent event) {
    Channel channel;
    if (event.getChannel().equals("weixin-clawbot")) {
        channel = weChatChannel;  // ← 需要注入所有通道实例
    } else if (event.getChannel().equals("dingding")) {
        channel = dingDingChannel;
    } else if (event.getChannel().equals("feishu")) {
        channel = feiShuChannel;
    } else {
        return;
    }
    channel.responseToUser(event.getResponseMessage());
}
```

**问题**：
- 每新增一个通道，都要修改这段代码
- 需要注入所有通道实例
- 违反了开闭原则

#### 场景 2：后台任务主动推送消息

定时任务触发时，需要主动向用户推送消息：

```java
// TaskBizAgent 触发定时提醒
public void sendReminder(String jobClawUserId, String reminderContent) {
    // 【问题 1】用户绑定了哪个通道？
    // 【问题 2】如何构建响应消息（需要通道特有的透传参数）？
    
    // 答案：通过 ChannelRegistry 获取心跳适配器
    Function<Object, ChannelResponseMessage> adapter = 
        channelRegistry.getChannelRspBuilderAdapter(jobClawUserId, channelName);
    
    if (adapter == null) {
        log.warn("用户未绑定通道，无法推送：{}", jobClawUserId);
        return;
    }
    
    // 构建响应消息
    ChannelResponseMessage response = adapter.apply(reminderContent);
    
    // 发布到事件总线
    channelEventPublisher.publishMessageResponse(..., response);
}
```

#### 场景 3：默认通道回退

如果事件中没有指定通道名称，应该使用默认通道：

```java
public Channel getChannel(String channelName) {
    if (StringUtils.isBlank(channelName)) {
        return channels.get(defaultChannelName);  // ← 使用默认通道
    }
    return channels.get(channelName);
}
```

### 4.2 ChannelRegistry 的功能

`ChannelRegistry` 管理所有已注册的通道实例：

```java
@Slf4j
public class ChannelRegistry {

    private final Map<String, Channel> channels;
    private String defaultChannelName;

    /**
     * 通道响应适配器（用于后台主动推送）
     * key = jobClawUserId + ":" + channelName
     * 
     * 【为什么需要这个 Map？】
     * 后台任务推送消息时，需要知道：
     * 1. 用户绑定了哪个通道？
     * 2. 如何构建响应消息（携带什么透传参数）？
     * 
     * 每个通道在用户主动发消息时，会注册一个适配器到 ChannelRegistry，
     * 后续后台任务就可以通过 jobClawUserId + channelName 查找这个适配器。
     */
    private final Map<String, Function<Object, ChannelResponseMessage>> channelResponseAdapters;

    /**
     * 【使用场景】通道启动时注册自己
     */
    public void registerChannel(Channel channel) {
        channels.put(channel.name(), channel);
        if (channels.size() == 1) {
            this.defaultChannelName = channel.name();  // ← 第一个注册的通道成为默认通道
        }
    }

    /**
     * 【使用场景】消息响应时查找通道
     */
    public Channel getChannel(String channelName) {
        if (StringUtils.isBlank(channelName)) {
            return channels.get(defaultChannelName);
        }
        return channels.get(channelName);
    }

    /**
     * 【使用场景】用户主动发消息时，刷新心跳信息
     */
    public void refreshChannelHeartBeatInfo(String jobClawUserId, String channelName, 
                                             Function<Object, ChannelResponseMessage> adapter) {
        String key = jobClawUserId + ":" + channelName;
        channelResponseAdapters.put(key, adapter);
        log.info("[ChannelRegistry] refresh channel heartbeat info, jobClawUserId={}, channelName={}", 
                 jobClawUserId, channelName);
    }

    /**
     * 【使用场景】后台任务推送消息时，查找心跳适配器
     */
    public Function<Object, ChannelResponseMessage> getChannelRspBuilderAdapter(
            String jobClawUserId, String channelName) {
        String key = jobClawUserId + ":" + channelName;
        return channelResponseAdapters.get(key);
    }
}
```

### 4.3 设计要点

**1. 为什么用 Map 而不是 List？**

```java
// ❌ 如果用 List
private final List<Channel> channels;

public Channel getChannel(String channelName) {
    for (Channel channel : channels) {
        if (channel.name().equals(channelName)) {
            return channel;
        }
    }
    return null;
}
```

**问题**：每次查找都要遍历，时间复杂度 O(n)

```java
// ✅ 用 Map
private final Map<String, Channel> channels;

public Channel getChannel(String channelName) {
    return channels.get(channelName);  // ← 时间复杂度 O(1)
}
```

**2. 为什么需要 defaultChannelName？**

某些场景下可能不知道通道名称（比如用户只有一个通道），此时使用默认通道：

```java
// 用户只有一个通道，不需要指定通道名称
channelEventPublisher.publishProactiveMessage(
    "TASK_" + System.currentTimeMillis(),
    jobClawUserId,
    null,  // ← 使用默认通道
    "您设置的提醒时间到了"
);
```

**3. 为什么心跳适配器用 `jobClawUserId + ":" + channelName` 作为 key？**

- 一个用户可能绑定多个通道（微信 + 钉钉）
- 一个通道可能服务多个用户
- 需要唯一标识“用户 + 通道”的组合

**设计要点**：
- 通道按名称注册，支持默认通道
- 心跳适配器用于后台任务主动推送消息时构建响应
- 线程安全（`ConcurrentHashMap`）

---

## 五、具体通道实现

### 5.1 微信 ClawBot 通道

微信通道基于 ClawBot API 实现，采用轮询方式获取消息：

```java
public class WeChatClawBotChannel extends AbsChannel<WeixinTypes.WeixinMessage> {

    private final Map<String, WeixinSdk> accountMap;
    private final WxChatClawBotProperties wxChatClawBotProperties;

    @Override
    public void activeChannelAccounts() {
        this.loadAllAccounts();
    }

    /**
     * 加载所有账号并启动轮询监听
     */
    public void loadAllAccounts() {
        this.channelRegistry.registerChannel(this);
        this.wxChatClawBotProperties.getAccounts().forEach((jobUserId, account) -> {
            this.addAccount(account);
        });
    }

    @Override
    public <T extends ChannelConfig> void addAccount(T account) {
        WxClawBotAccount botAccount = (WxClawBotAccount) account;
        var sdk = new WeixinSdk.Builder()
                .baseUrl(this.wxChatClawBotProperties.getBaseUrl())
                .token(account.getAppSecret())
                .stateDir(stateDir)
                .mediaDir(mediaDir.resolve(jobClawUserId).toString())
                .build();
        
        this.accountMap.put(wxUserId, sdk);

        // 启动轮询监听
        sdk.startPolling(new WeixinSdk.MessageHandler() {
            @Override
            public void onMessage(WeixinTypes.WeixinMessage message) {
                processMessage(MsgWrapper.<WeixinTypes.WeixinMessage>builder()
                    .msg(message)
                    .jobClawUserId(jobClawUserId)
                    .build());
            }
        });
    }

    @Override
    public ChannelReceiveMessage adaptToReceive(MsgWrapper<WeixinTypes.WeixinMessage> msgWrapper) {
        WeixinTypes.WeixinMessage message = msgWrapper.getMsg();
        
        // 提取文本
        String messageText = MessageBuilder.extractText(message);
        
        // 提取媒体
        List<ChannelReceiveMessage.MediaMsg> medias = new ArrayList<>();
        List<ChannelReceiveMessage.FileMsg> files = new ArrayList<>();
        for (WeixinTypes.MessageItem item : message.getItemList()) {
            if (item.getImageItem() != null) {
                medias.add(ChannelReceiveMessage.MediaMsg.builder()
                    .filePath(item.getImageItem().getLocalPath())
                    .mimeType("image/jpeg")
                    .build());
            }
            // ... 处理文件、视频等
        }

        return ChannelReceiveMessage.builder()
                .msgId("WX_" + message.getMessageId())
                .channel(name())
                .fromUserId(message.getFromUserId())
                .jobClawUserId(msgWrapper.getJobClawUserId())
                .passThrough(Map.of("msgContentToken", message.getContextToken()))
                .message(messageText)
                .files(files)
                .medias(medias)
                .build();
    }

    @Override
    public boolean responseToUser(ChannelResponseMessage msg) {
        WeixinSdk sdk = accountMap.get(...);
        // 根据消息类型发送
        if (msg.getType() == ResponseMessageType.TEXT) {
            sdk.sendText(msg.getToUserId(), msg.getContent());
        }
        return true;
    }
}
```

**设计要点**：
- 基于 ClawBot SDK 轮询获取消息
- 支持一个求职派用户配置多个微信账号
- 媒体文件自动下载到本地 `workspace/channel/wx/media/{jobClawUserId}/`

### 5.2 钉钉通道

钉钉通道基于钉钉开放平台 SDK，支持流式 AI 卡片：

```java
public class DingDingBotChannel extends AbsStreamChannel<ChatbotMessageEx> {

    private Map<String, DingDingSdk> sdkMap = new ConcurrentHashMap<>();

    @Override
    public void activeChannelAccounts() {
        Thread.ofVirtual().start(() -> {
            this.dingDingBotProperties.getAccounts().forEach((k, v) -> {
                v.forEach(tmp -> registerMsgListenerCallback(k, tmp));
            });
            channelRegistry.registerChannel(this);
        });
    }

    private void registerMsgListenerCallback(String robotOwnerUserId, ChannelConfig config) {
        var sdk = new DingDingSdk((DingDingBotAccount) config);
        sdkMap.put(config.getAppId(), sdk);
        
        sdk.start(chatbotMessage -> {
            // 创建流式 AI 卡片
            String aiCardId = sdk.initStreamAiCardId(config.getAppId(), chatbotMessage);
            aiCardStatus.startAiCard(config.getAppId(), dingDingId, aiCardId);
            
            ChatbotMessageEx msgEx = new ChatbotMessageEx();
            BeanUtils.copyProperties(chatbotMessage, msgEx);
            msgEx.setAiCardId(aiCardId);
            
            processMessage(MsgWrapper.<ChatbotMessageEx>builder()
                .msg(msgEx)
                .jobClawUserId(robotOwnerUserId)
                .build());
        });
    }

    @Override
    public ChannelReceiveMessage adaptToReceive(MsgWrapper<ChatbotMessageEx> msgWrapper) {
        ChatbotMessageEx msg = msgWrapper.getMsg();
        
        // 权限校验（OWNER/LOGIN/VIP/PUBLIC）
        String jobClawUserId = resolveUserId(msg, channelConfig);
        if (jobClawUserId == null) return null;
        
        // 解析消息内容（文本 + 媒体）
        var msgContent = sdkMap.get(msg.getRobotId()).parseContent(msg);
        
        // 自动下载媒体文件
        if (msgContent.media() != null) {
            var tmpFile = localStorageHelper.autoDownloadFile(...);
            msgContent.media().setFilePath(Path.of(tmpFile));
        }

        return ChannelReceiveMessage.builder()
                .msgId(msg.getMsgId())
                .message(msgContent.content())
                .medias(msgContent.media() == null ? null : List.of(msgContent.media()))
                .channel(name())
                .fromUserId(msg.getSenderStaffId())
                .jobClawUserId(jobClawUserId)
                .passThrough(Map.of("aiCardId", msg.getAiCardId()))
                .stream(true)  // ← 支持流式
                .build();
    }

    @Override
    public boolean responseToUser(ChannelResponseMessage msg) {
        String aiCardId = (String) msg.getPassThrough().get("aiCardId");
        DingDingSdk sdk = sdkMap.get(...);
        
        if (msg.getStreamContents() != null) {
            // 流式响应：逐步更新 AI 卡片
            sdk.streamUpdateAiCard(aiCardId, msg.getStreamContents());
        } else {
            // 同步响应
            sdk.sendText(msg.getToUserId(), msg.getContent());
        }
        return true;
    }
}
```

**设计要点**：
- 使用虚拟线程加速初始化
- 支持 Scope 权限控制（OWNER/LOGIN/VIP/PUBLIC）
- 流式响应通过 AI 卡片实现
- 媒体文件自动下载

### 5.3 飞书通道

飞书通道基于飞书 OpenAPI SDK，同样支持流式卡片：

```java
public class FeiShuBotChannel extends AbsStreamChannel<ChatbotMessageEx> {

    private final Map<String, StreamCardManager> cardManagers = new ConcurrentHashMap<>();

    private void registerMsgListenerCallback(String ownUserId, ChannelConfig config) {
        EventDispatcher eventDispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) throws Exception {
                        var eventData = event.getEvent();
                        String openId = eventData.getSender().getSenderId().getOpenId();
                        String messageId = eventData.getMessage().getMessageId();
                        
                        // 创建流式卡片
                        var cardManager = cardManagers.get(config.getAppId());
                        String cardId = cardManager.initStreamAiCardId(openId);
                        aiCardStatus.startAiCard(account.getAppId(), openId, cardId);
                        
                        var ex = new ChatbotMessageEx()
                                .setRobotId(account.getAppId())
                                .setAiCardId(cardId)
                                .setOpenId(openId)
                                .setMessageId(messageId)
                                .setMsgType(eventData.getMessage().getMessageType())
                                .setChatType(eventData.getMessage().getChatType())
                                .setContent(eventData.getMessage().getContent());
                        
                        processMessage(MsgWrapper.<ChatbotMessageEx>builder()
                            .jobClawUserId(ownUserId)
                            .msg(ex)
                            .build());
                    }
                })
                .build();

        Client feishuClient = new Client.Builder(config.getAppId(), config.getAppSecret())
                .eventHandler(eventDispatcher)
                .build();
        
        this.cardManagers.put(config.getAppId(), new StreamCardManager(account));
        feishuClient.start();
    }
}
```

**设计要点**：
- 基于飞书 EventDispatcher 处理事件
- `StreamCardManager` 管理飞书流式卡片生命周期
- 支持图片、文件、富文本等多种消息类型

---

## 六、心跳与主动推送机制：为什么需要心跳？

### 6.1 心跳机制的核心问题

**场景还原**：

```
用户（通过钉钉）："帮我设置一个明天早上 9 点的面试提醒"
Agent："好的，已为您设置明天 09:00 的面试提醒"

（第二天早上 09:00）
定时任务触发 → 需要推送消息给用户 → 问题来了：
1. 用户通过哪个通道接收消息？（微信？钉钉？飞书？）
2. 如何构建响应消息？（钉钉需要 aiCardId，微信需要 msgContentToken）
```

**如果没有心跳机制会怎样？**

```java
// 反例：后台任务不知道如何推送消息
public void sendReminder(String jobClawUserId, String content) {
    // 问题 1：用户绑定了哪个通道？
    String channelName = ???  // ← 不知道
    
    // 问题 2：如何构建响应消息？
    ChannelResponseMessage response = ???  // ← 不知道需要携带什么透传参数
    
    // 无法推送！
}
```

### 6.2 心跳配置的作用

当用户主动发送消息时，通道会保存心跳信息：

```java
// AbsChannel.processMessage()
var tag = this.saveHeartBeatConfig(msg, force);
if (tag) {
    var func = buildHeartBeatCallback(msg.getJobClawUserId());
    channelRegistry.refreshChannelHeartBeatInfoIgnoreNull(
        msg.getJobClawUserId(), r.getChannel(), func);
}
```

心跳信息包含：
- `jobClawUserId`：求职派用户 ID
- `channelName`：通道名称
- `adapter`：构建响应消息的回调函数

**心跳的本质**：告诉系统“这个用户通过这个通道活跃过，后续可以通过这个通道推送消息”。

### 6.3 主动推送的完整流程

后台任务（如定时提醒、岗位推荐）主动向用户推送消息：

```java
// TaskBizAgent 触发定时提醒
channelEventPublisher.publishProactiveMessage(
    "TASK_" + System.currentTimeMillis(),
    jobClawUserId,
    channelName,
    "您设置的提醒时间到了：面试准备"
);
```

推送流程：
```
后台任务触发
  │
  ▼
ChannelEventPublisher.publishProactiveMessage()
  │
  ├─ 【步骤 1】查找通道：channelRegistry.getChannel(channelName)
  │      ↓
  │   如果通道不存在 → 推送失败
  │
  ├─ 【步骤 2】获取心跳适配器：channelRegistry.getChannelRspBuilderAdapter(jobClawUserId, channelName)
  │      ↓
  │   如果适配器不存在 → 推送失败（用户未通过这个通道活跃过）
  │
  ├─ 【步骤 3】构建响应：adapter.apply(pushContent) → ChannelResponseMessage
  │      ↓
  │   适配器由通道层提供，会携带通道特有的透传参数（如 aiCardId、msgContentToken）
  │
  └─ 【步骤 4】发布事件：publishMessageResponse(responseId, null, channelName, responseMessage)
           │
           ▼
     MsgRouter.onMessageResponse() @Async
           │
           ▼
     Channel.responseToUser() → 推送到 IM
```

**为什么需要这么复杂？**

因为不同通道的推送方式完全不同：
- **微信**：需要 `msgContentToken` 定位回复上下文，通过 ClawBot API 发送
- **钉钉**：需要 `aiCardId` 更新流式卡片，通过钉钉 SDK 更新卡片内容
- **飞书**：需要 `openId` 和 `messageId` 指定接收人，通过飞书 API 发送

通过心跳机制，通道层自己决定如何构建响应，后台任务无需关心这些细节。

---

## 七、权限管控设计：为什么需要 Scope 权限？

### 7.1 Scope 权限模型的应用场景

**问题场景**：

用户 A 创建了一个钉钉机器人，希望这个机器人：
- 场景 1：只为自己服务（个人专属）
- 场景 2：为团队成员服务（需要绑定求职派账号）
- 场景 3：为 VIP 用户服务（付费功能）
- 场景 4：为所有人服务（公开机器人）

如何实现这种灵活的权限控制？

**Scope 权限模型**：

通道支持四级权限控制：

| Scope | 说明 | 适用场景 |
|-------|------|---------|
| `OWNER` | 只有创建者可以对话 | 个人专属机器人 |
| `LOGIN` | 绑定求职派用户可对话 | 团队内部使用 |
| `VIP` | VIP 用户可对话 | 付费用户专属 |
| `PUBLIC` | 所有用户可对话 | 公开服务 |

### 7.2 权限校验实现

以钉钉通道为例：

```java
// DingDingBotChannel.adaptToReceive()
var user = userService.getUser(dingDingId, channel());

if (channelConfig.getScope() == ChannelConfig.ChannelScope.OWNER) {
    if (user == null || !String.valueOf(user.userId()).equals(robotOwnerUserId)) {
        errorResponse(msg, "这个机器人只为创作者本人服务哦~");
        return null;
    }
    jobClawUserId = robotOwnerUserId;
} else if (channelConfig.getScope() == ChannelConfig.ChannelScope.LOGIN) {
    if (user == null) {
        errorResponse(msg, "您的个人求职派还没有绑定钉钉渠道哦");
        return null;
    }
    jobClawUserId = String.valueOf(user.userId());
} else if (channelConfig.getScope() == ChannelConfig.ChannelScope.VIP) {
    if (user == null || (user.role() != UserRoleEnum.VIP && user.role() != UserRoleEnum.ADMIN)) {
        errorResponse(msg, "这个机器人属于VIP专享哦~");
        return null;
    }
    jobClawUserId = String.valueOf(user.userId());
} else if (channelConfig.getScope() == ChannelConfig.ChannelScope.PUBLIC) {
    if (user == null) {
        jobClawUserId = "D-" + dingDingId;  // 使用钉钉用户体系
    } else {
        jobClawUserId = String.valueOf(user.userId());
    }
}
```

**设计要点**：
- 群聊中不支持设置用户个人偏好
- 未绑定用户基于渠道 ID（如钉钉 `staffId`）存储，功能受限
- 绑定用户后基于 `JobClawUserId` 构建偏好和会话

---

## 八、如何扩展新通道？

### 8.1 扩展步骤

以扩展"Telegram"通道为例：

**Step 1**: 创建 Maven 模块 `channels/telegram`

```xml
<dependency>
    <groupId>com.git.hui.jobclaw</groupId>
    <artifactId>core</artifactId>
</dependency>
```

**Step 2**: 继承 `AbsChannel` 或 `AbsStreamChannel`

```java
public class TelegramChannel extends AbsChannel<TgMessage> {
    
    @Override
    public ChannelConfig.ChannelEnum channel() {
        return ChannelConfig.ChannelEnum.TELEGRAM;  // ← 在 ChannelEnum 中新增
    }

    @Override
    public void activeChannelAccounts() {
        // 初始化 Telegram Bot，注册到 ChannelRegistry
        channelRegistry.registerChannel(this);
    }

    @Override
    public ChannelReceiveMessage adaptToReceive(MsgWrapper<TgMessage> msgWrapper) {
        TgMessage tgMsg = msgWrapper.getMsg();
        return ChannelReceiveMessage.builder()
                .msgId(tgMsg.getMessageId())
                .message(tgMsg.getText())
                .channel(name())
                .fromUserId(tgMsg.getFromUserId())
                .jobClawUserId(msgWrapper.getJobClawUserId())
                .build();
    }

    @Override
    public boolean responseToUser(ChannelResponseMessage msg) {
        // 调用 Telegram Bot API 发送消息
        telegramBot.sendMessage(msg.getToUserId(), msg.getContent());
        return true;
    }

    @Override
    public boolean saveHeartBeatConfig(MsgWrapper<TgMessage> wrapper, boolean force) {
        // 保存心跳配置
        return true;
    }

    @Override
    public Function<Object, ChannelResponseMessage> buildHeartBeatCallback(String jobClawUserId) {
        // 构建主动推送回调
        return content -> ChannelResponseMessage.builder()
                .content(content.toString())
                .build();
    }
}
```

**Step 3**: 在 `ChannelConfig.ChannelEnum` 中新增枚举

```java
public enum ChannelEnum {
    WEXIN_CLAW_BOT("weixin-clawbot"),
    DING_DING("dingding"),
    FEI_SHU("feishu"),
    TELEGRAM("telegram"),  // ← 新增
    ;
}
```

**Step 4**: 在 `app` 模块中引入依赖

```xml
<dependency>
    <groupId>com.git.hui.jobclaw</groupId>
    <artifactId>telegram</artifactId>
</dependency>
```

**Step 5**: 配置通道账号

```yaml
agent:
  channels:
    telegram:
      enabled: true
      accounts:
        "1001":  # jobClawUserId
          - appId: "telegram-bot-1"
            appSecret: "your-bot-token"
            scope: PUBLIC
```

**为什么扩展这么简单？**

因为 `AbsChannel` 已经处理了：
- ✅ 消息处理流程（适配 → 心跳 → 发布 → 异常处理）
- ✅ 事件发布逻辑
- ✅ 异常处理
- ✅ 应用启动时自动激活

你只需要实现 5 个抽象方法：
1. `channel()` - 返回通道类型
2. `activeChannelAccounts()` - 初始化通道
3. `adaptToReceive()` - SDK消息 → 统一消息模型
4. `responseToUser()` - 响应消息 → SDK消息格式
5. `saveHeartBeatConfig()` - 保存心跳信息
6. `buildHeartBeatCallback()` - 构建主动推送回调

**这就是模板方法模式的价值**：固定流程，扩展细节。

### 8.2 扩展示例：支持新消息类型

如果新通道支持语音消息：

```java
@Override
public ChannelReceiveMessage adaptToReceive(MsgWrapper<TgMessage> msgWrapper) {
    TgMessage tgMsg = msgWrapper.getMsg();
    
    // 处理语音消息
    if (tgMsg.getVoice() != null) {
        ChannelReceiveMessage.MediaMsg voice = ChannelReceiveMessage.MediaMsg.builder()
                .downUrl(tgMsg.getVoice().getFileUrl())
                .fileType("voice")
                .mimeType("audio/ogg")
                .build();
        
        // 自动下载
        var tmpFile = localStorageHelper.autoDownloadFile(...);
        voice.setFilePath(Path.of(tmpFile));
    }
    
    return ChannelReceiveMessage.builder()
            .medias(List.of(voice))
            .build();
}
```

---

## 九、关键技术细节

### 9.1 媒体文件处理：为什么需要自动下载？

**问题场景**：

用户通过钉钉发送一张图片，大模型需要分析图片内容：

```java
// 钉钉 SDK 返回的图片信息
String downUrl = "https://img.alicdn.com/imgextra/xxx.jpg";

// 【问题】大模型如何访问这个图片？
// 方案 1：直接传 URL → 可能有时效性、需要鉴权
// 方案 2：下载到本地 → 稳定可靠，但需要管理文件生命周期
```

**通道层的解决方案**：自动下载媒体文件到本地

```java
// ChannelStorageHelper.autoDownloadFile()
public String autoDownloadFile(String jobClawUserId, String channelName, 
                                String downUrl, String fileType) {
    // 存储路径：workspace/channel/{channel}/{user}/{type}/
    String filePath = workspace.resolve("channel")
            .resolve(channelName)
            .resolve(jobClawUserId)
            .resolve(fileType)
            .resolve(UUID.randomUUID() + "." + fileType)
            .toString();
    
    // 下载文件
    HttpUtil.downloadFile(downUrl, filePath);
    return filePath;
}
```

存储结构：
```
workspace/
└── channel/
    ├── weixin/
    │   └── media/
    │       └── {jobClawUserId}/
    │           ├── image/
    │           └── file/
    ├── dingding/
    │   └── {jobClawUserId}/
    │       └── image/
    └── feishu/
        └── {jobClawUserId}/
            ├── image/
            └── file/
```

**设计要点**：
- 按通道、用户、文件类型分层存储，便于管理
- UUID 命名避免冲突
- 后续可以定时清理过期文件

### 9.2 流式响应实现：为什么需要 AI 卡片？

钉钉/飞书的流式响应通过 AI 卡片实现：

```java
// DingDingSdk.streamUpdateAiCard()
public void streamUpdateAiCard(String aiCardId, Flux<LlmRspCell> streamContents) {
    streamContents.doOnNext(cell -> {
        if (cell.getContent() != null) {
            // 逐步更新卡片内容
            updateAiCardContent(aiCardId, cell.getContent());
            aiCardStatus.answerAiCard(...);
        }
    }).doOnComplete(() -> {
        // 完成时标记卡片状态
        aiCardStatus.finishAiCard(...);
    }).subscribe();
}
```

### 9.3 异常处理：为什么要在通道层统一处理？

通道层统一处理异常：

```java
// AbsChannel.processMessage()
try {
    reportToAgent(r);
} catch (Exception e) {
    ChannelResponseMessage response = ChannelResponseMessage.builder()
            .content("系统异常，请稍后再试\n" + ThrowableUtil.getStackTrace(e))
            .passThrough(r.getPassThrough())
            .build();
    responseToUser(response);
}
```

---

## 十、通道对比

| 特性 | 微信 ClawBot | 钉钉 | 飞书 |
|------|-------------|------|------|
| 基类 | `AbsChannel` | `AbsStreamChannel` | `AbsStreamChannel` |
| 通信方式 | 轮询 | WebSocket | WebSocket |
| 流式支持 | ❌ | ✅ AI 卡片 | ✅ 流式卡片 |
| 消息类型 | 文本/图片/文件/视频 | 文本/图片/文件 | 文本/图片/文件/富文本 |
| 权限控制 | 基础 | Scope 四级 | Scope 四级 |
| 心跳机制 | ✅ | ✅ | ✅ |
| 媒体下载 | ✅ 自动 | ✅ 自动 | ✅ 自动 |

---

## 十一、常见问题

### 11.1 为什么不直接让 Channel 调用 Agent？

**问题**：为什么不直接让 Channel 调用 Agent？

**答案**：详见 [消息模型与事件总线设计实战](./02-✅求职派消息模型与事件总线设计实战.md) 中的详细讲解。

### 11.2 为什么需要保存心跳信息？

**问题**：为什么需要保存心跳信息？

**答案**：心跳机制支持后台任务主动向用户推送消息：
- 定时任务触发时，需要知道用户的通道信息
- 不同通道的推送方式不同（微信需要 `msgContentToken`，钉钉需要 `aiCardId`）
- 心跳信息保存了构建响应消息所需的回调函数

### 11.3 什么时候用 `AbsStreamChannel`？

**问题**：什么时候用 `AbsStreamChannel`？

**答案**：
- **非流式**：微信 ClawBot 采用轮询，不支持实时推送，使用 `AbsChannel`
- **流式**：钉钉/飞书基于 WebSocket，支持实时流式更新，使用 `AbsStreamChannel`
- 判断标准：通道是否支持增量更新消息内容

### 11.4 如何调试通道？

**建议**：
1. 开启 DEBUG 日志：`logging.level.com.git.hui.jobclaw.channels=DEBUG`
2. 检查 `workspace/channel/` 目录下的媒体文件
3. 使用 `ChannelRegistry` 查看已注册通道
4. 测试主动推送：`/admin/channel/push?jobClawUserId=1001&channel=dingding`

---

## 十二、小结

Channel 层是求职派“多渠道即插即用”架构的核心，它通过以下设计实现了灵活扩展：

1. **统一抽象**：`Channel` 接口定义通道的标准行为
2. **模板方法**：`AbsChannel` 实现通用逻辑，子类只需关注渠道特性
3. **流式支持**：`AbsStreamChannel` 为钉钉、飞书等流式通道提供 AI 卡片管理
4. **事件驱动**：通过事件总线解耦通道与业务（详见 [消息模型与事件总线设计](./02-✅求职派消息模型与事件总线设计实战.md)）
5. **心跳机制**：支持后台任务主动推送消息
6. **权限管控**：Scope 四级权限模型，区分群聊与私聊

新增通道只需继承 `AbsChannel` 并实现 5 个抽象方法，无需改动核心业务逻辑。这种设计让求职派能够轻松对接各种 IM 平台，真正实现“多渠道、多模型、多 Agent”的灵活组合。

---

:::success
相关代码：
- Channel 接口：`core/src/main/java/com/git/hui/jobclaw/core/channel/Channel.java`
- AbsChannel 基类：`core/src/main/java/com/git/hui/jobclaw/core/channel/AbsChannel.java`
- AbsStreamChannel 流式通道：`core/src/main/java/com/git/hui/jobclaw/core/channel/AbsStreamChannel.java`
- ChannelRegistry 注册中心：`core/src/main/java/com/git/hui/jobclaw/core/channel/ChannelRegistry.java`
- 微信通道：`channels/wechat-clawbot/src/main/java/com/git/hui/jobclaw/channels/WeChatClawBotChannel.java`
- 钉钉通道：`channels/dingding/src/main/java/com/git/hui/jobclaw/channels/DingDingBotChannel.java`
- 飞书通道：`channels/feishu/src/main/java/com/git/hui/jobclaw/channels/FeiShuBotChannel.java`

:::

---

> 相关文档：
> - [消息模型与事件总线设计](./02-✅求职派消息模型与事件总线设计实战.md)
> - [V2 重构总览](../07.V2重构/00-求职派V2重构：从单体应用到多Agent运行时.md)
> - [架构总览](../03、架构篇/00-✅求职派OpenClaw式多Agent架构总览.md)
