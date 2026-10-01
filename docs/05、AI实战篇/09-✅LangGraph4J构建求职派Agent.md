---
title: LangGraph4J 构建求职派多 Agent 工作流
shortTitle: LangGraph4J 构建 Agent
description: 用 LangGraph4J + Spring AI 搭建求职派的四步多 Agent 工作流，从任务分类、数据采集到清洗发布，拆解状态流转、条件分支和 POJO 序列化的落地细节。
keywords:
  - LangGraph4J
  - 多Agent工作流
  - Spring AI
  - 求职派
  - Agent开发实战
tag:
  - Agent
category:
  - AI
author: 沉默王二
date: 2026-09-17
---

求职派 V0.0.2 版本的数据采集流水线看起来已经用上了 AI，大模型负责从原始信息中提取校招数据，结构化之后存入草稿表。

但整条流水线只有这一段是自动的。提取之前的任务分类靠人工在前端选择，提取之后的数据清洗靠人工逐条核对，核对完的发布上架还是人工点按钮。

这篇文章拆解的就是怎么用 LangGraph4J 把这四步串成一条自动化的工作流。任务分类、数据采集、数据清洗、数据发布，每一步交给一个独立的 Agent，状态在节点间流转，条件分支决定走向。读完之后会清楚，Java 生态下的多 Agent 工作流到底怎么搭、怎么传状态、怎么走分支。

## 01、单 Agent 够用吗？

截止到 V0.0.2 版本，求职派的岗位数据从录入到发布一共五步。

![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1755070401858-fb742ce0-bb88-48ec-9fcc-14d5738f2a01.png)

1. 录入采集任务
2. 调度执行采集 Agent（基于 Spring AI）
3. 采集结果保存到草稿表
4. 数据清洗
5. 发布到正式库

其中只有第 2 步是 AI 自动完成的。第 1 步需要用户手动指定输入类型（图片、Excel 还是文本），第 4 步需要人工逐条核对字段，第 5 步需要人工确认后才能发布。

问题不在 AI 能力不够，在于没有把多个步骤串联起来。单个 Agent 能做好一件事，但一条完整的流水线需要多个 Agent 各司其职，每一步执行完还要根据结果决定下一步该不该继续走。

这就是引入 LangGraph4J 的原因。它提供了状态管理和条件分支，能把多个 Agent 组装成一张有向图，每个节点执行完之后根据状态自动决定走哪条路。

![](https://cdn.paicoding.com/stutymore/jobclaw-langgraph4j-01-coverage-20260917193843-364965d3.png)

## 02、四步流水线怎么拆？

思路很直接，围绕校招信息"从进到出"的生命周期拆成四步，每步交给一个 Agent。

![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1755075770381-47ef1a49-bf99-4d76-b252-1ea68e08b437.png)

四个 Agent 的分工如下。

- **任务分类 Agent**，根据输入内容的文件后缀和文本特征，自动判断类型是图片、Excel、CSV、HTML 还是纯文本
- **任务采集 Agent**，调用大模型提取校招信息，将结构化结果存入草稿表，复用了 V0.0.2 版本已有的采集能力
- **数据清洗 Agent**，按照系统枚举和业务规则，对草稿数据做字段映射、格式校验、链接有效性检查
- **数据发布 Agent**，将清洗合格的草稿数据推送到正式库

每个 Agent 只关心自己这一步的输入和输出，边界清晰。

这四步之间不是无条件顺序执行的。任务分类失败了，后面的采集就不应该启动；采集没提取到任何数据，清洗就没有必要跑；清洗之后没有合格数据，发布也可以直接跳过。

这种"根据上一步结果决定要不要往下走"的能力，就是条件分支，也是选择 LangGraph4J 而不是简单的 if-else 串联的原因。

其中任务分类 Agent 的判断逻辑值得单独说一下，它没有调用大模型，而是用纯规则实现的。

![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1755081156215-c7818bc2-119b-428b-941a-e8ca4508c0ee.png)

原来用户提交采集任务的时候，需要在前端手动选择输入类型。现在这一步交给了分类 Agent 自动完成。

判断分两层。第一层看路径前缀，如果内容以 `/oc` 开头，说明是文件附件，按文件后缀来分类。第二层处理文本类型，包含 HTML 标签的归为 HTML 文本，只有一个 HTTP 链接的归为 URL 类型，其余归为纯文本。

用规则而不是 LLM 来做文件类型判断，原因很简单：这件事是确定性的，规则比大模型更快、更准、零成本。后续需要支持新的文件类型时，加一行后缀判断就够了。

![](https://cdn.paicoding.com/stutymore/jobclaw-langgraph4j-02-classification-20260917194037-ea0554a4.png)

## 03、状态怎么在节点间流转？

LangGraph4J 里，节点之间共享数据靠的是一个全局状态对象。每个节点从状态中读取自己需要的输入，执行完后把输出写回状态，下一个节点再从状态里取。

求职派定义了 5 个状态字段，分别对应外部输入和四个 Agent 的输出。

```java
public static final String INPUT = "input";    // 外部输入
public static final String TASK = "task";       // 分类结果
public static final String GATHER = "gather";   // 采集结果
public static final String WASHER = "washer";   // 清洗结果
public static final String PUBLISH = "publish"; // 发布结果
```

每个 Agent 执行完后返回一个键值对集合，LangGraph4J 会自动把它合并到全局状态中。下游节点通过字段名取值就行了。

这种设计让每个 Agent 各自维护一套输入输出，边界清楚、耦合度低。调试的时候只需要看单个节点的输入输出是否正确，不需要在脑子里模拟整张图的状态。

![](https://cdn.paicoding.com/stutymore/jobclaw-langgraph4j-03-state-20260917194233-af44140a.png)

不过这里有一个容易踩坑的地方。LangGraph4J 有自己的序列化协议，默认的序列化器对自定义的 Java 对象支持不太好。如果不做处理，状态在节点之间传递的时候会出现反序列化失败的问题。

求职派的做法是自定义了一个 JSON 序列化器。

```java
public void write(T obj, ObjectOutput out) throws IOException {
    out.writeObject(JsonUtil.toStr(obj));
}
public T read(ObjectInput in) throws IOException, ClassNotFoundException {
    return JsonUtil.toObj(Objects.toString(in.readObject()), type);
}
```

底层用 JSON 把 Java 对象转成字符串再传，可读性好，出了问题也容易从日志里定位。然后在全局状态的序列化器中，为每种业务对象类型注册这个 JSON 序列化器就完成了。

**如果你用 LangGraph4J 传递自定义对象，序列化这一步不能省，跳过它，状态流转到第二个节点就会报错。**

![](https://cdn.paicoding.com/stutymore/jobclaw-langgraph4j-04-serialization-20260917194343-0847150b.png)

## 04、条件分支怎么控制工作流？

Agent 定义好了，状态流转也清楚了，接下来就是把它们组装成一张图。

LangGraph4J 的图由两种元素组成，节点和边。节点就是 Agent，边定义节点之间的流向。边又分两种，无条件边表示直接往下走，条件边表示根据状态判断走哪条路。

求职派的工作流图是这样组装的。

起点连接到分类 Agent，这是一条无条件边。分类 Agent 之后接了一条条件边，检查分类结果是否为空，为空就结束，不为空就走采集 Agent。采集 Agent 之后同样是条件边，检查有没有提取到数据，没有就结束，有就走清洗 Agent。清洗 Agent 之后还是条件边，有合格数据就走发布 Agent，没有就结束。

用代码表达，条件分支的核心是传入一个判断函数。

```java
.addConditionalEdges(分类节点,
    edge_async(state -> 
        state.getTask() == null ? END : "采集"),
    EdgeMappings.builder()
        .to(采集节点, "采集").toEND().build())
```

判断函数检查上一步的输出是否为空，返回对应的分支标签。后面两个节点的条件边结构完全相同，只是检查的字段不同。

项目启动后，LangGraph4J 会自动输出整张图的 PlantUML 描述，粘到在线渲染工具里就能生成流程图。

![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1755082387646-1fce409c-9c97-4457-ae02-47369131ac9f.png)

渲染出来的效果如下，每个节点后面都有一个菱形条件判断，决定是继续往下走还是直接结束。

![](https://cdn.nlark.com/yuque/0/2025/webp/35158118/1755082530591-b22e8064-43b1-433f-9efb-1035975fd065.webp)

![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1755082839135-30cf7b54-22fa-48c9-8b0b-ba44f8107666.png)

条件分支让流程走向由图的拓扑结构决定。新增或修改一个分支条件时，不需要改动其他 Agent 一行代码，整张图自动适配新的走向。

![](https://cdn.paicoding.com/stutymore/jobclaw-langgraph4j-05-branches-20260917194449-612d2b46.png)

## 05、数据清洗 Agent 做了什么？

这是整条流水线中变化最大的一个 Agent。早期版本只是一个占位符，返回了硬编码的 ID 列表；现在已经实现了完整的清洗逻辑。

清洗 Agent 拿到采集结果后，遍历每一条草稿数据，依次执行三类规则。

**公司类型规范化**。大模型提取出来的公司类型可能写成"私企"，但系统枚举里只有"民企"。清洗 Agent 维护了一张映射表，把同义词统一到系统标准值。不在映射表里也没有对应同义词的值会被标记为异常，不传递给下一步。

**招聘类型和招聘对象校验**。"实习"需要映射为更具体的"暑期实习"，招聘对象的年份描述也需要规范化。比如把"届"转换为"年"，自动补全"毕业生"后缀。秋招场景下还有年份自动纠正的逻辑，防止大模型把 2026 秋招的招聘对象错误地标记为 2025 届。

**链接有效性检查**。所有从原文提取出来的链接（投递链接、详情页链接）都会做一次有效性验证，确保能够正常访问。无法访问的链接会被标记，避免发布后用户点进去是 404。

清洗完成后，只有通过了全部校验的数据 ID 才会传给下游的发布 Agent。不合格的数据留在草稿表中等待人工处理。

**数据清洗这一步，决定了最终数据的质量下限。大模型的提取能力再强，没有一道规则来兜底，脏数据迟早会混进正式库。**

![](https://cdn.paicoding.com/stutymore/jobclaw-langgraph4j-06-cleaning-20260917194556-01e6e42e.png)

## 06、怎么跑通全流程？

Agent 和工作流都就绪之后，还需要一个入口来触发执行。

求职派在管理后台暴露了一个接口，接收任务 ID，从数据库查出任务实体，交给工作流执行器。

```java
@GetMapping(path = "/agentRun")
public OcAgentState agentRun(Long taskId) {
    GatherTaskEntity task = gatherTaskService.getTask(taskId);
    return agentExecutor.invoke(task);
}
```

同时还有一个 SSE 流式接口。Agent 执行过程中的状态变化会通过 Server-Sent Events 实时推送到前端，不需要轮询就能看到每个节点的执行进度。

```java
@GetMapping(path = "autoInvoke",
    produces = TEXT_EVENT_STREAM_VALUE)
public SseEmitter autoInvoke(Long taskId) {
    GatherTaskEntity task = gatherTaskService.getTask(taskId);
    AsyncUtil.submit(() -> agentExecutor.invoke(task));
    return new SseEmitter(SSE_EXPIRE_TIME);
}
```

下面是一次完整执行的结果。可以看到四个 Agent 依次执行，状态在节点间传递，最终数据成功发布到了正式库。

![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1755082932893-8b0e0526-1cce-4838-be6b-e44ace5e632e.png)

![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1755082962506-53275018-c800-4530-af42-ab3fe1bec1ba.png)

目前是用接口直接触发的方式来验证全流程。后续可以在前端加上可视化监控，把每个节点的输入输出、执行耗时、分支走向都展示出来，排查问题会方便很多。

![](https://cdn.paicoding.com/stutymore/jobclaw-langgraph4j-07-results-20260917194800-a2de72dd.png)

## ending

【求职派的数据采集流水线，从人工选类型、人工核对、人工发布，变成了四步自动化的工作流。LangGraph4J 管状态和分支，Spring AI 管大模型调用，两者组合起来，Java 生态下的多 Agent 应用就有了一个可以落地的模板。】

状态想清楚，序列化别踩坑，条件分支写明白，多 Agent 工作流的骨架就立住了。

我们下期见。

![](https://cdn.paicoding.com/stutymore/jobclaw-langgraph4j-08-overview-20260917194945-3a967d95.png)

### 如何把求职派写到简历上？

项目名称：求职派（JobClaw）

项目简介：基于多 Agent 架构的智能求职系统，支持微信、钉钉、飞书多渠道接入，通过意图识别和 Agent 路由实现岗位信息自动采集、清洗、发布的全流程自动化。

技术栈：Java 21、Spring Boot 4.0、Spring AI 2.0、LangGraph4J 1.6、H2/MySQL、React 19、Next.js 15

核心职责：

- 基于 LangGraph4J 设计并实现了四步多 Agent 工作流（任务分类、数据采集、数据清洗、数据发布），通过条件分支实现了异常短路和流程自动控制，数据采集到发布的全流程自动化率从 20% 提升到 80%
- 设计了多 Agent 共享状态模型，自定义 JSON 序列化器解决了 LangGraph4J 的 POJO 序列化兼容性问题，支持 5 种业务对象在节点间无损传递
- 实现了基于规则的数据清洗 Agent，覆盖公司类型映射、招聘类型规范化、年份自动纠正、链接有效性验证等 4 类清洗规则，将草稿数据的字段合规率从 60% 提升到 95%
- 基于 Spring AI 封装了统一的大模型调用层，支持 DeepSeek、通义千问等多供应商切换，文本和多模态模型按用户偏好自动解析
- 实现了 SSE 流式推送接口，Agent 执行过程中的状态变化实时推送到管理后台，支持异步任务调度和执行监控

:::success
文章中所列相关代码，可以在 tag 中获取 [https://github.com/liuyueyi/JobClaw/releases/tag/0.0.3](https://github.com/liuyueyi/JobClaw/releases/tag/0.0.3)

相关实现，可以在 [https://github.com/liuyueyi/JobClaw/commit/b96552cb842f170a85b423d491a1685442b0e701](https://github.com/liuyueyi/JobClaw/commit/b96552cb842f170a85b423d491a1685442b0e701) 查看

:::
