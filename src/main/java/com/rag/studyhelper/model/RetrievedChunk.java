package com.rag.studyhelper.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检索结果片段（可序列化 DTO）。
 * <p>
 * <b>为什么不直接缓存 LangChain4j 的 {@code TextSegment}？</b>
 * 原因和 Embedding 一样：第三方库的类不一定有无参构造函数，
 * 也不保证字段结构稳定，放进 JSON 缓存后极易反序列化失败。
 * <p>
 * 用自有 DTO 后，缓存里存的就是"纯数据"：
 * <ul>
 *   <li>字段名、类型都由我们控制，升级 LangChain4j 不会导致缓存全部失效；</li>
 *   <li>{@code fileName} 单独抽出来，方便前端展示出处；</li>
 *   <li>需要 {@code TextSegment} 时再 {@code toTextSegment()} 转换。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievedChunk {

    /** 片段正文（已带 [来源:文件名] 前缀） */
    private String text;

    /** 来源文件名，从正文前缀解析出来，便于展示 */
    private String fileName;

    /** 向量检索时的相似度分数（-1 表示未记录） */
    private double score;

    /**
     * 从正文里解析出文件名。
     * <p>
     * 入库时统一加了 {@code [来源:文件名]\n} 前缀，
     * 所以这里按固定格式截取即可。解析失败时返回 "未知来源"，
     * 不影响主流程。
     */
    public static String parseFileName(String text) {
        if (text == null || text.isEmpty()) {
            return "未知来源";
        }
        int end = text.indexOf(']');
        if (text.startsWith("[来源:") && end > 4) {
            return text.substring(4, end);
        }
        return "未知来源";
    }

    /**
     * 转回 LangChain4j 的 TextSegment，用于拼装提示词。
     */
    public dev.langchain4j.data.segment.TextSegment toTextSegment() {
        return dev.langchain4j.data.segment.TextSegment.from(text);
    }
}
