package com.flowdesk.agent.ai;

/**
 * 答案引用校验失败（RAG 5/6）：只携带<b>稳定的失败类别</b>。
 *
 * <p>消息是固定的服务端文案：<b>不</b>包含模型原始答案、提示词或任何被检索内容 ——
 * 这个异常最终会变成 {@code AiProviderException}（HTTP 502），其 cause 只留在服务端。</p>
 */
public class GroundedAnswerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final GroundedAnswerFailure failure;

    /**
     * @param failure 稳定失败类别
     */
    public GroundedAnswerException(GroundedAnswerFailure failure) {
        super("模型答案未通过引用校验：" + failure.name());
        this.failure = failure;
    }

    /**
     * @return 稳定失败类别
     */
    public GroundedAnswerFailure failure() {
        return this.failure;
    }
}
