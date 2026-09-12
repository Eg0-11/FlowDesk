package com.flowdesk.application.ai;

/**
 * 普通聊天用例：单轮、无工具、无会话记忆。
 *
 * <p>实现方负责生成服务端 {@code requestId}，并在任何失败路径上抛出
 * {@link AiRequestException}（入参不合法）或 {@link AiProviderException}（上游模型服务失败）。</p>
 */
public interface AiChatUseCase {

    /**
     * 执行一次普通聊天。
     *
     * @param command 聊天命令
     * @return 聊天结果，包含服务端生成的 requestId
     * @throws AiRequestException  命令内容不合法
     * @throws AiProviderException 上游模型服务调用失败
     */
    ChatResult chat(ChatCommand command);
}
