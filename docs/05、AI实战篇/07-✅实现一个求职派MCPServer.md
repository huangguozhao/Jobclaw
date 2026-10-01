---
title: 实战求职派MCP Server，让AI助手直接查校招数据
shortTitle: 实现求职派MCP Server
description: 详解求职派基于Spring AI构建MCP Server的完整实践，涵盖早期SSE与当前无状态HTTP配置、工具定义、请求头凭证鉴权，以及在AI客户端中的联调对接。
keywords:
  - Spring AI
  - MCP Server
  - 求职派
  - 模型上下文协议
  - Trae
tag:
  - Agent
  - MCP
category:
  - AI
author: 沉默王二
date: 2026-09-11
---

大家好，我是二哥呀。

求职派在后台积累了大量的校招与实习岗位数据。如果这些数据只能通过网页或者小程序供大家检索，它的价值其实只发挥了一半。

如果我们能把这套职位库做成一个标准的 MCP 服务，让外部的 AI 助手直接按需调用，体验就会完全不同。

无论是 Claude Desktop、Cursor、Trae，还是大家自己搭建的智能体，都可以通过标准协议接入求职派，实时完成岗位筛选与智能推荐。

这篇内容将完整记录求职派首个 MCP 服务的诞生过程，带大家一步步跑通协议配置、工具定义、权限鉴权与客户端联调。

系好安全带，我们粗粗发～

> **当前版本演示说明（2026-09-17）**：正文保留了早期 SSE 实现过程及截图。当前源码默认配置为 `protocol: STATELESS`，客户端应使用 HTTP 传输，地址为 `http://localhost:8087/api/mcp`，不要直接照搬下文的 `/sse` 或 `/api/sse`。在个人中心的“我的会员”页面，会员或管理员可以点击“MCP配置”复制当前配置。当前 MCP 的 Bearer 凭证是用户 `wxId`，不是网页登录的 JWT。网页右下角助手使用 WebSocket，与外部客户端调用 MCP 是两个不同的演示入口。

当前本地管理员演示配置如下；其他用户请使用自己账号生成的凭证。客户端若通过界面选择传输类型，应选择 Streamable HTTP。

```json
{
  "mcpServers": {
    "求职派": {
      "type": "http",
      "url": "http://localhost:8087/api/mcp",
      "headers": {
        "Authorization": "Bearer demoUser-admin"
      }
    }
  }
}
```

本地启动及逐项验收记录见 [MCP 与网页助手演示验收](../mcp-assistant-validation.md)。`demoUser-admin` 仅用于本地开发演示。

## 01、为什么要把求职派做成一个 MCP Server？

在传统开发模式下，如果想把系统的数据能力开放给外部 AI，我们通常需要单独写一套 RESTful 接口。

客户端为了调用这套接口，既要理解参数格式，又要自己编写解析代码。一旦接口定义发生变化，两端都需要同步修改。

MCP 的出现，改变了这种繁琐的对接方式。

它提供了一套基于 JSON-RPC 的统一交互规范，服务端只要声明自己拥有的工具和输入描述，客户端就能在运行时自主发现并完成调用。

我们的一期设计目标非常明确，就是根据用户的个人背景与求职意向，为其推荐匹配的校招机会。

比如用户可以在 AI 助手里直接提问“帮我看看今年有哪些适合计算机专业应届生的深圳岗位”，或者“我想进银行类国企，有什么正在开放的校招吗”。

AI 助手会通过统一协议访问求职派服务端，自动提取用户的查询条件，最终返回结构化的推荐职位。

整个交互过程可以拆分为两个阶段。

首先是配置阶段，用户把求职派的连接端点配置到客户端工具（例如 Trae）中，双方完成握手与能力协商。

随后是使用阶段，用户在客户端发起对话，大模型识别到查询意图后，触发求职派的工具方法并渲染返回结果。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-01-20260917181243-2e3512a7.png)

通过上面的时序图可以看出，客户端与服务端之间解耦得非常彻底。我们只需要专心把服务端的接口和数据逻辑做好，剩下的意图解析与调用编排全部交给大模型来完成。

## 02、Spring AI 下如何快速搭建一个 MCP Server？

在 Spring AI 生态中搭建服务端并不复杂，官方已经为我们提供了高度封装的 Starter 依赖。

第一步，我们需要在工程的依赖管理文件里引入对应的 WebMVC Starter 依赖包。

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

引入依赖之后，Spring Boot 就会自动装配底层的传输通道与请求调度器。

接下来，我们需要在配置文件中声明服务的核心参数与传输端点。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-02-20260917181244-a082ab17.png)

具体的配置内容如下所示。

```yaml
spring:
  ai:
    mcp:
      server:
        name: 求职派
        type: sync
        instructions: "求职派是一个专业的校招信息推荐服务，可以根据您输入的个人求职意愿如求职公司类型、工作地点要求、求职岗位、校招或者实习、毕业年限，自动匹配返回最优的校招信息列表，让你不再错过招聘信息"
        sse-endpoint: /sse
        sse-message-endpoint: /mcp/messages
        version: 1.0.0
        capabilities:
          tool: true # 是否支持工具
          resource: true # 是否支持资源
          prompt: true # 是否支持提示词
          completion: true # 是否支持补全
```

其中参数 `sse-endpoint` 代表客户端建立监听通道的地址，服务端会通过长连接向客户端推送通知。

参数 `sse-message-endpoint` 则是客户端向服务端发送具体调用指令的消息入口。

我们在属性 `instructions` 里用清晰的自然语言描述了求职派的核心能力，这部分内容会在握手阶段直接同步给客户端，帮助大模型快速建立系统上下文认知。

## 03、面向大模型的查询工具该怎么设计？

给大模型写接口和日常写业务接口有一个区别。

平时写接口时，前端传入的参数类型和字段格式通常都是高度确定的规范格式。

大模型调用工具时，参数是由模型根据用户的模糊自然语言提取出来的。

这就要求我们的入参定义必须带有非常详尽的语义说明，指引模型把用户的口语转化成正确的检索值。

我们以用户查询校招岗位的场景为例，提炼出了五个核心维度。

```java
@Data
public class McpReqDto {
    @ToolParam(description = "招聘类型：春招、秋招、秋招提前批、补录、暑期实习、寒假实习、日常实习、社招等", required = false)
    private String recruitmentType;

    @ToolParam(description = "招聘对象：如2025年毕业生、2026年毕业生、2027年毕业生", required = false)
    private String recruitmentTarget;

    @ToolParam(description = "职位名称：如研发工程师、运营等", required = false)
    private String position;

    @ToolParam(description = "职位地点：如武汉、全国等", required = false)
    private String jobLocation;

    @ToolParam(description = "职位类型：如央国企、外企、私企、事业单位、学校、银行等", required = false)
    private String companyType;
}
```

注解 `@ToolParam` 里的内容最终会被转化为 JSON Schema 的属性描述，直接提供给大模型参考。

如果描述写得模糊不清，模型很容易提取出错误的取值，甚至发生调用幻觉。

定义好请求对象之后，我们就可以编写具体的工具类与执行方法。

```java
@Service
public class OcMcpService {
    private final OcService ocService;

    @Autowired
    public OcMcpService(OcService ocService) {
        this.ocService = ocService;
    }

    @Tool(description = "根据输入的用户求职意愿信息，返回满足条件的职位列表给用户")
    public List<OcVo> queryRecommendOcListForUser(McpReqDto req) {
        OcSearchReq search = new OcSearchReq();
        if (StringUtils.isNotBlank(req.getCompanyType())) {
            search.setCompanyType(req.getCompanyType());
        }
        if (StringUtils.isNotBlank(req.getRecruitmentType())) {
            search.setRecruitmentType(req.getRecruitmentType());
        }
        if (StringUtils.isNotBlank(req.getRecruitmentTarget())) {
            search.setRecruitmentTarget(req.getRecruitmentTarget());
        }
        if (StringUtils.isNotBlank(req.getPosition())) {
            search.setPosition(req.getPosition());
        }
        if (StringUtils.isNotBlank(req.getJobLocation())) {
            search.setJobLocation(req.getJobLocation());
        }
        search.setPage(1);
        search.setSize(20);
        PageListVo<OcVo> vo = ocService.searchOcList(search);
        return vo.getList();
    }
}
```

在这里，我们给具体方法标注了注解 `@Tool`，并用一句话讲清楚这个工具的具体职责。

方法内部直接复用了已有的职位检索业务逻辑，把大模型提取的参数组装成内部的查询条件。

我们需要把单次返回的记录条数限制在 20 条以内。如果无节制地把几百条岗位数据全部抛给大模型，很可能会撑爆模型的上下文窗口，造成昂贵的消耗或接口超时。

最后一步，是把这个包含工具方法的 Spring Bean 注册为系统能够识别的回调提供者。

```java
@Configuration
public class OcMcpConfiguration {
    @Bean
    public ToolCallbackProvider dateProvider(OcMcpService dateService) {
        return MethodToolCallbackProvider.builder().toolObjects(dateService).build();
    }
}
```

有了这个配置类，Spring AI 就会在启动时扫描被注解标记的方法，并在握手时把工具元数据暴露给客户端。

## 04、如何通过 Trae 验证 MCP Server？

核心逻辑编写完成之后，我们需要使用真实的客户端工具来进行联调。

这里我们选用 Trae 客户端作为测试工具。

首先打开客户端的 MCP 设置面板，点击添加自定义配置。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-03-20260917181245-04cce75d.png)

初始阶段填写的配置文件内容如下所示。

```json
{
  "mcpServers": {
    "求职派": {
      "type": "sse",
      "url": "http://localhost:8080/sse",
      "version": "1.0.0"
    }
  }
}
```

配置保存之后，我们可以在客户端中基于这个工具配置创建一个测试智能体。

在开始提问之前，先确保本地数据库中已经录入了一批真实的校招岗位数据。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-04-20260917181246-9a5b4821.png)

数据准备妥当后，我们在对话框中输入一段求职意图明确的测试问句。

“我现在是26届毕业的计算机专业学生，我理想的工作地点是北京，想找一些研发的岗位，请帮我推荐一些秋招信息。”

按下发送之后，客户端会自动向求职派的服务端发起请求，并返回匹配好的推荐结果。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-05-20260917181247-193fb3e7.png)

从实测结果来看，大模型精准识别出了我们定义的几个入参字段，成功提取出了北京、2026年毕业生、研发工程师等检索条件。

不过在本地测试中，我们也发现了一个细节问题。

目前服务端的查询采用的是模糊匹配，如果用户随口说了一个“校招”，而数据库字典中记录的都是“春招”或“秋招”，就可能匹配不到合适的数据。

针对这种口语与系统词典的映射差异，后续可以进一步结合向量检索或者意图映射模块来做持续优化。

## 05、没有鉴权的端点怎么防白嫖？

跑通基本功能后，我们很快就意识到了一个严重的工程隐患。

刚才配置的访问端点处于完全公开的状态，任何人只要拿到地址，就可以随意调用我们的职位检索工具。

在公网环境下，这种没有保护的端点容易被恶意脚本刷爆服务器，还会造成求职派私有数据的泄漏。

给端点加上权限校验，是必须完成的一步改造。

求职派的 Web 站点本身已经具备了完善的令牌鉴权体系，我们可以把这套机制沿用到长连接服务中。

客户端在发起请求时，需要在请求头中携带标准的认证信息。

我们选择在请求头中加入 `Authorization` 字段，格式统一采用 `Bearer <token>`。

考虑到求职派现有的微信扫码登录逻辑，我们可以把用户的微信唯一标识作为凭证主体，既能准确识别用户，又不需要额外增加繁琐的建表开销。

我们在权限拦截器中加入针对外部请求的处理逻辑。

```java
private boolean checkMcpPermission(HttpServletRequest request) {
    if (!mcpUrl(request)) {
        return true;
    }
    String auth = request.getHeader("Authorization");
    if (StringUtils.isBlank(auth) || !auth.startsWith("Bearer ")) {
        return false;
    }
    String token = auth.substring(7);
    UserBo user = SpringUtil.getBean(UserService.class).getUserByWxId(token);
    if (user == null) {
        return false;
    }
    ReqInfoContext.getReqInfo().setUserId(user.userId());
    ReqInfoContext.getReqInfo().setUser(user);
    return true;
}
```

这段逻辑会对请求的路径进行判断，一旦发现是外部协议端点，就必须从请求头中提取认证令牌。

如果令牌为空或者在系统用户表中找不到对应记录，拦截器会立刻拒绝这次握手请求。

只有通过验证的合法请求，系统才会把当前用户信息绑定到线程上下文中，供后续的业务逻辑读取。

由于求职派现有的拦截器默认只对带有 `/api` 前缀的请求生效，为了统一安全管控范围，我们需要把配置文件中的端点路径做一次规范调整。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-06-20260917181248-b055682a.png)

调整之后的配置文件如下所示。

```yaml
spring:
  ai:
    mcp:
      server:
        name: 求职派
        type: sync
        instructions: "求职派是一个专业的校招信息推荐服务，可以根据您输入的个人求职意愿如求职公司类型、工作地点要求、求职岗位、校招或者实习、毕业年限，自动匹配返回最优的校招信息列表，让你不再错过招聘信息"
        sse-endpoint: /api/sse
        sse-message-endpoint: /api/mcp/messages
        version: 1.0.0
        capabilities:
          tool: true
          resource: true
          prompt: true
          completion: true
```

把端点统一规范到统一前缀之下，既不需要额外新增繁杂的路由规则，又能让所有的外部调用都纳入统一的安全拦截流程中。

## 06、带鉴权的 MCP Server 联调踩坑实录？

服务端调整了访问端点并开启了鉴权校验，客户端的配置也需要同步更新。

大家在客户端的配置文件中找到对应的服务项，把端点路径与请求头信息补充完整。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-07-20260917181249-a2307eae.png)

更新后的配置信息如下所示。

```json
{
  "mcpServers": {
    "求职派": {
      "type": "sse",
      "url": "http://localhost:8087/api/sse",
      "version": "1.0.0",
      "headers": {
        "Authorization": "Bearer demoUser-admin"
      }
    }
  }
}
```

配置修改并保存后，如果能在客户端的服务状态列表里看到醒目的绿色对号，就说明握手校验已经顺利通过了。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-08-20260917181249-656e0983.png)

为了更清楚地观察调用时的参数传递情况，我们在服务端的执行入口处加上了一行详细的日志输出。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-09-20260917181250-c52b8986.png)

回到客户端界面，我们选择带有工具调用能力的模式，重新输入刚才的求职问题。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-10-20260917181251-c6aef1ca.png)

客户端很快识别出了匹配的工具，并在界面上弹出了调用确认提示。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-11-20260917181253-626b6801.png)

点击运行之后，服务端控制台立刻打印出了对应的用户身份与提取出来的参数信息，数据成功返回到了前端界面。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-12-20260917181254-e3c14c9e.png)

如果小伙伴在联调这一步发现没有跑通，通常是因为端点路径拼写有误，或者请求头里的凭证格式存在疏漏，对照上面的示例仔细检查一遍就能解决。

不过在连续测试时，我们遇到了一个非常诡异的现象。第一次调用完全正常，紧接着发起第二次调用时，却迟迟拿不到返回结果。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-13-20260917181255-512c7798.png)

翻看日志并深入排查底层实现后，我们发现问题出在通信会话的状态管理机制上。

在早期的开发包实现中，长连接端点与消息交互端点之间依赖保存在内存中的会话状态。

一旦服务发生重启，或者连接发生抖动重连，底层的会话上下文就会丢失，导致后续的指令因为找不到初始化会话而被直接丢弃。

针对这个深层问题，求职派后来专门设计了会话持久化与状态自动恢复机制，把握手阶段的上下文妥善存储到了数据库中。

这部分实战踩坑与解决方案非常硬核，我们在后续的面试专题里单独做了一期深度拆解。

## 07、怎样让普通用户也能一键接入 MCP？

到这里，整个服务的功能与安全机制都已经完备了，但从产品体验的角度来看，依然存在一个显而易见的门槛。

对于刚刚接触此类工具的普通求职者来说，手动编写复杂的配置代码实在太折磨人了。

很多人甚至不知道端点地址该怎么填，请求头该写什么内容，一不小心漏掉一个逗号就会导致连接失败。

真正好用的产品，应当尽可能把复杂的配置细节隐藏在后台，把最简单的操作交给用户。

我们在求职派的用户中心专门新增了一个配置接口，后端会根据当前登录用户的身份，自动拼装好属于该用户的完整配置片段。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-14-20260917181256-5c4a324d.png)

前端界面也同步做好了交互适配。

用户登录求职派之后，进入个人中心点击对应的设置入口，系统就会弹出一个卡片，展示已经自动带入用户专属凭证的配置内容。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-15-20260917181257-42cb56d1.png)

用户只需要点击右上角的一键复制，直接粘贴到自己的客户端中就能立刻完成对接，再也不用担心手动拼写出错的问题了。

## ending

这篇文章完整复盘了求职派第一个协议服务的搭建过程。

从最基础的工具方法定义，到面向生产环境的权限控制，再到最后的用户体验打磨，每一步都在回答一个核心问题，怎么让原本只能人肉点击的业务系统，安全高效地变成 AI 的超级外挂。

【把私有业务数据开放给大模型，协议对接往往只是第一步，权限边界和会话稳定性才是真正决定能不能跑进生产的关键所在。】

这大概就是工程落地的魅力所在，看着自己写出来的服务被 AI 助手熟练调用并精准给出结果，那一刻还是非常有成就感的。

### 如何把求职派写到简历上？

如果大家在求职过程中准备把求职派的这套实战经验写进简历，可以参考下面整理好的包装思路。

项目名称：求职派智能协同求职平台

项目简介：面向校招求职场景的智能服务平台，基于 Spring AI 与模型上下文协议打造开放服务生态，支持外部大模型客户端与自研智能体高效检索并推荐校招职位数据。

技术栈：Java 21、Spring Boot 4、Spring AI、Model Context Protocol (MCP)、Server-Sent Events (SSE)、Reactor、JWT。

核心职责（最少五条，量化清晰）：

- 基于 Spring AI WebMVC Starter 搭建标准协议服务端，对外暴露职位检索与意图推荐工具，支持 Claude Desktop、Trae 等多种外部 AI 客户端无缝对接。
- 针对大模型参数提取特征，结合注解语义规范重构接口入参模型，通过精准的字段边界描述将大模型入参幻觉率降低至 5% 以下。
- 设计基于 HTTP 请求头的无状态认证方案，将微信登录凭据无缝映射至内部身份上下文，实现私有职位数据接口的细粒度鉴权隔离。
- 深入分析长连接与请求分发机制，排查并定位服务重启后因内存会话状态丢失导致的请求失效缺陷，通过上下文持久化与自动握手补偿方案确保连接高可用。
- 优化开发者与终端用户接入体验，在管理后台提供自适应的配置自动生成与一键复制功能，将外部客户端的联调配置耗时缩短至 1 分钟以内。

希望通过这篇梳理，大家能对模型上下文协议的服务端实现建立起清晰完整的认知。

为了方便大家对照验证，项目仓库中打了一个对应的版本标签，大家可以直接参考源码。

👉 [https://github.com/liuyueyi/JobClaw/releases/tag/0.0.2](https://github.com/liuyueyi/JobClaw/releases/tag/0.0.2)

如果大家对相关技术栈有更多想要了解的内容，推荐阅读系列配套文档。

阅读过程中遇到任何疑问或者有想探讨的心得，也欢迎随时交流。

![](https://cdn.paicoding.com/stutymore/jobclaw-mcp-server-yuque-16-20260917181258-38ab108a.png)

下期见～
