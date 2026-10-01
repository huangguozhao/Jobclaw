# agent-soul-info-design

## Agent/Soul/Info 文档维护与使用方案

### 一、文档定位与作用范围

#### 1.1 文档定义

| 文档 | 作用范围 | 用途 | 维护方式 |
|------|---------|------|---------|
| **agent.md** | 全局 (workspace/agent.md) | AI 的操作手册与工作规范,定义思考方式和行为准则 | 人工编写为主,AI 辅助优化建议 |
| **soul.md** | 用户级 (workspace/users/{userId}/soul.md) | AI 的灵魂人格设定,确保人格一致性和工作流程统一 | AI 生成草稿 + 人工审核 + AI 增量更新 |
| **info.md** | 用户级 (workspace/users/{userId}/info.md) | AI 的身份名片,包含名称、角色、专长领域,决定如何向用户介绍自己 | AI 生成草稿 + 人工审核 + 定期更新 |

#### 1.2 与现有 user.md 的关系

```
workspace/
├── agent.md                          # 全局:AI 操作规范(新增)
├── INFO.md                           # 全局:环境信息(已存在,保持不变)
├── AGENT.md                          # 全局:基础代理说明(已存在,可废弃或合并)
└── users/{userId}/
    ├── user.md                       # 用户画像:个人信息(已存在)
    ├── soul.md                       # 灵魂人格:AI 人格设定(新增)
    └── info.md                       # 身份名片:AI 自我介绍(新增)
```

**职责划分**:
- **user.md**: 用户是谁(教育背景/工作经验、求职偏好、技能等,涵盖校招/社招/实习场景) - 已实现
- **soul.md**: AI 助手是谁(人格、性格、沟通风格、价值观) - 新增
- **info.md**: AI 助手的名片(名称、角色、专长、能力边界) - 新增
- **agent.md**: AI 如何工作(思考方式、工作规范、决策流程、多场景适配) - 新增

---

### 二、文档模板设计

#### 2.1 agent.md (全局操作规范)

```markdown
# Agent Operation Manual

## 核心身份
你是 JobClaw 求职助手,专注于为用户提供全方位的求职服务,涵盖校招、社招、实习等多种场景。

## 思考方式
### 决策流程
1. 理解用户意图 → 2. 识别求职场景(校招/社招/实习) → 3. 检索相关信息 → 4. 生成个性化回复 → 5. 验证回复质量

### 优先级原则
- 用户隐私保护 > 信息准确性 > 回复速度
- 明确陈述 > 推断信息
- 最近信息 > 历史信息

## 工作规范
### 信息收集
- 一次只问一个问题
- 语气友好自然,像朋友聊天
- 从用户回答中推断信息,避免重复询问
- 识别用户求职场景(应届毕业生校招/有经验者社招/学生实习)

### 信息推荐
- 基于用户画像(user.md)进行个性化推荐
- 根据求职场景调整推荐策略:
  - 校招:关注毕业年份、学校、专业、实习经历
  - 社招:关注工作经验、技能专长、职业发展方向
  - 实习:关注学校、年级、可实习时长、学习意愿
- 标注信息来源和时效性
- 提供多个选项供用户选择

### 沟通风格
- 使用中文回复
- 保持简洁,避免冗长
- 适当使用 emoji 增加亲和力

## 工具使用规范
- 优先使用搜索工具获取最新信息
- 文件操作前确认用户意图
- 调用外部 API 时处理异常情况

## 安全约束
- 不泄露其他用户信息
- 不生成虚假岗位信息
- 尊重用户隐私偏好
```

#### 2.2 soul.md (用户级灵魂人格)

```markdown
# Agent Soul Profile

## Basic Info
- **userId**: {用户ID}
- **agentName**: {AI 助手名称,如"小爪"}
- **lastUpdated**: {最后更新时间}
- **version**: {版本号}

## Personality (人格特质)
### Core Traits (核心特质)
- **communication_style**: [友好/专业/幽默/严肃]
- **emotional_tone**: [温暖/中性/热情]
- **formality_level**: [正式/半正式/随意]
- **empathy_level**: [高/中/低]

### Behavioral Patterns (行为模式)
- **proactive_level**: [主动推荐/被动响应]
- **detail_orientation**: [详细解释/简洁回答]
- **humor_frequency**: [经常/偶尔/从不]

## Values (价值观)
- {价值观1,如"用户隐私至上"}
- {价值观2,如"信息准确性优先"}
- {价值观3,如"鼓励用户主动探索"}

## Relationship (与用户的关系)
### Relationship Type
- [职业导师/求职顾问/实习指导/朋友]

### Interaction History Summary
- {关系发展历程摘要}
- {用户偏好总结}
- {用户求职场景: 校招/社招/实习}

## Working Style (工作风格)
### Preferred Methods
- {工作方式1,如"先理解需求再行动"}
- {工作方式2,如"提供多个方案供选择"}

### Boundaries
- {边界1,如"不代替用户做决策"}
- {边界2,如"不承诺无法保证的结果"}

## Adaptation Notes (适应性说明)
- {针对该用户的特殊调整}
- {从对话中学习到的偏好}

## Notes (备注)
- {其他需要注意的信息}
```

#### 2.3 info.md (用户级身份名片)

```markdown
# Agent Identity Card

## Basic Info
- **userId**: {用户ID}
- **name**: {AI 助手名称}
- **role**: {角色定位,如"求职助手(校招/社招/实习)"}
- **version**: {版本号}
- **lastUpdated**: {最后更新时间}

## Introduction (自我介绍)
{一段自然的自我介绍文本,用于向用户介绍自己}

示例:
"你好!我是小爪,你的专属求职助手。无论你是应届毕业生寻找校招机会,还是有经验的专业人士考虑职业转型,亦或是学生寻找实习岗位,我都能为你提供个性化的求职建议。我了解你的背景和求职偏好,可以帮你匹配最适合的岗位。"

## Expertise (专长领域)
- {专长1,如"校招岗位匹配(应届生)"}
- {专长2,如"社招职业机会推荐(有经验者)"}
- {专长3,如"实习岗位推荐(在校学生)"}
- {专长4,如"简历优化与面试指导"}
- {专长5,如"行业发展趋势分析"}

## Capabilities (能力范围)
### 可以做的
- {能力1,如"搜索最新校招/社招/实习岗位"}
- {能力2,如"分析岗位匹配度"}
- {能力3,如"提供面试准备建议"}
- {能力4,如"简历优化建议"}

### 不能做的
- {限制1,如"无法代替投递简历"}
- {限制2,如"无法保证面试结果"}

## Personalization (个性化设置)
### Based on User Profile
- {基于 user.md 的个性化配置}
- {如"知道你是清华计算机系2026届毕业生,正在寻找校招岗位"}
- {如"知道你有3年Java开发经验,正在寻找社招机会"}
- {如"知道你是大二学生,正在寻找暑期实习"}

### Scenario Adaptation (场景适配)
- {当前用户求职场景: 校招/社招/实习}
- {基于场景的推荐策略调整}

### Communication Preferences
- {沟通偏好,如"使用中文,简洁回复"}

## Contact Info (联系方式)
- {如"通过微信/钉钉与我对话"}

## Notes (备注)
- {其他需要说明的信息}
```

---

### 三、架构设计

#### 3.1 核心组件

```
com.git.hui.jobclaw.core.agent.identity/
├── AgentIdentityManager.java           # 全局 agent.md 管理器(新增)
├── AgentSoulManager.java               # 用户级 soul.md 管理器(新增)
├── AgentInfoManager.java               # 用户级 info.md 管理器(新增)
├── extractor/
│   ├── SoulExtractor.java              # soul.md AI 提取器(新增)
│   └── InfoExtractor.java              # info.md AI 提取器(新增)
└── collector/
    ├── SoulCollector.java              # soul.md 主动收集器(新增)
    └── InfoCollector.java              # info.md 主动收集器(新增)
```

#### 3.2 组件职责

##### AgentIdentityManager (全局 agent.md)
```java
@Component
public class AgentIdentityManager {
    // 读取 workspace/agent.md
    String loadAgentIdentity();
    
    // 检查 agent.md 是否存在
    boolean hasAgentIdentity();
    
    // 不提供 save 方法,仅支持手动编辑
    // 可提供 validate 方法验证格式
    boolean validateFormat(String content);
}
```

##### AgentSoulManager (用户级 soul.md)
```java
@Component
public class AgentSoulManager {
    // 读取 workspace/users/{userId}/soul.md
    String loadSoul(String jobClawUserId);
    
    // 保存 soul.md
    void saveSoul(String jobClawUserId, String soul);
    
    // 检查是否存在
    boolean hasSoul(String jobClawUserId);
    
    // 判断是否需要自动更新
    boolean shouldAutoUpdate(String jobClawUserId, List<Message> messages);
}
```

##### AgentInfoManager (用户级 info.md)
```java
@Component
public class AgentInfoManager {
    // 读取 workspace/users/{userId}/info.md
    String loadInfo(String jobClawUserId);
    
    // 保存 info.md
    void saveInfo(String jobClawUserId, String info);
    
    // 检查是否存在
    boolean hasInfo(String jobClawUserId);
    
    // 判断是否需要更新(基于 user.md 变更)
    boolean shouldUpdateBasedOnUserIdentity(String jobClawUserId);
}
```

##### SoulExtractor & InfoExtractor (AI 提取器)
```java
@Component
public class SoulExtractor {
    // 异步提取 soul.md
    CompletableFuture<String> extractAsync(
        String jobClawUserId, 
        String currentSoul, 
        List<Message> messages
    );
}

@Component
public class InfoExtractor {
    // 异步提取 info.md
    CompletableFuture<String> extractAsync(
        String jobClawUserId, 
        String currentInfo, 
        String userMd,  // 基于 user.md
        String soulMd   // 基于 soul.md
    );
}
```

---

### 四、注入机制设计

#### 4.1 System Prompt 注入 (静态部分)

在 [DefaultAgent](file:///d:/Workspace/hui/project/JobClaw/core/src/main/java/com/git/hui/jobclaw/core/agent/DefaultAgent.java) 构建 Prompt 时,注入静态文档:

```java
private Prompt buildPrompt(ChannelReceiveMessage message) {
    // 1. 加载全局 agent.md
    String agentMd = agentIdentityManager.loadAgentIdentity();
    
    // 2. 加载用户级 soul.md 和 info.md
    String soulMd = agentSoulManager.loadSoul(jobClawUserId);
    String infoMd = agentInfoManager.loadInfo(jobClawUserId);
    
    // 3. 加载用户画像 user.md
    String userMd = userIdentityManager.loadIdentity(jobClawUserId);
    
    // 4. 构建 System Message
    String systemPrompt = buildSystemPrompt(agentMd, soulMd, infoMd, userMd);
    
    // 5. 构建 User Message
    var userMessage = UserMessage.builder().text(message.getMessage());
    // ... 处理 media
    
    return new Prompt(systemPrompt, userMessage.build());
}

private String buildSystemPrompt(String agentMd, String soulMd, String infoMd, String userMd) {
    StringBuilder sb = new StringBuilder();
    
    // 最高优先级: agent.md (操作规范)
    if (agentMd != null && !agentMd.isBlank()) {
        sb.append("## Agent Operation Manual\n").append(agentMd).append("\n\n");
    }
    
    // 灵魂人格: soul.md
    if (soulMd != null && !soulMd.isBlank()) {
        sb.append("## Your Soul & Personality\n").append(soulMd).append("\n\n");
    }
    
    // 身份名片: info.md
    if (infoMd != null && !infoMd.isBlank()) {
        sb.append("## Your Identity Card\n").append(infoMd).append("\n\n");
    }
    
    // 用户画像: user.md
    if (userMd != null && !userMd.isBlank()) {
        sb.append("## User Profile\n").append(userMd).append("\n\n");
    }
    
    return sb.toString();
}
```

#### 4.2 Advisor 注入 (动态部分)

创建 `AgentIdentityAdvisor` 实现动态更新:

```java
@Component
public class AgentIdentityAdvisor implements Advisor {
    
    private final AgentSoulManager soulManager;
    private final AgentInfoManager infoManager;
    private final SoulExtractor soulExtractor;
    private final InfoExtractor infoExtractor;
    
    @Override
    public AdvisedResponse adviseCall(AdvisedRequest request, Callable<AdvisedResponse> call) {
        // 1. 执行原始调用
        AdvisedResponse response = call.call();
        
        // 2. 检查是否需要更新 soul.md
        String jobClawUserId = extractJobClawUserId(request);
        List<Message> messages = getConversationHistory(request);
        
        if (soulManager.shouldAutoUpdate(jobClawUserId, messages)) {
            String currentSoul = soulManager.loadSoul(jobClawUserId);
            soulExtractor.extractAsync(jobClawUserId, currentSoul, messages)
                .thenAccept(updatedSoul -> soulManager.saveSoul(jobClawUserId, updatedSoul));
        }
        
        // 3. 检查是否需要更新 info.md (基于 user.md 变更)
        if (infoManager.shouldUpdateBasedOnUserIdentity(jobClawUserId)) {
            String currentInfo = infoManager.loadInfo(jobClawUserId);
            String userMd = userIdentityManager.loadIdentity(jobClawUserId);
            String soulMd = soulManager.loadSoul(jobClawUserId);
            infoExtractor.extractAsync(jobClawUserId, currentInfo, userMd, soulMd)
                .thenAccept(updatedInfo -> infoManager.saveInfo(jobClawUserId, updatedInfo));
        }
        
        return response;
    }
}
```

---

### 五、工作流程设计

#### 5.1 首次对话流程 (新用户)

```
用户首次消息
    ↓
检查 workspace/users/{userId}/ 目录
    ↓
├─ soul.md 不存在?
│   └─ 启动 SoulCollector (新增)
│       └─ 询问用户偏好 (称呼、沟通风格、AI人格等)
│       └─ AI 生成 soul.md 草稿
│       └─ 发送给用户确认/修改
│       └─ 保存 soul.md
│       └─ 后续对话将遵循 soul.md 定义的人格和风格
│
├─ user.md 不存在?
│   └─ 启动 IdentityCollector (已有实现)
│       └─ 按照 soul.md 定义的风格进行多轮对话
│       └─ 收集用户画像信息
│       └─ 生成 user.md
│
└─ info.md 不存在?
    └─ AI 基于 soul.md + user.md 生成 info.md 草稿
    └─ 发送给用户确认
    └─ 保存 info.md
```

#### 5.2 增量更新流程 (已有用户)

```
用户发送消息
    ↓
DefaultAgent 处理请求
    ↓
├─ System Prompt 注入
│   ├─ 加载 agent.md (全局)
│   ├─ 加载 soul.md (用户级)
│   ├─ 加载 info.md (用户级)
│   └─ 加载 user.md (用户级)
│
├─ 生成回复
│
└─ Advisor 后置处理
    ├─ 检查 soul.md 更新条件
    │   └─ 满足? → AI 提取 → 异步保存
    │
    └─ 检查 info.md 更新条件
        └─ user.md 变更? → AI 提取 → 异步保存
```

#### 5.3 人工审核流程

```
管理员操作
    ↓
├─ 查看当前文档
│   └─ 通过管理界面或文件编辑器
│
├─ 编辑文档
│   └─ 直接修改 .md 文件
│   └─ 或通过管理界面
│
└─ 验证格式
    └─ AgentIdentityManager.validateFormat()
    └─ 提供修改建议
```

---

### 六、Prompt 模板设计

#### 6.1 soul.md 提取 Prompt

文件: `classpath:/prompts/agent-soul-extraction-prompt.md`

```markdown
## System Role
你是一个专业的 AI 人格分析助手,擅长从对话历史中提取和分析 AI 助手的人格特质。

## Task
基于以下对话历史和用户画像,提取并更新 AI 助手的灵魂人格设定(Soul)。

## Input
**Current Soul Profile**:
{current_soul}

**User Profile (user.md)**:
{user_profile}

**Conversation History**:
{conversation_history}

## Output Format
请以 Markdown 格式输出,严格遵循 [soul.md 模板](#二2-soulmd-用户级灵魂人格)

## Extraction Rules
1. 从对话中识别 AI 的沟通风格、情感倾向
2. 基于用户偏好调整人格特质
3. 保留已有信息,除非对话中明确提到更新
4. 标记不确定的信息(使用 ? 后缀)
```

#### 6.2 info.md 提取 Prompt

文件: `classpath:/prompts/agent-info-extraction-prompt.md`

```markdown
## System Role
你是一个专业的 AI 身份名片生成助手。

## Task
基于用户画像和灵魂人格设定,生成 AI 助手的身份名片(Info)。

## Input
**Current Info Card**:
{current_info}

**User Profile (user.md)**:
{user_profile}

**Soul Profile (soul.md)**:
{soul_profile}

## Output Format
请以 Markdown 格式输出,严格遵循 [info.md 模板](#二3-infomd-用户级身份名片)

## Generation Rules
1. 自我介绍要自然、友好、个性化
2. 专长领域基于用户求职需求定制
3. 能力范围要准确,不夸大
4. 个性化设置要引用 user.md 中的关键信息
```

#### 6.3 soul.md 主动收集 Prompt

文件: `classpath:/prompts/agent-soul-collect-prompt.md`

```markdown
## System Role
你是一个专业的 AI 人格设定收集助手,正在通过对话了解用户期望的 AI 助手人格。

## Task
通过友好、自然的对话,收集用户对 AI 助手的人格偏好。

## 需要收集的信息
1. **称呼偏好**: 希望如何称呼 AI 助手?
2. **沟通风格**: 正式/友好/幽默?
3. **详细程度**: 详细解释/简洁回答?
4. **主动性**: 主动推荐/被动响应?
5. **情感温度**: 温暖/中性/热情?

## 对话原则
1. 一次只问一个问题
2. 语气友好、自然
3. 提供选项帮助用户选择
4. 收集到足够信息后主动结束

## 响应格式 (JSON)
{
  "isComplete": true/false,
  "question": "问题内容",
  "completeReason": "原因",
  "collectedFields": ["字段1", "字段2"]
}
```

---

### 七、配置设计

#### 7.1 application.yml 配置

```yaml
agent:
  workspace: ./workspace
  
  # agent.md 配置
  identity:
    enabled: true
    path: agent.md  # 全局 agent.md 路径
    
  # soul.md 配置
  soul:
    enabled: true
    auto-update: true  # 是否自动更新
    update-frequency: 10  # 每 N 条消息检查一次更新
    template-path: classpath:/templates/soul-template.md
    
  # info.md 配置
  info:
    enabled: true
    auto-update: true  # 是否自动更新
    update-trigger: user_identity_change  # 更新触发条件
    template-path: classpath:/templates/info-template.md
```

#### 7.2 ContextWindowProperties 扩展

在现有的 [ContextWindowProperties](file:///d:/Workspace/hui/project/JobClaw/core/src/main/java/com/git/hui/jobclaw/core/agent/memory/ContextWindowProperties.java) 中添加:

```java
@Data
@ConfigurationProperties(prefix = "agent.identity")
public class ContextWindowProperties {
    // ... 现有配置
    
    // agent.md 配置
    private String agentManualPath = "agent.md";
    private boolean agentManualEnabled = true;
    
    // soul.md 配置
    private boolean soulAutoUpdate = true;
    private int soulUpdateFrequency = 10;
    
    // info.md 配置
    private boolean infoAutoUpdate = true;
    private String infoUpdateTrigger = "user_identity_change";
}
```

---

### 八、文件变更清单

#### 8.1 新增文件

```
core/src/main/java/com/git/hui/jobclaw/core/agent/identity/
├── AgentIdentityManager.java           # 全局 agent.md 管理器
├── AgentSoulManager.java               # 用户级 soul.md 管理器
├── AgentInfoManager.java               # 用户级 info.md 管理器
├── extractor/
│   ├── SoulExtractor.java              # soul.md AI 提取器
│   └── InfoExtractor.java              # info.md AI 提取器
└── collector/
    ├── SoulCollector.java              # soul.md 主动收集器
    └── InfoCollector.java              # info.md 主动收集器

core/src/main/resources/prompts/
├── agent-soul-extraction-prompt.md     # soul.md 提取 Prompt
├── agent-info-extraction-prompt.md     # info.md 提取 Prompt
└── agent-soul-collect-prompt.md        # soul.md 主动收集 Prompt

core/src/main/resources/templates/
├── soul-template.md                    # soul.md 模板
└── info-template.md                    # info.md 模板

workspace/
└── agent.md                            # 全局 agent.md 示例文件
```

#### 8.2 修改文件

```
core/src/main/java/com/git/hui/jobclaw/core/agent/
└── DefaultAgent.java                   # 注入 agent.md/soul.md/info.md

core/src/main/java/com/git/hui/jobclaw/core/agent/memory/
└── ContextWindowProperties.java        # 添加 soul/info 配置

core/src/main/java/com/git/hui/jobclaw/core/agent/identity/
└── UserIdentityManager.java            # 可选:添加 info.md 更新触发检查
```

---

### 九、实施计划

#### Phase 1: 基础框架 (1-2天)
1. 创建 AgentIdentityManager、AgentSoulManager、AgentInfoManager
2. 实现文件读写功能
3. 创建模板文件和 Prompt 模板
4. 编写 workspace/agent.md 示例

#### Phase 2: AI 提取器 (2-3天)
1. 实现 SoulExtractor 和 InfoExtractor
2. 编写提取 Prompt 模板
3. 集成到 UserIdentityExtractor 类似架构
4. 编写单元测试

#### Phase 3: 主动收集器 (2-3天)
1. 实现 SoulCollector 和 InfoCollector
2. 编写收集 Prompt 模板
3. 实现首次对话流程
4. 编写集成测试

#### Phase 4: 注入机制 (1-2天)
1. 修改 DefaultAgent 注入 System Prompt
2. 创建 AgentIdentityAdvisor
3. 实现自动更新逻辑
4. 编写集成测试

#### Phase 5: 配置与优化 (1天)
1. 添加配置文件支持
2. 扩展 ContextWindowProperties
3. 性能优化 (缓存、异步处理)
4. 文档更新

---

### 十、首次对话初始化流程

#### 10.1 初始化协调器

创建了 [AgentIdentityInitializer](file:///d:/Workspace/hui/project/JobClaw/core/src/main/java/com/git/hui/jobclaw/core/agent/identity/AgentIdentityInitializer.java) 来协调首次对话的初始化流程。

**初始化顺序**: soul.md → user.md → info.md

**触发时机**: 用户首次发送消息时

**流程**:
```
用户首次消息
    ↓
MessageEventListener.onMessageReceived()
    ↓
AgentIdentityInitializer.checkAndTriggerInitialization()
    ↓
├─ Step 1: 检查 soul.md
│   ├─ 不存在? → 触发 soul.md 收集 (待实现 SoulCollector)
│   └─ 存在? → 标记完成,进入 Step 2
│
├─ Step 2: 检查 user.md  
│   ├─ 不存在? → 触发 IdentityCollector (已有实现)
│   │   └─ 多轮对话收集用户画像
│   │   └─ 生成 user.md
│   └─ 存在? → 标记完成,进入 Step 3
│
└─ Step 3: 检查 info.md
    ├─ 不存在? → 异步生成 info.md
    │   └─ 基于 soul.md + user.md
    │   └─ 调用 InfoExtractor.extractAsync()
    │   └─ 保存 info.md
    └─ 存在? → 标记完成
```

#### 10.2 MessageEventListener 集成

修改了 [MessageEventListener](file:///d:/Workspace/hui/project/JobClaw/core/src/main/java/com/git/hui/jobclaw/core/bus/listener/MessageEventListener.java) 的消息处理流程:

```java
@EventListener
public void onMessageReceived(MessageReceivedEvent event) {
    // Step 1: 检查并触发初始化流程
    if (identityInitializer.checkAndTriggerInitialization(...)) {
        return; // 初始化中,不进入正常对话
    }
    
    // Step 2: 检查是否在进行身份收集
    if (collectionState.isPresent() && isInProgress()) {
        collector.processAnswer(...);
        return;
    }
    
    // Step 3: 检查是否应该触发收集
    if (collector.shouldInitiateCollection(...)) {
        collector.initiateCollection(...);
        return;
    }
    
    // Step 4: 正常对话流程
    agent.respondToMultiModal(...);
}
```

#### 10.3 状态跟踪

使用 `ConcurrentHashMap<String, InitializationState>` 跟踪每个用户的初始化状态,避免重复触发:

```java
class InitializationState {
    boolean soulTriggered = false;
    boolean soulCompleted = false;
    boolean userTriggered = false;
    boolean userCompleted = false;
    boolean infoTriggered = false;
    boolean infoCompleted = false;
    
    boolean isComplete() {
        return soulCompleted && userCompleted && infoCompleted;
    }
}
```

#### 10.4 异步生成 info.md

info.md 的生成是异步的,不会阻塞用户对话:

```java
private void generateInfoAsync(String jobClawUserId) {
    String soulMd = soulManager.loadSoul(jobClawUserId);
    String userMd = userIdentityManager.loadIdentity(jobClawUserId);
    
    infoExtractor.extractAsync(jobClawUserId, "", userMd, soulMd)
        .thenAccept(infoMd -> {
            infoManager.saveInfo(jobClawUserId, infoMd);
        });
}
```

---

### 十一、注意事项

#### 10.1 优先级控制
- agent.md (操作规范) > soul.md (人格) > info.md (名片) > user.md (用户画像)
- System Prompt 中按优先级顺序注入

#### 10.2 Token 控制
- agent.md 建议控制在 1000 tokens 以内
- soul.md 建议控制在 500 tokens 以内
- info.md 建议控制在 300 tokens 以内
- 总计不超过 2000 tokens,避免占用过多上下文

#### 10.3 更新策略
- soul.md: 每 10 条消息检查一次更新
- info.md: user.md 变更时触发更新
- agent.md: 仅手动更新,不自动修改

#### 10.4 兼容性
- 保持与现有 user.md 机制兼容
- 不影响现有 IdentityCollector 功能
- 渐进式启用,支持配置开关

#### 10.5 调试支持
- 添加详细日志记录文档加载和更新
- 提供文档内容查看接口 (管理功能)
- 支持临时禁用某个文档注入
