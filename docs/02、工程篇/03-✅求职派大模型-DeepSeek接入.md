# 求职派大模型-DeepSeek接入

接下来介绍如何获取 ApiKey 并在项目中完成 LLM 配置。

DeepSeek 的模型清单和计费说明可以在这里查看：https://api-docs.deepseek.com/zh-cn/quick_start/pricing

![](https://cdn.paicoding.com/stutymore/03-✅求职派大模型-智谱接入-20260626152636.png)

求职派默认的文本对话与视觉理解模型都是 `deepseek#deepseek-flash`，也就是 DeepSeek V4.1 Flash，它同时支持文本与多模态输入。

## 一、ApiKey 获取

首先进入官方开放平台：https://platform.deepseek.com/

登录成功之后，进入 ApiKey 管理页面 https://platform.deepseek.com/api_keys，创建新的 API Key：

![](https://cdn.paicoding.com/paicoding/b34259db97ddb0676cef58f8fff5c9c1.png)

![](https://cdn.paicoding.com/stutymore/01-✅求职派本地开发环境搭建指南-20260617170646.png)

保存完之后，在列表中将 ApiKey 复制出来，注意 Key 只在创建时完整展示一次。

## 二、项目配置

求职派的大模型供应商配置统一在后台维护，不再通过环境变量或 IDEA 启动参数配置 API Key。

DeepSeek 走的是 OpenAI 兼容协议，项目里对应的实现是 `providers/openai` 模块的 `OpenAiModelProvider`，它对外声明的 `apiStyle` 就是 `openai`。所以 `application.yml` 里的 deepseek 供应商把 `api-style` 也设成 `openai`，再用 `base-url` 与 `completions-path` 指向 DeepSeek 的接口，就能复用同一套 Provider 抽象完成调用。

启动后进入后台（用户头像点击【后台管理】），打开 **LLM供应商** 页面。

![](https://cdn.paicoding.com/stutymore/03-✅求职派大模型-智谱接入-20260626172134.png)

内置供应商里已经有 DeepSeek（另外还有硅基流动、阶跃星辰），添加供应商时把刚刚复制的 ApiKey 填到 API Key 输入框里。

Base URL 填 `https://api.deepseek.com`，API 风格选 OpenAI，也就是 `api-style: openai`；对话路径 `completions-path` 填 `/chat/completions`，与 `application.yml` 中 deepseek 供应商的默认值保持一致。

模型清单默认会有 `deepseek-flash`（DeepSeek V4.1 Flash，文本 + 多模态）和 `deepseek-v4-pro`（DeepSeek V4 Pro，旗舰文本模型），如果你想使用其他模型，可以在模型清单里添加。

![](https://cdn.paicoding.com/stutymore/03-✅求职派大模型-智谱接入-20260626201455.png)

填完可以点一下【测试连接】，确认通不通。

![](https://cdn.paicoding.com/stutymore/03-✅求职派大模型-智谱接入-20260626202907.png)

保存后，配置会以 `agent.ai.providers.deepseek.*` 的形式写入后端的全局配置表，后续模型调用会直接读取这份配置。对应的配置结构如下：

```yaml
agent:
  ai:
    providers:
      deepseek:
        api-key: ""  # 在后台「LLM供应商」页面填写
        api-style: openai
        base-url: https://api.deepseek.com
        completions-path: /chat/completions
        models:
          - name: deepseek-flash
            type: TEXT
            multimodal: true
          - name: deepseek-v4-pro
            type: TEXT
            multimodal: false
```

用户偏好里的模型写法是 `供应商#模型名`，求职派默认的文本与视觉偏好都是 `deepseek#deepseek-flash`。

如果遇到问题，建议仔细看看教程，或者在 VIP 交流群里问一嘴。我的微信 ID：qing_geee
