package com.rag.studyhelper.context;

import com.rag.studyhelper.exception.BusinessException;

/**
 * 当前登录用户的上下文持有器（ThreadLocal）。
 * <p>
 * <b>它解决什么问题？</b>
 * 认证拦截器在请求入口解析出 userId 后，需要把它传递给后面一长串调用链：
 * Controller → Service → Mapper……如果靠方法参数一路传，
 * 每个方法都要多一个 {@code ownerId} 参数，侵入性极大。
 * ThreadLocal 让"本次请求"在任何层都能直接拿到当前用户，
 * 而不同请求的线程互不干扰。
 * <p>
 * <b>必须成对使用 set / remove，为什么？</b>
 * Tomcat 用线程池复用工作线程：请求 A 处理完后，同一个线程会被拿去处理请求 B。
 * 如果不在请求结束时 {@code remove()}，请求 B 就能读到请求 A 的 userId——
 * 这不是内存泄漏，是<b>串号</b>，比泄漏严重得多。
 * 所以 {@code AuthInterceptor} 的 {@code afterCompletion}（无论成功失败都执行）
 * 里必须清理。
 * <p>
 * <b>异步线程读不到它。</b>
 * ThreadLocal 是"线程"维度的，{@code @Async} 方法、线程池里执行的代码
 * 都在别的线程上，拿不到当前请求的 userId。
 * 所以异步入库任务（ingestExecutor）的 ownerId 必须在<b>提交任务前</b>
 * 显式取出并作为参数传递，不能指望任务里再调 {@code UserContext.current()}。
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT_USER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId) {
        CURRENT_USER.set(userId);
    }

    /** 显式清理（必须是 null 也能清到，防止线程池复用串号） */
    public static void clear() {
        CURRENT_USER.remove();
    }

    /**
     * 获取当前用户 ID；未登录返回 null。
     * 供"登录后才有意义"的可选场景使用（如 scan 接口选择归属）。
     */
    public static Long currentOrNull() {
        return CURRENT_USER.get();
    }

    /**
     * 获取当前用户 ID；未登录直接抛 401。
     * 业务方法内部用这个——它保证"忘了配拦截器"也不会静默放行。
     */
    public static long requireUserId() {
        Long userId = CURRENT_USER.get();
        if (userId == null) {
            throw BusinessException.unauthorized("未登录或会话已过期");
        }
        return userId;
    }
}
