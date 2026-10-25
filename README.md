<h1 align="center">RAG Study Helper</h1>

<p align="center">
  <strong>面向个人知识库的 RAG（检索增强生成）智能问答系统</strong>
  <br>
  上传文档 → 自动切片建索引 → 带出处地回答你的问题
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-8-%23ED8B00?logo=openjdk">
  <img src="https://img.shields.io/badge/Spring_Boot-2.7.18-%236DB33F?logo=spring">
  <img src="https://img.shields.io/badge/LangChain4j-0.35.0-blue">
  <img src="https://img.shields.io/badge/Redisson-4.5.0-%23DC382D?logo=redis">
</p>

---

## 目录

- [一、项目背景](#一项目背景)
  - [1.1 要解决的痛点](#11-要解决的痛点)
  - [1.2 RAG 是什么](#12-rag-是什么)
  - [1.3 为什么是"学习型项目"](#13-为什么是学习型项目)
- [二、功能特性](#二功能特性)
  - [2.1 功能全景](#21-功能全景)
  - [2.2 核心功能详解](#22-核心功能详解)
  - [2.3 能力边界](#23-能力边界)
- [三、技术架构](#三技术架构)
  - [3.1 整体架构](#31-整体架构)
  - [3.2 技术栈](#32-技术栈)
  - [3.3 五条关键设计](#33-五条关键设计)
- [四、项目目录](#四项目目录)
  - [4.1 顶层结构](#41-顶层结构)
  - [4.2 代码分层](#42-代码分层)
  - [4.3 按重要性排序的文件](#43-按重要性排序的文件)
- [五、配置说明](#五配置说明)
  - [5.1 配置优先级](#51-配置优先级)
  - [5.2 模型配置](#52-模型配置)
  - [5.3 向量库配置](#53-向量库配置)
  - [5.4 数据源与限流](#54-数据源与限流)
  - [5.5 环境变量总表](#55-环境变量总表)
- [六、快速开始](#六快速开始)
  - [6.1 前置条件](#61-前置条件)
  - [6.2 方式一：本地运行（推荐新手）](#62-方式一本地运行推荐新手)
  - [6.3 方式二：Docker Compose](#63-方式二docker-compose)
  - [6.4 验证是否成功](#64-验证是否成功)
  - [6.5 常见问题](#65-常见问题)
- [附录](#附录)
  - [A. API 参考](#a-api-参考)
  - [B. 测试](#b-测试)
  - [C. JDK 8 版本锁定说明](#c-jdk-8-版本锁定说明)

---

# 一、项目背景

## 1.1 要解决的痛点

大模型（DeepSeek、GPT 等）很强，但直接问它有三个绕不过去的问题：

| 问题 | 具体表现 |
|------|---------|
| **不知道你的私有资料** | 你问"我这份面试笔记里 JVM 那一节怎么写的"，它只能编 |
| **知识有截止日期** | 训练数据不包含你昨天刚写的文档 |
| **会一本正经地胡说** | （幻觉）不给出处，你无法验证真假 |

这三种问题，靠"换个更强的模型"解决不了，因为它们不是能力问题，而是**信息不在模型手里**。

**RAG 的思路**：不改造模型，而是在提问的瞬间，**先把你自己的资料检索出来，塞进提示词一起发给模型**。模型只负责"读着资料回答问题"，不负责"记住资料"。

本项目就是把这条路走通、走稳的一套完整实现。

## 1.2 RAG 是什么

一句话：**检索（Retrieval）+ 增强（Augmented）+ 生成（Generation）**。

分成两个独立阶段，彼此靠"向量"连接：

```
【离线阶段：入库】只做一次
  你的文档 → 解析成纯文本 → 切成小段 → 每段转成向量 → 存进向量库

【在线阶段：问答】每次提问都做
  你的问题 → 转成向量 → 在向量库里找最相似的几段 → 重排序
           → 拼进提示词 → 大模型生成带出处的回答
```

关键在于"转成向量"这一步：**文字变成一串数字后，意思相近的文字，数字也相近**。所以能算出"哪段资料和你的问题最相关"。这一步由 Embedding 模型完成，和对话模型是两个不同的模型。

> 一个常见误解：以为用了 RAG 模型就不会答错。实际上 RAG 只保证"模型看到了正确的资料"，如果检索没命中，模型照样会自由发挥。所以本项目在检索环节做了**阈值过滤 + 重排序**两道关卡——宁可说"没找到相关资料"，也不拿不相关的内容去糊弄。

## 1.3 为什么是"学习型项目"

这个项目有两个身份：

**作为可用的工具**——上传你的 PDF、Word、笔记，问它问题，答案带出处。

**作为后端学习的教材**——它把后端开发中最常见的几件事都覆盖了一遍，且每件都用可读的方式实现：

- 分层架构（Controller / Service / Mapper）
- 第三方 API 调用与超时处理（Embedding、Rerank、LLM）
- 数据库事务与跨存储一致性（MySQL + 向量库）
- 分布式锁（Redisson）
- 分布式限流（令牌桶 + AOP）
- 分布式缓存（Spring Cache + Redis，含穿透/击穿/空值处理）
- 线程池隔离与外部调用并发控制（Semaphore）
- 异步任务与状态轮询
- 流式响应（SSE）
- 统一异常处理与参数校验
- 单元测试

代码规模控制在 **30 余个 Java 文件 / 约 2800 行**，故意没有拆微服务、没有引入 MQ——为的是"一个人能在一个月内读完全部代码"。

---

# 二、功能特性

## 2.1 功能全景

```
┌─────────────────────────────────────────────────────────────┐
│  知识入库                                                    │
│  ├─ 网页上传（拖拽 / 点击选择）      支持 12 种格式           │
│  ├─ 目录扫描（data/docs/）           启动时自动导入          │
│  ├─ 内容去重（SHA-256）              重复上传自动跳过        │
│  └─ 文档删除                         向量 + 元数据同步清理    │
├─────────────────────────────────────────────────────────────┤
│  智能问答                                                    │
│  ├─ 流式输出（SSE）                  逐字打字效果            │
│  ├─ 多轮对话                         上下文记忆（Redis 1h） │
│  ├─ 查询改写                         把"它"补全成完整问句    │
│  ├─ 相关性阈值过滤                   低于 0.77 直接丢弃      │
│  ├─ 重排序（Rerank）                 向量召回 20 → 精选 5    │
│  ├─ 答案带出处                       标注 [来源:xxx.pdf]     │
│  └─ 无命中兜底                       转普通聊天，不硬编资料   │
├─────────────────────────────────────────────────────────────┤
│  工程保障                                                    │
│  ├─ 分布式限流（IP 令牌桶 + 每日总量）                       │
│  ├─ 分布式锁（防并发重复入库）                               │
│  ├─ 统一响应格式 + 全局异常处理                              │
│  ├─ 参数校验（Bean Validation）                              │
│  └─ 三种向量库可切换（InMemory / Chroma / Milvus）           │
├─────────────────────────────────────────────────────────────┤
│  前端界面（单文件 index.html，无框架、无构建）                │
│  深色 / 亮色主题 · 移动端适配 · 会话历史 · 消息复制           │
└─────────────────────────────────────────────────────────────┘
```

## 2.2 核心功能详解

### ① 文档入库：从文件到可检索的向量

支持格式与解析方式：

| 格式 | 解析库 | 说明 |
|------|--------|------|
| PDF | Apache PDFBox | 纯文本层，扫描件（图片）提取不出内容 |
| DOCX / XLSX / XLS / PPTX | Apache POI | Office 全套 |
| HTML / HTM | JSoup | 自动剥离标签 |
| TXT / MD / CSV / JSON / XML | 文本读取 | 直接按文本处理 |

完整流程（这是项目里最复杂的一段代码，`DocumentIngestionService`）：

```
上传文件
  ↓
① 计算内容 SHA-256
  ↓
② 加分布式锁 lock:document:ingest:{hash}   ← 防两个请求同时入库同一文件
  ↓
③ 锁内二次查重（查询 documents 表）
  ├─ 已存在 → 直接返回已有记录，不再处理
  ↓
④ 解析成纯文本（按扩展名分派解析器）
  ↓
⑤ 递归切分：每块 512 token，块间重叠 51 token
  ↓
⑥ 批量 Embedding：每批 10 条，调用 Embedding API 转成 1024 维向量
  ↓
⑦ 先写向量库，收集返回的 vectorId
  ↓
⑧ 再在 MySQL 事务内写 documents + document_chunks
  ↓
⑨ 若第 ⑧ 步失败 → 补偿删除第 ⑦ 步写入的向量（避免孤儿向量）
```

**为什么第 ⑤ 步要重叠 51 token？** 因为切分会把一句话从中间截断。相邻两块重叠一部分，能保证被截断的语义在至少一块里是完整的。

**为什么第 ⑦⑧ 步要分先后而不是同时写？** 因为 MySQL 有事务、向量库没有。如果先写 MySQL 再写向量库，向量库失败就会留下一堆"查不到向量的文档记录"，用户点删除都不好删。反过来先写向量库，MySQL 失败时还能精确按 vectorId 删回去——**有补偿手段的那一步放后面做**。

去重用的是内容哈希而不是文件名，所以同一个文件改名后再传，依然会被识别为重复。

### ② 智能问答：检索 → 重排 → 生成

```
用户提问
  ↓
① 从 Redis 取该会话最近 2 轮对话历史
  ↓
② 查询改写（Query Rewrite）
     "它的默认端口是多少"  →  "Spring Boot 的默认端口是多少"
     （把指代词补全，否则向量检索找不到东西）
  ↓
③ 问题转向量（Embedding，1024 维）
  ↓
④ 向量库检索 top 20
  ↓
⑤ 分数阈值过滤：score < 0.77 的全部丢弃
     全部被丢弃 → 转普通聊天模式（明确告知没查资料）
  ↓
⑥ Rerank 重排序：20 条候选 → 精选 5 条
     （向量检索比的是"意思像不像"，Rerank 比的是"真的能回答吗"）
  ↓
⑦ 拼装提示词：系统约束 + 5 段资料 + 对话历史 + 当前问题
  ↓
⑧ 调用 LLM 流式生成，逐 token 推送 SSE
  ↓
⑨ 会话写回 Redis（TTL 1 小时）
```

**为什么要两道关卡（阈值 + Rerank）？** 两者解决的不是同一个问题：

- **阈值过滤**解决"根本没有相关资料"——比如问"Java 是什么"，库里只有红烧肉做法。余弦相似度可能只有 0.3，直接砍掉，避免误导模型。
- **Rerank** 解决"有相关资料，但排序不对"——向量检索为了快，用的是双塔模型（问题和文档分别编码后算距离），精度有限。Rerank 用的是交叉编码器（把问题和文档拼一起过一遍模型），慢但准，所以只对前 20 条做。

二者合起来：**先用便宜的召回保证速度，再用贵的重排保证精度**。

阈值为 0.77 是实测调出来的，配置项 `app.rag.score-threshold` 可改。调高 → 更保守，容易"没找到"；调低 → 更宽松，容易答偏。

### ③ 流式输出：为什么用 SSE 而不是 WebSocket

问答动辄要等好几秒。如果一次性返回，用户只能看着白屏。

项目用 **SSE（Server-Sent Events）** 实现逐字输出，数据格式：

```text
data:{"token":"Spring"}
data:{"token":" Boot"}
data:{"token":" 的核心"}
data:[DONE]
```

三个通道各司其职：

| 通道 | 用途 |
|------|------|
| `data:{"token":"..."}` | 正常文本片段 |
| `event:error` | 出错时推送 `Results` 格式的错误信息 |
| `data:[DONE]` | 正常结束标记（前端据此停止加载动画） |

**选 SSE 而非 WebSocket 的理由**：这是个单向推送（服务端 → 浏览器）场景，不需要双向通信。SSE 基于普通 HTTP，浏览器原生支持 `EventSource`，服务端就是一个 `SseEmitter`，没有握手、没有心跳维持、网关和代理对它也更友好。**用 WebSocket 属于杀鸡用牛刀。**

### ④ 多轮对话与查询改写

会话存在 Redis（`session:{sessionId}` → JSON 数组），TTL 1 小时。存在 Redis 而不是内存，是为了多实例部署时任意一台机器都能接住同一个会话。

**查询改写**是 RAG 里容易被忽略但很关键的一环：

| 轮次 | 用户输入 | 不改写会怎样 | 改写后 |
|------|---------|-------------|--------|
| 第 1 轮 | "Spring Boot 有哪些核心特性" | 正常 | — |
| 第 2 轮 | "**它**的默认端口是什么" | 拿"它的默认端口"去检索，向量库里查不到 | "Spring Boot 的默认端口是什么" |

改写通过一次额外的 LLM 调用完成，代价是多花一次 token，换来的是多轮问答的准确率。

### ⑤ 前端界面

单文件 `static/index.html`（约 1800 行），零构建、零框架，打开就能用：

- SSE 流式渲染 + Markdown 实时解析
- 深色 / 亮色主题切换
- 会话列表（存 localStorage）、消息复制
- 知识库面板：拖拽上传、文档列表、删除、目录扫描
- 移动端适配、自定义确认弹框与 toast

> **依赖已本地化**：Markdown 解析库 `marked` 放在 `static/vendor/marked.min.js`，不走外网 CDN。外网不可达时还有纯文本降级渲染，不会白屏。

## 2.3 能力边界

诚实说明项目**没有**做什么，避免误解：

| 维度 | 现状 |
|------|------|
| 用户体系 | **无登录、无鉴权**，所有数据共享。单机自用可以，多用户需自行加 Spring Security + 数据隔离 |
| 文档增量更新 | 同内容重复上传会跳过；**内容变了会当成新文档**，不会替换旧的（需先删再传） |
| 扫描件 PDF | 不支持 OCR，图片型 PDF 提取不到文字 |
| 并发能力 | 无消息队列、无线程池、无异步任务，所有处理都是请求内同步完成 |
| 大文件 | 上限 50MB（`spring.servlet.multipart`），超大文件解析会长时间占用请求线程 |
| 向量库 | 默认 `in-memory`，**重启后向量全部丢失**（MySQL 里的文档记录还在，但检索不到），需重新上传或用 Chroma/Milvus |

---

# 三、技术架构

## 3.1 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                     Web UI (index.html)                       │
│     SSE 流式渲染 · Markdown 解析 · 会话管理 · 知识库面板       │
└───────────────────────┬──────────────────────────────────────┘
                        │ POST /api/chat {sessionId, question}
                        ▼
┌──────────────────────────────────────────────────────────────┐
│                    RateLimitAspect (@RateLimit)               │
│        Redisson 令牌桶（IP + 全局每日）                        │
│        429 → 统一 Results 格式返回                             │
└───────────────────────┬──────────────────────────────────────┘
                        ▼
┌──────────────────────────────────────────────────────────────┐
│                      ChatController                           │
│            SseEmitter (60s timeout) · Jackson 序列化          │
└───────────────────────┬──────────────────────────────────────┘
                        ▼
┌──────────────────────────────────────────────────────────────┐
│                       RAG Pipeline                            │
│                                                               │
│  ┌──────────────┐   ┌───────────────┐   ┌─────────────────┐  │
│  │  会话历史      │──▶│  Query        │──▶│  Embedding      │  │
│  │  Redis        │   │  Rewriting    │   │  BGE-large-zh   │  │
│  └──────────────┘   └───────────────┘   └───────┬─────────┘  │
│                                                  ▼            │
│                                           ┌────────────┐     │
│                                           │ Vector      │     │
│                                           │ Store       │     │
│                                           │ top 20      │     │
│                                           └─────┬──────┘     │
│                                                  ▼            │
│  ┌──────────────┐   ┌───────────────┐   ┌─────────────────┐  │
│  │ LLM 回答      │◀──│ Prompt 组装    │◀──│ Rerank          │  │
│  │ DeepSeek /    │   │ 引用 + 约束   │   │ BGE-reranker   │  │
│  │ OpenAI 兼容   │   │              │   │ top 5          │  │
│  └──────────────┘   └───────────────┘   └─────────────────┘  │
│                                                               │
│  ┌───────────────────────────────────────────────────────┐    │
│  │ 文档来源                                               │   │
│  │  ├─ Web 上传（Multipart, SHA256 去重）                 │   │
│  │  └─ 目录扫描（data/docs/ 自动扫描）                    │   │
│  └───────────────┬───────────────────────────────────────┘    │
│                  │ 元数据                                     │
│                  ▼                                            │
│  ┌──────────────────────────────────────────────────────┐    │
│  │              MySQL + MyBatis-Plus                     │    │
│  │  documents（文档元数据） · document_chunks（分块映射） │    │
│  └──────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────┘

                         ┌────────────┐
                         │   Redis    │ ← 多实例共享会话 + 分布式限流 + 分布式锁
                         └────────────┘
```

**数据存在三个地方，各管一摊**：

| 存储 | 存什么 | 为什么是它 |
|------|--------|-----------|
| **向量库** | 文本块 + 向量 | 唯一能做"语义相似度检索"的地方 |
| **MySQL** | 文档元数据、分块→向量ID 映射 | 需要事务、需要能按条件查 |
| **Redis** | 会话历史、限流计数、分布式锁 | 需要过期时间、需要多实例共享 |

## 3.2 技术栈

| 分类 | 技术选型 | 版本 | 为什么选它 |
|------|---------|------|-----------|
| 语言 / 运行时 | Java | 8 | 兼容性最好，绝大多数企业后端仍在用 |
| 框架 | Spring Boot | 2.7.18 | 2.x 最后一个维护版本，也是支持 JDK 8 的最后一代 |
| AI 编排 | LangChain4j | 0.35.0 | 最后支持 JDK 8 的版本系列 |
| 对话模型 | OpenAI 兼容 API | — | 默认 DeepSeek；可换 GPT / GLM / 通义，只改配置 |
| Embedding | BAAI/bge-large-zh-v1.5 | 1024 维 | 中文效果好的开源模型，硅基流动免费额度可用 |
| Rerank | BAAI/bge-reranker-v2-m3 | — | 交叉编码器重排，与 Embedding 同一服务商 |
| 向量库 | InMemory / Chroma 0.4.24 / Milvus 2.3.x | — | 三档可选，按规模切换，只改一行配置 |
| 元数据库 | MySQL + MyBatis-Plus | 8.0 / 3.5.2 | 事务 + 条件查询，业务数据标准配置 |
| 缓存 | Redis + Redisson | 7 / 4.5.0 | 会话、限流、分布式锁共用一套客户端 |
| 文档解析 | POI 5.5.1 / PDFBox / JSoup | — | Office / PDF / HTML 三件套 |
| HTTP 客户端 | OkHttp | 4.12.0 | Rerank 与 SSE 调用，支持细粒度超时 |
| 参数校验 | Spring Boot Validation | — | `@Valid` + `@NotBlank` 声明式校验 |
| 构建 | Maven + Docker 多阶段 | — | 见 `Dockerfile`（构建用 maven:3.8-temurin-8，运行用 temurin:8） |
| 测试 | JUnit 5 + Mockito + MockWebServer | — | 6 个测试类，13 个用例 |

## 3.3 五条关键设计

这五处是本项目最有"后端味"的地方，也是面试常被追问的点。

### ① 分布式锁：同一个文件不许并发入库

```java
RLock lock = redissonClient.getLock("lock:document:ingest:" + contentHash);
if (!lock.tryLock(0, 60, TimeUnit.SECONDS)) {
    return existingDocument;   // 别的线程正在处理同一个文件，直接跳过
}
try {
    // 锁内二次查重 → 解析 → 切分 → 向量化 → 双写
} finally {
    if (lock.isHeldByCurrentThread()) lock.unlock();
}
```

**两个细节**：

- 锁的 key 用**内容哈希**而不是文件名。两个用户同时传同一份资料，会被同一把锁串行化。
- 拿到锁后必须**再查一次数据库**。因为可能在你等锁的这段时间里，前一个请求已经把它入库了——这叫 double-check。

锁的粒度分三级，互不干扰：

| 锁 Key | 场景 | 目的 |
|--------|------|------|
| `lock:document:ingest:{hash}` | 入库 | 防同一文件并发处理 |
| `lock:document:delete:{id}` | 删除 | 防删除与入库交叉 |
| `session:lock:{sessionId}` | 会话 | 防同一会话并发写坏历史 |

### ② 两级限流：控制成本而不是控制性能

LLM 调用**按 token 收费**，一次问答可能几毛钱。如果接口被刷，账单会很难看。所以限流在这里不是防 DDoS，而是**防钱包**。

| 层级 | 实现 | 默认值 | 作用 |
|------|------|--------|------|
| IP 令牌桶 | Redisson `RRateLimiter` | 容量 20，每 60 秒补满 | 单个来源的速率 |
| 每日总量 | Redis `INCR` + `EXPIRE` | 10000 次/天 | 全天成本兜底 |

用法是注解式：

```java
@RateLimit(value = "upload", maxCapacity = 10, supplementTimeOfSeconds = 60)
@PostMapping("/upload")
public Results<DocumentInfo> upload(@RequestParam("file") MultipartFile file) { ... }
```

**热更新技巧**：限流参数被编码进 Redis Key（如 `rl:upload:10:60:24`）。改了配置之后，Key 名就变了，等于自动切换到一套新桶，旧的随 TTL 自然过期——**不用重启，也不用清理旧数据**。

### ③ 统一响应与异常处理

所有同步接口返回同一个壳子：

```json
{ "resCode": "200", "msg": "成功", "obj": { } }
```

业务代码里只管 `throw`，不用到处 catch：

```java
throw BusinessException.badRequest("文件为空");
```

`GlobalExceptionHandler`（`@RestControllerAdvice`）统一翻译成 HTTP 状态 + `resCode`：

| 异常 | HTTP | 场景 |
|------|------|------|
| `BusinessException` | 由异常自带 | 主动抛出的业务错误 |
| `MethodArgumentNotValidException` | 400 | `@Valid` 校验失败 |
| `IllegalArgumentException` | 400 | 参数非法 |
| `NoSuchElementException` | 404 | 资源不存在 |
| `MaxUploadSizeExceededException` | 413 | 文件超过 50MB |
| `Exception` | 500 | 兜底 |

SSE 接口是例外——它一旦开始推送就不能再改 HTTP 状态码了，所以错误走 `event:error` 通道，data 内容仍用 `Results` 格式，前端一套解析逻辑通吃。

### ④ 三级缓存：缓存"事实"，不缓存"结论"

RAG 链路里每一步都能缓存，但收益和风险差别很大。本项目的取舍是**只缓存可复现的事实**：

| 缓存区 | 缓存内容 | TTL | 命中后省掉什么 |
|--------|---------|-----|---------------|
| `embedding` | 文本 → 向量（`float[]`） | 24h | 一次 Embedding API（100ms~1s，按 token 计费） |
| `retrieval` | 问题 → 检索片段列表 | 5min | Embedding + 向量检索 + Rerank 三次往返 |
| `documents` | 文档列表 | 10min | 一次 MySQL 查询 |

**为什么不缓存最终答案？** 答案依赖对话历史。同一句"它有什么好处"，在第一轮和第二轮指向的对象完全不同，缓存答案会让多轮对话"串味"。检索结果只依赖`问题文本 + 知识库内容`，知识库变更时主动失效，所以它是安全的缓存对象。

**一句话原则**：缓存"事实"是安全的，缓存"结合上下文的推理结论"是不安全的。

核心用法就是 Spring Cache 注解 + Redis 作为后端，配置在 `CacheConfig`：

```java
@Cacheable(cacheNames = CACHE_RETRIEVAL,
        key = "T(com.rag.studyhelper.config.CacheKeyGenerator).sha256(#searchQuery)",
        unless = "#result == null || #result.isEmpty()")
public List<RetrievedChunk> retrieve(String searchQuery, Embedding questionEmbedding) { ... }
```

这里有四个**踩过的坑**，每个都用代码注释详细记录在对应类里：

**坑 1：self-invocation 让 `@Cacheable` 静默失效。**
Spring Cache 靠 AOP 代理工作。如果 `A` 类里的方法调用**自己另一个**加了注解的方法，这次调用走的是 `this` 引用、不经过代理，注解被完全跳过。症状是：缓存永远不命中、Redis 里一个 key 都没有、**不报任何错**。解法是把检索逻辑拆到独立的 `RetrievalService`——顺带职责也更清晰。

```java
❌ 无效：a() { this.b(); }        // b() 上的注解不生效
✅ 有效：a() { otherBean.b(); }   // 跨 Bean 调用，走代理
```

**坑 2：第三方值对象不能直接进 JSON 缓存。**
LangChain4j 的 `Embedding` 没有无参构造函数，Jackson 反序列化会抛 `Cannot construct instance ... no Creators`。所以缓存的是 `float[]` 和自有 DTO，而不是第三方对象。**通用规则：缓存里放"数据"，不放"对象"。**

**坑 3：空结果不能用"方法内 evict"来处理。**
最初在方法末尾写了"结果为空就 `cache.evict(key)`"，日志打印了"已清除"，但 Redis 里那个 key 依然存在。原因是执行顺序：`方法体执行（evict，此时还没写入）→ 方法返回 → 拦截器写入空集合`，清除被后来的写入覆盖。正确做法是 `unless = "#result.isEmpty()"`，让拦截器自己跳过写入。

**坑 4：派生 getter 会被 Jackson 当成可读写属性。**
`IngestTask.isFinished()` 被当成 `finished` 字段：序列化时写进 JSON，反序列化时找不到 setter 就报 `Unrecognized field`。结果是任务状态能写进 Redis，但每次查询都 404"任务不存在"。用 `@JsonProperty(access = READ_ONLY)` 解决（前端仍需要这个字段判断是否停止轮询）。

另外做了**缓存降级**：`CacheConfig` 里注册了自定义 `CacheErrorHandler`，Redis 挂掉时只打 warn、直接回源查库，**不抛异常**。缓存是加速手段，不该成为新的故障点。

### ⑤ 线程池隔离：IO 密集与 CPU 密集不能共用一个池

两个线程池按任务性质分开，避免互相饿死：

| 线程池 | 场景 | core / max / queue | 拒绝策略 | 为什么这么配 |
|--------|------|-------------------|---------|------------|
| `ingestExecutor` | 文档入库（重 IO） | 2 / 4 / 200 | `CallerRunsPolicy` | 入库不能丢，队列满了让提交者自己跑 |
| `ragExecutor` | 并行 Embedding | 8 / 32 / 500 | `AbortPolicy` | 问答可失败重试，宁可快速报错也不堆积 |

**为什么必须分开？** 入库是长任务（大文件可能几十秒），问答是短任务。如果共用一个池，一次批量入库就能把池子占满，导致所有问答请求排队超时——这就是**线程池饥饿**。

**并行 Embedding** 是入库提速的关键。一个 50 页的 PDF 切出 200 个分块，串行调用 Embedding API 要几十秒。改造后按每批 10 条并行提交，用 `Semaphore` 限制并发度为 4：

```java
List<int[]> ranges = ...;                          // 每 10 条一个批次
Semaphore permits = new Semaphore(embeddingParallelism);   // 最多 4 个批次同时在飞
for (int[] range : ranges) {
    futures.add(ragExecutor.submit(() -> {
        permits.acquire();                          // 限制外部 API 并发，防止被限流
        try {
            List<Embedding> embeddings = cachedEmbeddingService.embedAll(batchTexts);
            // 按原始下标写回，保证顺序不乱
        } finally {
            permits.release();
        }
    }));
}
```

两个细节值得注意：

- **结果按原始下标写回**预填充的 `Collections.nCopies(size, null)` 列表，而不是 `add()`。并行执行完成顺序不确定，`add()` 会打乱分块顺序，导致检索结果的上下文错位。
- **单批失败降级为逐条重试**。批量请求可能因为其中一条超长而整体失败，逐条重试能把能救的都救回来。

**异步任务模式**则解决了"上传大文件时请求一直挂着"的体验问题：

```
POST /api/documents/upload-async  →  立即返回 taskId（PENDING）
                                    ↓ ingestExecutor 后台处理
GET  /api/documents/task/{taskId} →  PENDING → RUNNING → SUCCESS / FAILED
```

任务状态存 Redis（`ingest:task:{taskId}`，2 小时过期），所以**多实例部署时任一节点都能查到进度**。实测 6 个文件并发提交，全部在 200ms 内返回，后台 7~37ms 完成入库。

有一个必踩的细节：`MultipartFile` 的生命周期绑定在 HTTP 请求上，请求结束临时文件就被清理。所以**必须在请求线程里先 `getBytes()`**，再把字节数组交给异步线程，否则会抛 `File has been moved`。

---

# 四、项目目录

## 4.1 顶层结构

```
rag-study-helper/
├── src/                              # 源码
├── docs/                             # SQL 迁移脚本
│   └── remove-feishu.sql             #   历史迁移脚本
├── data/docs/                        # 文档扫描目录（启动时自动导入）
├── logs/                             # 运行日志
├── init.sql                          # MySQL 建表脚本
├── Dockerfile                        # 多阶段构建（Maven → Temurin 8）
├── docker-compose.yml                # Docker 部署：MySQL + Redis + Milvus + app
├── Dockerfile                        # 应用镜像构建（多阶段：Maven 构建 → JDK 运行）
├── .env.example                      # 环境变量模板
├── start.bat                         # Windows 一键启动脚本
├── pom.xml
└── README.md
```

## 4.2 代码分层

```
src/main/java/com/rag/studyhelper/
│
├── controller/                    ← 接入层：只做参数接收与结果返回
│   ├── ChatController.java             SSE 流式问答（POST /api/chat）
│   └── DocumentController.java         文档增删查（/api/documents/**）
│
├── service/                       ← 业务层：真正的逻辑都在这里
│   ├── RagQueryService.java            RAG 主流程编排（11 步）
│   ├── DocumentIngestionService.java   解析→切分→向量化→双写→补偿（12 步，最复杂）
│   ├── DocumentMetadataService.java    MySQL 事务边界
│   ├── QueryRewriteService.java        多轮查询改写
│   ├── RerankService.java              调用 Rerank API 重排序
│   ├── RateLimitService.java           令牌桶 + 每日计数
│   ├── ConversationStore.java          会话存储接口
│   └── RedisConversationStore.java     会话存储 Redis 实现
│
├── config/                        ← 配置层：Bean 装配与横切关注点
│   ├── LangChain4jConfig.java          Chat/Embedding/VectorStore 三个 Bean
│   ├── RedissonConfig.java             Redisson 客户端
│   ├── MyBatisPlusConfig.java          分页插件
│   ├── MyMetaObjectHandler.java        create_time/update_time 自动填充
│   ├── GlobalExceptionHandler.java     全局异常处理
│   ├── RateLimit.java                  @RateLimit 注解
│   ├── IpRateLimit.java                @IpRateLimit 注解
│   └── RateLimitAspect.java            限流切面（AOP 环绕通知）
│
├── mapper/                        ← 持久层：MyBatis-Plus BaseMapper
│   ├── DocumentsMapper.java
│   └── DocumentChunksMapper.java
│
├── model/                         ← 数据模型
│   ├── Documents.java                  documents 实体
│   ├── DocumentChunks.java             document_chunks 实体
│   ├── DocumentInfo.java               返回给前端的 DTO
│   ├── ChatRequest.java                问答请求体（带 @NotBlank 校验）
│   └── ChatMessage.java                role + content
│
├── exception/
│   └── BusinessException.java          带 HTTP 状态 + 业务码的自定义异常
│
├── utils/
│   └── Results.java                    统一响应壳（resCode / msg / obj）
│
└── RagStudyHelperApplication.java      启动类

src/main/resources/
├── application.yml                     唯一配置文件
└── static/
    ├── index.html                      前端单页（约 1800 行）
    └── vendor/marked.min.js            Markdown 解析库（本地化，不走 CDN）
```

## 4.3 按重要性排序的文件

如果要读懂这个项目，按这个顺序看，从高到低：

| 优先级 | 文件 | 行数级别 | 读它你能学到 |
|--------|------|---------|-------------|
| ★★★★★ | `DocumentIngestionService` | ~510 | 分布式锁、双写一致性、补偿事务、文档解析 |
| ★★★★★ | `RagQueryService` | ~200 | RAG 全链路、阈值过滤、SSE 推送 |
| ★★★★☆ | `RateLimitAspect` + `RateLimitService` | ~200 | AOP、注解参数读取、Redisson 令牌桶 |
| ★★★★☆ | `LangChain4jConfig` | ~130 | `@ConditionalOnProperty` 条件装配、第三方 SDK 接入 |
| ★★★☆☆ | `GlobalExceptionHandler` + `BusinessException` | ~120 | 统一异常设计 |
| ★★★☆☆ | `RedisConversationStore` | ~90 | Redis 序列化、TTL、并发保护 |
| ★★☆☆☆ | `QueryRewriteService` | ~90 | 用 LLM 做文本处理（不用 LLM 回答时它还能干什么） |
| ★★☆☆☆ | `RerankService` | ~130 | OkHttp 调外部 API、超时与容错 |
| ★☆☆☆☆ | `ChatController` / `DocumentController` | ~120 | Controller 应该只做多薄 |
| ★☆☆☆☆ | `Results` / `DocumentInfo` / `ChatRequest` | ~100 | 统一响应与 DTO |

---

# 五、配置说明

## 5.1 配置优先级

配置文件只有一个：`src/main/resources/application.yml`。

外部输入优先级从高到低：

```
命令行参数 / 环境变量   >   .env（仅 Docker Compose 读取）   >   application.yml 默认值
```

> **注意**：`mvn spring-boot:run` 和 IDEA 直接运行**都不会自动读取 `.env` 文件**。`.env` 只对 `docker compose` 有效。本地运行必须通过环境变量或 IDE 的 Run Configuration 传入。

## 5.2 模型配置

需要**两个独立的模型服务**，因为 DeepSeek 只提供对话，不提供 Embedding：

```yaml
app:
  rag:
    # ---- 对话模型（真正"写答案"的那个）----
    chat-api-key:                              # 环境变量 APP_RAG_CHAT_API_KEY
    chat-base-url: https://api.deepseek.com
    chat-model-name: deepseek-chat             # DeepSeek 可用值：deepseek-chat / deepseek-reasoner

    # ---- Embedding 模型（把文字转成向量）----
    embedding-api-key:                         # 环境变量 APP_RAG_EMBEDDING_API_KEY
    embedding-base-url: https://api.siliconflow.cn/v1
    embedding-model-name: BAAI/bge-large-zh-v1.5

    # ---- Rerank 模型（默认复用 Embedding 的 Key）----
    rerank-api-key: ${app.rag.embedding-api-key}
    rerank-base-url: https://api.siliconflow.cn/v1
    rerank-model-name: BAAI/bge-reranker-v2-m3

    # ---- 检索参数 ----
    score-threshold: 0.77                      # 相似度阈值，低于此值的内容不送给模型
    document-scan-path: data/docs              # 启动时扫描的目录
```

**两个 Key 都必填，缺一个都启动不了**（`LangChain4jConfig` 会抛 `openAiApiKey cannot be null or empty`）。

常用服务商对照：

| 服务商 | Chat Base URL | Embedding 模型 | Embedding Base URL |
|--------|--------------|---------------|-------------------|
| DeepSeek | `https://api.deepseek.com` | 不支持 | — |
| 硅基流动 | `https://api.siliconflow.cn/v1` | `BAAI/bge-large-zh-v1.5` | 同左 |
| OpenAI | `https://api.openai.com/v1` | `text-embedding-3-small`（1536 维） | 同左 |
| 阿里百炼 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `text-embedding-v2` | 同左 |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `embedding-2` | 同左 |

> ⚠ **换 Embedding 模型等于清空知识库**：不同模型的向量维度和空间不兼容。改了 `embedding-model-name` 之后，必须删除向量库里的旧数据重新入库，否则检索结果毫无意义。用 Milvus 时还要同步改 `milvus.dimension`。

## 5.3 向量库配置

三种模式靠一行配置切换，`LangChain4jConfig` 里用 `@ConditionalOnProperty` 做条件装配——**同一时刻只会创建其中一个 Bean**：

```yaml
vector:
  store:
    type: in-memory      # 可选：in-memory | chroma | milvus
```

| 模式 | 适用场景 | 数据持久性 | 额外部署 |
|------|---------|-----------|---------|
| `in-memory` | 开发调试、演示 | ❌ 重启即丢 | 无 |
| `chroma` | 个人/中型使用 | ✅ 持久化 | 需启动 Chroma 服务 |
| `milvus` | 大数据量、生产 | ✅ 持久化 + 分布式 | 需 etcd + MinIO + Milvus |

对应的连接参数：

```yaml
chroma:
  host: localhost
  port: 8000
  collection-name: rag_study_helper

milvus:
  host: localhost
  port: 19530
  collection-name: rag_study_helper
  dimension: 1024          # 必须与 Embedding 模型维度一致！
```

> Chroma 服务端必须用 **0.4.24**。0.6.x 起 API 有破坏性变更，langchain4j 0.35 调不通。

## 5.4 数据源与限流

```yaml
server:
  port: 8083               # 默认 8083（8080 常被其他程序占用，可按需改）

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/rag_study_helper?...
    username: root
    password: ${SPRING_DATASOURCE_PASSWORD:123456}   # 值从环境变量读，不写死在文件里

  redis:
    host: localhost
    port: 6379
    password:              # 留空表示无密码

  servlet:
    multipart:
      max-file-size: 50MB  # 单文件上限
      max-request-size: 50MB

app:
  rate-limit:
    ip-rate: 20                   # 单 IP 令牌桶容量
    ip-supplement-seconds: 60     # 每 60 秒补满
    ip-expire-hours: 24           # Redis Key 过期时间
    daily-max: 10000              # 全局每日上限

  # 线程池与并行度（高并发相关）
  async:
    ingest:                       # 文档入库（IO 密集，长任务）
      core-size: 2
      max-size: 4
      queue-capacity: 200
    rag:                          # 并行 Embedding（短任务）
      core-size: 8
      max-size: 32
      queue-capacity: 500
    embedding-batch-size: 10      # 每批多少条一起向量化
    embedding-parallelism: 4      # 同时在飞的批次数（Semaphore 许可数）

  # 缓存（Redis 后端）
  cache:
    key-prefix: "rag:cache:"      # 统一前缀，方便批量排查和清理
    documents-ttl-minutes: 10     # 文档列表缓存
    embedding-ttl-hours: 24       # 向量缓存
    retrieval-ttl-minutes: 5      # 检索结果缓存
```

**调参建议**：

- `embedding-parallelism` 不要超过 Embedding 服务商给你的 QPS 上限，否则会大量 429。硅基流动免费额度建议 2~4。
- `rag.max-size` 按机器核数调，一般 `核数 * 2 ~ 4`（IO 密集）。
- `retrieval-ttl-minutes` 别设太长。知识库更新频繁时，缓存过期越慢，用户看到旧结果的窗口就越长（入库/删除时已主动清缓存，这个 TTL 只是兜底）。

## 5.5 环境变量总表

复制模板起步：

```bash
cp .env.example .env
```

| 变量 | 必填 | 默认值 | 说明 |
|------|:----:|--------|------|
| `APP_RAG_CHAT_API_KEY` | ✅ | — | 对话模型密钥 |
| `APP_RAG_CHAT_BASE_URL` | | `https://api.deepseek.com` | 对话模型地址 |
| `APP_RAG_CHAT_MODEL_NAME` | | `deepseek-chat` | 对话模型名 |
| `APP_RAG_EMBEDDING_API_KEY` | ✅ | — | Embedding 密钥 |
| `APP_RAG_EMBEDDING_BASE_URL` | | 硅基流动 | Embedding 地址 |
| `APP_RAG_EMBEDDING_MODEL_NAME` | | `BAAI/bge-large-zh-v1.5` | Embedding 模型 |
| `APP_RAG_RERANK_API_KEY` | | 复用 Embedding | Rerank 密钥 |
| `SPRING_DATASOURCE_URL` | | `localhost:3306/rag_study_helper` | MySQL 连接串 |
| `SPRING_DATASOURCE_PASSWORD` | ✅ | `123456` | MySQL 密码 |
| `SPRING_REDIS_HOST` | | `localhost` | Redis 地址 |
| `SPRING_REDIS_PASSWORD` | | 空 | Redis 密码 |
| `SERVER_PORT` | | `8083` | 服务端口 |
| `APP_RATE_LIMIT_IP_RATE` | | `20` | IP 限流容量 |
| `APP_RATE_LIMIT_DAILY_MAX` | | `10000` | 每日上限 |
| `MYSQL_ROOT_PASSWORD` | | `root` | 仅 Docker Compose 用 |

> `.env` 已在 `.gitignore` 中，不会提交到仓库。**不要把真实密钥写进 `application.yml`。**

---

# 六、快速开始

## 6.1 前置条件

| 依赖 | 版本要求 | 检查命令 |
|------|---------|---------|
| JDK | 8 或以上（推荐 8 / 11 / 17） | `java -version` |
| Maven | 3.6+ | `mvn -v` |
| MySQL | 8.0+ | `mysql --version` |
| Redis | 5.0+（Windows 可用 msys2 版） | `redis-cli ping` |
| API Key | 对话模型 1 个 + Embedding 模型 1 个 | — |

> Redis 是必须的，哪怕只做单机试用——会话、限流、分布式锁都依赖它，**不是可选项**。

## 6.2 方式一：本地运行（推荐新手）

### 第 1 步：准备数据库

```bash
# 登录 MySQL
mysql -u root -p

# 建库
CREATE DATABASE IF NOT EXISTS rag_study_helper DEFAULT CHARSET utf8mb4;

# 导入表结构（退出 mysql 后在项目根目录执行）
mysql -u root -p rag_study_helper < init.sql

# 验证：应该看到 documents 和 document_chunks 两张表
mysql -u root -p rag_study_helper -e "SHOW TABLES;"
```

### 第 2 步：启动 Redis

```bash
# Linux / macOS
redis-server

# Windows（msys2 版）
redis-server.exe redis.conf

# 验证
redis-cli ping     # 返回 PONG 即正常
```

### 第 3 步：配置密钥

**三选一**：

**A. 改 `application.yml`**（最简单，但别提交到 git）

```yaml
app:
  rag:
    chat-api-key: sk-你的对话模型密钥
    embedding-api-key: sk-你的Embedding密钥
```

**B. 设环境变量**（推荐，密钥不落盘）

```bash
# Windows PowerShell
$env:APP_RAG_CHAT_API_KEY="sk-xxx"
$env:APP_RAG_EMBEDDING_API_KEY="sk-xxx"
$env:SPRING_DATASOURCE_PASSWORD="你的MySQL密码"

# Linux / macOS
export APP_RAG_CHAT_API_KEY="sk-xxx"
export APP_RAG_EMBEDDING_API_KEY="sk-xxx"
export SPRING_DATASOURCE_PASSWORD="你的MySQL密码"
```

**C. IDEA 里配**

`Run → Edit Configurations → Environment variables`，把上面几个变量填进去。

### 第 4 步：启动

```bash
# 方式 A：直接跑
mvn spring-boot:run

# 方式 B：打包后跑（推荐，启动更快）
mvn clean package -DskipTests
java -jar target/rag-study-helper-0.0.1-SNAPSHOT.jar
```

Windows 用户可直接双击项目根目录的 `start.bat`（已内置环境变量与端口设置）。

看到这行就是成功了：

```
Started RagStudyHelperApplication in 7.584 seconds
Tomcat started on port(s): 8083 (http)
```

### 第 5 步：打开界面

浏览器访问 **http://localhost:8083**

## 6.3 方式二：Docker Compose

需要先安装 Docker Desktop，且**能正常拉取镜像**。

```bash
# 1. 准备环境变量
cp .env.example .env
# 编辑 .env，填入两个 API Key 和 MySQL 密码

# 2. 启动（Milvus 生产级方案）
docker compose up -d

# 3. 看日志
docker compose logs -f app

# 4. 等待就绪
docker compose ps        # milvus 就绪约需 1-2 分钟

# 5. 访问 http://localhost:8080（注意：Docker 内映射的是 8080）
```

| 服务 | 镜像 | 作用 |
|------|------|------|
| app | 本地构建 | Spring Boot 应用 |
| mysql | mysql:8.0 | 文档元数据，首次启动自动执行 `init.sql` |
| redis | redis:7-alpine | 会话 + 限流 + 锁 |
| milvus | milvusdb/milvus:v2.3.3 | 向量库（持久化） |
| etcd | quay.io/coreos/etcd:v3.5.5 | Milvus 元数据存储 |
| minio | minio/minio | Milvus 对象存储 |

> Milvus 还依赖 etcd 和 MinIO，首次启动要等 1–2 分钟。用 `docker compose ps` 检查健康状态。
> `.env` 里的 `SPRING_DATASOURCE_URL` 主机名是 `mysql`（容器内网），与本地的 `localhost` 不同，别混用。

## 6.4 验证是否成功

按顺序验证，每一步都过了才算真的跑通：

**① 首页能打开**

```bash
curl -o /dev/null -w "%{http_code}\n" http://localhost:8083/
# 期望：200
```

**② 文档列表接口正常**

```bash
curl http://localhost:8083/api/documents
# 期望：{"resCode":"200","msg":"成功","obj":[]}
```

**③ 上传一个文件能入库**

```bash
# 先准备一个测试文件
echo "# 测试文档

Spring Boot 的核心特性包括自动配置、起步依赖、内嵌容器、Actuator 监控。" > test.md

curl -X POST http://localhost:8083/api/documents/upload -F "file=@test.md"
# 期望：{"resCode":"200","msg":"成功","obj":{"id":1,"documentName":"test.md","chunks":1}}
```

或者在浏览器知识库面板里拖一个 PDF 进去。

**④ 参数校验生效**

```bash
curl -X POST http://localhost:8083/api/chat \
  -H "Content-Type: application/json" -d '{"sessionId":"t1","question":""}'
# 期望：HTTP 400，{"resCode":"400","msg":"question: question 不能为空"}
```

**⑤ 完整问答链路（最关键）**

```bash
curl -N -X POST http://localhost:8083/api/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"t1","question":"Spring Boot 有哪些核心特性"}'
```

期望看到逐 token 推流，并且回答里**引用了刚上传的文档**：

```
data:{"token":"根据"}
data:{"token":" ["}
data:{"token":"来源"}
...
data:[DONE]
```

如果回答里出现了 `[来源:test.md]`，说明**整条链路都通了**：解析 → 切分 → Embedding → 向量检索 → 阈值过滤 → Rerank → LLM 生成 → SSE 推送。

**⑥ Redis 有数据了**

```bash
redis-cli --scan
# 期望看到：session:t1、rl:daily:chat:2026-xx-xx、rl:ip:chat:... 等
```

## 6.5 常见问题

### ❌ 启动报 `openAiApiKey cannot be null or empty`

**原因**：`APP_RAG_CHAT_API_KEY` 没读到。

**排查**：
- 用 `mvn spring-boot:run` 时 `.env` 文件不会自动加载，必须设成环境变量
- IDEA 里配了变量但没勾选启用
- 变量名拼错（注意是 `APP_RAG_CHAT_API_KEY`，不是 `APP_CHAT_API_KEY`）

### ❌ 启动报 `Port XXXX was already in use`

**排查**：

```bash
# Windows
netstat -ano | findstr :8083
# Linux / macOS
lsof -i :8083
```

要么杀掉占用进程，要么改 `server.port`。

### ❌ 上传成功但问答说"没有找到相关资料"

**可能原因，按概率排序**：

1. **向量库是 `in-memory`，重启过**——向量丢了。MySQL 里还有文档记录，但检索不到。解决：重新上传，或改用 Chroma/Milvus。
2. **阈值 0.77 太高**——把你的问题换个说法再问；或临时调低 `score-threshold` 到 `0.6` 试试。
3. **Embedding Key 无效**——检查日志有没有 `401` 或 `Embedding failed`。
4. **文档内容太少**——切分后每块太短，语义信息不足。

### ❌ 网页能打开，但上传文件提示失败

**先看浏览器控制台（F12）**，两种典型情况完全不同：

- **Console 报 `marked is not defined` 或静态资源 404** → 前端依赖问题。项目已把 `marked` 本地化到 `static/vendor/`，若仍报错，检查 jar 里是否包含该文件（`unzip -l target/*.jar | grep vendor`），并**硬刷新**浏览器（`Ctrl + Shift + R`）。
- **Network 里 `/api/documents/upload` 返回 4xx/5xx** → 看响应体里的 `msg` 字段，那是真实原因。

> 注意：日志里出现 `Cannot delete ...upload_xxx.tmp` 是 Windows 临时文件清理告警，发生在业务完成后，**不影响上传结果**，可忽略。

### ❌ Docker 启动后连不上 MySQL

`.env` 里的 `SPRING_DATASOURCE_URL` 主机名在容器内必须是 `mysql`（服务名），不是 `localhost`。

### ❌ 换了 Embedding 模型后检索全乱

向量维度和空间都变了。必须清空向量库重新入库：

```bash
# Milvus：删数据卷
docker compose down -v

# InMemory：重启应用即可（本来就不持久化）
```

同时确认 `milvus.dimension` 与新模型维度一致（bge-large-zh 是 1024，OpenAI text-embedding-3-small 是 1536）。

---

# 附录

## A. API 参考

所有同步接口统一返回：

```json
{ "resCode": "200", "msg": "成功", "obj": { } }
```

| resCode | 含义 |
|---------|------|
| `200` | 成功 |
| `400` | 参数错误 |
| `404` | 资源不存在 |
| `413` | 文件过大 |
| `429` | 请求过于频繁（限流） |
| `500` | 服务器内部错误 |

### 接口列表

| 接口 | 方法 | 请求 | 返回 | 说明 |
|------|------|------|------|------|
| `/api/chat` | POST | `{"sessionId","question"}` | SSE | 流式问答，错误走 `event:error` |
| `/api/documents/upload` | POST | `multipart/form-data` | `Results<DocumentInfo>` | 同步上传，阻塞到入库完成（限流 10 次/分钟） |
| `/api/documents/upload-async` | POST | `multipart/form-data` | `Results<IngestTask>` | **异步**上传，立即返回 `taskId`（限流 60 次/分钟） |
| `/api/documents/task/{taskId}` | GET | — | `Results<IngestTask>` | 查询异步任务状态，404 表示不存在或已过期 |
| `/api/documents` | GET | — | `Results<List<DocumentInfo>>` | 已入库文档列表（10 分钟缓存） |
| `/api/documents/scan` | POST | — | `Results<List<DocumentInfo>>` | 扫描 `data/docs/` |
| `/api/documents/{id}` | DELETE | — | `Results<Void>` | 删除文档及向量（同时清缓存） |

**两个上传接口怎么选？**

- 小文件（几 MB）、想立刻拿到结果 → `/upload`
- 大文件（几十 MB 的 PDF）、不想让请求挂几十秒 → `/upload-async` + 轮询

异步任务的 `status` 流转：`PENDING → RUNNING → SUCCESS / FAILED`，`finished` 字段可直接用于判断是否停止轮询：

```bash
# 提交
curl -X POST -F "file=@big.pdf" http://localhost:8083/api/documents/upload-async
# {"obj":{"taskId":"a20a8f5f...","status":"PENDING","finished":false}}

# 轮询（建议 1~2 秒一次）
curl http://localhost:8083/api/documents/task/a20a8f5f...
# {"obj":{"status":"SUCCESS","chunks":30,"costMillis":8,"finished":true}}
```

### SSE 协议

```text
# 正常
data:{"token":"逐"}
data:{"token":"步"}
data:[DONE]

# 出错
event:error
data:{"resCode":"500","msg":"流式处理失败: Redis 连接异常"}
data:[DONE]
```

## B. 测试

```bash
mvn test
```

| 测试类 | 用例 | 覆盖点 |
|--------|:----:|--------|
| `ChatRequestValidationTest` | 2 | 请求参数校验 |
| `GlobalExceptionHandlerTest` | 2 | 异常 → HTTP 状态映射 |
| `DocumentMetadataServiceTest` | 2 | 元数据事务与分块关联 |
| `QueryRewriteServiceTest` | 3 | 查询改写触发条件 |
| `RateLimitServiceTest` | 3 | 令牌桶与每日限流 |
| `RedisConversationStoreTest` | 1 | 会话序列化与并发写入 |
| **合计** | **13** | |

## C. JDK 8 版本锁定说明

本项目刻意锁死了一整条版本链，改动任何一环都要连带改其他环：

| 组件 | 版本 | 原因 |
|------|------|------|
| Java | 8 | 起点约束 |
| Spring Boot | 2.7.18 | 2.x 最后维护版 |
| LangChain4j | 0.35.0 | **0.36.0 起要求 JDK 17** |
| Chroma | 0.4.24 | 0.6.x+ API 不兼容 langchain4j 0.35 |
| Milvus | 2.3.x | 与 langchain4j 0.35 匹配 |
| redisson-spring-data | 27 | Redisson 4.5.0 默认带 `spring-data-40`，与 Boot 2.7 不兼容，已在 `pom.xml` 里排除并显式引入 27 |
| snakeyaml | 2.2 | 覆盖 Boot 默认版本，修复中文注释 YAML 解析时的 `ArrayIndexOutOfBoundsException` |

想升级到新版，需要**整套一起升**：JDK 17 + Spring Boot 3.x + LangChain4j 0.36+。这不是改个版本号的事，Spring Boot 3 把 `javax.*` 换成了 `jakarta.*`，所有 Web 相关 import 都要改。
