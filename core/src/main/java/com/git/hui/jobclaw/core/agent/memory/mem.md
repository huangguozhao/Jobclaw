# JobClaw 上下文管理方案

## 方案概述

当前的会话上下文采用 `MessageWindowChatMemory.builder().chatMemoryRepository(chatMemoryRepository).build()` 的方式，使用Yaml文件保存会话历史。

这里只实现了会话级别的记忆管理，对于上下文窗口的实现，还需要考虑上下文膨胀、对话历史压缩等问题。

---

## 简化方案（当前实施）

### 设计理念

**核心原则**：简单、易理解、效果好，避免过度设计。

基于现有SpringAI基础设施进行增强，而非完全重写。通过智能滑动窗口解决80%的上下文膨胀问题，1-2天即可见效。

### 方案架构

```
┌─────────────────────────────────────┐
│   Smart Window (智能滑动窗口)        │  ← 保留最近N轮完整消息
│   - 可配置窗口大小                   │
│   - 简单规则过滤无用消息              │
│   - 基于token计数智能截断            │
└─────────────────────────────────────┘
        ↓ 复用现有基础设施
┌─────────────────────────────────────┐
│   FileSystemChatMemoryRepository    │  ← YAML文件存储
└─────────────────────────────────────┘
```

### 核心策略

#### 1. 智能滑动窗口

**工作原理**：
- 保留最近N轮对话（可配置，默认10轮）
- 超出窗口的消息按token计数进行截断
- 过滤明显无用的短消息（"好的"、"嗯"、"收到"等）

**配置示例**：
```yaml
agent:
  context:
    window:
      max-messages: 20          # 最大保留消息数
      keep-recent: 10           # 保留最近完整轮数
      max-tokens: 8000          # token上限
      filter-short-messages: true  # 是否过滤短消息
      short-message-threshold: 5   # 短消息字符阈值
```

#### 2. 消息过滤规则

**过滤条件**（满足任一即过滤）：
- 消息长度 < 5字符（如"好的"、"嗯"）
- 仅包含语气词/确认词（正则匹配）
- 重复消息（连续3条相同内容）

**保留条件**（强制保留）：
- 包含用户明确需求/问题
- 包含Agent的关键决策/建议
- 包含数据/事实信息

#### 3. Token计数截断

**策略**：
- 优先保留最近的消息
- 从最旧的消息开始截断
- 确保总token数不超过配置上限
- 使用简单估算（1中文字符≈2 tokens，1英文单词≈1.3 tokens）

### 技术实现

#### 实现要点

```java
public class SmartWindowChatMemory {
    
    private final int maxMessages;
    private final int keepRecent;
    private final int maxTokens;
    private final boolean filterShortMessages;
    
    public List<Message> manage(List<Message> messages) {
        // 1. 过滤无用短消息
        List<Message> filtered = filterShortMessages 
            ? filterUselessMessages(messages) 
            : messages;
        
        // 2. 检查token上限
        if (estimateTokens(filtered) > maxTokens) {
            filtered = truncateByToken(filtered);
        }
        
        // 3. 确保不超过最大消息数
        if (filtered.size() > maxMessages) {
            filtered = filtered.subList(
                filtered.size() - maxMessages, 
                filtered.size()
            );
        }
        
        return filtered;
    }
    
    private List<Message> filterUselessMessages(List<Message> messages) {
        // 基于规则的简单过滤
        return messages.stream()
            .filter(msg -> !isShortMessage(msg))
            .filter(msg -> !isConfirmationOnly(msg))
            .toList();
    }
    
    private List<Message> truncateByToken(List<Message> messages) {
        // 从最旧消息开始截断，直到token数低于上限
        int totalTokens = estimateTokens(messages);
        List<Message> result = new ArrayList<>(messages);
        
        while (totalTokens > maxTokens && result.size() > keepRecent) {
            Message removed = result.remove(0);
            totalTokens -= estimateMessageTokens(removed);
        }
        
        return result;
    }
}
```

#### 集成方式

扩展现有的 [FileSystemChatMemoryRepository](file:///d:/Workspace/hui/project/JobClaw/core/src/main/java/com/git/hui/jobclaw/core/agent/memory/FileSystemChatMemoryRepository.java)：

```java
@Component
public class FileSystemChatMemoryRepository implements AppendableChatMemoryRepository {
    
    @Autowired
    private SmartWindowChatMemory smartWindow;
    
    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<Message> allMessages = loadFromFile(conversationId);
        // 应用智能窗口管理
        return smartWindow.manage(allMessages);
    }
}
```

### 优势分析

| 维度 | 说明 |
|------|------|
| **实现复杂度** | ⭐⭐ 低，扩展现有代码 |
| **开发周期** | 1-2天 |
| **额外成本** | 零额外AI调用成本 |
| **维护难度** | 低，单一组件 |
| **效果** | 解决80%上下文膨胀问题 |
| **风险** | 极低，可快速回滚 |

---

## 后续增强方案

### Phase 2: 轻量级会话摘要 ✅ 已完成

**实施日期**: 2026-04-14  
**状态**: ✅ 已完成

**触发条件**：对话超过20轮或token超过8000时

**实现方式**：
- 使用同一模型生成摘要（无需额外API调用）
- 摘要格式简化为纯文本
- 存储在YAML frontmatter中

**核心组件**：

1. **Prompt模板** - [session-summary-prompt.md](file:///d:/Workspace/hui/project/JobClaw/core/src/main/resources/prompts/session-summary-prompt.md)
   - 定义了摘要生成的规则和示例
   - 要求摘要不超过3句话，100字以内
   - 包含3个典型示例

2. **SessionSummarizer** - [SessionSummarizer.java](file:///d:/Workspace/hui/project/JobClaw/core/src/main/java/com/git/hui/jobclaw/core/agent/memory/SessionSummarizer.java)
   - 异步摘要生成
   - 失败降级处理（返回空字符串）
   - 自动验证和清理摘要内容

3. **集成到Repository** - [FileSystemChatMemoryRepository.java](file:///d:/Workspace/hui/project/JobClaw/core/src/main/java/com/git/hui/jobclaw/core/agent/memory/FileSystemChatMemoryRepository.java)
   - 保存时自动生成摘要
   - 读取时注入摘要到上下文
   - 保持向后兼容

**存储格式**：
```yaml
---
createdAt: 2026-04-13T10:00:00Z
updatedAt: 2026-04-13T10:30:00Z
summary: "用户想找北京Java实习，已推荐5个岗位，等待简历"
---
- user: |
    有没有新的岗位？
- assistant: |
    ...
```

**测试覆盖**：
- 单元测试：[SessionSummarizerTest.java](file:///d:/Workspace/hui/project/JobClaw/core/src/test/java/com/git/hui/jobclaw/core/agent/memory/SessionSummarizerTest.java) (15个测试)
- 集成测试：[SessionSummaryIntegrationTest.java](file:///d:/Workspace/hui/project/JobClaw/core/src/test/java/com/git/hui/jobclaw/core/agent/memory/SessionSummaryIntegrationTest.java) (10个测试)

**预期收益**：
- ✅ 进一步压缩历史上下文60%-80%
- ✅ 保持对话连贯性
- ✅ 异步生成，不阻塞主流程
- ✅ 失败自动降级

**验收标准**：
- ✅ 所有单元测试代码已编写
- ✅ 集成测试代码已编写
- ✅ Prompt模板已创建
- ✅ 摘要存储和注入已实现
- ⏳ 实际效果待Java 21环境验证

---

## 原方案评估（参考）

> 以下为mem.md原始方案，设计完整但复杂度高，暂不实施，供后续参考。

### 原始设计目标

1. **解决上下文膨胀问题**：在有限token窗口内保留最有价值的信息
2. **智能压缩策略**：自动识别并保留关键信息，丢弃冗余内容
3. **用户透明**：压缩过程对用户无感知，保持对话连贯性
4. **可配置化**：支持不同场景采用不同的管理策略

### 原始核心概念

#### 1. 分层记忆架构

```
┌─────────────────────────────────────┐
│     Working Memory (工作记忆)        │  ← 最近N轮对话，完整保留
├─────────────────────────────────────┤
│     Short-term Summary (短期摘要)    │  ← 前M轮的摘要，保留关键信息
├─────────────────────────────────────┤
│     Long-term Memory (长期记忆)      │  ← 向量化的关键事实/偏好
└─────────────────────────────────────┘
```

**各层职责**：
- **工作记忆**：最近的对话轮次（如最近10轮），原样保留，保证即时上下文
- **短期摘要**：较早对话的压缩版本（如10-50轮），由AI生成结构化摘要
- **长期记忆**：提取的用户偏好、关键事实，向量化存储，按需检索

#### 2. 消息重要性评分

为每条消息计算重要性分数（0-1），用于决定保留/压缩/丢弃：

**评分维度**：
- **用户意图明确度**：包含明确需求、问题、指令 → 高分
- **信息密度**：包含事实、数据、决策 → 高分
- **对话转折**：话题切换、新任务开始 → 高分
- **情感强度**：表达强烈情绪、紧急程度 → 中分
- **确认类消息**："好的"、"收到"、"嗯" → 低分
- **闲聊内容**：问候、寒暄 → 低分

#### 3. 压缩触发策略

**多维度触发条件**：

| 触发条件 | 阈值 | 说明 |
|---------|------|------|
| 消息数量 | > 20轮 | 超过固定轮数 |
| Token数量 | > 8000 tokens | 接近模型窗口限制 |
| 时间间隔 | > 1小时 | 长时间中断后恢复对话 |
| 话题切换 | 检测到新主题 | 用户明确提出新任务 |

**压缩动作**：
1. **轻度压缩**：保留最近15轮 + 生成前5轮的摘要
2. **中度压缩**：保留最近10轮 + 生成前15轮的摘要
3. **重度压缩**：保留最近5轮 + 生成前20轮的摘要 + 提取关键事实到长期记忆

#### 4. 长期记忆提取

**提取内容**：
- 用户偏好（地点、岗位类型、薪资期望等）
- 关键事实（毕业时间、学校、技能等）
- 历史决策（已投递的公司、面试结果等）

**存储方式**：
```yaml
# workspace/memories/{jobClawUserId}/profile.yaml
userProfile:
  jobClawUserId: "user123"
  preferences:
    location: ["北京", "上海"]
    jobType: ["Java开发", "后端工程师"]
    internship: true
  facts:
    graduationYear: 2026
    university: "某某大学"
    skills: ["Java", "Spring Boot", "MySQL"]
  history:
    appliedJobs:
      - company: "公司A"
        position: "Java实习生"
        appliedAt: "2026-04-10"
        status: "pending"
```

### 原方案劣势与风险

1. **过度设计风险** ⚠️⚠️⚠️
   - 三层记忆架构对当前项目可能过于复杂
   - 消息重要性评分需要额外模型调用，增加成本和延迟
   - 长期记忆的向量化存储引入新的技术栈依赖

2. **实现复杂度高**
   - 需要维护多个组件：`MessageImportanceScorer`、`ContextManager`、摘要生成Agent、记忆提取器等
   - 异步摘要生成、失败降级、回滚机制增加系统复杂度
   - 预估实施周期8-10天，对V2重构阶段可能分散精力

3. **摘要准确性难以保证**
   - AI生成的摘要可能丢失关键信息或产生误导
   - 错误摘要比没有摘要更危险（会持续影响后续对话）

---

## 研发计划

### Phase 1: 智能滑动窗口（当前实施）✅ 已完成

**目标**：实现基于规则的智能窗口管理，解决80%上下文膨胀问题

**实施日期**：2026-04-14

**状态**：✅ 已完成

**时间**：1-2天

**任务清单**：

#### ✅ Day 1: 核心功能实现

- [x] **1.1 创建配置类**
  - 文件：[ContextWindowProperties.java](file:///d:/Workspace/hui/project/JobClaw/core/src/main/java/com/git/hui/jobclaw/core/agent/memory/ContextWindowProperties.java)
  - 内容：定义窗口配置属性（maxMessages、keepRecent、maxTokens等）
  - 使用 `@ConfigurationProperties` 绑定YAML配置

- [x] **1.2 实现智能窗口管理器**
  - 文件：[SmartWindowChatMemory.java](file:///d:/Workspace/hui/project/JobClaw/core/src/main/java/com/git/hui/jobclaw/core/agent/memory/SmartWindowChatMemory.java)
  - 功能：
    - 消息过滤（短消息、确认消息）
    - Token计数估算
    - 窗口截断逻辑
  - 依赖：Spring `@Component`，注入配置属性

- [x] **1.3 扩展FileSystemChatMemoryRepository**
  - 文件：[FileSystemChatMemoryRepository.java](file:///d:/Workspace/hui/project/JobClaw/core/src/main/java/com/git/hui/jobclaw/core/agent/memory/FileSystemChatMemoryRepository.java)
  - 修改：
    - 注入 `SmartWindowChatMemory`
    - 在 `findByConversationId` 方法中应用窗口管理
    - 保持向后兼容（可通过配置开关启用/禁用）

- [x] **1.4 添加配置项**
  - 文件：[application.yml](file:///d:/Workspace/hui/project/JobClaw/app/src/main/resources/application.yml)
  - 内容：添加 `agent.context.window` 配置节
  - 启用：在 [JobClawConfiguration.java](file:///d:/Workspace/hui/project/JobClaw/core/src/main/java/com/git/hui/jobclaw/core/JobClawConfiguration.java) 中添加 `@EnableConfigurationProperties`

---

### Phase 2: 轻量级会话摘要（可选，后续实施）

**目标**：在智能窗口基础上增加摘要功能，进一步压缩上下文

**时间**：2-3天（Phase 1完成后）

**任务清单**：

- [x] **2.1 设计摘要Prompt模板**
  - 位置：`core/src/main/resources/prompts/summary-prompt.txt`
  - 要求：简洁、结构化、保留关键信息

- [x] **2.2 实现摘要生成器**
  - 文件：`core/src/main/java/com/git/hui/jobclaw/core/agent/memory/SessionSummarizer.java`
  - 功能：
    - 触发条件检测（轮数/token数）
    - 调用模型生成摘要
    - 失败降级处理

- [x] **2.3 扩展YAML存储格式**
  - 修改 `ChatYamlSerializer` 支持frontmatter中的summary字段
  - 保持向后兼容

- [x] **2.4 集成到窗口管理器**
  - 在 `SmartWindowChatMemory` 中注入摘要
  - 摘要作为system message注入上下文

---

### Phase 3: 监控与优化（可选）

**目标**：添加监控指标，收集使用数据，持续优化

**时间**：1-2天

**任务清单**：

- [ ] **3.1 添加压缩日志**
  - 记录每次压缩的触发原因
  - 记录压缩前后的消息数/token数

- [ ] **3.2 添加指标收集**
  - 使用Micrometer收集指标
  - 指标：压缩次数、平均压缩率、失败次数等

- [ ] **3.3 添加调试模式**
  - 配置开关：`agent.context.debug=true`
  - 输出详细的压缩过程日志

- [ ] **3.4 参数调优**
  - 根据实际使用数据调整配置
  - 优化过滤规则和token估算

---

## 方案对比

| 维度 | 原方案（三层记忆） | 简化方案（智能窗口） |
|------|-------------------|---------------------|
| 实现复杂度 | ⭐⭐⭐⭐⭐ 高 | ⭐⭐ 低 |
| 开发周期 | 8-10天 | 1-2天 |
| 额外成本 | 需要多次AI调用 | 零额外成本 |
| 维护难度 | 多层架构复杂 | 单一组件简单 |
| 效果 | 理论最优 | 解决80%问题 |
| 风险 | 摘要错误/性能开销 | 极低 |
| 适合阶段 | 核心功能稳定后 | 当前V2重构期 |

---

## 决策记录

**2026-04-14**：选择简化方案（智能滑动窗口）作为Phase 1实施方案

**理由**：
1. 项目处于V2重构阶段，需要快速见效
2. 避免过度设计，保持系统简洁
3. 零额外成本，无需引入新依赖
4. 可快速迭代，后续可根据实际需求增强

**暂缓实施**：
- 消息重要性评分（需要额外模型调用）
- 向量化长期记忆（需要额外基础设施）
- 复杂的三层记忆架构（当前不需要）

---

## 预期效果

- ✅ 支持20+轮次的长对话，不会出现上下文溢出
- ✅ 自动过滤冗余内容，节省token成本30%-50%
- ✅ 配置化窗口大小，适应不同场景需求
- ✅ 实现简单，易于理解和维护
- ✅ 为后续增强（摘要、长期记忆）预留扩展点

