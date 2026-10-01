# 03-✅求职派LLM大模型层设计与实现实战

> LLM大模型层是求职派智能能力的核心引擎。本文完整记录基于Spring AI的模型抽象设计、多厂商Provider扩展机制、智能模型路由与缓存策略，以及BizAgentLlmCaller的Agent级封装。

---

## 一、为什么需要统一的LLM抽象层？

### 1.1 多厂商模型的复杂性

求职派需要支持多种大模型厂商，每种厂商的API风格和SDK实现差异巨大：

| 厂商 | API风格 | SDK依赖 | 特点 |
|------|---------|---------|------|
| OpenAI | OpenAI兼容 | `spring-ai-openai` | 行业标准，广泛兼容 |
| DeepSeek | OpenAI兼容 | `spring-ai-openai` | 默认文本与多模态模型，走OpenAI兼容协议 |
| 阿里百炼（DashScope） | 阿里自定义 | `spring-ai-dashscope` | 支持ASR/TTS等多模态 |
| Anthropic | Anthropic自定义 | `spring-ai-anthropic` | Claude系列模型 |
| SiliconFlow | OpenAI兼容 | `spring-ai-openai` | 聚合平台，多模型支持 |

如果不做统一抽象，业务层（Agent）需要处理五种不同的模型调用方式，代码会非常混乱。

### 1.2 设计目标

> **如何让业务层（Agent）无需关心底层是哪个厂商的模型，以统一的方式调用所有大模型？**

答案就是：
1. **统一模型接口**：`ModelProvider` 接口抽象厂商差异
2. **智能模型路由**：`ModelProviders` 根据用户偏好自动选择模型
3. **Agent级封装**：`BizAgentLlmCaller` 封装ChatClient、记忆、工具调用等复杂逻辑

---

## 二、LLM架构概览

### 2.1 整体架构

```
┌────────────────────────────────────────────────────────────┐
│                    业务层 (agents/)                          │
│   IdentityAgent    JobFetchAgent    JobRecommendAgent       │
│         ↕                ↕                    ↕             │
│              BizAgentLlmCaller / SimpleLlmCaller             │
│                    (ChatClient + Memory + Tools)             │
├────────────────────────────────────────────────────────────┤
│                   模型路由层 (providers/)                     │
│   ModelProviders.getModel(userId, modelType)                │
│         ↕                ↕                    ↕             │
│   用户偏好 → 厂商配置 → API风格 → ModelProvider → Model     │
├────────────────────────────────────────────────────────────┤
│                   厂商实现层 (providers/)                     │
│ OpenAiModelProvider AliModelProvider AnthropicModelProvider │
│          ↕                  ↕                   ↕           │
│   OpenAiChatModel   DashScopeChatModel   AnthropicChatModel │
├────────────────────────────────────────────────────────────┤
│                   Spring AI 底层                             │
│   ChatModel / EmbeddingModel / ImageModel / ...             │
└────────────────────────────────────────────────────────────┘
```

### 2.2 核心组件

| 组件 | 位置 | 职责 |
|------|------|------|
| `ModelProvider` | `core/providers/` | 厂商接入接口，定义API风格和模型构建 |
| `ModelProviders` | `core/providers/` | 模型路由器，根据用户偏好解析并缓存模型 |
| `ModelConfig` | `core/providers/` | 模型配置模型，定义厂商、路径、类型等 |
| `LlmCaller` | `core/agent/llm/` | 大模型调用接口，定义call/stream方法 |
| `SimpleLlmCaller` | `core/agent/llm/` | 简单LLM调用器，由调用者维护上下文 |
| `BizAgentLlmCaller` | `core/agent/llm/` | Agent级LLM调用器，封装记忆、工具、身份等 |

---

## 三、ModelProvider接口：为什么这样设计？

### 3.1 接口定义

```java
public interface ModelProvider {
    /**
     * 模型接入的API风格
     * 如：openai, ali, anthropic
     */
    String apiStyle();

    /**
     * 构建模型实例
     * @param info 模型配置信息（apiKey, baseUrl, modelName, type等）
     * @return Spring AI的Model实例（ChatModel/EmbeddingModel等）
     */
    Model model(ModelConfig.ModelInfo info);
}
```

### 3.2 为什么需要 apiStyle() 方法？

**问题**：为什么不直接用厂商名称（如`deepseek`、`ali`）来查找Provider？

**答案**：因为有些厂商的API是兼容OpenAI风格的，比如：
- SiliconFlow：底层是OpenAI兼容接口
- 某些私有化部署：也使用OpenAI兼容接口

如果使用厂商名称查找，会导致：
```java
// 反例：硬编码厂商名称
if (provider.equals("silicon")) {
    return openAiModelProvider.model(info);  // ← 复用OpenAI Provider
} else if (provider.equals("ali")) {
    return aliModelProvider.model(info);
}
```

**使用 apiStyle 的优势**：
```java
// 配置文件
silicon:
  api-key: xxx
  api-style: openai  # ← 声明使用OpenAI风格的API
  base-url: https://api.siliconflow.cn

// ModelProviders查找Provider
var modelProvider = providerMap.get(providerInfo.getApiStyle());
// providerMap = {
//   "openai" -> OpenAiModelProvider,
//   "ali" -> AliModelProvider,
//   "anthropic" -> AnthropicModelProvider
// }
```

**设计价值**：
- 支持多个厂商复用同一个Provider（如SiliconFlow复用OpenAI Provider）
- 新增OpenAI兼容厂商时，无需编写新Provider，只需配置`api-style: openai`
- 配置灵活，运行时可切换API风格

### 3.3 为什么 model() 方法返回 Model 接口？

**问题**：为什么不直接返回具体的`ChatModel`？

**答案**：因为模型类型不止对话模型，还有多种类型：

```java
public enum ModelType {
    TEXT,       // 纯文本模型（ChatModel）
    VISION,     // 视觉理解模型（ChatModel，支持图片）
    IMAGE,      // 生图模型（ImageModel）
    VIDEO,      // 视频模型（VideoModel）
    EMBEDDING,  // 嵌入模型（EmbeddingModel）
    ASR,        // 语音识别（AudioTranscriptionModel）
    TTS,        // 语音合成（AudioSpeechModel）
}
```

**不同厂商支持的模型类型不同**：

| 厂商 | TEXT | VISION | IMAGE | EMBEDDING | ASR | TTS |
|------|------|--------|-------|-----------|-----|-----|
| OpenAI | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| DeepSeek | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| 阿里百炼 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

**返回 Model 接口的优势**：
- 支持返回不同类型的模型实例
- 由Provider内部根据`info.getType()`决定构建哪种模型
- 调用者（ModelProviders）无需关心具体类型

---

## 四、具体Provider实现：如何解决厂商差异？

### 4.1 OpenAiModelProvider（OpenAI兼容接口）

```java
public class OpenAiModelProvider implements ModelProvider {
    
    @Override
    public String apiStyle() {
        return "openai";  // ← 声明OpenAI风格
    }

    @Override
    public Model model(ModelConfig.ModelInfo info) {
        return switch (info.getType()) {
            case TEXT -> buildChatModel(info);
            case VISION -> buildVisionModel(info);
            case IMAGE -> buildImageModel(info);
            case EMBEDDING -> buildEmbeddingModel(info);
            default -> throw new IllegalArgumentException("unsupported model type");
        };
    }

    private ChatModel buildChatModel(ModelConfig.ModelInfo info) {
        // 构建OpenAI API客户端
        var builder = OpenAiApi.builder()
                .apiKey(info.getApiKey())
                .restClientBuilder(restClientBuilderProvider.getIfAvailable(RestClient::builder))
                .webClientBuilder(webClientBuilderProvider.getIfAvailable(WebClient::builder));
        
        // 支持自定义baseUrl和path（兼容不同厂商）
        if (StringUtils.isNotBlank(info.getBaseUrl())) {
            builder.baseUrl(info.getBaseUrl());
        }
        if (StringUtils.isNotBlank(info.getPath())) {
            builder.completionsPath(info.getPath());
        }
        
        OpenAiApi openAiApi = builder.build();
        
        // 构建ChatModel
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(info.getModelName())
                        .maxTokens(info.getMaxTokens())
                        .build())
                .build();
    }
}
```

**设计要点**：

**1. 为什么支持自定义 baseUrl 和 path？**

因为很多厂商虽然使用OpenAI兼容接口，但URL不同：

| 厂商 | baseUrl | completionsPath |
|------|---------|-----------------|
| OpenAI官方 | `https://api.openai.com` | `/v1/chat/completions` |
| SiliconFlow | `https://api.siliconflow.cn` | `/v1/chat/completions` |
| DeepSeek | `https://api.deepseek.com` | `/chat/completions` |

**设计价值**：一个Provider兼容多种厂商，只需在配置文件中指定不同的baseUrl。

### 4.2 OpenAiModelProvider完整实现：DeepSeek是怎么接进来的

DeepSeek没有官方Spring AI starter，但它的接口是OpenAI兼容协议，所以不需要单独写一个`DeepSeekModelProvider`：在供应商配置里声明`api-style: openai`，`ModelProviders` 就会把模型构建交给`OpenAiModelProvider`完成。

```java
public class OpenAiModelProvider implements ModelProvider {
    private ObjectProvider<RestClient.Builder> restClientBuilderProvider;
    private ObjectProvider<WebClient.Builder> webClientBuilderProvider;
    private ObjectProvider<ResponseErrorHandler> responseErrorHandler;

    public OpenAiModelProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider,
                               ObjectProvider<WebClient.Builder> webClientBuilderProvider,
                               ObjectProvider<ResponseErrorHandler> responseErrorHandler) {
        this.restClientBuilderProvider = restClientBuilderProvider;
        this.webClientBuilderProvider = webClientBuilderProvider;
        this.responseErrorHandler = responseErrorHandler;
    }

    @Override
    public String apiStyle() {
        return "openai";  // ← 声明OpenAI风格，DeepSeek也复用这条路径
    }

    @Override
    public Model model(ModelConfig.ModelInfo info) {
        return switch (info.getType()) {
            case TEXT -> buildChatModel(info);
            case VISION -> buildVisionModel(info);
            case IMAGE -> buildImageModel(info);
            case EMBEDDING -> buildEmbeddingModel(info);
            case VIDEO -> throw new IllegalArgumentException("unsupported model type: " + info.getType());
            case ASR -> throw new IllegalArgumentException("unsupported model type: " + info.getType());
            case TTS -> throw new IllegalArgumentException("unsupported model type: " + info.getType());
        };
    }

    private Model buildChatModel(ModelConfig.ModelInfo info) {
        var builder = OpenAiApi.builder()
                .apiKey(info.getApiKey())
                .restClientBuilder(restClientBuilderProvider.getIfAvailable(RestClient::builder))
                .webClientBuilder(webClientBuilderProvider.getIfAvailable(WebClient::builder))
                .responseErrorHandler(responseErrorHandler.getIfAvailable(
                        () -> RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER));
        if (StringUtils.isNotBlank(info.getBaseUrl())) {
            builder.baseUrl(info.getBaseUrl());          // ← https://api.deepseek.com
        }
        if (StringUtils.isNotBlank(info.getPath())) {
            builder.completionsPath(info.getPath());     // ← /chat/completions
        }
        OpenAiApi openAiApi = builder.build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(info.getModelName())      // ← deepseek-flash
                        .maxTokens(info.getMaxTokens())
                        .build())
                .build();
        if (SpringUtil.getBeanOrNull(ChatModelObservationConvention.class) != null) {
            chatModel.setObservationConvention(SpringUtil.getBeanOrNull(ChatModelObservationConvention.class));
        }
        return chatModel;
    }
}
```

**设计要点**：

**1. 为什么baseUrl和completionsPath要做成配置？**

`OpenAiApi` 默认指向OpenAI官方地址，DeepSeek只是在供应商配置里把 `base-url` 覆盖成 `https://api.deepseek.com`、把 `completions-path` 覆盖成 `/chat/completions`。同一份`OpenAiModelProvider`代码因此可以同时服务OpenAI、DeepSeek、硅基流动等一批兼容厂商，新增厂商时只加配置、不加类。

**2. DeepSeek采集链路适配器：DeepSeekOcChatModel**

采集链路不再依赖厂商自己的starter，而是通过项目自己的`ModelProviders`解析模型。`DeepSeekOcChatModel`实现`modelEnum()`返回`GatherModelEnum.DEEPSEEK`，`chatClient()`走`ModelType.TEXT`，`imgClient()`走`ModelType.VISION`，两条链路都从默认偏好里解析：

```java
@Override
public GatherModelEnum modelEnum() {
    return GatherModelEnum.DEEPSEEK;
}

private ChatModel resolveChatModel(ModelConfig.ModelType type) {
    String selection = modelProviders.getDefaultModelSelection(type);
    log.info("当前选中的模型为：{}", selection);
    return (ChatModel) modelProviders.getGlobalModel(selection, type);
}
```

**3. 为什么TEXT和VISION可以解析到同一个模型？**

`application.yml`里`deepseek-flash`声明为`type: TEXT`、`multimodal: true`。`ModelProviders.assertCompatibleModelType()` 放宽了类型校验：`multimodal: true`的TEXT模型同样可以满足VISION需求，所以`text`与`vision`偏好都写`deepseek#deepseek-flash`时，两条链路拿到的是同一个模型实例。

### 4.3 AliModelProvider（阿里百炼自定义接口）

```java
public class AliModelProvider implements ModelProvider {
    
    @Override
    public String apiStyle() {
        return "ali";  // ← 声明阿里风格
    }

    @Override
    public Model model(ModelConfig.ModelInfo info) {
        return switch (info.getType()) {
            case TEXT -> buildChatModel(info);
            case VISION -> buildChatModel(info);
            case IMAGE -> buildImageModel(info);
            case EMBEDDING -> buildEmbeddingModel(info);
            case ASR -> buildAsrModel(info);    // ← 阿里支持语音识别
            case TTS -> buildTtsModel(info);    // ← 阿里支持语音合成
        };
    }

    private DashScopeAudioTranscriptionModel buildAsrModel(ModelConfig.ModelInfo info) {
        DashScopeAudioTranscriptionApi api = DashScopeAudioTranscriptionApi.builder()
                .apiKey(new SimpleApiKey(info.getApiKey()))
                .restClientBuilder(restClientBuilderProvider.getIfAvailable(RestClient::builder))
                .webClientBuilder(webClientBuilderProvider.getIfAvailable(WebClient::builder))
                .build();
        
        return DashScopeAudioTranscriptionModel.builder()
                .audioTranscriptionApi(api)
                .defaultOptions(DashScopeAudioTranscriptionOptions.builder()
                        .model(info.getModelName())
                        .build())
                .build();
    }
}
```

**设计要点**：

**1. 为什么阿里Provider支持ASR和TTS？**

因为阿里百炼SDK（DashScope）提供了完整的语音能力：
- `DashScopeAudioTranscriptionModel`：语音识别（ASR）
- `DashScopeAudioSpeechModel`：语音合成（TTS）

而OpenAI兼容协议这条路径没有对应的模型封装：DeepSeek、硅基流动这类供应商复用`OpenAiModelProvider`，它只实现TEXT/VISION/IMAGE/EMBEDDING四类，遇到ASR/TTS会直接抛出`IllegalArgumentException`。

**设计价值**：不同Provider根据自身能力支持不同的模型类型，调用者无需关心。

---

## 五、ModelProviders：为什么需要智能路由？

### 5.1 为什么需要模型路由？

**问题场景**：

用户A配置了DeepSeek的API Key，用户B配置了阿里的API Key，他们发送消息时：

```
用户A："帮我分析这份简历"
  → 使用 deepseek#deepseek-flash 模型

用户B："帮我分析这份简历"
  → 使用阿里 qwen-plus 模型
```

**如果没有模型路由会怎样？**

```java
// 反例：硬编码模型选择
public ChatModel getModel(String userId) {
    if (userId.equals("user-a")) {
        return zhiPuChatModel;  // ← 硬编码
    } else if (userId.equals("user-b")) {
        return aliChatModel;    // ← 硬编码
    }
    return defaultChatModel;
}
```

**问题**：
- 每新增一个用户，都要修改代码
- 无法支持用户动态切换模型
- 无法支持不同模型类型（TEXT/VISION）

### 5.2 ModelProviders 的完整路由流程

```java
@Component
public class ModelProviders {
    
    // 缓存：ModelInfo → Model实例
    public Map<ModelConfig.ModelInfo, Model> modelCache;
    
    // Provider注册表：apiStyle → ModelProvider实例
    private Map<String, ModelProvider> providerMap;

    public Model getModel(String userId, ModelConfig.ModelType modelType) {
        // Step 1: 获取用户偏好配置
        var preference = aiUserPreferenceProperties.getUserPreference(userId);
        if (preference == null) {
            // 降级到全局默认配置
            preference = aiUserPreferenceProperties.getUserPreference("total");
        }

        // Step 2: 根据模型类型获取偏好模型名称
        var preferModel = switch (modelType) {
            case TEXT -> preference.getModels().getText();      // "deepseek#deepseek-flash"
            case VISION -> preference.getModels().getVision();  // "deepseek#deepseek-flash"
            case IMAGE -> preference.getModels().getImage();
            // ...
        };

        // Step 3: 解析 provider + modelName
        var cell = preferModel.split("#");
        String provider = cell[0];      // "deepseek"
        String modelName = cell[1];     // "deepseek-flash"

        // Step 4: 获取厂商配置（apiKey, baseUrl, apiStyle等）
        var providerInfo = preference.getProviders().get(provider);
        if (providerInfo == null) {
            // 降级到全局厂商配置
            providerInfo = aiUserPreferenceProperties.getProviders().get(provider);
        }

        // Step 5: 构建ModelInfo
        var modelInfo = ModelConfig.ModelInfo.builder()
                .provider(provider)
                .modelName(modelName)
                .apiKey(providerInfo.getApiKey())
                .baseUrl(providerInfo.getBaseUrl())
                .path(providerInfo.getCompletionsPath())
                .type(modelInfo.getType())
                .build();

        // Step 6: 检查缓存
        if (modelCache.containsKey(modelInfo)) {
            return modelCache.get(modelInfo);
        }

        // Step 7: 根据apiStyle查找Provider并创建Model
        var modelProvider = providerMap.get(providerInfo.getApiStyle());
        var model = modelProvider.model(modelInfo);
        
        // Step 8: 缓存Model实例
        modelCache.put(modelInfo, model);
        return model;
    }
}
```

### 5.3 为什么需要缓存？

**问题**：每次调用都创建新的Model实例会怎样？

```java
// 反例：不缓存，每次都创建
public Model getModel(String userId, ModelType modelType) {
    var modelProvider = providerMap.get(apiStyle);
    return modelProvider.model(modelInfo);  // ← 每次都创建新实例
}
```

**问题**：
- `ChatModel` 内部包含 `RestClient`、`WebClient` 等HTTP客户端，创建成本高
- 频繁创建会导致连接池无法复用
- 内存泄漏风险（旧实例未被GC回收）

**缓存的设计价值**：
- 同一用户+同一模型类型 → 复用同一个Model实例
- HTTP连接池复用，提升性能
- 用户偏好变更时清除缓存（监听`PropertiesRefreshedEvent`）

```java
@Async
@EventListener
public void registerUserPreferenceChangeCallback(PropertiesRefreshedEvent event) {
    if (AiUserPreferenceProperties.class.equals(event.getPropertiesClz())) {
        modelCache.clear();  // ← 用户偏好变更时清除缓存
        log.info("[ModelProviders] User preference changed, clear user cache");
    }
}
```

### 5.4 配置示例：用户偏好与厂商配置

```yaml
agent:
  ai:
    preference:
      # 全局默认配置（所有用户共用）
      - user-id: total
        models:
          vision: deepseek#deepseek-flash    # ← 视觉模型
          text: deepseek#deepseek-flash   # ← 文本模型（默认）
        providers:
          deepseek:
            api-key: ""      # ← 在后台 LLM供应商 页面保存
            api-style: openai  # ← 使用OpenAI兼容接口
            base-url: https://api.deepseek.com
            completions-path: /chat/completions
            models:
              - name: deepseek-flash
                type: TEXT
                multimodal: true   # ← 多模态模型声明为TEXT，同样满足VISION需求
              - name: deepseek-v4-pro
                type: TEXT
                multimodal: false

          silicon:
            api-key: ""      # ← 在后台 LLM供应商 页面保存
            api-style: openai  # ← 复用OpenAI Provider
            base-url: https://api.siliconflow.cn
            models:
              - name: Qwen/Qwen2.5-7B-Instruct
                type: TEXT
              - name: PaddlePaddle/PaddleOCR-VL-1.5
                type: VISION

      # 用户自定义配置（覆盖全局配置）
      - user-id: 2
        models:
          text: silicon#Qwen/Qwen2.5-7B-Instruct  # ← 用户2使用硅基流动
        providers:
          silicon:
            api-key: user2-custom-api-key  # ← 用户自己的API Key
```

**解析流程**：

```
用户2发送消息
  ↓
ModelProviders.getModel("2", TEXT)
  ↓
查找用户2的偏好 → text: "silicon#Qwen/Qwen2.5-7B-Instruct"
  ↓
解析：provider="silicon", modelName="Qwen/Qwen2.5-7B-Instruct"
  ↓
查找厂商配置 → api-style: "openai"
  ↓
providerMap.get("openai") → OpenAiModelProvider
  ↓
OpenAiModelProvider.model(modelInfo) → OpenAiChatModel
  ↓
缓存并返回
```

---

## 六、ModelConfig：为什么需要完整的配置模型？

### 6.1 ModelInfo 的完整字段

```java
@Data
@Builder
public static class ModelInfo {
    private String provider;           // 厂商名称（deepseek, silicon）
    private String apiKey;             // API密钥
    private String baseUrl;            // 基础URL
    private String path;               // 对话路径（/v1/chat/completions）
    private String modelName;          // 模型名称（deepseek-flash）
    private ModelType type;            // 模型类型（TEXT/VISION/IMAGE...）
    private Boolean multimodal;        // 是否多模态
    private Integer maxTokens;         // 最大Token数
    private BigDecimal inputPricePerMillionTokens;   // 输入价格
    private BigDecimal outputPricePerMillionTokens;  // 输出价格
}
```

### 6.2 为什么需要这些字段？

| 字段 | 用途 | 示例 |
|------|------|------|
| `apiKey` | 鉴权 | 不同用户用自己的API Key |
| `baseUrl` + `path` | 路由 | 支持不同厂商的URL |
| `modelName` | 选择模型 | deepseek-flash vs deepseek-v4-pro |
| `type` | 构建不同类型的Model | TEXT → ChatModel, IMAGE → ImageModel |
| `multimodal` | 判断是否支持图片 | 视觉模型需要传递图片 |
| `maxTokens` | 控制输出长度 | 防止模型输出过长内容 |
| `inputPrice` / `outputPrice` | 成本统计 | 计算每次调用的费用 |

### 6.3 ModelType 枚举：为什么区分这么多类型？

```java
public enum ModelType {
    TEXT,       // 纯文本模型
    VISION,     // 视觉理解模型（支持图片输入）
    IMAGE,      // 生图模型（输出图片）
    VIDEO,      // 视频模型
    EMBEDDING,  // 嵌入模型（文本向量）
    ASR,        // 语音识别
    TTS,        // 语音合成
}
```

**为什么需要区分 TEXT 和 VISION？**

因为它们指向的模型配置不同：
- `TEXT`：默认文本对话模型，`deepseek#deepseek-flash`（在`application.yml`里声明为`type: TEXT`、`multimodal: true`）
- `VISION`：需要图片输入时使用的模型，同样可以是`deepseek#deepseek-flash`

`ModelProviders.assertCompatibleModelType()` 放宽了这里的类型校验：`multimodal: true`的TEXT模型也能满足VISION需求，因此一个多模态模型可以同时承担两种类型的调用；如果将来把视觉请求切到独立的视觉模型，只需要改`vision`这一行偏好配置。

**BizAgentLlmCaller 自动判断模型类型**：

```java
protected ChatClient getClient(UserConversationInfo user, Prompt prompt) {
    // 判断消息中是否包含media
    ModelConfig.ModelType model = ModelConfig.ModelType.TEXT;
    if (prompt.getUserMessages().stream()
            .anyMatch(m -> !CollectionUtils.isEmpty(m.getMedia()))) {
        model = ModelConfig.ModelType.VISION;  // ← 自动切换到视觉模型
    }
    
    var chatModel = (ChatModel) modelProviders.getModel(
        user.jobClawUserId(), model);
    // ...
}
```

**设计价值**：用户发送图片时，自动使用视觉模型，无需手动切换。

---

## 七、LlmCaller接口：为什么需要两层封装？

### 7.1 LlmCaller 接口定义

```java
public interface LlmCaller {
    // 结构化返回
    <T> T call(UserConversationInfo user, Prompt prompt, Class<T> clz);
    
    // 普通调用
    String call(UserConversationInfo user, Prompt prompt);
    
    // 流式调用（简单版）
    Flux<String> stream(UserConversationInfo user, Prompt prompt);
    
    // 流式调用（完整版）
    <T> Flux<T> stream(UserConversationInfo user, Prompt prompt, Function<ChatResponse, T> func);
}
```

### 7.2 为什么需要 SimpleLlmCaller 和 BizAgentLlmCaller？

**问题**：为什么不只提供一个实现？

**答案**：因为使用场景不同：

| 场景 | 实现 | 特点 |
|------|------|------|
| 简单调用 | `SimpleLlmCaller` | 不维护上下文，由调用者自己管理 |
| Agent调用 | `BizAgentLlmCaller` | 自动维护记忆、工具、身份等 |

### 7.3 SimpleLlmCaller：简单场景

```java
@Component
public class SimpleLlmCaller implements LlmCaller {
    
    protected ChatClient getClient(UserConversationInfo user, Prompt prompt) {
        // 自动判断TEXT/VISION
        ModelConfig.ModelType model = ModelConfig.ModelType.TEXT;
        if (prompt.getUserMessages().stream()
                .anyMatch(m -> !CollectionUtils.isEmpty(m.getMedia()))) {
            model = ModelConfig.ModelType.VISION;
        }
        
        var chatModel = (ChatModel) modelProviders.getModel(
            user.jobClawUserId(), model);

        // 构建ChatClient
        var builder = ChatClient.builder(chatModel)
                .defaultOptions(
                    ToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)  // ← 不自动执行工具
                        .build()
                )
                .defaultAdvisors(
                    ReActAdvisor.builder()
                        .chatModel(chatModel)
                        .autoInjectMiddleware()
                        .build()
                );
        
        return builder.build();
    }
}
```

**设计要点**：

**1. 为什么不维护上下文？**

因为某些场景不需要历史对话：
- 数据采集Agent：每次都是独立任务
- 一次性问答：无需记忆

**2. 为什么设置 internalToolExecutionEnabled(false)？**

因为Spring AI默认会自动执行工具，但求职派使用自定义的`ReActAdvisor`来管理工具调用流程（支持Middleware生命周期拦截）。

### 7.4 BizAgentLlmCaller：Agent级封装

```java
public class BizAgentLlmCaller extends SimpleLlmCaller {
    protected final ChatMemory chatMemory;          // 对话记忆
    protected final IIdentityAgent identityAgent;   // 身份采集
    protected final String systemPrompt;            // 系统提示词
    protected final ToolCallback[] tools;           // 工具列表
    protected final Map<String, ChatClient> chatClientMap;  // ChatClient缓存

    public BizAgentLlmCaller(ChatMemory chatMemory,
                             IIdentityAgent identityAgent,
                             ModelProviders modelProviders,
                             String systemPrompt, 
                             ToolCallback... tools) {
        super(modelProviders);
        this.chatMemory = chatMemory;
        this.identityAgent = identityAgent;
        this.systemPrompt = systemPrompt;
        this.tools = tools;
    }
}
```

**BizAgentLlmCaller 的核心增强**：

#### 增强 1：自动注入用户身份

```java
protected ChatClient getClient(UserConversationInfo user, Prompt prompt) {
    // ... 获取chatModel
    
    // 获取用户身份信息
    String identity = identityAgent.buildSoulPrompt(user.jobClawUserId());
    
    Consumer<ChatClient.PromptSystemSpec> sys;
    if (StringUtils.isBlank(identity)) {
        sys = buildSystemPrompt(systemPrompt);
    } else {
        // 将身份信息注入到系统提示词
        sys = buildSystemPrompt(identity + "\n\n" + systemPrompt);
    }
    
    if (sys != null) {
        builder.defaultSystem(sys);
    }
    // ...
}
```

**设计价值**：Agent调用时，自动携带用户的Soul/Identity信息，让大模型了解用户画像。

#### 增强 2：自动维护对话记忆

```java
var builder = ChatClient.builder(chatModel)
        .defaultAdvisors(
            ReActAdvisor.builder()
                .chatModel(chatModel)
                .autoInjectMiddleware()
                .build(),
            SimpleLoggerAdvisor.builder().build(),
            MessageChatMemoryAdvisor.builder(chatMemory).build()  // ← 记忆Advisor
        );
```

**MessageChatMemoryAdvisor 的作用**：
- 调用前：从记忆加载历史对话
- 调用后：保存新的对话到记忆

**记忆配置**（application.yml）：
```yaml
agent:
  context:
    window:
      max-messages: 20              # 最大消息数
      keep-recent: 10               # 保留最近消息数
      max-tokens: 8000              # Token上限
      trim-enabled: true            # 上下文压缩开关
      summary-enabled: true         # 自动提炼会话总结
      filter-short-messages: true   # 过滤短消息
```

#### 增强 3：自动注册工具

```java
if (tools != null && tools.length > 0) {
    builder.defaultToolCallbacks(tools);  // ← 注册工具
}
```

**工具调用示例**：
```java
// JobFetchAgent 注册爬虫工具
public class JobFetchAgent extends AbsBizAgent {
    public JobFetchAgent(...) {
        super(..., new JobSearchTool(), new PlaywrightTool());
    }
}
```

#### 增强 4：自动传递上下文

```java
public String call(UserConversationInfo user, ChannelReceiveMessage msg) {
    Prompt prompt = new Prompt(buildUserMessage(msg));
    return getClient(user, prompt).prompt(prompt)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, user.genId()))
            .toolContext(Map.of(
                "jobClawUserId", user.jobClawUserId(),
                "user", user,
                "msg", msg  // ← 工具中可以访问原始消息
            ))
            .call().content();
}
```

**设计价值**：工具函数可以通过`ToolContext`获取用户信息和原始消息：

```java
@Tool(description = "搜索岗位")
public String searchJobs(String keyword, ToolContext toolContext) {
    UserConversationInfo user = (UserConversationInfo) 
        toolContext.getContext().get("user");
    String userId = user.jobClawUserId();
    // ...
}
```

#### 增强 5：支持多模态消息

```java
protected UserMessage buildUserMessage(ChannelReceiveMessage message) {
    List<Media> mediaList = new ArrayList<>();

    // 添加图片
    if (message.getMedias() != null) {
        for (var image : message.getMedias()) {
            Media media = createImageMedia(image);
            if (media != null) {
                mediaList.add(media);
            }
        }
    }

    // 添加文件
    if (message.getFiles() != null) {
        for (var file : message.getFiles()) {
            Media media = createFileMedia(file);
            if (media != null) {
                mediaList.add(media);
            }
        }
    }

    var msgBuilder = UserMessage.builder().text(textContent);
    if (!mediaList.isEmpty()) {
        msgBuilder.media(mediaList);  // ← 添加媒体
    }
    return msgBuilder.build();
}
```

**设计价值**：用户发送图片/文件时，自动转换为Spring AI的Media对象，传递给视觉模型。

---

## 八、ReActAdvisor：为什么需要自定义工具调用？

### 8.1 为什么不用Spring AI的默认工具调用？

**问题**：Spring AI已经提供了`ToolCallingAdvisor`，为什么还要自定义`ReActAdvisor`？

**答案**：因为需要支持Middleware生命周期拦截：

```java
// 自定义ReActAdvisor
var reactBuilder = ReActAdvisor.builder()
        .chatModel(chatModel)
        .autoInjectMiddleware();  // ← 自动注入中间件
```

**Middleware的作用**：
- 工具调用前：权限校验、日志记录
- 工具调用后：结果过滤、异常处理
- 工具调用链：支持多个工具组合执行

### 8.2 ReAct 模式的本质

ReAct（Reasoning + Acting）是一种工具调用模式：

```
用户："帮我找北京的开发岗位"
  ↓
大模型思考：需要调用搜索工具
  ↓
大模型生成：ToolCall { tool: "searchJobs", args: {location: "北京", type: "开发"} }
  ↓
ReActAdvisor 拦截 → 执行 searchJobs 工具
  ↓
工具返回：[{id: 1, title: "Java开发"}, {id: 2, title: "前端开发"}]
  ↓
大模型思考：需要总结结果
  ↓
大模型生成：为您找到以下岗位...
```

**ReAct vs 自动执行**：

| 方式 | 特点 | 适用场景 |
|------|------|----------|
| 自动执行 | Spring AI自动调用工具 | 简单工具调用 |
| ReAct模式 | 大模型显式生成ToolCall | 复杂工具链、需要中间处理 |

---

## 九、扩展新Provider：如何接入新厂商？

### 9.1 扩展步骤

**Step 1：创建Provider类**

```java
public class SiliconModelProvider implements ModelProvider {
    
    @Override
    public String apiStyle() {
        return "openai";  // ← SiliconFlow使用OpenAI兼容接口
    }

    @Override
    public Model model(ModelConfig.ModelInfo info) {
        // 复用OpenAiModelProvider的逻辑
        return switch (info.getType()) {
            case TEXT -> buildChatModel(info);
            case VISION -> buildVisionModel(info);
            case EMBEDDING -> buildEmbeddingModel(info);
            default -> throw new IllegalArgumentException("unsupported model type");
        };
    }
    
    private ChatModel buildChatModel(ModelConfig.ModelInfo info) {
        // ... 与OpenAiModelProvider类似
    }
}
```

**Step 2：注册为Spring Bean**

```java
@Configuration
public class SiliconProviderConfiguration {
    
    @Bean
    public ModelProvider siliconModelProvider(
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectProvider<ResponseErrorHandler> responseErrorHandler) {
        return new SiliconModelProvider(
            restClientBuilderProvider, 
            webClientBuilderProvider, 
            responseErrorHandler);
    }
}
```

**Step 3：配置文件添加厂商信息**

```yaml
agent:
  ai:
    providers:
      silicon:
        api-key: ""      # ← 在后台 LLM供应商 页面保存
        api-style: openai  # ← 使用OpenAI风格
        base-url: https://api.siliconflow.cn
        completions-path: /v1/chat/completions
        models:
          - name: Qwen/Qwen2.5-7B-Instruct
            type: TEXT
          - name: PaddlePaddle/PaddleOCR-VL-1.5
            type: VISION
            multimodal: true
```

**Step 4：配置用户偏好**

```yaml
agent:
  ai:
    preference:
      - user-id: total
        models:
          text: silicon#Qwen/Qwen2.5-7B-Instruct  # ← 使用硅基流动
          vision: silicon#PaddlePaddle/PaddleOCR-VL-1.5
```

### 9.2 为什么扩展这么简单？

因为 `ModelProvider` 接口已经处理了：
- ✅ 模型路由（ModelProviders）
- ✅ 模型缓存（modelCache）
- ✅ 类型判断（TEXT/VISION/IMAGE...）
- ✅ 配置解析（ModelConfig）

你只需要实现 2 个方法：
1. `apiStyle()` - 返回API风格
2. `model(info)` - 根据类型构建Model

**这就是策略模式的价值**：固定流程，扩展细节。

---

## 十、常见问题：深度解答

### 10.1 为什么不直接用Spring AI的自动配置？

**问题**：Spring AI已经提供了自动配置，为什么还要自己封装？

**答案**：

| Spring AI自动配置 | 求职派封装 |
|------------------|-----------|
| 单模型配置 | 多厂商、多模型动态路由 |
| 全局API Key | 用户维度API Key |
| 固定模型 | 用户偏好+动态切换 |
| 无缓存 | Model实例缓存 |

**Spring AI的配置方式**：
```yaml
spring:
  ai:
    openai:
      api-key: sk-xxx
      chat:
        options:
          model: gpt-4o
```

**问题**：
- 只能配置一个模型
- 无法运行时切换
- 不支持多用户不同API Key

**求职派的封装解决了这些问题**。

### 10.2 模型缓存会过期吗？

**问题**：缓存的Model实例会不会一直不更新？

**答案**：

1. **正常情况下不会过期**：Model实例内部包含HTTP连接池，长期复用更高效
2. **用户偏好变更时清除**：监听`PropertiesRefreshedEvent`事件
3. **应用重启时清除**：内存缓存，重启后重建

```java
@Async
@EventListener
public void registerUserPreferenceChangeCallback(PropertiesRefreshedEvent event) {
    if (AiUserPreferenceProperties.class.equals(event.getPropertiesClz())) {
        modelCache.clear();  // ← 用户偏好变更时清除
    }
}
```

### 10.3 如何保证API Key的安全性？

**问题**：用户的API Key存在配置文件中，会不会泄露？

**答案**：

1. **后台全局配置覆盖**：
```yaml
providers:
  deepseek:
    api-key: ""  # ← 在后台 LLM供应商 页面保存
```

2. **用户维度隔离**：
```yaml
- user-id: 2
  providers:
    silicon:
      api-key: user2-custom-api-key  # ← 用户自己的API Key
```

3. **生产环境建议**：
   - 使用KMS（密钥管理服务）存储API Key
   - 不要将`.env`文件提交到代码仓库
   - 定期轮换API Key

### 10.4 如何选择TEXT和VISION模型？

**问题**：BizAgentLlmCaller如何知道使用哪种模型？

**答案**：根据消息内容自动判断：

```java
protected ChatClient getClient(UserConversationInfo user, Prompt prompt) {
    ModelConfig.ModelType model = ModelConfig.ModelType.TEXT;
    
    // 检查消息中是否包含media
    if (prompt.getUserMessages().stream()
            .anyMatch(m -> !CollectionUtils.isEmpty(m.getMedia()))) {
        model = ModelConfig.ModelType.VISION;  // ← 自动切换
    }
    
    var chatModel = (ChatModel) modelProviders.getModel(
        user.jobClawUserId(), model);
    // ...
}
```

**用户发送图片时的流程**：
```
用户发送图片
  ↓
通道层适配 → ChannelReceiveMessage.medias = [image]
  ↓
BizAgentLlmCaller.buildUserMessage() → 添加Media到Prompt
  ↓
getClient() 检测到media → 使用VISION模型
  ↓
视觉模型处理图片+文本
```

---

## 十一、小结：设计思想总结

LLM大模型层是求职派智能能力的核心引擎，背后的设计思想是：

1. **策略模式**：`ModelProvider` 接口抽象厂商差异，支持运行时扩展
2. **智能路由**：`ModelProviders` 根据用户偏好自动选择模型，支持降级
3. **缓存优化**：Model实例缓存，避免重复创建HTTP客户端
4. **Agent级封装**：`BizAgentLlmCaller` 封装记忆、工具、身份等复杂逻辑
5. **自动判断**：根据消息内容自动选择TEXT/VISION模型
6. **配置灵活**：用户维度API Key，支持动态切换模型

**核心设计原则**：
- **关注点分离**：厂商实现与业务逻辑解耦
- **开闭原则**：对扩展开放（新增Provider），对修改封闭（业务层无需改动）
- **单一职责**：每个组件只负责一件事（路由、缓存、调用）
- **配置驱动**：通过配置文件控制模型选择，无需修改代码

这种设计让求职派能够以统一的方式调用所有大模型，同时保持厂商层的灵活性和可扩展性。

---

:::success
相关代码：
- 模型提供者接口：`core/src/main/java/com/git/hui/jobclaw/core/providers/ModelProvider.java`
- 模型路由器：`core/src/main/java/com/git/hui/jobclaw/core/providers/ModelProviders.java`
- 模型配置：`core/src/main/java/com/git/hui/jobclaw/core/providers/ModelConfig.java`
- LLM调用接口：`core/src/main/java/com/git/hui/jobclaw/core/agent/llm/LlmCaller.java`
- 简单调用器：`core/src/main/java/com/git/hui/jobclaw/core/agent/llm/SimpleLlmCaller.java`
- Agent调用器：`core/src/main/java/com/git/hui/jobclaw/core/agent/llm/BizAgentLlmCaller.java`
- OpenAI提供者：`providers/openai/src/main/java/com/git/hui/jobclaw/provider/openai/OpenAiModelProvider.java`
- DeepSeek采集链路适配器：`app/src/main/java/com/git/hui/jobclaw/gather/service/ai/impl/deepseek/DeepSeekOcChatModel.java`
- 阿里提供者：`providers/ali/src/main/java/com/git/hui/jobclaw/provider/ali/AliModelProvider.java`

:::

---

> 相关文档：
> - [Channel 通道层设计](./01-✅求职派Channel通道层设计与实现实战.md)
> - [消息模型与事件总线](./02-✅求职派消息模型与事件总线设计实战.md)
> - [V2 重构总览](../07.V2重构/00-求职派V2重构：从单体应用到多Agent运行时.md)
> - [Spring AI ChatClient使用说明](../04、SpringAI篇/09-✅11.SpringAI接入图像模型.md)
