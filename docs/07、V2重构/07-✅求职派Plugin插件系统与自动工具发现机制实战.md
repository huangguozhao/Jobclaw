# ✅求职派 Plugin 插件系统与自动工具发现机制实战

> 当你的 Agent 需要"浏览网页"、"搜索职位"、"追踪计划"等能力时，是把这些能力硬编码进 Agent，还是做成可插拔的插件？求职派选择了后者——基于 `AutoDiscoveredTool` 和 Spring Boot `@AutoConfiguration` 构建了一套零配置的插件系统。本文完整记录其设计思路与实现细节。

---

## 一、为什么需要插件系统？

### 1.1 问题场景：Agent 的能力从哪来？

**场景还原**：

用户通过 IM 对 Agent 说：

```
用户："帮我打开这个链接 https://xxx.com/campus-recruit，看看有哪些岗位"
```

**没有插件系统的 Agent 会怎样？**

```java
// 反例：每个 Agent 硬编码自己的工具
public class DefaultBizAgent extends AbsBizAgent {
    @Override
    public ToolCallback[] getTools() {
        return ToolCallbacks.from(
            new TaskTool(),           // 任务工具
            new SmartWebFetchTool(),  // 网页抓取工具
            new McpTool(),           // MCP 工具
            // ... 更多工具
        );
    }
}

public class JobRecommendAgent extends AbsBizAgent {
    @Override
    public ToolCallback[] getTools() {
        return ToolCallbacks.from(
            new TaskTool(),
            new SmartWebFetchTool(),
            new JobLibraryTool(),     // 职位库工具
            // ... 又写一遍
        );
    }
}
```

**问题**：

| 问题 | 后果 |
|------|------|
| **代码重复** | 每个 Agent 都要手动罗列所有工具，新增工具要改 N 个 Agent |
| **耦合 Agent** | 工具和 Agent 绑定在一起，工具不能独立于 Agent 存在 |
| **无开关控制** | 不能按需启用/禁用某个工具（如"这个环境没有浏览器，关掉 Playwright"） |
| **难以扩展** | 第三方开发者想贡献一个新工具，需要修改 Agent 代码 |

### 1.2 V2 的设计目标

我们希望回答一个问题：

> **如何让 Agent 的能力像"插电源"一样——插件放到类路径上，能力自动出现在所有 Agent 中？**

三个核心诉求：

1. **零配置注册**：插件只要放到类路径上，Spring Boot 自动发现，Agent 自动获得能力
2. **按需启用**：通过 `@ConditionalOnProperty` 控制插件开关
3. **独立演进**：插件作为独立 Maven 模块开发，不依赖任何 Agent，Agent 也不依赖具体插件

---

## 二、插件架构总览

### 2.1 设计推演：从三个问题到三层结构

**问题 1：怎么让 Spring Boot 自动加载插件？**

**直觉方案**：在每个 Agent 的构造器里手动 `if (classExists)` 检测。

```java
// 反例：手动检测类是否存在
if (isClassPresent("com.git.hui.jobclaw.plugins.playwright.PlaywrightBrowserTool")) {
    tools.add(new PlaywrightBrowserTool());
}
```

**问题**：字符串反射，编译期无类型安全，每新增插件都要加一段。

**答案**：Spring Boot 的 `AutoConfiguration.imports` 机制 + `AutoDiscoveredTool` 包装器。插件声明一个 `@AutoConfiguration` 类和一个 `AutoDiscoveredTool<Xxx>` bean，Spring Boot 启动时自动加载。

---

**问题 2：怎么让工具自动出现在所有 Agent 中？**

**直觉方案**：给每个 Agent 加一个 `List<ToolCallback>` 参数。

```java
// 反例：手动传入工具列表到每个 Agent
@Bean
public DefaultBizAgent defaultBizAgent(List<ToolCallback> allTools) {
    return new DefaultBizAgent(allTools);
}
```

**问题**：Agent 的创建方式各不相同（有的通过泛型，有的继承），没法统一传参。

**答案**：`AutoDiscoveredTool<T>` —— 一个泛型 record 包装工具实例，Agent 通过 `List<AutoDiscoveredTool<?>>` 注入，Spring 自动收集所有此类 bean。

---

**问题 3：插件如何影响 Agent 行为（不仅提供工具）？**

**直觉方案**：插件只能提供工具，不能影响 Agent 的推理过程。

```java
// 局限：工具只能被动等待 LLM 调用
@Tool(description = "搜索职位")
public String searchJobs(...) { ... }
```

**问题**：有些插件需要主动影响 Agent 行为（如 PlanNotebook 需要在每次推理前注入当前计划）。

**答案**：插件还可以注册 `ReActMiddleware` bean，通过 Middleware 机制在 ReAct 循环的关键节点注入逻辑。

### 2.2 三层架构

```
┌────────────────────────────────────────────────────────────┐
│               插件定义层 (plugins/)                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Playwright  │  │  Job-Library │  │ Plan-Notebook │      │
│  │  浏览器自动化  │  │  职位库检索   │  │  计划追踪     │      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
│         │                  │                  │             │
│  【注册方式】               │                  │             │
│  @AutoConfiguration        │                  │             │
│  + AutoDiscoveredTool<T>   │                  │             │
│  + (可选) ReActMiddleware  │                  │             │
├────────────────────────────┼──────────────────┼─────────────┤
│               自动发现层 (core/tools/)                        │
│                                                             │
│  AutoDiscoveredTool<T>     ← 泛型 record，标记一个工具实例    │
│  PlanNotebookCapability    ← 标记接口，Agent 按能力发现      │
│  DynamicToolDiscovery       ← Lucene 工具搜索（可选增强）     │
│                                                             │
├────────────────────────────────────────────────────────────┤
│               Agent 集成层 (core/agent/llm/)                 │
│                                                             │
│  UserPreferenceBasedLlmCaller                               │
│    → initClient()                                           │
│      → autoDiscoveredTools.forEach(                         │
│           t → chatClientBuilder.defaultTools(t.tool()))     │
│      → 所有插件工具自动注册到 ChatClient                    │
│                                                             │
│  AbsBizAgent                                                │
│    → List<AutoDiscoveredTool<?>> 注入                       │
│    → 工具对所有 Agent 透明可用                              │
└────────────────────────────────────────────────────────────┘
```

### 2.3 插件的三种扩展维度

| 扩展维度 | 机制 | 示例 |
|---------|------|------|
| **提供工具** | `@Tool` + `AutoDiscoveredTool<T>` | Playwright 的 navigateTo/clickElement |
| **注入 Middleware** | `ReActMiddleware` | PlanNotebook 的 PlanHintReActMiddleware |
| **标记能力** | 标记接口 + `instanceof` 检测 | PlanNotebookCapability → PlanBizAgent 可用性判断 |

---

## 三、核心抽象设计

### 3.1 AutoDiscoveredTool：最简单的桥接模式

#### 设计起点：怎么让 Spring 自动收集所有插件工具？

**直觉方案**：用接口或注解标记。

```java
// 方案 A：让所有工具实现同一个接口
public interface PluginTool { }
public class PlaywrightBrowserTool implements PluginTool { ... }

// 方案 B：用 @PluginTool 注解
@PluginTool
public class PlaywrightBrowserTool { ... }
```

**问题**：
- 接口方案：工具类必须实现 `PluginTool`，侵入性强
- 注解方案：需要自定义注解处理器扫描，复杂度高

**求职派的方案**：不要求工具类实现任何接口，而是在插件配置层用一个泛型 wrapper 包装。

```java
public record AutoDiscoveredTool<T>(T tool) {}
```

**为什么用 record？**

```java
// 如果用 class
public class AutoDiscoveredTool {
    private final Object tool;  // ← 失去泛型信息，getter 返回 Object
}

// 如果用 record（求职派的方案）
public record AutoDiscoveredTool<T>(T tool) {}
// ← 天然生成构造器、getter、equals、hashCode
// ← 保留泛型信息，编译器可在注册时检查类型
```

**注册方式**：

```java
@AutoConfiguration
public class PlaywrightAutoConfiguration {

    @Bean
    public AutoDiscoveredTool<PlaywrightBrowserTool> autoDiscoveredTool(
            PlaywrightBrowserTool tool) {
        return new AutoDiscoveredTool<>(tool);
    }

    @Bean(destroyMethod = "close")
    public PlaywrightBrowserTool playwrightBrowserTool(
            @Value("${agent.tools.playwright.headless:true}") boolean headless) {
        return PlaywrightBrowserTool.builder().headless(headless).build();
    }
}
```

**消费方式**：

```java
// Agent 注入所有 AutoDiscoveredTool bean
private final List<AutoDiscoveredTool<?>> autoDiscoveredTools;

// 在构造器中注入
public SimpleDefaultBizAgent(..., List<AutoDiscoveredTool<?>> autoDiscoveredTools) {
    this.autoDiscoveredTools = autoDiscoveredTools;
}

// 在 initClient 中注册到 ChatClient
private void initClient(...) {
    if (autoDiscoveredTools != null && !autoDiscoveredTools.isEmpty()) {
        autoDiscoveredTools.forEach(t -> 
            chatClientBuilder.defaultTools(t.tool()));
    }
}
```

**设计价值**：

```
Spring 容器                 Agent
    │                         │
    ├─ AutoDiscoveredTool<P>  │
    ├─ AutoDiscoveredTool<J>  │── List<AutoDiscoveredTool<?>>
    ├─ AutoDiscoveredTool<Pt>─┤      ↓
    │                         │  chatClientBuilder.defaultTools(t.tool())
    │                         │      ↓
    │                         │  ChatClient 自动注册所有 @Tool 方法
```

- 工具类零侵入：不需要实现任何插件接口
- 类型安全：`AutoDiscoveredTool<T>` 保留泛型信息
- 零配置：Spring 自动收集 `List<AutoDiscoveredTool<?>>`

### 3.2 PlanNotebookCapability：标记接口实现能力检测

#### 设计起点：Agent 怎么知道某个插件是否可用？

**场景**：`PlanBizAgent` 只在 PlanNotebook 插件存在时才可用。

```java
// 反例：Class.forName 检测
public boolean isAvailable() {
    try {
        Class.forName("com.git.hui.jobclaw.plugins.plannotebook.PlanNotebookTool");
        return true;
    } catch (ClassNotFoundException e) {
        return false;
    }
}
```

**问题**：字符串反射，编译期无类型安全，重构时容易遗漏。

**解决方案**：标记接口。

```java
// core/tools/ 中定义标记接口
public interface PlanNotebookCapability { }

// plugins/ 中实现
public class PlanNotebookTool implements PlanNotebookCapability {
    // ...
}

// Agent 中检测
public boolean isAvailable() {
    return autoDiscoveredTools.stream()
        .map(AutoDiscoveredTool::tool)
        .anyMatch(PlanNotebookCapability.class::isInstance);
}
```

**为什么标记接口比反射好？**

| 方案 | 类型安全 | 编译期检查 | IDE 支持重构 |
|------|---------|-----------|------------|
| `Class.forName` | ❌ | ❌ | ❌ |
| 标记接口 + `instanceof` | ✅ | ✅ | ✅ |

### 3.3 插件作为 Middleware 提供者

有些插件不仅提供工具，还需要影响 Agent 的推理过程。`ReActMiddleware` 机制为插件提供了第二个入口。

**场景**：PlanNotebook 插件需要在每次 LLM 推理前注入当前计划进度，让 AI 知道"做到哪一步了"。

```java
// PlanNotebookAutoConfiguration 注册一个 Middleware
@Bean
public ReActMiddleware planHintReActMiddleware(PlanNotebook notebook) {
    return new PlanHintReActMiddleware(notebook);
}
```

`PlanHintReActMiddleware` 实现了 `ReActMiddleware` 的 `beforeReasoning` 钩子：

```java
public class PlanHintReActMiddleware implements ReActMiddleware {

    private final PlanNotebook notebook;
    private final ThreadLocal<String> currentUserId = new TransmittableThreadLocal<>();

    @Override
    public void setContext(ChatClientRequest request, String chatId) {
        // 从 ToolContext 中提取当前用户 ID
        if (request.prompt().getOptions() instanceof ToolCallingChatOptions options) {
            Object userId = options.getToolContext().get("jobClawUserId");
            if (userId instanceof String value && !value.isBlank()) {
                currentUserId.set(value);
            }
        }
    }

    @Override
    public void beforeReasoning(List<Message> messages, int iter, String chatId) {
        String userId = currentUserId.get();
        if (userId == null) return;
        // 移除旧的 hint，注入最新的计划
        messages.removeIf(m -> m.getText().startsWith(HINT_PREFIX));
        String hint = notebook.generateHint(userId);
        if (!hint.isBlank()) {
            messages.add(new SystemMessage(hint));
        }
    }

    @Override
    public void onComplete(...) { currentUserId.remove(); }
    @Override
    public void onError(...) { currentUserId.remove(); }
}
```

**设计价值**：
- 插件不止可以"提供工具"，还可以"影响推理"
- Middleware 是 ReAct 循环的自然扩展点（详见 [ReActAdvisor 与 Middleware 机制文档](./04-✅求职派ReActAdvisor实现ReAct范式与Middleware机制实战.md)）

---

## 四、插件注册机制：从 AutoConfiguration 到 AutoDiscoveredTool

### 4.1 三步注册法

每个插件遵循统一的注册流程：

**Step 1：创建 Maven 模块**

```xml
<!-- plugins/playwright/pom.xml -->
<parent>
    <groupId>com.git.hui.jobclaw</groupId>
    <artifactId>plugins</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</parent>
<artifactId>playwright</artifactId>
<dependencies>
    <dependency>
        <groupId>com.git.hui.jobclaw</groupId>
        <artifactId>core</artifactId>
    </dependency>
</dependencies>
```

**Step 2：声明 AutoConfiguration.imports**

```
文件：plugins/{module}/src/main/resources/META-INF/spring/
      org.springframework.boot.autoconfigure.AutoConfiguration.imports

内容：com.git.hui.jobclaw.plugins.playwright.PlaywrightAutoConfiguration
```

Spring Boot 启动时会读取所有类路径上的 `AutoConfiguration.imports` 文件，自动加载其中声明的 `@AutoConfiguration` 类。

**Step 3：定义 @AutoConfiguration + @Bean**

```java
@AutoConfiguration
@ConditionalOnProperty(name = "agent.tools.playwright.enabled", havingValue = "true")
public class PlaywrightAutoConfiguration {

    @Bean(destroyMethod = "close")
    public PlaywrightBrowserTool playwrightBrowserTool(
            @Value("${agent.tools.playwright.headless:true}") boolean headless) {
        return PlaywrightBrowserTool.builder().headless(headless).build();
    }

    @Bean
    public AutoDiscoveredTool<PlaywrightBrowserTool> autoDiscoveredTool(
            PlaywrightBrowserTool tool) {
        return new AutoDiscoveredTool<>(tool);
    }
}
```

### 4.2 三种插件注册模式

根据插件的复杂度，有三种注册模式：

**模式一：简单插件 —— 只有工具（Job-Library）**

```java
@AutoConfiguration
public class JobLibraryAutoConfiguration {

    @Bean
    public AutoDiscoveredTool<JobLibraryTool> autoDiscoveredJobLibraryTool(
            JobLibraryTool jobLibraryTool) {
        return new AutoDiscoveredTool<>(jobLibraryTool);
    }

    @Bean
    public JobLibraryTool jobLibraryTool() {
        return new JobLibraryTool();
    }
}
```

无 `@ConditionalOnProperty`，默认始终启用。

**模式二：带开关 + 资源管理的插件（Playwright）**

```java
@AutoConfiguration
@ConditionalOnProperty(name = "agent.tools.playwright.enabled", havingValue = "true")
public class PlaywrightAutoConfiguration {

    @Bean(destroyMethod = "close")
    public PlaywrightBrowserTool playwrightBrowserTool(
            @Value("${agent.tools.playwright.headless:true}") boolean headless) {
        return PlaywrightBrowserTool.builder().headless(headless).build();
    }

    @Bean
    public AutoDiscoveredTool<PlaywrightBrowserTool> autoDiscoveredTool(
            PlaywrightBrowserTool tool) {
        return new AutoDiscoveredTool<>(tool);
    }
}
```

**模式三：带 Middleware + 持久化的插件（Plan-Notebook）**

```java
@AutoConfiguration
@ConditionalOnProperty(name = "agent.tools.plan-notebook.enabled", havingValue = "true")
public class PlanNotebookAutoConfiguration {

    @Bean
    public PlanNotebook planNotebook(
            @Value("${agent.workspace:Unknown}") Resource workspace) throws IOException {
        return new PlanNotebook(workspace);
    }

    @Bean
    public PlanNotebookTool planNotebookTool(PlanNotebook notebook) {
        return new PlanNotebookTool(notebook);
    }

    @Bean
    public AutoDiscoveredTool<PlanNotebookTool> autoDiscoveredTool(PlanNotebookTool tool) {
        return new AutoDiscoveredTool<>(tool);
    }

    @Bean
    public ReActMiddleware planHintReActMiddleware(PlanNotebook notebook) {
        return new PlanHintReActMiddleware(notebook);
    }
}
```

### 4.3 三种模式的对比

| 维度 | Job-Library | Playwright | Plan-Notebook |
|------|-------------|-----------|--------------|
| `@ConditionalOnProperty` | ❌ | ✅ | ✅ |
| 资源管理 | 无 | `destroyMethod="close"` | 文件读写 |
| Middleware | 无 | 无 | `PlanHintReActMiddleware` |
| 适用场景 | 纯查询工具 | 有状态、需清理 | 持久化 + 影响推理 |

---

## 五、具体插件实现

### 5.1 Playwright：浏览器自动化插件

#### 用途

AI Agent 通过自然语言"浏览网页"——导航、点击、填表单、截图、执行 JS。

#### 工具列表

```java
public class PlaywrightBrowserTool implements AutoCloseable {

    @Tool(description = "Navigate to a URL. Returns the page title and a text summary.")
    public synchronized String navigateTo(String url) { ... }

    @Tool(description = "Click an element matching the given CSS selector.")
    public synchronized String clickElement(String selector) { ... }

    @Tool(description = "Fill an input field with the given value.")
    public synchronized String fillInput(String selector, String value) { ... }

    @Tool(description = "Extract text content from elements matching a CSS selector.")
    public synchronized String getText(String selector) { ... }

    @Tool(description = "Take a screenshot. Returns the file path to the saved PNG image.")
    public synchronized String takeScreenshot() { ... }

    @Tool(description = "Execute JavaScript in the current page context.")
    public synchronized String evaluateJavaScript(String expression) { ... }

    @Tool(description = "Wait for an element to appear on the page.")
    public synchronized String waitForSelector(String selector, int timeoutMs) { ... }

    @Tool(description = "Close the browser session.")
    public synchronized String closeBrowser() { ... }
}
```

#### 设计要点

**1. 懒加载 + 同步控制**

```java
private synchronized Page getOrCreatePage() {
    if (playwright == null) {
        playwright = Playwright.create();
        browser = launchBrowser();
    }
    if (page == null || page.isClosed()) {
        page = browser.newPage();
    }
    return page;
}
```

- 第一次使用工具时才初始化 Playwright，而不是启动时
- `synchronized` 保证多线程安全（一个 Agent 可能同时处理多个用户请求）

**2. 自动安装 Chromium**

```java
private Browser launchBrowser() {
    try {
        return playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
    } catch (PlaywrightException e) {
        if (e.getMessage().contains("Executable doesn't exist")) {
            com.microsoft.playwright.CLI.main(new String[]{"install", "chromium"});
            return playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
        }
        throw e;
    }
}
```

- 首次运行时自动下载 Chromium，无需手动安装

**3. 内容截断**

```java
private static String truncate(String text) {
    if (text.length() <= MAX_TEXT_LENGTH) return text;
    return text.substring(0, MAX_TEXT_LENGTH) + "\n\n[Content truncated - " + text.length() + " total characters]";
}
```

- 网页文本可能很长（上万字符），截断到 `MAX_TEXT_LENGTH = 10_000`，避免 Token 浪费

### 5.2 Job-Library：职位库检索插件

#### 用途

让 AI Agent 能够查询职位库的字典筛选项，并根据用户偏好搜索匹配的职位。

#### 两个核心工具

**工具 1：查询可用筛选项**

```java
@Tool(description = """
        查询职位库中可用的筛选选项.
        触发时机: 当用户想了解有哪些筛选条件、可以按什么筛选时.
        返回: 公司类型、行业、地点、招聘对象、招聘类型、岗位关键词等.
        """)
public String getAvailableFilterOptions() {
    List<CommonDict> dictList = commonDictService.queryByApp("oc");
    // 按 key 聚合，返回格式化的筛选项列表
}
```

**工具 2：根据偏好搜索职位**

```java
@Tool(description = """
        根据用户的偏好信息，从正式库中智能检索最可能关心的岗位.
        触发时机: 当用户想查职位、要求推荐、筛选查询时.
        """)
public String searchJobsByPreference(UserPreference preference) {
    UserInterestRecommendReq req = buildRecommendRequest(preference);
    PageListVo<OcJobInfo> result = jobSearchService.recommend(req);
    // 返回格式化的职位列表
}
```

#### 设计要点

**1. 结构化参数**

```java
@Data
public static class UserPreference {
    @JsonPropertyDescription("期望的公司类型，如：国企、事业单位、学校、银行、私企等")
    private List<String> companyTypes;

    @JsonPropertyDescription("期望的行业，如：IT/互联网、金融、建筑、生物制药等")
    private List<String> industries;

    @JsonPropertyDescription("期望的工作地点，如：北京、上海、深圳、杭州等")
    private List<String> locations;

    // ... 更多字段
}
```

LLM 会自动根据 `@JsonPropertyDescription` 的描述生成合适的参数值。

**2. 构建工具 vs 搜索工具分离**

```
getAvailableFilterOptions()
  ─ 先了解有什么筛选项

searchJobsByPreference(preference)
  ─ 再用筛选项构建查询条件
```

**典型对话**：

```
用户："帮我看看有哪些公司类型可以选择"
  → 调用 getAvailableFilterOptions()
  → 返回：国企、事业单位、学校、银行、私企...

用户："帮我找北京的国企秋招职位"
  → 调用 searchJobsByPreference({locations:["北京"], companyTypes:["国企"], recruitmentTypes:["秋招"]})
  → 返回匹配的职位列表
```

### 5.3 Plan-Notebook：计划追踪插件

#### 用途

让 AI Agent 能够为复杂任务创建结构化计划，追踪每个子任务的进度。

#### 工具列表

| 工具 | 用途 |
|------|------|
| `createPlan` | 创建或替换当前计划 |
| `getCurrentPlan` | 查看当前计划 |
| `updateSubtaskState` | 更新子任务状态 |
| `finishSubtask` | 标记子任务完成 |
| `abandonSubtask` | 标记子任务放弃 |
| `clearPlan` | 清除当前计划 |

#### Middleware：在推理中注入计划

PlanNotebook 不止提供工具，还通过 `PlanHintReActMiddleware` 在每次 LLM 推理前注入当前计划：

```
without PlanHintReActMiddleware:
  用户："继续帮我查之前说的那些公司"
  LLM 思考："之前说的是哪些公司来着..."  ← 上下文已丢失

with PlanHintReActMiddleware:
  beforeReasoning() injects:
    "<当前计划: 1.✅ 搜索公司信息 2.🔴 对比薪资待遇 3.⏳ 整理推荐列表>"
  LLM 思考："当前在第2步，需要对比薪资待遇"  ← 上下文保持
```

---

## 六、Agent 集成：工具如何到达 Agent

### 6.1 完整链路

```
Spring Boot 启动
  │
  ├── 扫描 AutoConfiguration.imports
  │     ↓
  ├── PlaywrightAutoConfiguration
  │     → @Bean PlaywrightBrowserTool
  │     → @Bean AutoDiscoveredTool<PlaywrightBrowserTool>
  │
  ├── JobLibraryAutoConfiguration
  │     → @Bean JobLibraryTool
  │     → @Bean AutoDiscoveredTool<JobLibraryTool>
  │
  └── PlanNotebookAutoConfiguration
        → @Bean PlanNotebookTool
        → @Bean AutoDiscoveredTool<PlanNotebookTool>
        → @Bean PlanHintReActMiddleware

Agent 初始化
  │
  ├── 注入 List<AutoDiscoveredTool<?>>
  │     ↓ (Spring 自动收集所有 AutoDiscoveredTool bean)
  │
  ├── @PostConstruct init()
  │     → new UserPreferenceBasedLlmCaller(
  │         modelProviders, ..., autoDiscoveredTools, ..., getTools())
  │
  ├── UserPreferenceBasedLlmCaller.initClient()
  │     → chatClientBuilder.defaultTools(...内置工具...)
  │     → autoDiscoveredTools.forEach(t → chatClientBuilder.defaultTools(t.tool()))
  │     → chatClientBuilder.defaultToolCallbacks(...Agent 特有工具...)
  │
  └── ChatClient 就绪
        → 内置工具 + 插件工具 + Agent 特有工具，全部可用
```

### 6.2 Agent 对工具的可见性

| 工具来源 | 注册方式 | 哪些 Agent 可见 |
|---------|---------|----------------|
| **内置工具**（TaskTool, McpTool, CheckListTool 等） | `chatClientBuilder.defaultTools()` | 所有使用 `UserPreferenceBasedLlmCaller` 的 Agent |
| **插件工具**（Playwright, JobLibrary, PlanNotebook） | `autoDiscoveredTools.forEach()` | 所有使用 `UserPreferenceBasedLlmCaller` 的 Agent |
| **Agent 特有工具** | `chatClientBuilder.defaultToolCallbacks(getTools())` | 仅当前 Agent |

**Agent 可能看到的完整工具列表**：

```
DefaultBizAgent 的工具箱：
  ├── 内置工具：TaskTool, McpTool, CheckListTool, ShellTools
  ├── 插件工具：PlaywrightBrowserTool (navigateTo/clickElement/...)
  ├── 插件工具：JobLibraryTool (getAvailableFilterOptions/searchJobsByPreference)
  └── 插件工具：PlanNotebookTool (createPlan/getCurrentPlan/...)
```

---

## 七、扩展指南：如何开发新插件

### 7.1 五步创建新插件

以创建一个"邮件发送"插件为例。

**Step 1：创建 Maven 模块**

```
plugins/
├── pom.xml                    ← 添加 <module>email</module>
└── email/
    ├── pom.xml                ← 依赖 core
    └── src/main/
```

```xml
<!-- plugins/email/pom.xml -->
<parent>
    <groupId>com.git.hui.jobclaw</groupId>
    <artifactId>plugins</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</parent>
<artifactId>email</artifactId>
<dependencies>
    <dependency>
        <groupId>com.git.hui.jobclaw</groupId>
        <artifactId>core</artifactId>
    </dependency>
</dependencies>
```

**Step 2：编写工具类**

```java
package com.git.hui.jobclaw.plugins.email;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class EmailTool {

    @Tool(description = "Send an email to a specified recipient.")
    public String sendEmail(
            @ToolParam(description = "Email address of the recipient") String to,
            @ToolParam(description = "Subject of the email") String subject,
            @ToolParam(description = "Body content of the email") String body) {
        // ... 发送邮件逻辑
        return "Email sent to " + to;
    }

    @Tool(description = "List all emails in the inbox.")
    public String listInbox(
            @ToolParam(description = "Maximum number of emails to return") int maxResults) {
        // ... 查询邮件列表
        return "Found N emails...";
    }
}
```

**Step 3：创建 AutoConfiguration**

```java
package com.git.hui.jobclaw.plugins.email;

import com.git.hui.jobclaw.core.tools.AutoDiscoveredTool;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(name = "agent.tools.email.enabled", havingValue = "true")
public class EmailAutoConfiguration {

    @Bean
    public EmailTool emailTool() {
        return new EmailTool();
    }

    @Bean
    public AutoDiscoveredTool<EmailTool> autoDiscoveredEmailTool(EmailTool tool) {
        return new AutoDiscoveredTool<>(tool);
    }
}
```

**Step 4：声明 AutoConfiguration.imports**

```
创建文件：
plugins/email/src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports

内容：
com.git.hui.jobclaw.plugins.email.EmailAutoConfiguration
```

**Step 5：配置启用**

```yaml
agent:
  tools:
    email:
      enabled: true
```

**完成！** 重启应用后，所有 Agent 自动获得发送邮件的能力。

### 7.2 插件开发 Checklist

| 步骤 | 必须？ | 说明 |
|------|-------|------|
| 创建 Maven 模块 | ✅ | 依赖 `core` |
| 编写 `@Tool` 类 | ✅ | 每个工具方法加 `@Tool(description=...)` |
| 创建 `@AutoConfiguration` | ✅ | 注册 `AutoDiscoveredTool<T>` bean |
| `AutoConfiguration.imports` | ✅ | 一行类全限定名 |
| `@ConditionalOnProperty` | 推荐 | 可开关的插件 |
| `destroyMethod` | 有资源时 | 如 `Playwright` 需要关闭浏览器 |
| `ReActMiddleware` | 可选 | 需要影响推理过程时 |

---

## 八、工具发现机制的进阶：动态工具搜索

当工具数量超过一定阈值（比如 50+ 个），LLM 可能不知道有哪些工具可用。求职派通过 `DynamicToolDiscoveryConfiguration` 集成了 Lucene 工具搜索：

```java
@Configuration
@ConditionalOnProperty(name = "agent.tools.dynamic-discovery.enabled",
                       havingValue = "true", matchIfMissing = true)
public class DynamicToolDiscoveryConfiguration {

    @Bean
    public ToolSearcher toolSearcher(DynamicToolDiscoveryProperties properties) {
        return new LuceneToolSearcher(properties.luceneMinScoreThreshold());
    }

    @Bean
    public ToolSearchToolCallAdvisor toolSearchToolCallAdvisor(
            ToolSearcher toolSearcher, DynamicToolDiscoveryProperties properties) {
        return ToolSearchToolCallAdvisor.builder()
                .toolSearcher(toolSearcher)
                .maxResults(properties.maxResults())
                .build();
    }
}
```

**工作原理**：
- 使用 Lucene 索引所有工具的 name 和 description
- 当 LLM 收到用户请求时，先搜索相关工具
- 只向 LLM 注册匹配到的工具子集，减少上下文占用

---

## 九、常见问题

### 9.1 AutoDiscoveredTool 和 @Tool 注解的关系？

`AutoDiscoveredTool` 是"注册机制"，`@Tool` 是"定义机制"。

```
AutoDiscoveredTool<PlaywrightBrowserTool>
  │  Spring 通过这个 bean 发现了工具类
  │
  ▼
PlaywrightBrowserTool.navigateTo()  ← @Tool 注解
PlaywrightBrowserTool.clickElement() ← @Tool 注解
  │  Spring AI 通过 @Tool 发现了具体方法
  │
  ▼
ToolCallback 实例，可在 ReAct 循环中被调用
```

### 9.2 为什么插件工具对所有 Agent 可见？

这是设计目标而不是问题。如果希望某个工具只对特定 Agent 可见，有两种方式：

1. **不在插件中注册**，直接在 Agent 的 `getTools()` 中返回
2. **使用标记接口** + Agent 端过滤

```java
// 在 Agent 中过滤
autoDiscoveredTools.stream()
    .filter(t -> !(t.tool() instanceof SensitiveTool))  // 排除敏感工具
    .forEach(t -> chatClientBuilder.defaultTools(t.tool()));
```

### 9.3 插件之间可以依赖吗？

不建议。插件应该只依赖 `core`，不依赖其他插件。如果两个插件需要协作，应该通过 `core` 的扩展点（如 Middleware、EventBus、标记接口）实现。

---

## 十、小结

求职派的插件系统基于三个核心设计原则：

1. **零侵入**：工具类不需要实现任何插件接口，普通的 `@Tool` 方法即可
2. **零配置**：`AutoDiscoveredTool` + Spring 的自动收集机制，插件放到类路径就生效
3. **可扩展**：插件不仅能提供工具，还能注册 `ReActMiddleware` 影响 Agent 推理

**插件 == 可插拔的能力模块**：

```
         Playwright          Job-Library          Plan-Notebook
       （浏览器自动化）      （职位库检索）        （计划追踪）
              │                    │                    │
              └─────────┬──────────┴──────────┬─────────┘
                        │                     │
                  AutoDiscoveredTool     ReActMiddleware
                        │                     │
                        └──────────┬──────────┘
                                   │
                     UserPreferenceBasedLlmCaller
                           ↓ 注册到 ChatClient
                    所有 Agent 自动获得能力
```

当前已有三个插件覆盖了浏览器自动化、职位检索、计划追踪三个核心场景。后续可以继续扩展：邮件发送、IM 通知、文档生成等，每个插件只需 5 步即可完成接入。

---

:::success
相关代码：
- AutoDiscoveredTool 定义：`core/src/main/java/com/git/hui/jobclaw/core/tools/AutoDiscoveredTool.java`
- PlanNotebookCapability 标记接口：`core/src/main/java/com/git/hui/jobclaw/core/tools/PlanNotebookCapability.java`
- DynamicToolDiscoveryConfiguration：`core/src/main/java/com/git/hui/jobclaw/core/tools/DynamicToolDiscoveryConfiguration.java`
- 插件模块定义：`plugins/pom.xml`
- Playwright 插件：`plugins/playwright/src/main/java/com/git/hui/jobclaw/plugins/playwright/`
- Job-Library 插件：`plugins/job-library/src/main/java/com/git/hui/jobclaw/plugins/jobs/`
- Plan-Notebook 插件：`plugins/plan-notebook/src/main/java/com/git/hui/jobclaw/plugins/plannotebook/`
:::

---

> 相关文档：
> - [ReActAdvisor 与 Middleware 机制](./04-✅求职派ReActAdvisor实现ReAct范式与Middleware机制实战.md)
> - [V2 重构总览](./00-求职派V2重构：从单体应用到多Agent运行时.md)
> - [定时任务与周期任务设计](./06-✅求职派定时任务与周期任务设计与实现实战.md)
