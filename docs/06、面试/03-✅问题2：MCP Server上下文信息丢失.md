# 03-✅问题2：MCP Server上下文信息丢失

这一个是在上一篇抛出来的 [✅问题分析：服务重启导致MCP连接会话丢失](https://www.yuque.com/itwanger/yyt72l/asc9b3zl0434iwhw) ，在McpServer执行时，发现获取线程上下文信息为空


![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1760443666713-c51dee67-2bed-490b-ba5c-2c108923dfc0.png?x-oss-process=image%2Fwatermark%2Ctype_d3F5LW1pY3JvaGVp%2Csize_52%2Ctext_5p2l5LqM5ZOl57yW56iL5pif55CD77yM5LiA6LW36L-b5q2l%2Ccolor_FFFFFF%2Cshadow_50%2Ct_80%2Cg_se%2Cx_10%2Cy_10%2Fformat%2Cwebp)


这个问题就有点蛋疼了，极大的阻碍了我们将现有的服务包装为McpServer的改造进程。如果线程上下文信息都拿不到，那么很多关于访问者的判定，就都会有问题


### 1.先搜一下，有没有踩坑检验可以借鉴
在SpringAI的项目上我也找到了类似的讨论：

+ [https://github.com/spring-projects/spring-ai/issues/2506](https://github.com/spring-projects/spring-ai/issues/2506)

这个讨论中，主要围绕的是用户认证的信息丢失；求职派的具体实现和他们还有点区别，虽然我们也是通过 Http Auth来进行身份认证，但是上下文信息我们是由自己管理的 ReqInfoContext 来维护的，在这个自定义的线程上下文中，我们借助阿里的TTL来实现主子线程的上下文传递


### 2.debug分析问题
因此我们这里直接针对求职派的具体问题来分析，通过debug/关键日志，我们可以得到以下关键信息

+ 在权限判定的拦截器中，可以正确拿到上下文
+ 在响应McpClient请求，做method分发的 WebMvcSseServerTransportProvider 中我们也可以正确拿到上下文
+ 在 McpAsyncServer的执行链路中，我们也可以正确拿到上下文
+ 但是在 McpServer 的执行逻辑中，因为切换到子线程 `boundedElastic-X`中，拿不到上下文 （但是，请注意，某些情况下又可能拿到，因为线程的复用；通常 boundedElastic-1 对应的子线程就拿不到，想要复现，就重启后端服务，立马发起Mcp调用）


### 3.确认问题原因
基于上面的表现，经验丰富的小伙伴，很容易就能定位到问题原因了，因为 `boundedElastic-1`对应的线程池没有被TTL托管，这就导致我们使用TTL维护的上下文，可能无法正确的被子线程获取到


### 4.制定解决方案
对应的解决方案也很明确：使用TTL托管 `boundedElastic-1`对应的 Executor

### 5.编码实现


要使用 TTL 包装的调度器替代 Reactor 的默认调度器（如 `Schedulers.boundedElastic()`），核心是通过** Reactor 提供的调度器装饰机制**，全局拦截默认调度器的创建，自动用 TTL 增强后的版本替换。这样无需在代码中逐个修改调度器的使用，实现 “一次配置，全局生效”。

#### 实现原理
Reactor 的 `Schedulers` 类提供了 `addExecutorServiceDecorator` 方法，允许在调度器底层的 `ExecutorService` 创建时对其进行装饰。通过注册一个装饰器，我们可以：

1. 识别默认调度器（如 `boundedElastic`）的创建过程；
2. 用 `TtlExecutors` 对其底层线程池进行包装，使其具备 TTL 上下文传递能力；
3. 确保所有通过 `Schedulers.boundedElastic()` 获取的调度器都是 TTL 增强版。

#### 实现方案
```java
// 注册ExecutorService装饰器，针对boundedElastic类型的调度器
Schedulers.addExecutorServiceDecorator(
        "boundedElastic", // 目标调度器的名称（与Reactor默认boundedElastic名称匹配）
        (schedulerName, executorService) -> {
            // 用TtlExecutors包装原始线程池，增强TTL上下文传递能力
            log.info("schedulerName -> {}", schedulerName);
            return TtlExecutors.getTtlScheduledExecutorService(executorService);
        }
);
```


为了确保这段逻辑会优先于Reactor的默认执行顺序，我们将它放在项目启动之初


![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1760611217922-23868e87-41db-4614-876a-cafe6010d3e3.png)

### 6.测试验证
重启求职派，然后尝试调用mcp server；下图是一个测试case，其中MCP Client是idea装的通义灵码插件 (MCP的配置方式同 Qoder)


![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1760611349030-d58459f2-d860-4691-a569-7171268f6d55.png)

### 7.小结
对于后端服务而言，上下文传递也是一个非常基础的能力；那么这种基础性要求，结合MCP又会出现什么样的问题，在当前SpringAI还在快速更新的时候，谁也说不准（如果没有真正的体验过，也很难编出一些问题）；这不，我们这里又给大家提供了一个典型的素材


在McpServer的实现过程中，除了前面说到的后端重启之后McpClient会话信息丢失之外，我们还有两个影响深刻的场景

1. 如何做用户身份鉴权
    1. 我们实现的接口不希望被白嫖，同样也出于审计、监控、统计等考虑，我们希望对McpClient的调用进行权限验证；因为MCP实际上是HTTP的上层应用协议，因此在求职派中，我们使用了标准的Http `Authorization` 来进行权限管控；
    2. 结合自定义的拦截器，在每次请求之前，先校验用户身份，如果满足要求才准许执行后续的逻辑；
2. 异步McpServer中，上下文信息丢失的问题
    1. 使用异步的McpServer方案时，我们发现在McpServer提供的实现方法中，可能拿不到保存用户请求的上下文信息
    2. 我们通过搜索相关的案例、本地问题模拟复现、添加关键的链路日志、单步debug等方式，找到出现这个问题的原因是：McpServer在执行McpServer提供的工具方法时，是基于Reactor的响应式编程实现的，在一个子线程中调度了我们的McpServer方法，虽然我们的用户上下文是TTL进行包装的，但是因为Reactor的线程池没有被TTL包装，这就导致了用户上下文信息的丢失
    3. 针对上面这种问题，我们借助Reactor提供的装饰器机制，用 `TtlExecutors` 对其底层线程池进行包装；从而解决上面这个问题


下面是扩展知识点，TTL的原理

:::color2
TransmittableThreadLocal（TTL）是解决多线程场景下上下文传递的工具类，核心解决 **“主子线程上下文继承”** 和 **“线程池复用线程时上下文隔离与传递”** 两大问题。


以下是其工作原理的简洁说明：

#### 一、核心问题：普通 ThreadLocal 的局限性
1. **主子线程场景**：普通`ThreadLocal`的上下文无法被子线程继承（子线程有独立的`ThreadLocal`副本）。例：主线程设置`ThreadLocal.set("value")`，子线程`get()`会返回`null`。
2. **线程池场景**：线程池复用线程时，`ThreadLocal`的上下文会残留（前一个任务的上下文可能被后一个任务读取到）。例：线程 A 执行任务 1 时设置`ThreadLocal.set("task1")`，复用线程 A 执行任务 2 时，`get()`可能仍获取到`"task1"`。

#### 二、TTL 的解决方案
##### 1. 主子线程上下文传递
+ **机制**：基于`InheritableThreadLocal`（可继承的 ThreadLocal）增强，确保子线程创建时自动复制父线程的 TTL 上下文。
+ **过程**：
    - 主线程设置`TTL.set("value")`；
    - 子线程启动时，TTL 自动将主线程的上下文复制到子线程的 TTL 中；
    - 子线程可直接通过`TTL.get()`获取主线程的上下文。

##### 2. 线程池场景上下文传递
+ **核心**：通过`TtlRunnable`/`TtlCallable`包装任务，实现 “上下文快照 + 注入 + 恢复” 的闭环。
+ **过程**：
    - ① **任务提交时**：`TtlRunnable`捕获当前线程的 TTL 上下文，保存为 “快照”；
    - ② **任务执行时**：将 “快照” 注入到线程池的工作线程中（覆盖工作线程原有的 TTL 上下文）；
    - ③ **任务执行后**：恢复工作线程原有的 TTL 上下文（避免影响后续复用该线程的任务）。

:::


