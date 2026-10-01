# 08-✅求职派通过MCP Client调用外部服务

大家好，我是二哥呀。

我们在给求职派开发职位数据采集功能的时候，遇到过一个非常棘手的问题。很多招聘网站为了保护自身数据并提升前端体验，页面几乎全部采用了单页面应用架构或者异步渲染技术。如果直接使用传统的轻量级网络请求工具去拉取网页，拿不到职位的表格信息。

想要拿到真实的网页内容，最直接有效的办法就是拉起一个完整的无头浏览器，让脚本和样式在真实的浏览器内核中彻底跑完，等文档对象模型渲染成型后再去抓取数据。然而，如果直接在业务服务里硬编码引入一套庞大的自动化测试驱动，不仅会让整个工程的代码和依赖急剧膨胀，常驻的浏览器进程还会占用很多内存资源。

这时候，MCP 就为我们提供了一种极具美感的解题思路。我们可以把重型的浏览器操作剥离成一个独立的外部服务，让求职派作为客户端按需与它建立通信。这样一来，大模型能借助外部服务实时浏览真实网页。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-01-20260917192555-34860602.png)

## 01、为什么选择 MCP？

在过去很长一段时间里，大模型想要与外部世界交互，主要依赖于各个大模型厂商各自推出的函数调用机制。我们在自己的业务代码里定义几个带有特定注解的方法，把方法的名字、描述和入参要求作为提示词的一部分发给模型。大模型在理解用户意图后，如果觉得需要查数据库或者调用接口，就会返回一个结构化的调用指令，告诉我们它打算调用哪个方法以及传入什么参数。我们拿到指令后在本地通过反射执行对应的方法，再把结果打包作为新的消息上下文喂回给大模型。

这种模式在面对简单的本地计算或者内部接口调用时足够轻快，可一旦外部能力变得复杂，它的弊端就会彻底暴露出来。比如我们需要让大模型操作本地文件系统、执行一段不受信任的代码、或者像求职派现在这样需要拉起一个真实的浏览器去加载页面，所有的驱动程序、运行环境以及潜在的崩溃风险，全都被迫塞进了同一个应用进程中。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-01-architecture-20260917192819-21f73b2b.png)

MCP 的核心思想，就是将工具的声明与实际执行彻底隔离。宿主应用充当客户端，外部工具则作为一个完全独立的微服务运行。两者之间通过标准输入输出或者服务端推送事件进行跨进程通信。无论外部服务是用 Node.js、Python 还是 Go 语言编写，主应用完全不需要关心它的底层细节。当应用启动时，客户端会自动向外部服务端发起请求，获取当前所有可用的工具描述；当大模型决定调用某个工具时，客户端把调用参数以标准协议格式派发给子进程，由子进程在隔离的环境中完成页面渲染与内容抽取，最后再把标准格式的文本结果传回主应用。

## 02、在 IDE 中验证

在把任何外部服务搬进求职派的主工程之前，我强烈建议大家先在一个轻量级的开发环境中进行独立验证。这样做的最大好处在于，我们可以脱离业务代码，专注于观察大模型对该服务工具描述的理解程度、参数构建的准确性，以及外部子进程在实际执行过程中的返回表现。

我们这次选用的开源服务名为浏览器自动化服务，它底层借助成熟的网页测试驱动封装了标准工具集，能够通过全局命令直接安装和拉起。我们打开 TRAE 的全局设置，在外部服务配置文件中将这个工具添加进去，配置好对应的启动命令与运行目录。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-02-20260917192557-72d1aa70.png)

配置生效之后，我们直接在智能助手的对话窗口中丢出一个具体的任务，要求它从某一个公开的校招网页中提取招聘岗位信息。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-03-20260917192558-b65848bc.png)

在接收到我们的自然语言请求后，大模型首先会对当前已经注册的所有工具进行语义匹配与意图分析。它敏锐地察觉到用户提供了一个网络链接，并且当前的诉求是获取该网页上的表格数据，而刚刚注册的浏览器自动化工具恰好声明了网页访问与内容抓取的能力。于是，大模型自主做出了发起工具调用的决策，生成了包含目标网址的标准调用入参。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-04-20260917192559-4c648e05.png)

紧接着，外部服务端在后台被真正唤醒，启动了一个真实的浏览器实例，导航到指定的网址，等待页面中的动态脚本彻底执行完毕。待页面中的招聘表格完全加载出来之后，服务端将捕获到的文本节点封装成协议消息，回传给大模型。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-05-20260917192559-058ca90d.png)

大模型拿到这份经过真实渲染的原始文本后，结合我们预设的格式化提示词，将杂乱的文字迅速整理成条理清晰的结构化岗位清单返回在聊天界面上。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-06-20260917192600-851d30b9.png)

大模型负责思考、规划与结果整理，外部服务则充当手脚，专心地完成网页加载与文本抓取。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-07-20260917192602-cf83725b.gif)

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-02-sequence-20260917192924-aa225c3f.png)

## 03、Spring AI 引入 MCP 客户端

求职派底层依托于 Spring Boot 4 与 Spring AI 框架，而 Spring AI 恰好在最新版本中提供了对 MCP 客户端的原生支持，让我们能够以非常符合 Spring 编程习惯的方式快速接入。

首先，我们在工程的依赖管理文件中加入对应的客户端依赖：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

引入依赖之后，Spring AI 会在应用启动时自动开启客户端装配流程。但是外部服务究竟该如何启动、需要执行哪条命令、传递哪些启动参数，框架本身并不能凭空预知。因此，我们需要在工程的资源目录下创建一份专用的服务描述文件，向框架清晰地交代外部进程的启动参数。

```json
{
  "mcpServers": {
    "selenium": {
      "command": "cmd",
      "args": [
        "/c",
        "npx",
        "-y",
        "@angiejones/mcp-selenium",
        "D:/"
      ]
    }
  }
}
```

大家如果仔细观察上面的配置，会发现启动命令写的是系统的命令解释器，而不是直接写包管理工具命令。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-03-platform-20260917193039-ae579636.png)

之所以要这样写，根源在于 Windows 系统的底层机制。在 Windows 环境下，许多全局安装的命令工具本质上是一个批处理脚本。Java 底层的进程构建器在尝试寻找可执行程序时，如果直接根据该命令去寻找文件，往往会因为找不到对应的二进制可执行文件而瞬间报错崩溃。为了让进程能够平稳拉起，我们必须借助系统命令解释器作为宿主来引导启动。

类 Unix 系统拥有非常规范的全局寻址机制。如果在苹果电脑或者 Linux 服务器上照搬上面的写法，服务在启动时就会直接抛出无法运行外部程序的致命错误。因此对于非 Windows 环境，我们必须把配置还原为最干净的原生形式：

```json
{
  "mcpServers": {
    "selenium": {
      "command": "npx",
      "args": [
        "-y",
        "@angiejones/mcp-selenium"
      ]
    }
  }
}
```

为了让求职派在不同开发者的个人电脑上编译调试，同时在后续容器化部署和自动化测试中稳定运行，我们在环境配置文件中引入了非常灵活的属性占位符机制：

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          servers-configuration: ${MCP_SERVERS_CONFIG:classpath:mcp-servers.json}
        enabled: ${AGENT_MCP_CLIENT_ENABLED:false}
        name: 求职派MCP
        version: 1.0.0
        request-timeout: 30s
        type: async
```

其一，外部浏览器的启动与网络渲染通常需要消耗数秒乃至十几秒的时间，因此我们明确指定客户端通信类型为异步非阻塞模式。使用异步模式能够避免主应用的业务线程在等待外部返回时产生长时间的挂起。

其二，外部服务的总开关默认设置为关闭状态，并交由环境变量控制。这样可以保证其他不涉及浏览器爬虫的模块在开发时，即便开发者机器上没有配置复杂的脚本环境，整个项目依然能够顺畅启动。

其三，配置文件的具体位置支持通过环境变量覆盖。Windows 开发者可以直接使用默认值，而 macOS 或 Linux 开发者只需在本地的环境变量配置中指定对应的非 Windows 配置文件，就能彻底规避跨平台引起的启动异常。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-08-20260917192604-f2ef3a29.png)

## 04、两大冲突深度拆解

当依赖和配置文件全部就绪之后，按照常规的开发套路，我们只需要在模型组件中把容器自动装配好的异步客户端注入进来，作为默认工具挂载给大模型，整个技术流程就该顺理成章地跑通了。

求职派在数据提取业务中默认接入了 DeepSeek，它本身对函数调用提供了原生支持，非常适合承载复杂的工具调用任务。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-09-20260917192605-d8c5eec1.png)

在最初的原型代码里，我们在模型装配组件中注入了异步客户端列表，并在构建声明式模型客户端时，将这些客户端封装为异步工具提供者注册了进去：

```java
// AI-GENERATED AIDEV-NOTE: 在构建声明式客户端时注册 MCP 异步工具
if (!CollectionUtils.isEmpty(mcpClients)) {
    builder.defaultToolCallbacks(new AsyncMcpToolCallbackProvider(mcpClients));
}
```

可就在我们满心欢喜地提交了一个真实的网页地址后，大模型却完全没有任何调用外部浏览器的迹象，而是直接把链接原封不动地返回了回来。

为了找出真正的问题根源，我顺着数据流向深入追踪了框架底层的交互细节，挖出了导致工具调用彻底失效的两个隐蔽硬伤。

### 避坑点一：客户端注册与底层调用脱节

第一个硬伤来自于 Spring AI 框架的分层抽象与求职派具体业务实现之间的结构性脱节。

在求职派的职位采集场景下，很多招聘页面包含数十条甚至上百条岗位数据。由于大模型的单次输出窗口长度有限，一旦返回的数据量过大，就很容易在输出中途被截断。为此，我们设计了一套基于会话记忆的多轮自动续写机制。当检测到上一次模型响应没有完整闭合时，系统会自动携带历史会话向大模型发起追问，直至完整拿到全部数据。

这就导致了一个极其诡异的现象，代码层面看似已经把工具完完整整地注册到了容器中，但在实际执行多轮追问的底层循环里，发往大模型的请求选项中根本没有携带任何工具信息。大模型收到的上下文里只有历史对话记录，它的工具清单上是空的，自然无法触发任何外部调用。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-04-tools-20260917193140-0f1cd2e1.png)

### 避坑点二：语义竞争导致的决策混乱

在求职派早期的代码库中，我们曾经内置过一个基于轻量网络库的本地抓取工具，专门负责下载纯静态页面并用选择器提取其中的表格节点。当我们把外部浏览器服务成功引入后，本地抓取工具和外部服务被同时呈现在了大模型面前。此时两个工具在语义描述上产生了严重的重叠冲突：本地工具声称自己可以根据网址获取网页表格，外部服务同样声称自己能够导航至网址并提取网页内容。

大模型在面对两个功能极其相似的工具选项时，陷入了选择困难。在实际测试中，大模型有时会随性地挑选本地工具去抓取动态单页应用，结果只能拿到空的结果集；有时在多次权衡后，甚至会直接跳过工具调用，选择用已有知识进行幻觉编造。

### 自适应工具装配与降级设计

厘清了这两个关键痛点后，我们对应用层代码展开了一次针对性的重构。首先，我们在后台管理界面放宽了对输入内容的格式限制，允许用户直接传入包含目标网页链接的非结构化富文本。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-10-20260917192606-e0ce3ece.png)

放开限制后，用户在录入数据时就可以自由黏贴各类复杂的校招推文地址或官网链接：

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-11-20260917192606-69f7ee69.png)

而在后端的业务智能体中，我们重新设计了一套自适应的工具回调装配与平滑降级机制。在组件属性注入阶段，我们使用可选依赖注入异步客户端列表；如果在容器中成功发现了外部客户端，就将其包装为异步工具提供者；如果应用在初始化完成后发现外部服务并未开启，则在后置处理阶段自动回退并初始化本地轻量抓取工具。

```java
// AI-GENERATED AIDEV-NOTE: 优先装配外部 MCP 客户端，无外部服务时优雅降级为本地静态工具
@Autowired(required = false)
public void setToolCallbackProvider(List<McpAsyncClient> mcpClients) {
    if (!CollectionUtils.isEmpty(mcpClients)) {
        this.toolCallbackProvider = new AsyncMcpToolCallbackProvider(mcpClients);
        log.info("----> 将 MCP Server 注册为大模型的回调工具 <-----");
    }
}

@PostConstruct
public void initLocalToolCallback() {
    if (this.toolCallbackProvider == null) {
        this.toolCallbackProvider = new StaticToolCallbackProvider(
                ToolCallbacks.from(new CrawlerTools()));
        log.info("----> 将应用的 CrawlerTools 注册为大模型的回调工具 <-----");
    }
}
```

随后，在底层循环发起原生模型交互的核心位置，我们通过工具调用选项直接从工具提供者中动态提取回调实例，显式绑定到当前调用的提示词选项中：

```java
// AI-GENERATED AIDEV-NOTE: 在底层原生调用中动态绑定工具回调
ChatOptions chatOptions = ToolCallingChatOptions.builder()
        .model(modelPair.getSecond())
        .toolCallbacks(toolCallbackProvider.getToolCallbacks())
        .build();
```

通过这一层自适应装配与动态绑定的设计，我们不仅消除了客户端与底层调用之间的割裂，彻底激活了大模型在底层多轮交互中的工具调用能力，还实现了对外部服务与本地轻量工具的互斥隔离。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-12-20260917192607-8791607d.png)

## 05、真机联调与跨平台踩坑实录

完成了上述底层重构后，终于迎来了激动人心的全流程联调测试。

我们在开发环境配置文件中将客户端开关显式设置为开启，随后重新编译启动求职派主应用，在后台的职位采集工作台里提交一个包含真实招聘链接的抓取任务。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-13-20260917192608-79c01f75.png)

系统运行之后，我们如何确信大模型确实调用了外部浏览器而不是在瞎猜呢？

首先，在后台发起解析请求的数秒之后，操作系统的桌面上会突然弹出一个独立的浏览器窗口，并且窗口顶部会醒目地标出“Chrome 正受到自动测试软件的控制”。这充分表明后端的独立子进程已经被成功激活，正在真实地加载网页中的动态组件。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-14-20260917192608-432b6e27.png)

其次，当外部服务将页面彻底渲染完成并把文本内容回传之后，大模型会准确抽取出原本隐藏在异步渲染列表里的岗位名称、薪资福利、专业要求和投递渠道，并将其自动转换为格式规范的职位草稿保存入库。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-15-20260917192609-255c00d2.png)

一灰在他的 Windows 电脑上进行全流程验证时，从服务拉起到数据抓取一气呵成，没有出现问题。然而当我把代码同步到自己的苹果电脑上，并且使用测试环境模式启动主应用时，控制台却出错了。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-16-20260917192610-f5867f79.png)

仔细翻阅报错日志后才恍然大悟，原来我在配置外部进程文件时，直接把一灰在 Windows 下使用的命令格式搬了过来，导致底层在 macOS 下尝试寻找系统命令解释器时，直接抛出了程序不存在的异常。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-05-checklist-20260917193242-b71c88d8.png)

这就是我们在前文中重点强调的跨平台差异。非 Windows 系统的同学在配置外部服务时，务必将启动命令切换为原生支持的格式，去掉 Windows 特有的前置调用参数：

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-17-20260917192611-92a29bf4.png)

把配置文件修正完毕后重新启动应用，并在数据采集后台重新点击执行刚才失败的任务记录：

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-18-20260917192612-bc57a6d2.png)

这一次，桌面再次弹出了受自动化控制的浏览器窗口，网页内容被完整提取并成功解析。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-client-yuque-19-20260917192613-57a588c3.png)
