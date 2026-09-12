package com.flowdesk.bootstrap.ai;

import com.flowdesk.application.ai.ChatCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 普通聊天请求体。
 *
 * @param message 用户消息
 */
public record ChatRequest(

        @NotBlank(message = "不能为空")
        @Size(max = ChatCommand.MAX_MESSAGE_LENGTH,
                message = "长度不能超过 " + ChatCommand.MAX_MESSAGE_LENGTH + " 个字符")
        String message) {
}
