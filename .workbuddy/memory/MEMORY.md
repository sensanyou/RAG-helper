# 项目长期约定

## 绝对不能做的事

- **不要修改用户 MySQL 的账号密码**（2026-10-21 用户明确要求）。环境不一致时只改项目配置（`application.yml` / `.env`），绝不执行 `ALTER USER`。
- 不要把真实密钥（API Key、密码）写进 `application.yml`。
- **不要再产出学习文档 / 学习计划**（2026-10-21 用户明确要求）。不要新建 `docs/*STUDY*`、`docs/*ANATOMY*`、`notes/`、N 天计划之类的材料。用户不需要这些，只在对话里回答即可。已删除 `docs/PROJECT_ANATOMY.md`、`docs/STUDY_PLAN_22D.md`、`docs/BACKEND_STUDY.md`。

## 环境事实

- 本机 MySQL 8.0.46（Windows 服务 `MySQL80`），root 密码为 `SSC163425`，库名 `rag_study_helper`。
- `application.yml` 中 `spring.datasource.password` 使用 `${SPRING_DATASOURCE_PASSWORD:123456}` 形式，真实密码经环境变量传入。
- Redis 已安装于 `E:\Redis-8.10.2-Windows-x64-msys2\`（msys2 版，**无密码**，`bind 127.0.0.1`），启动：`redis-server.exe redis.conf`。CLI 需全路径，且用 `--scan` 而非 `keys`。
- **Redis 不是 Windows 服务，不会开机自启**。启动报 `RedisConnectionException: Unable to connect ... 6379` 时，第一件事就是确认 Redis 有没有起来（`netstat -ano | grep 6379`）。进程异常退出会留 `redis_6379.pid`，启动前删掉即可（不影响 `dump.rdb`）。
- `mvn dependency:tree` 在本机**离线模式不可用**（插件自身依赖未下载）。排查依赖来源改用 grep `~/.m2/repository` 下的 `*.pom` 反查。
- 应用端口已改为 **8083**（8080 被 `ApplicationWebServer` 占用）。启动 jar 前必须显式设 `SERVER_PORT=8083`（沙箱会注入 63724 干扰）。
- 向量库已改为 **Milvus-only**（2026-10-25 用户明确"我要用milvus"）：`vector.store.type=milvus`，in-memory/chroma 的 bean、配置段、pom 依赖已全部删除。
- **Docker Desktop 装在用户目录**：`C:\Users\hp\AppData\Local\Programs\DockerDesktop\`（WSL2 后端，发行版 `docker-desktop`）。沙箱 PATH 里没有 docker，调用要全路径：`/c/Users/hp/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe`，且需把该 bin 目录加进 PATH 才能找到 `docker-credential-desktop`（否则拉镜像报 credentials 错误）。
- **MinIO 镜像国内全部"not found"**（官方已从国内镜像站下架）；Milvus **内嵌 etcd 模式在 Docker Desktop/WSL2 下启动即 SIGSEGV panic**（v2.3.3 与 v2.4.15 都试过）。最终方案：**独立 etcd 容器（quay.io 直连可用）+ `COMMON_STORAGETYPE=local` 本地存储**，docker-compose.yml 已按此改写，启动 `docker compose up -d etcd milvus`。
- Milvus API Key 等密钥在 `start.bat` 里以环境变量注入（APP_RAG_CHAT_API_KEY / APP_RAG_EMBEDDING_API_KEY），命令行启动 jar 时要带上。
- 认证头是标准 `Authorization: Bearer <token>`（或 `X-Auth-Token`）；register 响应里的 `tokenHeader:"auth.token"` 是内部常量名，不是请求头名。
- **本机外网受限**：`cdn.jsdelivr.net` SSL 握手失败（curl exit 35），`cdn.bootcdn.net` 可用。前端所有第三方库必须本地化到 `static/vendor/`，不要用 jsdelivr/googleapis。

## Git / GitHub

- 远程：`https://github.com/sensanyou/RAG-helper.git`，分支 `main`。
- **本机直连 GitHub 不通**（443 超时）；WorkBuddy 注入的 `http_proxy=127.0.0.1:63741` 会让 git 报 `CONNECT tunnel failed, response 502`。
- **必须用本地 SOCKS5 `127.0.0.1:10808`**（已写入仓库级 `http.proxy`/`https.proxy`，终端直接 `git push` 即可）。该代理需客户端在运行；报连不上时先确认 10808 有监听。
- `logs/` 已加入 `.gitignore` 并移出跟踪，运行日志不进版本库。

## 已完成的改造

- 2026-10-25：**多用户改造 + Milvus 迁移全链路验证通过**——注册/登录（PBKDF2+token）、owner_id 全链路隔离（MySQL 唯一键 + Milvus metadata filter + 缓存/会话 key 含 userId）、任务化异步上传（PENDING/PROCESSING/COMPLETED/FAILED + 重试）、SSE sources 可追溯（真实分数+文件名）。E2E：401 拦截 ✓、A/B 隔离 ✓、A 检索命中 score=0.843 ✓、B 检索为空 ✓、异步任务 PENDING→COMPLETED ✓。
- 2026-10-25：向量库切 Milvus-only（删 in-memory/chroma bean+配置+依赖），docker-compose 改 etcd+本地存储（无 MinIO），Milvus 镜像 v2.4.15。

- 2026-10-21：移除飞书同步模块（5 个类 + 配置 + 表字段）。
- 2026-10-21：修复前端"上传失败"误报——marked.js 本地化 + `mdRender()` 降级 + 字体异步加载 + 上传错误提示增强。
- 2026-10-21：高并发 + 缓存改造——`AsyncConfig`（双线程池）、`CacheConfig`（Redis 三缓存区 + 降级）、`CacheKeyGenerator`、`CachedEmbeddingService`、`RetrievalService`（解决 self-invocation）、`AsyncIngestService`（异步任务 + Redis 状态）、并行 Embedding + Semaphore 限流、`IngestTask`/`RetrievedChunk` DTO、异步上传接口。`mvn test` 21/21 通过。

## 用户背景与讲解方式

- 用户仅具备 Java SE 基础，目标是把这个 RAG 项目作为后端学习项目 + 改造 + 面试准备。
- **用户不需要生成任何学习文档/计划文件**，讲解直接在对话里完成。
- 讲解：先给结论，再给依据；避免术语堆砌；要讲"为什么"而不只是"是什么"；对项目定位要诚实，不要为了夸改造效果而夸大"高并发"。
