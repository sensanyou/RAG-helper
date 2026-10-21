package com.rag.studyhelper.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 缓存 key 生成工具。
 * <p>
 * <b>为什么不直接用原始文本做 key？</b>
 * <ul>
 *   <li><b>长度</b>：一个文档分块可能有 500 个汉字（UTF-8 下 1500 字节），
 *       Redis 里存 key 也是要占内存的，而且越长查找越慢。</li>
 *   <li><b>可读性</b>：key 里塞满全文，用 {@code redis-cli --scan} 看的时候
 *       根本没法辨认，还会把中文、换行符带进去。</li>
 *   <li><b>隐私</b>：用户的文档内容不应该明文躺在 Redis 的 key 里。</li>
 * </ul>
 * 用 SHA-256 摘要后，key 固定为 64 个十六进制字符，
 * 且天然规避了特殊字符转义问题。
 * <p>
 * <b>哈希碰撞风险</b>：SHA-256 的碰撞概率在工程上可以视为 0，
 * 远低于"同一台机器同时坏两块硬盘"的概率，不需要额外处理。
 */
public final class CacheKeyGenerator {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CacheKeyGenerator() {
    }

    /**
     * 计算字符串的 SHA-256 摘要，返回 64 位十六进制串。
     * <p>
     * 该方法在 SpEL 里通过 {@code T(...).sha256(...)} 调用，
     * 所以必须是 {@code public static}。
     */
    public static String sha256(String input) {
        if (input == null) {
            return "null";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 是 JDK 必备算法，理论上不会到这里；真到了就用长度+首尾兜底，保证不崩
            return "len" + input.length() + "-" + Math.abs(input.hashCode());
        }
    }
}
