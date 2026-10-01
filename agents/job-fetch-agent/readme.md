# Job Fetch Agent - 职位信息获取Agent

## 📋 概述

Job Fetch Agent 是 JobClaw 系统中专门用于获取职业信息的智能Agent，支持两种主要方式：

1. **网络爬虫模式**：从招聘网站自动爬取职位信息
2. **内容提取模式**：从文本、文件、图片等中提取职位信息

两种方式输出的都是统一结构的 `JobInfo` 对象列表。

---

## 🏗️ 架构设计

### 核心组件

```
job-fetch-agent/
├── model/
│   └── JobInfo.java              # 统一的职位信息模型
├── crawler/                       # 爬虫模块
│   ├── JobCrawler.java           # 爬虫接口
│   └── impl/
│       └── GenericWebCrawler.java # 通用网页爬虫（示例）
├── extract/                       # 提取器模块
│   ├── JobExtractor.java         # 提取器接口
│   └── impl/
│       └── AiBasedJobExtractor.java # AI文本提取器
├── JobFetchService.java          # 统一管理服务
└── JobFetchAgent.java            # Agent主类
```

### 数据流

```
用户请求
   ↓
JobFetchAgent (识别意图)
   ↓
JobFetchService (路由选择)
   ↓
┌─────────────┬──────────────┐
│  JobCrawler │ JobExtractor │
│  (爬虫)      │  (提取器)     │
└─────────────┴──────────────┘
   ↓
List<JobInfo> (统一输出)
```

---

## 📦 职位信息模型 (JobInfo)

所有爬虫和提取器都输出相同结构的 `JobInfo` 对象。

### 与现有系统的兼容性

`JobInfo` 完全兼容现有的 `GatherOcDraftBo` 和 `OcDraftEntity`，并提供了转换工具类 `JobInfoConverter`。

**字段映射关系**：

| JobInfo | GatherOcDraftBo | OcDraftEntity | 说明 |
|---------|-----------------|---------------|------|
| companyName | companyName | companyName | ✅ 完全一致 |
| companyType | companyType | companyType | ✅ 完全一致 |
| companyIndustry | companyIndustry | companyIndustry | ✅ 完全一致 |
| jobLocation | jobLocation | jobLocation | ✅ 完全一致 |
| recruitmentType | recruitmentType | recruitmentType | ✅ 完全一致 |
| recruitmentTarget | **requirementTarget** | recruitmentTarget | ⚠️ 别名兼容 |
| position | position | position | ✅ 完全一致 |
| deliveryProgress | deliveryProgress | deliveryProgress | ✅ 完全一致 |
| lastUpdatedTime | lastUpdatedTime | lastUpdatedTime | ✅ 完全一致 |
| deadline | deadline | deadline | ✅ 完全一致 |
| relatedLink | relatedLink | relatedLink | ✅ 完全一致 |
| jobAnnouncement | jobAnnouncement | jobAnnouncement | ✅ 完全一致 |
| internalReferralCode | internalReferralCode | internalReferralCode | ✅ 完全一致 |
| remarks | remarks | remarks | ✅ 完全一致 |
| salary | - | - | ✨ 新增字段 |
| education | - | - | ✨ 新增字段 |
| experience | - | - | ✨ 新增字段 |
| source | - | - | ✨ 新增字段（追踪来源） |
| fetchTime | - | - | ✨ 新增字段（抓取时间） |

**注意**：
- `GatherOcDraftBo` 使用 `requirementTarget`，而 `OcDraftEntity` 和 `JobInfo` 使用 `recruitmentTarget`
- `JobInfo` 同时支持两个字段名，通过 `setRequirementTarget()` 和 `setRecruitmentTarget()` 自动同步
- 使用 `JobInfoConverter` 进行转换时会自动处理字段映射

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| companyName | String | 公司名称 | 某某科技有限公司 |
| companyType | String | 公司类型 | 国企/私企/外企 |
| companyIndustry | String | 公司行业 | IT/互联网 |
| jobLocation | String | 工作地点 | 北京,上海 |
| recruitmentType | String | 招聘类型 | 校招/社招/实习 |
| recruitmentTarget | String | 招聘对象 | 2026年毕业生 |
| position | String | 岗位名称 | Java开发工程师 |
| salary | String | 薪资范围 | 15k-25k |
| education | String | 学历要求 | 本科/硕士 |
| experience | String | 工作经验 | 应届生/1-3年 |
| deadline | String | 投递截止 | 2026-05-01 |
| relatedLink | String | 相关链接 | https://... |
| jobAnnouncement | String | 招聘公告详情 | ... |
| internalReferralCode | String | 内推码 | ABC123 |
| source | String | 信息来源 | URL/文件名 |
| fetchTime | LocalDateTime | 抓取时间 | 2026-04-18T10:00:00 |

---

## 🕷️ 爬虫模块 (Crawler)

### 接口定义

```java
public interface JobCrawler {
    String getName();                    // 获取爬虫名称
    boolean supports(String url);        // 判断是否支持该URL
    List<JobInfo> crawl(String url);     // 爬取职位信息
}
```

### 扩展示例：添加新的爬虫

```java
@Component
public class BossZhipinCrawler implements JobCrawler {
    
    @Override
    public String getName() {
        return "BOSS直聘爬虫";
    }
    
    @Override
    public boolean supports(String url) {
        return url != null && url.contains("zhipin.com");
    }
    
    @Override
    public List<JobInfo> crawl(String url) {
        // 实现具体的爬取逻辑
        // 1. 使用Playwright访问页面
        // 2. 解析HTML结构
        // 3. 提取职位信息
        // 4. 返回JobInfo列表
    }
}
```

### 已实现的爬虫

- ✅ **GenericWebCrawler**: 通用网页爬虫（示例实现）
  - 支持主流招聘网站
  - 需要集成Playwright进行实际爬取

---

## 📄 提取器模块 (Extractor)

### 接口定义

```java
public interface JobExtractor {
    String getName();                                    // 获取提取器名称
    boolean supports(String contentType);                // 判断是否支持该类型
    List<JobInfo> extractFromText(String text);          // 从文本提取
    List<JobInfo> extractFromStream(InputStream is, String type); // 从流提取
    List<JobInfo> extractFromFile(String filePath);      // 从文件提取
}
```

### 已实现的提取器

#### 1. AiBasedJobExtractor (AI文本提取器)

使用大模型从各种文本内容中提取结构化职位信息。

**支持的类型**：
- 纯文本 (text/plain)
- HTML (text/html)
- Markdown (text/markdown)

**使用示例**：

```java
@Autowired
private JobFetchService jobFetchService;

// 从文本提取
String text = "某某公司招聘Java工程师，地点北京，要求本科...";
List<JobInfo> jobs = jobFetchService.fetchFromText(text);

// 从文件提取
List<JobInfo> jobs = jobFetchService.fetchFromFile("/path/to/jobs.txt");
```

**工作原理**：
1. 接收原始文本内容
2. 构造Prompt发送给LLM
3. LLM返回JSON格式的职位信息
4. 解析JSON为JobInfo对象列表

---

## 🔧 使用方式

### 1. 通过Agent工具调用

```java
// 从URL爬取
fetchJobsFromUrl("https://www.lagou.com/jobs/list_java")

// 从文本提取
extractJobsFromText("某公司招聘Java开发，地点上海...")

// 查看可用爬虫
getAvailableCrawlers()

// 查看可用提取器
getAvailableExtractors()
```

### 2. 通过服务层调用

```java
@Autowired
private JobFetchService jobFetchService;

// 从URL爬取
List<JobInfo> jobs = jobFetchService.fetchFromUrl(url);

// 从文本提取
List<JobInfo> jobs = jobFetchService.fetchFromText(text);

// 从文件提取
List<JobInfo> jobs = jobFetchService.fetchFromFile(filePath);

// 从输入流提取
List<JobInfo> jobs = jobFetchService.fetchFromStream(inputStream, "application/pdf");
```

---

## 💡 使用示例

### 场景1：爬取招聘网站

```
用户：帮我从这个链接爬取职位信息
https://www.lagou.com/jobs/list_Java?city=北京

Agent：正在使用 Generic Web Crawler 爬取...
成功获取 15 个职位信息：

1. 某某科技公司 - Java高级工程师 - 北京 (25k-40k)
2. 某某互联网公司 - Java开发工程师 - 北京 (15k-25k)
...
```

### 场景2：从文本提取

```
用户：从这段文字中提取职位信息

【某某集团2026校园招聘】
招聘岗位：Java开发工程师、前端开发工程师
工作地点：北京、上海、深圳
招聘对象：2026届应届毕业生
薪资待遇：15k-25k/月
投递链接：https://xxx.com/apply

Agent：正在使用 AI-Based Job Extractor 提取...
成功提取 2 个职位：

1. 某某集团 - Java开发工程师 - 北京,上海,深圳 (15k-25k)
2. 某某集团 - 前端开发工程师 - 北京,上海,深圳 (15k-25k)
```

### 场景3：上传文件提取

```
用户：分析这个PDF文件中的招聘信息
[上传 jobs.pdf]

Agent：正在从PDF文件中提取...
成功提取 8 个职位信息...
```

---

## 🚀 扩展指南

### 添加新的爬虫

1. 实现 `JobCrawler` 接口
2. 添加 `@Component` 注解
3. Spring会自动注册到列表中

```java
@Component
public class MyCustomCrawler implements JobCrawler {
    // 实现接口方法
}
```

### 添加新的提取器

1. 实现 `JobExtractor` 接口
2. 添加 `@Component` 注解
3. Spring会自动注册到列表中

```java
@Component
public class PdfJobExtractor implements JobExtractor {
    // 实现接口方法
}
```

### 自定义职位字段

修改 `JobInfo.java` 添加新字段，所有爬虫和提取器会自动适配。

---

## ⚠️ 注意事项

1. **爬虫合规性**：爬取网站前请确认robots.txt规则，遵守网站使用条款
2. **频率限制**：避免高频请求，建议添加延时和重试机制
3. **数据验证**：使用 `JobInfo.isValid()` 验证提取的数据有效性
4. **异常处理**：爬虫和提取器失败时返回空列表，不会抛出异常
5. **文本长度**：AI提取器对超过10000字符的文本会进行截断

---

## 📝 TODO

- [ ] 集成Playwright实现真实的网页爬取
- [ ] 添加PDF文件提取器（使用PDFBox或iText）
- [ ] 添加Excel/CSV文件提取器（使用Apache POI）
- [ ] 添加图片OCR提取器（使用Tesseract或云OCR服务）
- [ ] 实现爬虫结果缓存机制
- [ ] 添加爬虫代理和反反爬策略
- [ ] 支持增量爬取和去重

---

## 👥 作者

YiHui  
2026/4/18
