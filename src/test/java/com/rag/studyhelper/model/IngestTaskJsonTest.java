package com.rag.studyhelper.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IngestTask} 的 JSON 往返测试。
 * <p>
 * <b>为什么专门写这一组测试？</b>
 * 因为这里踩过一个非常隐蔽的坑：{@code isFinished()} 是派生方法，
 * Jackson 默认会把它当成名为 {@code finished} 的<b>可读写属性</b>：
 * 序列化时写进 JSON，反序列化时却找不到 setter → 抛 {@code Unrecognized field}。
 * <p>
 * 后果是：任务状态成功写进 Redis，但每一次查询都读取失败，
 * 接口返回 404"任务不存在或已过期"——排查时极易误判为"任务丢了"或"Redis 有问题"。
 * <p>
 * 这组测试锁住两个行为：
 * <ol>
 *   <li>序列化 → 反序列化必须无损往返（防回归）；</li>
 *   <li>{@code finished} 字段对外可见（前端靠它决定是否停止轮询）。</li>
 * </ol>
 */
class IngestTaskJsonTest {

    /** 与 AsyncIngestService 中保持一致的 ObjectMapper 配置 */
    private ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    @Test
    void serializeShouldContainFinishedField() throws Exception {
        IngestTask task = IngestTask.success("t1", "a.md", 7L, 3, false, 8L);
        String json = objectMapper().writeValueAsString(task);

        assertTrue(json.contains("\"finished\":true"),
                "finished 字段应该对前端可见，JSON 实际内容: " + json);
    }

    @Test
    void roundTripShouldNotLoseData() throws Exception {
        ObjectMapper mapper = objectMapper();
        IngestTask original = IngestTask.success("t2", "b.md", 9L, 5, true, 120L);

        String json = mapper.writeValueAsString(original);
        IngestTask restored = mapper.readValue(json, IngestTask.class);

        assertNotNull(restored, "反序列化结果不应为 null");
        assertEquals(original.getTaskId(), restored.getTaskId());
        assertEquals(original.getFileName(), restored.getFileName());
        assertEquals(original.getStatus(), restored.getStatus());
        assertEquals(original.getDocumentId(), restored.getDocumentId());
        assertEquals(original.getChunks(), restored.getChunks());
        assertEquals(original.getCostMillis(), restored.getCostMillis());
        assertTrue(restored.isFinished(), "SUCCESS 状态应判定为已结束");
    }

    @Test
    void pendingTaskShouldNotBeFinished() {
        IngestTask task = IngestTask.pending("t3", "c.md");
        assertEquals("PENDING", task.getStatus());
        assertTrue(!task.isFinished(), "PENDING 不应判定为已结束");
    }

    /**
     * 旧格式兼容：即使 Redis 里存的是"带未知字段"的 JSON（例如未来新增了字段），
     * 也应该能读出来，而不是整条任务读取失败。
     */
    @Test
    void unknownFieldShouldBeIgnored() throws Exception {
        String jsonWithExtra = "{\"taskId\":\"t4\",\"fileName\":\"d.md\",\"status\":\"SUCCESS\","
                + "\"chunks\":2,\"finished\":true,\"someFutureField\":\"whatever\"}";

        IngestTask task = objectMapper().readValue(jsonWithExtra, IngestTask.class);

        assertNotNull(task);
        assertEquals("t4", task.getTaskId());
        assertTrue(task.isFinished());
    }
}
