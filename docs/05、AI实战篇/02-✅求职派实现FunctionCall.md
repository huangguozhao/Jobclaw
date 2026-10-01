---
title: 求职派实现 Function Call
shortTitle: 02-求职派实现 Function Call
description: 深度拆解求职派如何通过 Spring AI 实现 Function Call，从工具注册到多轮数据采集、从本地工具到 MCP 自动切换，用源码证据讲清楚工具调用的完整流程。
keywords:
  - Function Call
  - Spring AI
  - 求职派
  - Tool Calling
  - Spring AI 工具调用教程
tag:
  - Agent
category:
  - AI
author: 沉默王二
date: 2026-09-11
---

求职派的岗位采集需要从招聘网站抓取数据，但大模型本身并不具备访问网络的能力。

大模型只负责文本推理，不负责和外部世界打交道。

![](https://cdn.paicoding.com/stutymore/jobclaw-function-call-01-overview-20260911153808-b834d482.png)

Function Call 就是为了解决这个问题出现的。我们预先注册好工具方法，大模型在推理过程中判断需要外部能力时，发出一个工具调用请求，框架去执行对应的工具方法并把结果返回给模型，模型拿到结果后继续推理，最终生成完整的回答。

Spring AI 2.0.0-M4 对 Function Call 的支持已经相当成熟。

![](https://cdn.paicoding.com/stutymore/02-✅求职派实现FunctionCall-20260911160241.png)

我们只需要关注两件事，用 `@Tool` 注解标注工具方法、在调用时用 `.tools()` 注册工具类。剩下的参数序列化、工具清单生成、调用过程管理，全部由框架来处理。

这篇内容会从 Spring AI 的工具注册讲起，然后依次讲解求职派是怎么处理返回数据截断的、怎么在 Function Call 和 MCP 之间自动切换的。

## 01、怎么把一个 Java 方法变成工具？

Spring AI 提供了 `@Tool` 注解，加在方法上就能把它注册为大模型可以调用的工具。

不是所有大模型都支持 Function Call，不过 DeepSeek V4.1 Flash 是支持的，求职派用的就是它。

![](https://cdn.paicoding.com/stutymore/02-✅求职派实现FunctionCall-20260911202109.png)

求职派的岗位采集场景需要从网页中抓取表格数据。对于静态网页，用 Jsoup 解析 HTML 就够了；HTTP 请求则交给 Hutool 处理。

```java
@Tool(description = "输入一个http链接，返回对应网页中的表格内容")
public String crawlerHttpTable(
        @ToolParam(description = "http格式的url地址") String url) {
    String text = HttpUtil.get(url, CharsetUtil.CHARSET_UTF_8);
    Document doc = Jsoup.parse(text);
    Element table = doc.select("table").first();
    return table == null ? doc.body().text() : table.html().trim();
}
```

这里有个细节，当页面中没有 `<table>` 标签时，方法会降级返回整个页面的文本内容，而不是直接抛异常。

![](https://cdn.paicoding.com/stutymore/jobclaw-function-call-02-tool-schema-20260911154037-4ae98b09.png)

`@Tool` 的 `description` 参数是整个 Function Call 能不能正确触发的关键。

大模型拿到的是一份工具清单，清单里每个工具只有名称和描述两项信息。大模型就是根据这段描述来判断“用户的请求是否需要调用这个工具”。描述写得模糊或者不准确，模型在该调用的时候就可能不会调用。

`@ToolParam` 的作用是告诉大模型这个参数应该传什么值。框架会把参数描述转换成 JSON Schema，大模型根据 Schema 来构造入参。

求职派的网页抓取工具类中还包含了一个文件内容转换方法，同样用 `@Tool` 标注，用于处理用户上传的文件附件。一个工具类里可以包含多个 `@Tool` 方法，Spring AI 在扫描时会把它们全部注册为独立的工具。

实际项目中可以按功能领域来组织，比如数据采集相关的工具放在一起，浏览器操作的工具放在另一个类里，而不是每个工具方法都单独建一个类。

注册到大模型是通过 ChatClient 的 `.tools()` 方法传入工具类的实例。

```java
chatClient.prompt(text)
    .tools(new CrawlerTools())
    .call()
    .entity(new ParameterizedTypeReference<>() {});
```

Spring AI 拿到工具实例后，会通过反射扫描所有标注了 `@Tool` 的方法，为每个方法生成一份工具描述（方法名、参数的 JSON Schema、功能描述），打包成工具列表一起发给大模型。大模型在推理时就知道自己有哪些工具可以用了。

除了 `.tools()` 方法，还有一种注册方式是 `ToolCallbacks.from()`。它接收一个包含 `@Tool` 方法的类实例，返回工具回调数组。这种方式适合在构建 ChatClient 时就把工具预先配置好的场景，求职派在业务 Agent 的构建器中就用了这种方式。

两种方式在底层做的事情是一样的，都是把包含 `@Tool` 注解的方法转换成标准的工具回调对象。区别只在于时机——`.tools()` 是在发送请求时临时指定工具集，`ToolCallbacks.from()` 是在构建 ChatClient 时预先配好默认的工具集。求职派两种都用了，数据采集场景用 `.tools()` 临时传入，业务 Agent 场景用构建器预先配置。

## 02、大模型调用工具时，背后发生了什么？

理解背后的执行机制，出问题的时候才知道往哪里排查。

先说结论，大模型并不会真的执行你的 Java 方法。它只是在响应中返回了一个“我想调用某个工具”的结构化请求，真正的方法执行是由框架来完成的。

![](https://cdn.paicoding.com/stutymore/jobclaw-function-call-03-five-steps-20260911154250-3738213b.png)

完整的流程分 5 步：

1. 框架把用户输入和工具清单一起发给大模型
2. 大模型推理后，如果判断需要外部能力，就在响应中返回一个工具调用请求，里面包含了工具名和参数值
3. 框架收到响应后，根据工具名找到对应的 Java 方法，把参数反序列化成方法入参，然后执行这个方法
4. 方法的返回值会作为一条新的消息追加到对话历史中
5. 框架再次把对话历史（包含工具返回值）发给大模型，大模型拿到了工具的执行结果，就能继续推理并生成最终的回答

这个“推理→调用工具→拿到结果→继续推理”的循环，就是 ReAct 模式。如果一次工具调用解决不了问题，大模型还可以在后续轮次中继续调用工具，形成多轮迭代。

求职派在这个环节做了一个重要的设计决策：禁用 Spring AI 的自动工具执行，改用自定义的 ReAct 控制器来接管整个流程。

```java
ToolCallingChatOptions.builder()
    .internalToolExecutionEnabled(false)
    .build()
```

为什么？

自动模式下，Spring AI 会在内部完成工具调用和结果回传的整个循环，中间过程对业务代码来说是完全不可见的。出了问题只能看到最终结果，看不到中间发生了什么。

求职派自己实现的 ReAct 控制器能在每一步插入日志、做异常恢复、控制最大迭代次数，还能在工具执行前后插入中间件逻辑。

具体来说，自定义控制器在每次工具执行前，会自动构建一个工具上下文对象，把当前用户的身份信息、会话状态、渠道来源等数据打包进去。工具方法可以直接从上下文中读取这些信息，不需要自己去查数据库或者解析请求参数。如果某个工具在一次对话中被反复调用超过了预设的迭代上限（默认 10 次），控制器会强制终止循环，避免因为大模型的幻觉而陷入无限重试。这些精细化的控制能力，在自动模式下是做不到的。

在配置文件中把日志级别调到 debug，就能清楚地看到整个 Function Call 的执行过程。

```yaml
logging:
  level:
    org.springframework.ai.chat.client.advisor: debug
    com.git.hui.jobclaw.gather.service.GatherAiAgent: debug
```

![](https://cdn.paicoding.com/stutymore/jobclaw-function-call-yuque-1-20260911204051-874a7a23.png)

日志里能看到大模型判断需要调用工具、框架执行工具方法、工具返回结果的完整过程。

![](https://cdn.paicoding.com/stutymore/jobclaw-function-call-yuque-2-20260911204052-e0abb522.png)

这是大模型最终提取出来的结构化岗位数据。大模型负责理解需求和解析数据，工具方法负责抓取网页，各做各的事情。

## 03、返回数据太长被截断了怎么办？

单次工具调用在大多数场景下是够用的。但求职派的岗位数据采集有一个麻烦，招聘页面的数据量经常很大，大模型一次性返回的结果会被截断，JSON 数据不完整，解析时直接报错。

解决方案是多轮对话——第一次拿到不完整的数据后，自动追问大模型“继续返回剩余内容”，直到数据完整为止。

![](https://cdn.paicoding.com/stutymore/jobclaw-function-call-yuque-3-20260911204053-657c877e.png)

求职派的数据采集 Agent 实现了一个自动续传方法来处理这个问题。核心逻辑分三步。

**第一步，初始化对话上下文。**

创建一个窗口记忆，最多保留 10 条消息，保证多轮对话中大模型能记住之前说了什么，同时上下文窗口不会无限膨胀。先往记忆里塞一条系统消息，告诉大模型它的角色是“岗位数据提取助手”，然后根据是否有图片附件来选择文本模型还是视觉模型。

**第二步，循环调用大模型。**

第一轮发送用户的原始输入和格式要求（比如“请用 JSON 数组格式返回”），后续轮次只发一句提示，告诉大模型“你之前返回的结果不完整，继续返回剩余的内容”。每一轮都通过工具回调提供者来获取可用的工具列表——如果 MCP 服务可用就用 MCP 的工具，否则就降级到本地的网页抓取工具。

![](https://cdn.paicoding.com/stutymore/jobclaw-function-call-04-continuation-20260911154507-89786c8e.png)

**第三步，判断什么时候该停下来。**

三个终止条件同时生效：

- 大模型返回的内容以代码块结束标记收尾，说明 JSON 数据块已经完整了
- 对话轮数达到了上限（10 轮），防止无限循环
- 大模型没有接着上次的位置继续，而是重新返回了完整数据

第三个条件单独说。大模型有时候会“忘记”自己上一轮说到哪里了，直接从头开始生成一份完整的数据。如果不检测这种情况，循环就会一直重复拿到相同的内容，永远不会结束。

```java
if (cnt > 1 && outText.startsWith("```json")) {
    int idx = outText.indexOf("[");
    if (idx > 0 && idx < 15) {
        log.info("大模型重复返回完整数据，主动退出");
        break;
    }
}
```

判断逻辑是：如果从第二轮开始，返回的内容以 JSON 代码块标记开头，并且紧跟着 JSON 数组的起始符号 `[`，就认为大模型是在从头开始生成，主动退出循环。

还有一个细节。异常处理用的是 `break` 而不是 `throw`。当某一轮调用大模型出错时，方法不会整体失败，而是保留前面轮次已经解析出来的数据。在生产环境中这个设计非常实用——比如抓了 8 条岗位数据，第 9 条出错了，前面 8 条照样返回给调用方，不会因为一个错误丢掉所有成果。

每一轮拿到大模型的响应之后，会用一个专门的格式解析器从中提取结构化数据项。解析器能够处理不完整的 JSON 片段，把跨轮次的数据拼接成完整的结果列表。这样即使大模型分了 5 轮才返回完整数据，最终拿到的结果和一次性返回是一样的。

窗口记忆的大小设置为 10 条消息，这个数字是在实际使用中调出来的。设太小了，大模型在后续轮次中可能丢失前面的上下文，不知道自己之前已经返回到了哪里，导致重复生成。设太大了，每一轮发给大模型的消息列表会越来越长，不仅增加了 Token 消耗，还可能逼近模型的上下文窗口限制。10 条在绝大多数的采集场景下是够用的。一条系统消息加上若干轮用户追问和模型回复，基本上能覆盖一次完整的数据提取过程。

## 04、Function Call 和 MCP 怎么共存？

Function Call 用 `@Tool` 注解把工具方法写在应用代码里，和业务逻辑耦合在一起。MCP 则是把工具拆成独立的服务，通过标准化协议暴露给大模型。

两者解决的是同一个问题，给大模型扩展外部交互能力——但各有各的适用场景。

Function Call 适合和业务紧密耦合的工具，比如求职派的岗位查询需要直接访问数据库，拆成独立服务反而增加了复杂度。MCP 适合通用的、可以跨项目复用的工具，比如浏览器自动化、发布公众号文章。

![](https://cdn.paicoding.com/stutymore/jobclaw-function-call-05-dual-tools-20260911154756-5e7b9e5b.png)

数据采集 Agent 在启动时会检查 Spring 容器中有没有注入 MCP 客户端。如果有，就优先使用 MCP 提供的工具集；如果没有，就降级到本地的 `@Tool` 工具。

```java
@Autowired(required = false)
public void setToolCallbackProvider(
        List<McpAsyncClient> mcpClients) {
    if (!CollectionUtils.isEmpty(mcpClients)) {
        this.toolCallbackProvider =
            new AsyncMcpToolCallbackProvider(mcpClients);
    }
}
```

`@Autowired(required = false)` 保证了在没有配置 MCP 的环境下，这个方法根本不会被调用，应用也不会报错。

如果 MCP 不可用，就会走 `@PostConstruct` 的初始化方法，把本地的网页抓取工具注册为兜底方案。

```java
@PostConstruct
public void initLocalToolCallback() {
    if (this.toolCallbackProvider == null) {
        this.toolCallbackProvider =
            new StaticToolCallbackProvider(
                ToolCallbacks.from(new CrawlerTools()));
    }
}
```

这里的执行顺序很关键：`@PostConstruct` 在 `@Autowired` 注入完成之后才执行。如果 MCP 客户端注入成功了，`toolCallbackProvider` 已经有值了，本地工具就不会被加载。这个顺序保证了“MCP 优先、本地工具兜底”的策略。

这个设计的实际效果是，开发环境不需要启动 MCP 服务就能正常跑起来，生产环境配置好 MCP 后自动切换到更强大的工具集，业务代码不需要做任何修改。

除了网页抓取工具，求职派在其他模块也注册了大量的 `@Tool` 工具。

![](https://cdn.paicoding.com/stutymore/jobclaw-function-call-06-tools-20260911154914-b819e0a9.png)

浏览器自动化插件提供了导航、点击、填充表单、截图等 8 个工具方法，让大模型能够操作浏览器来抓取动态渲染的页面。岗位检索 Agent 提供了从 URL 或文本中提取岗位信息的工具，支持异步任务模式，工具方法返回一个任务 ID，用户可以随时查询进度。智能网页抓取工具带了 15 分钟的自清理缓存和指数退避重试，还会自动把 HTML 转换成 Markdown 格式来提高大模型的理解准确率。

这些工具有一个共同的模式，都是通过 `ToolContext` 获取当前用户的上下文信息。

`ToolContext` 是 Spring AI 提供的上下文传递机制。当大模型触发工具调用时，框架会把预先设置好的上下文信息（用户 ID、会话信息、渠道消息等）自动注入到 `ToolContext` 中。工具方法通过它来获取“当前是谁在使用这个工具”，在多用户环境下实现了数据隔离——A 用户触发的工具调用只能访问 A 的数据，不会串到 B 那边去。

具体的用法很简单，在工具方法的参数列表末尾加一个 `ToolContext` 类型的参数，Spring AI 会自动把上下文注入进来。从中取出用户 ID、用户对话信息、渠道消息等数据，就能在工具内部做出正确的业务判断了。

举个例子，岗位检索工具在接收到搜索请求时，需要知道当前用户的职业画像才能做个性化推荐。它不需要自己去查用户画像数据库，直接从工具上下文中取出用户对象就行了。任务管理工具在创建后台任务时，也需要知道任务归属于哪个用户，同样从上下文中获取。这种设计让工具方法保持了纯粹，只需要关心自己的核心业务逻辑，不需要操心身份验证和权限判断的事情。

## ending

【大模型抓不了网页，就写一个 Java 方法帮它抓；一次返回不完整，就循环追问让它继续；MCP 可用就切到 MCP，不可用就降级到本地工具。】

Function Call 和 MCP 不是非此即彼的选择。

我们下期见。

### 如何把求职派写到简历上？

项目名称：求职派（JobClaw）

项目简介：基于 Spring AI 的多 Agent 求职系统，支持微信/钉钉/飞书多渠道接入，通过意图识别和 Agent 路由实现智能求职服务。

技术栈：Java 21、Spring Boot 4.0.5、Spring AI 2.0.0-M4、DeepSeek V4.1 Flash、H2/MySQL

核心职责：

- 基于 Spring AI 的 `@Tool` 注解实现 Function Call 工具注册，为大模型扩展了网页抓取、文件解析等外部交互能力，支撑岗位数据自动化采集
- 实现了多轮对话式数据采集方案，通过窗口记忆和自动续传机制解决了大模型返回数据截断的问题
- 实现了 Function Call 与 MCP 的自动切换策略，完成本地工具到 MCP 服务的无缝降级与升级
- 自定义 ReAct 循环控制器替代 Spring AI 默认的工具自动执行，支持细粒度的日志记录、异常恢复和最大迭代次数控制
- 通过 ToolContext 上下文传递机制实现了多用户环境下的工具调用数据隔离，确保工具方法能正确获取当前用户的身份和会话信息
