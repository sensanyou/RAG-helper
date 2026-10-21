package com.rag.studyhelper.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置。
 * <p>
 * 全项目有两个独立线程池，职责严格分开：
 * <ul>
 *   <li><b>ingestExecutor</b>：文档入库专用。任务是 IO 密集（等 Embedding API 返回），
 *       单个任务可能跑几十秒，所以队列要能存住、拒绝策略要能回退给调用方。</li>
 *   <li><b>ragExecutor</b>：RAG 检索链路的并行子任务（如批量 Embedding、多路召回）。
 *       任务是短平快，队列小、线程多，避免长任务把线程占满。</li>
 * </ul>
 * <p>
 * <b>为什么不共用一个池？</b> 线程池隔离是防止"故障扩散"的第一道墙。
 * 如果入库的长任务和问答的短任务共用池，一个 50MB 的 PDF 入库就能把池子占满，
 * 导致所有问答请求排队超时——这叫线程池饥饿（Thread Pool Starvation）。
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Value("${app.async.ingest.core-size:2}")
    private int ingestCoreSize;

    @Value("${app.async.ingest.max-size:4}")
    private int ingestMaxSize;

    @Value("${app.async.ingest.queue-capacity:200}")
    private int ingestQueueCapacity;

    @Value("${app.async.rag.core-size:8}")
    private int ragCoreSize;

    @Value("${app.async.rag.max-size:32}")
    private int ragMaxSize;

    @Value("${app.async.rag.queue-capacity:500}")
    private int ragQueueCapacity;

    /**
     * 文档入库线程池。
     * <p>
     * <b>参数依据</b>：入库瓶颈全部是外部 HTTP 调用（Embedding API），线程本身不占 CPU，
     * 所以核心线程数不需要等于 CPU 核数。核心 2 + 最大 4 是"限流"的体现——
     * 同时最多 4 个文档在解析，避免把 Embedding 服务的配额打满，反而让每个请求都变慢。
     * <p>
     * <b>拒绝策略用 CallerRunsPolicy</b>：队列满了就让提交任务的线程自己执行。
     * 对 HTTP 请求线程来说，这等于退化成同步处理——宁可慢，不能丢任务。
     * 如果用 AbortPolicy，用户会看到一个莫名的 500，而文件其实还没处理。
     */
    @Bean("ingestExecutor")
    public ThreadPoolTaskExecutor ingestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(ingestCoreSize);
        executor.setMaxPoolSize(ingestMaxSize);
        executor.setQueueCapacity(ingestQueueCapacity);
        executor.setKeepAliveSeconds(120);
        executor.setThreadNamePrefix("ingest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 关停时等待在途任务跑完，避免入库到一半被强杀
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("ingestExecutor 初始化完成: core={}, max={}, queue={}", ingestCoreSize, ingestMaxSize, ingestQueueCapacity);
        return executor;
    }

    /**
     * RAG 检索线程池。
     * <p>
     * <b>参数依据</b>：这里的任务基本是并行化的子查询（Embedding 批处理、Rerank 预取），
     * 单任务耗时 100ms~2s，需要"多线程一起干"来把总耗时压下来。
     * 最大 32 线程是因为外部 API 的并发配额通常在这个量级，再多只会在服务商侧排队。
     * <p>
     * <b>拒绝策略用 AbortPolicy</b>：这里跟入库相反——并行子任务是可降级的，
     * 池子满了就抛异常，由调用方退化为串行执行，不影响正确性，只影响速度。
     */
    @Bean("ragExecutor")
    public ThreadPoolTaskExecutor ragExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(ragCoreSize);
        executor.setMaxPoolSize(ragMaxSize);
        executor.setQueueCapacity(ragQueueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("rag-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("ragExecutor 初始化完成: core={}, max={}, queue={}", ragCoreSize, ragMaxSize, ragQueueCapacity);
        return executor;
    }

    /**
     * {@code @Async} 未指定线程池时的默认池。
     */
    @Override
    public Executor getAsyncExecutor() {
        return ragExecutor();
    }

    /**
     * 异步方法抛异常时不会被调用方感知，必须显式记录，否则错误会静默丢失。
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }
}
