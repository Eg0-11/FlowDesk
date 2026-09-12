package com.flowdesk.application.ai;

/**
 * 普通聊天命令。
 *
 * @param message 用户消息；去除首尾空白后不能为空，长度不得超过 {@link #MAX_MESSAGE_LENGTH}
 */
public record ChatCommand(String message) {

    /** 消息最大长度（字符数）。 */
    public static final int MAX_MESSAGE_LENGTH = 4000;
}
