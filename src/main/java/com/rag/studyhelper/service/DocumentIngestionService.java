package com.rag.studyhelper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.studyhelper.config.CacheConfig;
import com.rag.studyhelper.exception.BusinessException;
import com.rag.studyhelper.mapper.DocumentChunksMapper;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.model.DocumentChunks;
import com.rag.studyhelper.model.DocumentInfo;
import com.rag.studyhelper.model.Documents;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.Tokenizer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import org.apache.commons.io.IOUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 文档处理服务
 * 包括 文档解析、向量化、入库（向量数据库 + 关系型数据库）
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    /**
     * 缓存"向量库里是否有数据"的探测结果。
     * <p>
     * 用 {@link java.util.concurrent.atomic.AtomicReference} 而不是 boolean，
     * 是为了区分三态：{@code null}=还没探测过、{@code true}=有数据、{@code false}=确认空。
     * 这样"确认为空"的情况下次会重新探测（因为上传成功后就会变成非空）。
     */
    private final java.util.concurrent.atomic.AtomicReference<Boolean> vectorStoreState =
            new java.util.concurrent.atomic.AtomicReference<>();

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final DocumentsMapper documentsMapper;
    private final DocumentChunksMapper documentChunksMapper;
    private final DocumentMetadataService documentMetadataService;
    private final RedissonClient redissonClient;
    private final CachedEmbeddingService cachedEmbeddingService;
    private final ThreadPoolTaskExecutor ragExecutor;
    private final org.springframework.cache.CacheManager cacheManager;

    @Value("${app.rag.document-scan-path}")
    private String scanPath;

    /** 单次批量 Embedding 的条数，与远程 API 的批大小保持一致 */
    @Value("${app.async.embedding-batch-size:10}")
    private int embeddingBatchSize;

    /** 并行 Embedding 的并发度上限，防止一次提交过多批次把服务商配额打满 */
    @Value("${app.async.embedding-parallelism:4}")
    private int embeddingParallelism;

    public DocumentIngestionService(EmbeddingStore<TextSegment> embeddingStore,
                                    EmbeddingModel embeddingModel,
                                    DocumentsMapper documentsMapper,
                                    DocumentChunksMapper documentChunksMapper,
                                    DocumentMetadataService documentMetadataService,
                                    RedissonClient redissonClient,
                                    CachedEmbeddingService cachedEmbeddingService,
                                    @Qualifier("ragExecutor") ThreadPoolTaskExecutor ragExecutor,
                                    org.springframework.cache.CacheManager cacheManager) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.documentsMapper = documentsMapper;
        this.documentChunksMapper = documentChunksMapper;
        this.documentMetadataService = documentMetadataService;
        this.redissonClient = redissonClient;
        this.cachedEmbeddingService = cachedEmbeddingService;
        this.ragExecutor = ragExecutor;
        this.cacheManager = cacheManager;
    }

    // 初始化用于跑本地文档
    @PostConstruct
    public void init() {
        scanAndIngest();
    }

    /**
     * 判断文件内容是否已经入库，通过hash判断文件一致性
     */
    private String sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 扫描目录下所有文件，并解析入库
     */
    public List<DocumentInfo> scanAndIngest() {
        log.info("Scanning document directory: {}", scanPath);
        List<DocumentInfo> results = new ArrayList<>();
        File dir = new File(scanPath);
        if (!dir.exists()) {
            dir.mkdirs();
            log.info("Created document directory: {}", scanPath);
            return results;
        }
        File[] files = dir.listFiles((d, name) -> {
            String n = name.toLowerCase();
            return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv")
                    || n.endsWith(".json") || n.endsWith(".xml")
                    || n.endsWith(".pdf")
                    || n.endsWith(".xlsx") || n.endsWith(".xls")
                    || n.endsWith(".docx")
                    || n.endsWith(".pptx")
                    || n.endsWith(".html") || n.endsWith(".htm");
        });
        if (files == null || files.length == 0) {
            log.info("No documents found in {}", scanPath);
            return results;
        }
        for (File file : files) {
            try {
                DocumentInfo info = ingestDocument(file.toPath());
                results.add(info);
            } catch (Exception e) {
                log.error("Failed to ingest document: {}", file.getName(), e);
            }
        }
        log.info("Document scan complete. Total ingested: {}", results.size());
        return results;
    }

    /**
     * 通过文件路径直接解析文档入库
     */
    public DocumentInfo ingestDocument(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString();
        try (InputStream inputStream = new FileInputStream(filePath.toFile())) {
            return ingestDocument(fileName, inputStream, "SCAN");
        }
    }

    /**
     * 通过输入流直接解析文档入库
     */
    public DocumentInfo ingestDocument(String fileName, InputStream inputStream) throws IOException {
        return ingestDocument(fileName, inputStream, "UPLOAD");
    }

    private DocumentInfo ingestDocument(String fileName, InputStream inputStream, String source) throws IOException {
        byte[] content = IOUtils.toByteArray(inputStream);
        if (content.length == 0) {
            throw BusinessException.badRequest("文件内容为空");
        }
        String hash = sha256(content);
        log.info("Ingesting document: {}, hash={}", fileName, hash);

        RLock lock = redissonClient.getLock("lock:document:ingest:" + hash);
        boolean locked = false;
        try {
            locked = lock.tryLock(10, TimeUnit.SECONDS);
            if (!locked) {
                throw BusinessException.conflict("相同文档正在处理中，请稍后重试");
            }

            // 锁内再次检查，避免并发上传时重复向量化和重复插入。
            Documents existing = documentsMapper.selectOne(
                    Wrappers.<Documents>lambdaQuery().eq(Documents::getContentHash, hash)
            );
            if (existing != null && vectorStoreHasData()) {
                log.info("Document already ingested: {} (hash={})", existing.getDocumentName(), hash);
                return new DocumentInfo(existing.getId(), existing.getDocumentName(), existing.getChunkCount());
            }
            if (existing != null) {
                // MySQL 里有记录，但向量库里是空的 —— 典型场景：in-memory 向量库 + 进程重启。
                // 此时若直接返回"已存在"，用户会看到"上传成功"但永远检索不到内容
                // （因为向量库已经随进程一起清空了）。
                // 所以这里必须重建向量：删除旧记录后走完整的入库流程。
                log.warn("检测到向量库为空但 MySQL 存在同 hash 记录（in-memory 重启场景），"
                        + "重建向量: {} (id={})", existing.getDocumentName(), existing.getId());
                documentsMapper.deleteById(existing.getId());
            }

            Document document = parseDocument(fileName, new ByteArrayInputStream(content));
            return processAndSave(document, fileName, source, hash, (long) content.length,
                    "UPLOAD".equals(source) ? "upload" : "scan");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "409",
                    "文档处理锁等待被中断", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 探测向量库里<b>是否有任何</b>数据。
     * <p>
     * <b>为什么需要这个探测？</b>
     * 这是 {@code in-memory} 向量库 + MySQL 哈希去重组合出的一个隐藏 bug：
     * <ol>
     *   <li>第一次启动，上传文档 A → 向量写入内存，MySQL 记录 hash；</li>
     *   <li>重启进程 → <b>内存里的向量全没了</b>，但 MySQL 记录还在；</li>
     *   <li>再次上传文档 A → 哈希去重命中 → 直接返回"已存在"，<b>向量从未重建</b>；</li>
     *   <li>结果：接口返回成功，但检索永远返回 0 条。</li>
     * </ol>
     * 用户的表现就是"上传成功了，但问什么都答不上来"——极难排查。
     * <p>
     * <b>实现方式</b>：用 {@code maxResults(1)} 做一次探针查询——
     * 只要返回任意匹配就说明"库里有东西"，我们只关心"空 / 非空"，不关心相似度。
     * 即使库里有几十万条向量，这个探测的开销也可以忽略。
     * <p>
     * <b>对持久化向量库（chroma / milvus）而言这个判断恒为 true</b>，
     * 所以不会影响正常生产行为；它只在 in-memory 场景下起作用。
     * <p>
     * <b>结果做了缓存</b>（{@link #vectorStoreState}）：一旦确认库里有数据，
     * 本进程内就不会再重复探测。因为向量库在单个进程生命周期内只会"从空变满"，
     * 不会"从满变空"，缓存这个状态是安全的。
     */
    private boolean vectorStoreHasData() {
        Boolean cached = vectorStoreState.get();
        if (cached != null && cached) {
            return true;
        }
        try {
            // 探针向量必须与库里已有向量"同维度"，否则很多向量库会直接报错。
            // 所以用真实 embedding 模型转一个固定短句，而不是手工造全零向量。
            // "probe" 会走 Embedding 缓存，每个进程最多产生一次 API 开销。
            float[] dimSource = cachedEmbeddingService.embedVector("probe");
            Embedding probe = Embedding.from(new float[dimSource.length]);
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(probe)
                    .maxResults(1)
                    .build();
            boolean hasData = !embeddingStore.search(request).matches().isEmpty();
            vectorStoreState.set(hasData);
            return hasData;
        } catch (Exception e) {
            // 探测失败时宁可"认为库里有数据"，避免每次上传都全量重建造成重复向量
            log.debug("向量库探测失败，按非空处理: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 解析文档
     */
    private Document parseDocument(String fileName, InputStream inputStream) throws IOException {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return new ApachePdfBoxDocumentParser().parse(inputStream);
        } else if (lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".csv") || lower.endsWith(".json")
                || lower.endsWith(".xml")) {
            return new TextDocumentParser().parse(inputStream);
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return parseExcel(inputStream);
        } else if (lower.endsWith(".docx")) {
            return parseWord(inputStream);
        } else if (lower.endsWith(".pptx")) {
            return parsePowerPoint(inputStream);
        } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return parseHtml(inputStream);
        } else {
            throw new IllegalArgumentException("Unsupported file: " + fileName);
        }
    }

    /**
     * 入库主流程
     */
    private DocumentInfo processAndSave(Document document, String fileName, String source,
                                        String contentHash, Long fileSize,
                                        String creator) throws IOException {
        Tokenizer tokenizer = new OpenAiTokenizer();
        String prefix = "[来源:" + fileName + "]\n";
        int prefixTokenCount = tokenizer.estimateTokenCountInText(prefix);
        if (prefixTokenCount > 200) {
            log.warn("文件名前缀占用 token 过多: {} tokens, fileName={}", prefixTokenCount, fileName);
        }

        // bge-large-zh-v1.5 的上限为 512 token （虽然 bge-large-zh-v1.5 上限低，但他在硅基流动上是免费的，而且能力也不错）
        // 如果切换模型后，那么向量数据库中记录的数据都不可用了，要注意哦
        int maxSegmentSize = Math.max(50, 512 - prefixTokenCount);
        int maxOverlap = 51;
        // 基于 token 的分割器，层级降级（官方推荐）
        DocumentSplitter splitter = DocumentSplitters.recursive(
                // maxSegmentSize: 每个分段最大token数
                maxSegmentSize,
                // maxOverlap: 段落间重叠token数
                maxOverlap,
                // separator 优先级
                tokenizer
        );
        List<TextSegment> segments = splitter.split(document);
        if (segments.isEmpty()) {
            throw BusinessException.badRequest("文档没有可入库的文本内容");
        }

        segments.replaceAll(textSegment -> TextSegment.from(
                prefix + textSegment.text()));

//        todo 权限 rag 新增 仅 chroma 和 milvus 使用 如果是 私有文档，使用 .put("document_id", docRecord.getId())))
//        Metadata 的内容不会被送入 Embedding 模型，因此不消耗模型的 token 额度。
//        segments.replaceAll(textSegment -> TextSegment.from(
//                "[来源:" + fileName + "]\n" + textSegment.text(),
//                new Metadata()
//                        .put("visibility", "public")));

        // ── 并行 Embedding ──────────────────────────────────────────────
        // 原来是一批一批串行等 HTTP 响应：34 个分块 = 4 批 = 4 个 RTT 相加。
        // 改成并行后总耗时 ≈ 最慢的那一批，而不是所有批次之和。
        //
        // 为什么能并行？因为每批 Embedding 之间「互相不依赖」——这是可并行的前提。
        // 如果第 N 批要用第 N-1 批的结果，就只能串行，强行并行会算错。
        //
        // 并发度为什么要限制？不是线程越多越快：
        //   1. 服务商侧有 QPS 配额，超了会返回 429，反而要重试，总耗时更长；
        //   2. 并发过高会占满 ragExecutor，影响同时在跑的问答请求。
        // 所以用「固定大小的窗口」而不是「一次全发」。
        // 用固定大小的占位列表，保证 allEmbeddings 与 successSegments 下标一一对应。
        // 这两个引用必须是 final：lambda 里要捕获它们，而 Java 的闭包只能捕获
        // 「effectively final」的变量——后面一旦重新赋值，编译就报
        // "lambda 表达式引用的本地变量必须是最终变量"。
        final List<Embedding> allEmbeddings = new ArrayList<>(java.util.Collections.nCopies(segments.size(), null));
        final List<TextSegment> successSegments = new ArrayList<>(java.util.Collections.nCopies(segments.size(), null));

        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < segments.size(); i += embeddingBatchSize) {
            ranges.add(new int[]{i, Math.min(i + embeddingBatchSize, segments.size())});
        }

        java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(embeddingParallelism);
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        for (final int[] range : ranges) {
            final int start = range[0];
            final int end = range[1];
            final List<TextSegment> batch = new ArrayList<>(segments.subList(start, end));
            try {
                futures.add(ragExecutor.submit(() -> {
                    try {
                        permits.acquire();
                        try {
                            // 走带缓存的 Embedding：文本相同的分块直接命中，不发 HTTP 请求
                            List<Embedding> embeddings = cachedEmbeddingService.embedAll(
                                    batch.stream().map(TextSegment::text).collect(Collectors.toList()));
                            for (int k = 0; k < batch.size() && k < embeddings.size(); k++) {
                                allEmbeddings.set(start + k, embeddings.get(k));
                                successSegments.set(start + k, batch.get(k));
                            }
                            successCount.addAndGet(Math.min(batch.size(), embeddings.size()));
                            log.info("  Embedded batch {}-{}/{} (parallel)", start, end, segments.size());
                        } finally {
                            permits.release();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        errors.add(ie);
                    } catch (Exception ex) {
                        log.warn("  Batch {}-{} failed: {}", start, end, ex.getMessage());
                        errors.add(ex);
                        // 降级：批次失败时退化为逐条处理，尽量多救回一些分块
                        for (int k = start; k < end; k++) {
                            try {
                                Embedding e = cachedEmbeddingService.embed(segments.get(k).text());
                                allEmbeddings.set(k, e);
                                successSegments.set(k, segments.get(k));
                                successCount.incrementAndGet();
                            } catch (Exception ignored) {
                                // 单条也失败，留 null，后续过滤掉
                            }
                        }
                    }
                }));
            } catch (Exception submitError) {
                // ragExecutor 拒绝（AbortPolicy）→ 降级为当前线程同步处理这一批
                log.warn("并行 Embedding 提交被拒绝，改为同步执行 batch {}-{}", start, end);
                try {
                    List<Embedding> embeddings = cachedEmbeddingService.embedAll(
                            batch.stream().map(TextSegment::text).collect(Collectors.toList()));
                    for (int k = 0; k < batch.size() && k < embeddings.size(); k++) {
                        allEmbeddings.set(start + k, embeddings.get(k));
                        successSegments.set(start + k, batch.get(k));
                    }
                    successCount.addAndGet(Math.min(batch.size(), embeddings.size()));
                } catch (Exception ex) {
                    log.warn("同步 Embedding 同样失败: {}", ex.getMessage());
                }
            }
        }

        // 等所有批次跑完（总超时时间按批次数动态放宽）
        long waitTimeoutSec = Math.max(60, (long) ranges.size() * 30);
        for (Future<?> f : futures) {
            try {
                f.get(waitTimeoutSec, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("等待 Embedding 批次超时/异常: {}", e.getMessage());
            }
        }

        // 剔除失败留下的 null 占位，得到最终可写入向量库的结果。
        // 这里不能覆盖 allEmbeddings / successSegments（它们是 final），
        // 所以另起两个变量承载过滤后的结果。
        List<Embedding> filteredEmbeddings = new ArrayList<>(successCount.get());
        List<TextSegment> filteredSegments = new ArrayList<>(successCount.get());
        for (int i = 0; i < allEmbeddings.size(); i++) {
            if (allEmbeddings.get(i) != null && successSegments.get(i) != null) {
                filteredEmbeddings.add(allEmbeddings.get(i));
                filteredSegments.add(successSegments.get(i));
            }
        }

        log.info("Embedding 完成: 成功 {}/{} 分块, 并行度={}", filteredSegments.size(), segments.size(), embeddingParallelism);

        if (filteredEmbeddings.isEmpty()) {
            throw BusinessException.badRequest("文档分块向量化失败，请稍后重试");
        }

        // 向量 ID 后面方便删除
        List<String> vectorIds = embeddingStore.addAll(filteredEmbeddings, filteredSegments);

        String docType = "unknown";
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            docType = fileName.substring(dotIdx + 1).toLowerCase();
        }

        // 文档入库
        Documents docRecord = new Documents();
        docRecord.setDocumentName(fileName);
        docRecord.setDocumentType(docType);
        docRecord.setSource(source);
        docRecord.setContentHash(contentHash);
        docRecord.setFileSize(fileSize != null ? fileSize : 0L);
        docRecord.setChunkCount(filteredSegments.size());
        docRecord.setCreator(creator);

        List<DocumentChunks> chunks = new ArrayList<>(filteredSegments.size());
        for (int i = 0; i < filteredSegments.size(); i++) {
            DocumentChunks chunk = new DocumentChunks();
            chunk.setVectorId(vectorIds.get(i));
            chunk.setChunkIndex(i);
            chunk.setChunkText(filteredSegments.get(i).text());
            chunks.add(chunk);
        }

        try {
            // MySQL 元数据与分块记录在同一个事务中写入。
            documentMetadataService.save(docRecord, chunks);
        } catch (RuntimeException e) {
            // 向量库不受本地事务管理，数据库失败后需要补偿删除已写入的向量。
            removeVectorsAfterFailure(vectorIds, e);
            throw e;
        }

        log.info("Ingested {} with {} chunks, documentId={}", fileName, filteredSegments.size(), docRecord.getId());
        // 新文档入库后，文档列表和检索结果都必须失效：
        //   列表不清 → 用户看不到刚传的文档；
        //   检索不清 → 新文档要等 5 分钟 TTL 才能被搜到，用户会以为入库失败。
        // 入库成功意味着向量库里现在肯定有数据了，更新探测状态，
        // 后续上传不必再为"库是否为空"多发一次 embedding 请求。
        vectorStoreState.set(true);
        evictCachesAfterIngest();
        return new DocumentInfo(docRecord.getId(), fileName, filteredSegments.size());
    }

    /**
     * 入库成功后清空相关缓存。
     * <p>
     * 这里用 {@code CacheManager} 手动清理而不是 {@code @CacheEvict} 注解，
     * 原因是 {@code processAndSave} 是 private 方法——Spring AOP 代理
     * 无法对 private / 同类内部调用织入注解（self-invocation 问题）。
     * 这种情况下，编程式清理是唯一正确的做法。
     */
    private void evictCachesAfterIngest() {
        try {
            for (String name : new String[]{CacheConfig.CACHE_DOCUMENTS, CacheConfig.CACHE_RETRIEVAL}) {
                org.springframework.cache.Cache cache = cacheManager.getCache(name);
                if (cache != null) {
                    cache.clear();
                }
            }
        } catch (Exception e) {
            // 缓存清理失败不能影响入库结果本身，记日志即可（TTL 会兜底）
            log.warn("入库后清理缓存失败，将依赖 TTL 过期兜底: {}", e.getMessage());
        }
    }

    /**
     * 解析 Excel
     */
    private Document parseExcel(InputStream inputStream) throws IOException {
        StringBuilder text = new StringBuilder();
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        try (XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (sheet.getPhysicalNumberOfRows() == 0) continue;
                text.append("=== 工作表: ").append(sheet.getSheetName()).append(" ===\n");

                for (Row row : sheet) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        if (cell != null) {
                            switch (cell.getCellType()) {
                                case STRING:
                                    text.append(cell.getStringCellValue());
                                    break;
                                case NUMERIC:
                                    if (DateUtil.isCellDateFormatted(cell)) {
                                        text.append(dateFmt.format(cell.getDateCellValue()));
                                    } else {
                                        double val = cell.getNumericCellValue();
                                        if (val == Math.floor(val) && !Double.isInfinite(val)) {
                                            text.append((long) val);
                                        } else {
                                            text.append(val);
                                        }
                                    }
                                    break;
                                case BOOLEAN:
                                    text.append(cell.getBooleanCellValue());
                                    break;
                                case FORMULA:
                                    try {
                                        String formulaVal = cell.getStringCellValue();
                                        text.append(formulaVal);
                                    } catch (Exception e) {
                                        text.append(cell.getCellFormula());
                                    }
                                    break;
                                default:
                                    text.append(" ");
                            }
                        }
                        if (c < row.getLastCellNum() - 1) {
                            text.append(" | ");
                        }
                    }
                    text.append("\n");
                }
                text.append("\n");
            }
        }
        String content = text.toString();
        log.info("  Extracted {} chars from Excel", content.length());
        return Document.from(content);
    }

    /**
     * 解析 Word
     */
    private Document parseWord(InputStream inputStream) throws IOException {
        StringBuilder text = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(inputStream)) {
            for (org.apache.poi.xwpf.usermodel.XWPFParagraph para : doc.getParagraphs()) {
                text.append(para.getText()).append("\n");
            }
            for (org.apache.poi.xwpf.usermodel.XWPFTable table : doc.getTables()) {
                for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : table.getRows()) {
                    for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                        text.append(cell.getText()).append(" | ");
                    }
                    text.append("\n");
                }
                text.append("\n");
            }
        }
        return Document.from(text.toString());
    }

    /**
     * 解析 PPT
     */
    private Document parsePowerPoint(InputStream inputStream) throws IOException {
        StringBuilder text = new StringBuilder();
        try (XMLSlideShow ppt = new XMLSlideShow(inputStream)) {
            int slideNum = 1;
            for (org.apache.poi.xslf.usermodel.XSLFSlide slide : ppt.getSlides()) {
                text.append("=== 幻灯片 ").append(slideNum++).append(" ===\n");
                for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape) {
                        text.append(((org.apache.poi.xslf.usermodel.XSLFTextShape) shape).getText()).append("\n");
                    }
                }
                text.append("\n");
            }
        }
        return Document.from(text.toString());
    }

    /**
     * 解析 HTML
     */
    private Document parseHtml(InputStream inputStream) throws IOException {
        org.jsoup.nodes.Document html = Jsoup.parse(inputStream, "UTF-8", "");
        html.select("script, style, nav, footer, header").remove();
        return Document.from(html.body().text());
    }

    /**
     * 获取已导入的文档列表。
     * <p>
     * <b>为什么这个接口值得缓存？</b>
     * 它是前端知识库面板每次打开都会调的接口，典型「读多写极少」：
     * 用户可能一分钟内反复开关面板 20 次，但一天只上传两三个文档。
     * 每次都去 MySQL 查全表 + 按时间排序，纯属浪费。
     * <p>
     * <b>一致性怎么保证？</b>
     * 不做定时刷新，而是<b>主动失效</b>——入库成功、删除成功时立刻清掉缓存。
     * 这样用户上传完立刻能看到新文档，不会出现"传了但列表里没有"的困惑。
     * 这也是缓存设计的第一原则：<b>能精确失效的，就不要靠 TTL 兜底</b>。
     * <p>
     * {@code sync = true} 的意义见 {@link #getIngestedDocumentsSync()}。
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_DOCUMENTS, key = "'all'")
    public List<DocumentInfo> getIngestedDocuments() {
        List<Documents> docs = documentsMapper.selectList(
                Wrappers.<Documents>lambdaQuery()
                        .select(Documents::getId, Documents::getDocumentName, Documents::getChunkCount)
                        .orderByDesc(Documents::getCreateTime)
        );
        List<DocumentInfo> result = new ArrayList<>();
        for (Documents doc : docs) {
            result.add(new DocumentInfo(doc.getId(), doc.getDocumentName(), doc.getChunkCount()));
        }
        return result;
    }

    /**
     * 与 {@link #getIngestedDocuments()} 等价，但加了 {@code sync = true}。
     * <p>
     * <b>这是缓存击穿（Cache Breakdown）的标准解法。</b>
     * 场景：文档列表缓存过期的那一瞬间，如果有 100 个请求同时进来，
     * 它们会同时发现"缓存没了"，然后一起去查数据库——数据库瞬间被打 100 次。
     * <p>
     * {@code sync = true} 会让 Spring Cache 加锁：<b>只让第一个线程去查库，
     * 其余线程阻塞等待，等第一个查完写回缓存后直接拿结果</b>。
     * 代价是并发时响应略慢（要排队），收益是数据库绝不会被打穿。
     * <p>
     * 注意：这里<b>不能</b>写 {@code return getIngestedDocuments();}。
     * 因为 Spring Cache 依赖 AOP 代理，同类内部方法调用不经过代理，
     * 注解会被完全忽略——这是 Spring AOP 最常见的坑（self-invocation）。
     * 所以下面的查询逻辑虽然重复，但必须独立写一遍。
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_DOCUMENTS, key = "'all'", sync = true)
    public List<DocumentInfo> getIngestedDocumentsSync() {
        List<Documents> docs = documentsMapper.selectList(
                Wrappers.<Documents>lambdaQuery()
                        .select(Documents::getId, Documents::getDocumentName, Documents::getChunkCount)
                        .orderByDesc(Documents::getCreateTime)
        );
        List<DocumentInfo> result = new ArrayList<>();
        for (Documents doc : docs) {
            result.add(new DocumentInfo(doc.getId(), doc.getDocumentName(), doc.getChunkCount()));
        }
        return result;
    }

    /**
     * 删除文档。
     * <p>
     * <b>删除时要清两类缓存，缺一不可：</b>
     * <ul>
     *   <li>{@code documents}：文档列表，否则列表里还显示着已删除的文档；</li>
     *   <li>{@code retrieval}：检索结果缓存。这个最容易被忽略——
     *       如果不删，用户"删了文档又提问"，还会从缓存里拿到刚删掉的文档片段，
     *       模型会引用一份"已经不存在的资料"，属于严重的一致性 bug。</li>
     * </ul>
     * 所以这里用 {@code allEntries = true} 整区清空，而不是逐个 key 删。
     * 为什么能这么粗暴？因为这两个缓存区都只有少量 key，
     * 全清代价远低于维护"哪个 key 对应哪个文档"的映射关系复杂度。
     */
    @CacheEvict(cacheNames = {CacheConfig.CACHE_DOCUMENTS, CacheConfig.CACHE_RETRIEVAL}, allEntries = true)
    public void deleteDocument(Long documentId) {
        Documents document = documentsMapper.selectById(documentId);
        if (document == null) {
            throw new NoSuchElementException("文档不存在: " + documentId);
        }

        RLock lock = redissonClient.getLock("lock:document:delete:" + documentId);
        lock.lock();
        try {
            List<DocumentChunks> chunks = documentChunksMapper.selectList(
                    Wrappers.<DocumentChunks>lambdaQuery()
                            .eq(DocumentChunks::getDocumentId, documentId)
            );
            List<String> vectorIds = chunks.stream()
                    .map(DocumentChunks::getVectorId)
                    .collect(Collectors.toList());
            embeddingStore.removeAll(vectorIds);

            documentMetadataService.delete(documentId);
            log.info("Deleted document id={} with {} chunks", documentId, chunks.size());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void removeVectorsAfterFailure(List<String> vectorIds, RuntimeException original) {
        try {
            embeddingStore.removeAll(vectorIds);
        } catch (Exception cleanupError) {
            original.addSuppressed(cleanupError);
            log.error("Failed to compensate vector data after metadata write failure", cleanupError);
        }
    }
}
