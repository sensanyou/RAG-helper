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
- 向量库默认 `in-memory`，重启丢向量（已加 `vectorStoreHasData()` 探测 + 自动重建）。
- **本机外网受限**：`cdn.jsdelivr.net` SSL 握手失败（curl exit 35），`cdn.bootcdn.net` 可用。前端所有第三方库必须本地化到 `static/vendor/`，不要用 jsdelivr/googleapis。

## Git / GitHub

- 远程：`https://github.com/sensanyou/RAG-helper.git`，分支 `main`。
- **本机直连 GitHub 不通**（443 超时）；WorkBuddy 注入的 `http_proxy=127.0.0.1:63741` 会让 git 报 `CONNECT tunnel failed, response 502`。
- **必须用本地 SOCKS5 `127.0.0.1:10808`**（已写入仓库级 `http.proxy`/`https.proxy`，终端直接 `git push` 即可）。该代理需客户端在运行；报连不上时先确认 10808 有监听。
- `logs/` 已加入 `.gitignore` 并移出跟踪，运行日志不进版本库。

## 已完成的改造

- 2026-10-21：移除飞书同步模块（5 个类 + 配置 + 表字段）。
- 2026-10-21：修复前端"上传失败"误报——marked.js 本地化 + `mdRender()` 降级 + 字体异步加载 + 上传错误提示增强。
- 2026-10-21：高并发 + 缓存改造——`AsyncConfig`（双线程池）、`CacheConfig`（Redis 三缓存区 + 降级）、`CacheKeyGenerator`、`CachedEmbeddingService`、`RetrievalService`（解决 self-invocation）、`AsyncIngestService`（异步任务 + Redis 状态）、并行 Embedding + Semaphore 限流、`IngestTask`/`RetrievedChunk` DTO、异步上传接口。`mvn test` 21/21 通过。

## 用户背景与讲解方式

- 用户仅具备 Java SE 基础，目标是把这个 RAG 项目作为后端学习项目 + 改造 + 面试准备。
- **用户不需要生成任何学习文档/计划文件**，讲解直接在对话里完成。
- 讲解：先给结论，再给依据；避免术语堆砌；要讲"为什么"而不只是"是什么"；对项目定位要诚实，不要为了夸改造效果而夸大"高并发"。
