# MCP 与网页助手演示验收

验收日期：2026-09-17。范围是本机 MySQL 环境、网页助手和第 07 篇文章的当前版本 MCP 演示。没有提交、推送或发布到线上。

## 本地入口

- 已同步的截图演示入口：http://localhost:8087/ 。前端开发入口：http://localhost:3000/ 。
- 健康检查：http://localhost:8087/actuator/health 。
- MCP：无状态 Streamable HTTP，`http://localhost:8087/api/mcp`。
- 网页聊天：STOMP over WebSocket，`/ws`，JWT 放在 CONNECT 的 `X-OC-TOKEN` 中。
- MCP：Bearer 使用用户的 `wxId`，与网页登录 JWT 不同。开发管理员是 `demoUser-admin`，仅限本地演示。
- 用户确认后已将新版静态前端同步到 8087，并验证首页内容与构建产物一致、首页引用的静态资源全部返回 HTTP 200。8087 真实浏览器复测显示“已连接”，实际调用职位库并返回中国一汽，控制台无错误。原静态文件已备份到本机 `/tmp/jobclaw-static-backup-20260917-191544.tar.gz`。

后端从仓库根目录启动：

```bash
./mvnw -o -Ptest -pl app spring-boot:run
```

修改公共模块后，先从根目录安装模块，再启动：

```bash
./mvnw -o -Ptest -DskipTests install
```

前端在 `ui-react/` 运行 `pnpm dev`。本次已经运行，截图时无需重复启动。

## 修复与验证结果

| 项目 | 实测结果 |
| --- | --- |
| MySQL 用户偏好表 | Liquibase 创建 `ai_user_preference`，重复启动不重复建表 |
| 未登录聊天 | 显示“请先登录”，提供登录入口，禁用发送 |
| 登录与消息收发 | 普通用户、管理员均能建立连接并收到命令回复 |
| 新用户引导 | Soul 与 Identity 收集能够回复并落盘；普通演示用户已完成虚构资料引导 |
| 通用问答 | 实际模型调用可回复；空 Skills 目录不再中断调用 |
| 岗位推荐 | 真正调用职位库工具；北京＋2026＋研发＋秋招命中中国一汽 1 条 |
| 排版与链接 | 保留流式空格和换行，渲染 Markdown 列表、表格、可点击链接；表格可横向滚动 |
| 重连与账号隔离 | 服务停机显示重连并禁用发送，重启恢复；不同用户消息队列和本地历史隔离 |
| 切换 Agent | 按服务端确认更新标签，重连后查询真实绑定；移除未注册的画像、订阅快捷入口 |
| 偏好设置 | 无个人偏好记录时可查询继承的默认模型；修改不污染全局默认项 |
| 上传 | 未登录、跨用户、空文件均拒绝；成功响应不再重复包装 |
| 文本附件 | 文本内容送给文本模型，实测读出虚构校验码 `JOBCLAW-20260917` |
| 图片附件 | PNG 图片通过视觉输入，实际识别从左到右红、绿、蓝；可与文本附件同时发送 |
| 模型统一 | 文本与视觉都是 `deepseek#deepseek-flash`，多模态标记为 true |
| MCP 配置弹窗 | 正确显示 HTTP 地址、用户 Bearer 凭证；复制后可实际粘贴 |
| MCP 协议 | 初始化、工具发现、五种筛选、空条件、全国、无结果、重复调用、无效工具和鉴权已验证 |

网页附件当前明确支持 TXT、Markdown、CSV、JSON、Log（单个 1 MB 以内），以及 PNG、JPEG、GIF、WebP（单个 5 MB 以内）。PDF、Word 的通用聊天解析不在本次验收范围。

## DeepSeek 多模态配置残留的原因

主配置已经使用 DeepSeek V4.1 Flash，但数据库 `global_env_config` 的默认视觉偏好仍为 `zhipu#glm-4.6v-flash`；本机忽略跟踪的 `application-private.yml` 也有旧默认值。数据库配置优先级高于 YAML，因此只看源码默认值不能判断实际调用模型。

另外，后台前后端原先都根据 `type == VISION` 重算多模态标记，会把 `TEXT` 类型的 `deepseek-flash` 保存成 `multimodal=false`。视觉选择器和后端校验也没有接受支持视觉的 TEXT 模型。

已补充数据库迁移，修正本机私有配置默认值，保留多模态标记，并允许支持图片的 TEXT 模型被选为默认视觉模型。实际图片调用成功。官方模型能力说明见 [DeepSeek Vision 文档](https://api-docs.deepseek.com/guides/vision/)。

## 可重复执行的检查

```bash
# 仓库根目录；Node 22，先安装 ui-react 依赖
node scripts/verify-assistant-mcp.mjs

./mvnw -o -Ptest -pl app -am \
  -Dtest=WebSocketChannelTest,WsAuthChannelInterceptorTest,UserPreferencePromptTest,LlmRspCellTest,TextAttachmentTest,PreferenceSettingBizAgentTest,AdminMultimodalConfigTest \
  -Dsurefire.failIfNoSpecifiedTests=false install

# ui-react 目录
pnpm exec tsc --noEmit
pnpm build
```

自动接口检查共 28 项；Java 定向测试共 11 项。另有真实浏览器中的 LLM 问答、岗位推荐、混合附件、复制、登录退出、最小化、关闭重开和断线恢复验证。接口脚本会使用本地开发账号、上传一份虚构文本，并切换演示账号的会话 Agent，建议在截图前运行，避免测试命令混入截图。

## 截图顺序

1. 网页首页，普通用户登录，打开助手，确认显示“已连接”。首次使用的新账号先完成助手风格与求职资料引导。
2. 点击“岗位推荐”，输入：“请只查询北京、2026年毕业生、秋招、研发岗位，不要放宽条件。用简洁列表展示公司、岗位、地点和投递链接。”
3. 展开“工具调用”或“执行结果”，截图实际查库证据；折叠后截图最终推荐结果。
4. 管理员登录，个人中心 → 我的会员 → MCP配置，截图当前 HTTP 配置。
5. 外部 MCP 客户端选择 Streamable HTTP 并粘贴配置。可发现 `queryRecommendOcListForUser`，参数位于 `req` 内。

第 07 篇正文中的 `/api/sse` 和 Trae 截图属于早期 SSE 版本，本次保持当前 STATELESS 配置。真实 HTTP 协议调用已经验证；本次没有操作 Trae GUI，也没有把历史 SSE 会话恢复描述当作已验收功能。网页助手使用 WebSocket，不能把网页截图标注为外部 MCP 客户端调用截图。
