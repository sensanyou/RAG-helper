package com.rag.studyhelper.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 聊天消息
 */
@Data
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
public class ChatRequest {

    @NotBlank(message = "sessionId 不能为空")
    @Size(max = 64, message = "sessionId 长度不能超过 64")
    private String sessionId;

    @NotBlank(message = "question 不能为空")
    @Size(max = 2000, message = "question 长度不能超过 2000")
    private String question;
}
