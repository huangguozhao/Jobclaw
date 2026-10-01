# Weixin ClawBot Java SDK

完整的微信 ClawBot API Java 实现，提供与微信 iLink Bot API 交互的所有功能。

## 功能特性

- ✅ **消息接收**：长轮询方式接收消息（getUpdates）
- ✅ **文本消息**：发送纯文本消息
- ✅ **图片消息**：发送图片（支持 AES-128-ECB 加密上传到 CDN）
- ✅ **视频消息**：发送视频文件
- ✅ **文件消息**：发送任意文件附件
- ✅ **语音消息**：支持语音转文字
- ✅ **输入状态**：显示/取消"正在输入"状态
- ✅ **上下文管理**：自动管理 context_token，支持持久化
- ✅ **CDN 上传**：完整的 CDN 上传流程，包含 AES 加密

## 快速开始

### 1. 创建 SDK 实例

```java
import ai.javaclaw.channels.weclawbot.sdk.WeixinSdk;

WeixinSdk sdk = new WeixinSdk.Builder()
    .baseUrl("https://ilinkai.weixin.qq.com")
    .cdnBaseUrl("https://novac2c.cdn.weixin.qq.com/c2c")
    .token("your-bot-token")
    .channelVersion("1.0.3")
    .stateDir("./workspace/weixin-state")
    .accountId("my-account")
    .build();
```

### 2. 启动消息轮询

```java
sdk.startPolling(message -> {
    String fromUser = message.getFromUserId();
    String text = MessageBuilder.extractText(message);
    String contextToken = message.getContextToken();
    
    System.out.println("收到消息: " + fromUser + " - " + text);
    
    // 回复消息
    try {
        sdk.getMessageSender().sendTextMessage(fromUser, "你好！", contextToken);
    } catch (Exception e) {
        e.printStackTrace();
    }
});
```

### 3. 发送不同类型的消息

#### 发送文本消息

```java
String clientId = sdk.getMessageSender().sendTextMessage(
    userId, 
    "Hello, World!", 
    contextToken
);
```

#### 发送图片消息

```java
String clientId = sdk.getMessageSender().sendImageMessage(
    userId,
    "这是一张图片",
    "/path/to/image.jpg",
    contextToken
);
```

#### 发送视频消息

```java
String clientId = sdk.getMessageSender().sendVideoMessage(
    userId,
    "这是一个视频",
    "/path/to/video.mp4",
    contextToken
);
```

#### 发送文件附件

```java
String clientId = sdk.getMessageSender().sendFileMessage(
    userId,
    "请查看附件",
    "/path/to/document.pdf",
    "document.pdf",
    contextToken
);
```

### 4. 使用输入状态指示器

```java
// 获取 typing ticket
String typingTicket = sdk.getMessageSender().getTypingTicket(userId, contextToken);

// 显示"正在输入"
sdk.getMessageSender().sendTyping(userId, typingTicket, TypingStatus.TYPING);

// 处理中...

// 取消"正在输入"
sdk.getMessageSender().sendTyping(userId, typingTicket, TypingStatus.CANCEL);
```

### 5. 管理上下文令牌

```java
ContextTokenManager tokenManager = sdk.getContextTokenManager();

// 设置令牌
tokenManager.setContextToken(accountId, userId, token);

// 获取令牌
String token = tokenManager.getContextToken(accountId, userId);

// 清除令牌
tokenManager.clearContextTokensForAccount(accountId);
```

### 6. 直接使用底层 API

```java
WeixinApiClient apiClient = sdk.getApiClient();

// 获取更新
GetUpdatesResp resp = apiClient.getUpdates(buf, 35000);

// 发送自定义消息
SendMessageReq req = MessageBuilder.buildTextMessage(userId, text, contextToken);
apiClient.sendMessage(req);

// 获取配置
GetConfigResp config = apiClient.getConfig(userId, contextToken);
```

## 架构说明

### 核心组件

```
WeixinSdk (入口类)
├── WeixinApiClient (HTTP API 客户端)
│   ├── getUpdates() - 长轮询获取消息
│   ├── sendMessage() - 发送消息
│   ├── getUploadUrl() - 获取 CDN 上传 URL
│   ├── getConfig() - 获取配置
│   └── sendTyping() - 发送输入状态
├── CdnUploader (CDN 上传工具)
│   ├── uploadImage() - 上传图片
│   ├── uploadVideo() - 上传视频
│   └── uploadFileAttachment() - 上传文件
├── MessageSender (消息发送器)
│   ├── sendTextMessage() - 发送文本
│   ├── sendImageMessage() - 发送图片
│   ├── sendVideoMessage() - 发送视频
│   ├── sendFileMessage() - 发送文件
│   └── sendTyping() - 发送输入状态
├── MessageBuilder (消息构建器)
│   ├── buildTextMessage() - 构建文本消息
│   ├── buildImageMessage() - 构建图片消息
│   ├── buildVideoMessage() - 构建视频消息
│   └── buildFileMessage() - 构建文件消息
└── ContextTokenManager (上下文令牌管理器)
    ├── setContextToken() - 存储令牌
    ├── getContextToken() - 获取令牌
    └── persistContextTokens() - 持久化令牌
```

### 消息类型常量

```java
// 消息类型
MessageType.USER = 1  // 用户消息
MessageType.BOT = 2   // 机器人消息

// 消息项类型
MessageItemType.TEXT = 1   // 文本
MessageItemType.IMAGE = 2  // 图片
MessageItemType.VOICE = 3  // 语音
MessageItemType.FILE = 4   // 文件
MessageItemType.VIDEO = 5  // 视频

// 消息状态
MessageState.NEW = 0         // 新消息
MessageState.GENERATING = 1  // 生成中
MessageState.FINISH = 2      // 完成

// 上传媒体类型
UploadMediaType.IMAGE = 1  // 图片
UploadMediaType.VIDEO = 2  // 视频
UploadMediaType.FILE = 3   // 文件
UploadMediaType.VOICE = 4  // 语音

// 输入状态
TypingStatus.TYPING = 1   // 正在输入
TypingStatus.CANCEL = 2   // 取消输入
```

## 高级用法

### 自定义消息处理

```java
sdk.startPolling(message -> {
    // 提取文本内容
    String text = MessageBuilder.extractText(message);
    
    // 检查是否有媒体
    if (message.getItemList() != null) {
        for (MessageItem item : message.getItemList()) {
            if (MessageBuilder.isMediaItem(item)) {
                System.out.println("收到媒体消息，类型: " + item.getType());
                
                // 处理图片
                if (item.getType() == MessageItemType.IMAGE) {
                    ImageItem img = item.getImageItem();
                    // 下载并处理图片...
                }
            }
        }
    }
    
    // 根据消息内容回复
    if (text.contains("图片")) {
        sdk.getMessageSender().sendImageMessage(
            message.getFromUserId(),
            "这是你要的图片",
            "/path/to/image.jpg",
            message.getContextToken()
        );
    } else {
        sdk.getMessageSender().sendTextMessage(
            message.getFromUserId(),
            "你说: " + text,
            message.getContextToken()
        );
    }
});
```

### 多账号管理

```java
// 为每个账号创建独立的 SDK 实例
WeixinSdk account1 = new WeixinSdk.Builder()
    .token("token1")
    .accountId("account1")
    .build();

WeixinSdk account2 = new WeixinSdk.Builder()
    .token("token2")
    .accountId("account2")
    .build();

// 分别启动轮询
account1.startPolling(msg -> handleAccount1(msg));
account2.startPolling(msg -> handleAccount2(msg));
```

### 优雅关闭

```java
// 注册关闭钩子
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    sdk.shutdown();
}));
```

## 注意事项

1. **Context Token**：每条消息都会携带 context_token，必须在回复时原样返回。SDK 会自动管理，但也可以手动通过 `ContextTokenManager` 访问。

2. **CDN 上传**：发送媒体文件前需要先上传到微信 CDN，SDK 会自动处理 AES-128-ECB 加密和上传流程。

3. **长轮询超时**：getUpdates 默认超时 35 秒，服务器会在此时间内保持连接直到有新消息。

4. **错误处理**：常见错误码：
   - 40001, 40014, 42001: Session 过期，需要重新登录
   - -14: 会话超时

5. **文件限制**：单个文件最大 100MB。

## 依赖

- Jackson (JSON 序列化)
- Apache HttpClient 5 (HTTP 请求)
- SLF4J (日志)

## 参考

- https://npmx.dev/package-code/@tencent-weixin/openclaw-weixin/v/2.1.7
