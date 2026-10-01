# 教练参考：成品源码映射

> 本文包含实现线索。建议在对应期次完成第一版后再阅读。成品代码是比较对象，不是唯一答案。

| 期次 | 成品中的主要参考位置 | 对照重点 |
|---|---|---|
| 00 | `pom.xml`、`app/`、`ui-react/`、`docs/getting-started.md` | Profile、H2/MySQL、静态前端装配 |
| 01 | `app/.../oc/`、`ui-react/components/jobs/` | 岗位实体、查询、公开展示 |
| 02 | `app/.../oc/dao/entity/OcDraftEntity.java`、`web/controller/admin/` | 草稿与正式岗位的分离 |
| 03 | `app/.../user/`、`core/.../apis/context/` | 用户角色、请求上下文、个人数据 |
| 04 | 根 `pom.xml`、`core/`、`channels/`、`providers/`、`plugins/`、`agents/` | Maven 模块依赖和装配层 |
| 05 | `core/.../channel/`、`core/.../bus/` | 内部消息、渠道接口和事件 |
| 06 | `channels/jobclaw-websocket/`、`ui-react/components/chat/` | STOMP 会话与前端流式状态 |
| 07 | `core/.../providers/`、`providers/openai/`、`providers/ali/`、`providers/anthropic/` | 模型引用与适配器差异 |
| 08 | `core/.../agent/llm/`、`core/.../agent/react/`、`core/.../tools/` | 调用层次、工具循环和中间事件 |
| 09 | `core/.../router/`、`core/.../cli/`、`core/.../agent/BizAgent.java` | 注册、分类、绑定和命令 |
| 10 | `core/.../agent/memory/` | YAML 持久化、窗口、摘要和情景记忆 |
| 11 | `agents/identity-collector-agent/`、`core/.../preference/` | 三层画像和个人模型偏好 |
| 12 | `agents/job-fetch-agent/.../extractor/`、`crawler/`、`service/` | 多格式提取器和网页抓取 |
| 13 | `core/.../agent/extraction/`、`agents/job-fetch-agent/`、`app/.../gather/` | 批量结构化提取、任务与证据 |
| 14 | `plugins/job-library/`、`agents/job-recommend-agent/` | 职位库工具与推荐智能体 |
| 15 | `core/.../tasks/`、`plugins/plan-notebook/` | 文件任务、周期处理和计划模式 |
| 16 | `channels/wechat-clawbot/`、`channels/dingding/`、`channels/feishu/` | 平台适配、账号绑定与媒体 |
| 17 | `core/.../configuration/`、`core/.../monitor/`、`app/.../llm/` | 配置刷新和 LLM 调用记录 |
| 18 | 全仓库测试、配置与启动脚本 | 综合风险与尚未生产化的部分 |

## 对照时应回答的问题

1. 成品把复杂度藏在哪个模块后面？调用方需要知道多少规则？
2. 成品建立的接口是否真的存在两个适配器，还是为了未来假设而抽象？
3. 测试是否通过模块接口验证业务结果，还是绑定内部实现？
4. 哪些做法适合个人项目，但在更大规模下需要迁移？
5. 你的方案在哪些需求上更简单或更可靠？证据是什么？

## 已观察到的成品特点

- 成品已经形成 `Channel`、`BizAgent`、`ModelProvider`、`LlmCaller` 等关键接口，并通过主应用组装具体模块。
- 消息主链路包含身份采集、系统命令、会话绑定、意图识别、Agent 路由和回退调用，适合在第 09 期后做完整时序对照。
- 会话和部分任务偏向文件系统存储，岗位、用户、配置和监控偏向关系数据库，值得重点讨论一致性与规模边界。
- 当前自动化测试数量明显少于生产代码规模。练习版本应把验收测试作为独立能力建设，而不是复制现有测试密度。
- 成品仍有历史业务与新运行时并存的痕迹。练习时不必复制所有旧功能，应以每期业务闭环和清晰模块接口为主。

