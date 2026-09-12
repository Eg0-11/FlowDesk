# ADR 0001：DeepSeek 采用 OpenAI 兼容传输

- 状态：已接受
- 日期：2026-09-12
- 决策范围：FlowDesk 主服务的模型接入方式（FD-0002）

## 背景

FlowDesk 的模型提供方是 DeepSeek。项目版本基线固定为：

| 组件 | 版本 |
| --- | --- |
| Spring Boot | 3.5.8 |
| Spring AI | 1.1.2 |
| Spring AI Alibaba | 1.1.2.2 |

Spring AI 1.1.2 同时提供两条接入路径：原生 DeepSeek 适配器和 OpenAI 兼容适配器。
本阶段需要一条「模型调用本地只读工具，再基于工具结果生成最终回答」的完整闭环。

约束来自 DeepSeek 当前的模型行为：

1. 当前 DeepSeek 模型默认开启 thinking（思维链）。
2. 携带工具的后续轮次要求把上一轮的 `reasoning_content` 完整回传。
3. 项目固定的 Spring AI 1.1.2 原生 DeepSeek 适配器无法配置当前 thinking 参数，
   其工具续轮的消息序列化也无法满足「完整回传 reasoning_content」这一要求。
4. Spring AI 1.1.2 的 OpenAI 适配器支持 `extra-body`，可以显式发送：

   ```yaml
   thinking:
     type: disabled
   ```

## 决策

本阶段使用 `org.springframework.ai:spring-ai-starter-model-openai`，
通过 DeepSeek 的 OpenAI 兼容 Chat Completions 接口访问 DeepSeek，
并在配置中显式关闭 thinking：

```yaml
spring:
  ai:
    model:
      chat: openai
    openai:
      base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        completions-path: /chat/completions
        options:
          model: ${DEEPSEEK_MODEL:deepseek-flash}
          extra-body:
            thinking:
              type: disabled
```

**不使用** `org.springframework.ai:spring-ai-starter-model-deepseek`。

需要强调：模型提供方仍然是 DeepSeek，改变的只是**传输适配器**（OpenAI 兼容协议），
不是模型、不是供应商、也不是账号。

模型名通过 `DEEPSEEK_MODEL` 注入，默认 `deepseek-flash`；不把旧模型名写死在代码里。

## 影响

正面：

- 绕开原生适配器在 thinking 参数与工具续轮序列化上的限制，本阶段可以稳定跑通工具闭环。
- 传输层是业界事实标准，后续接入其他 OpenAI 兼容提供方时复用成本低。
- thinking 显式关闭后，单轮工具闭环的行为是确定的，便于验收与排查。

负面 / 代价：

- 无法使用原生适配器独有的 DeepSeek 特性（例如原生推理相关字段的直接映射）。
- DeepSeek 若调整 OpenAI 兼容接口的细节，需要跟随适配。
- `extra-body` 是「透传字段」，其正确性依赖提供方契约，缺少编译期保护 ——
  因此本任务以测试断言实际发出的请求体确实包含该字段。

## 被否决的备选方案

| 方案 | 否决原因 |
| --- | --- |
| 使用原生 `spring-ai-starter-model-deepseek` | 1.1.2 无法配置 thinking 参数，工具续轮无法完整回传 `reasoning_content` |
| 保持 thinking 开启并自行补齐 `reasoning_content` 回传 | 需要绕过 Spring AI 的消息模型自行拼装续轮请求，超出本阶段范围且脆弱 |
| 升级 Spring AI 版本 | 本阶段明确冻结版本基线，升级需独立任务验证 |

## 实施中发现的问题（FD-0002 期间实测）

`extra-body` 在携带工具的请求上**不会真正到达请求体**，这是 Spring AI 1.1.2 的缺陷：
`OpenAiChatModel.createRequest(Prompt, boolean)` 在解析出工具定义后，会再执行一次
`ModelOptionsUtils.merge` 把 `tools` 数组并进请求体。这次合并会把已经写入请求的 `extraBody` 清空。
实测（用同一套 1.1.2 制品复现）：

```
merge(options, request)              → {"messages":[],"model":"deepseek-flash","thinking":{"type":"disabled"}}
merge(emptyOptions, 上一步的结果)      → {"messages":[],"model":"deepseek-flash"}
```

也就是说：普通聊天路径 `extra-body` 有效，一旦注册工具就失效 —— 而工具调用正是必须关闭
thinking 的场景。若不做处理，本 ADR 想要规避的 `reasoning_content` 续轮问题会在工具路径上重新出现。

**绕行方案**：`flowdesk-infrastructure` 中的 `DeepSeekThinkingDisabledInterceptor`
挂在 Spring Boot 自动配置的 `RestClient.Builder` 上（Spring AI 的 OpenAI 适配器正是用它构造
`OpenAiApi`），在 `/chat/completions` 请求发出前把 `thinking.type=disabled` 补回请求体。
这样仍然使用自动配置出来的 `ChatModel`，只在 HTTP 层修正请求体。

该行为由 `ToolCallingLoopTests::sendsThinkingDisabledAndConfiguredModelOnTheWire` 断言：
它用一个合成 OpenAI 兼容端点抓取真实请求体，确认携带工具时该字段依然在线。

**清理条件**：Spring AI 修复上述合并缺陷后，删除 `DeepSeekTransportConfiguration`
与 `DeepSeekThinkingDisabledInterceptor` 即可，其余代码无需改动。

## 实施中发现的第二个问题：上游故障的失败时效

Spring AI 的默认重试策略为 `max-attempts=10`、`backoff.multiplier=5`、
`backoff.initial-interval=2000ms`，累计退避可达约 4 分钟。
实测：停掉上游后，客户端在 300 秒内**没有**收到 502，只会等到自身的请求超时 ——
即「DeepSeek 调用失败 → HTTP 502」这一契约在真实故障下形同虚设。

**处理**：`deepseek` profile 显式收窄为有界重试（最多 3 次、
初始 500ms、倍率 2、上限 2000ms），典型连接类故障约 1.5 秒即返回 502。
该时效由 `ProviderFailureBoundedTests` 断言，避免日后被无意识地放宽。

## 重新评估条件

满足以下任一条件时重新评估是否切回原生适配器：

1. Spring AI 版本升级，且其原生 DeepSeek 适配器能够配置 thinking 并提供满足工具续轮要求的消息序列化。
2. DeepSeek 调整默认 thinking 行为，或明确不再要求续轮回传 `reasoning_content`。
3. FlowDesk 需要 DeepSeek 原生适配器独有的能力，且该能力成为刚需。
