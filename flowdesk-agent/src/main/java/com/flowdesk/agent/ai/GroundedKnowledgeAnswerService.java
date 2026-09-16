package com.flowdesk.agent.ai;

import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.KnowledgeAnswerCommand;
import com.flowdesk.application.ai.KnowledgeAnswerResult;
import com.flowdesk.application.ai.KnowledgeAnswerUseCase;
import com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 基于检索证据的知识库问答实现（RAG 5/6）。
 *
 * <h2>固定执行顺序</h2>
 * <ol>
 *   <li><b>检索</b>：{@link RetrieveKnowledgeUseCase#retrieve}（输入的规范化与边界校验全部由它完成，
 *       本类<b>不</b>重复实现 query / topK / minScore 规则）；</li>
 *   <li><b>检索失败原样传播</b>：{@code KnowledgeApplicationException}（503 未启用向量化、
 *       502 向量服务失败、500 内部失败、400 输入不合法）直接上抛，<b>不调用模型</b>；</li>
 *   <li><b>无证据不调用模型</b>：{@code citations} 为空时返回固定降级答案、
 *       {@code grounded=false}、空引用列表与空证据；</li>
 *   <li><b>构造提示词</b>：{@link KnowledgeAnswerPromptBuilder}（系统指令与用户数据分离、
 *       正文边界不可伪造）；</li>
 *   <li><b>调用 DeepSeek 一次</b>：只发送 system + user 两条消息，<b>不</b>注册工具、
 *       <b>不</b>启用会话记忆、<b>不</b>在本类内重试生成结果；</li>
 *   <li><b>校验引用</b>：{@link GroundedCitationValidator}（非空、至少一个规范引用、
 *       引用必须在本次证据内），失败即 502；</li>
 *   <li><b>返回</b>：答案 + 实际引用编号 + 完整检索证据。</li>
 * </ol>
 *
 * <h2>为什么答案必须后校验</h2>
 * <p>提示词只能「要求」模型给出规范引用，不能保证它照做。真正决定响应能否返回成功的，
 * 是对模型输出的<b>独立校验</b>：空答案、没有引用、{@code [K0]}/{@code [K01]}/{@code [K]} 这类
 * 非法形式、以及本次没有给出的编号，全部判为失败（HTTP 502）。这样「答案看起来合理」不再等于
 * 「答案有据可查」。</p>
 *
 * <h2>不做的事</h2>
 * <p>不生成流式输出、不做 Rerank、不拼接多轮会话记忆、不注册任何工具；
 * 也不修改检索阶段的 SQL、排序、阈值或引用编号。</p>
 *
 * <h2>日志</h2>
 * <p>成功只记录 {@code operation}、{@code requestId}、{@code grounded}、
 * {@code retrievedCitationCount}、{@code usedCitationCount}、{@code success}、{@code durationMs}；
 * 失败只记录稳定失败类别与异常类名。<b>不</b>记录 query、提示词、切片正文、模型答案、密钥、
 * SQL/连接串、模型原始错误响应，也不记录 cause message 与堆栈正文。</p>
 */
public class GroundedKnowledgeAnswerService implements KnowledgeAnswerUseCase {

    private static final Logger log = LoggerFactory.getLogger(GroundedKnowledgeAnswerService.class);

    /** 无检索证据时的固定降级答案（不调用模型）。 */
    static final String NO_EVIDENCE_ANSWER = "当前知识库中没有足够证据回答该问题。";

    private final RetrieveKnowledgeUseCase retrieveKnowledgeUseCase;

    private final ChatClient deepSeekChatClient;

    /**
     * @param retrieveKnowledgeUseCase 检索用例（输入的规范化与校验唯一入口）
     * @param deepSeekChatClient       命名明确的 DeepSeek ChatClient（不注册工具、无会话记忆）
     */
    public GroundedKnowledgeAnswerService(RetrieveKnowledgeUseCase retrieveKnowledgeUseCase,
            ChatClient deepSeekChatClient) {

        this.retrieveKnowledgeUseCase = retrieveKnowledgeUseCase;
        this.deepSeekChatClient = deepSeekChatClient;
    }

    @Override
    public KnowledgeAnswerResult answer(KnowledgeAnswerCommand command) {
        long startedAt = System.nanoTime();

        // ① 检索：失败原样传播（不调用模型）；输入的规范化与校验由检索用例负责
        KnowledgeRetrievalView retrieval = this.retrieveKnowledgeUseCase.retrieve(
                (command == null ? new KnowledgeAnswerCommand(null, null, null) : command).toRetrievalQuery());

        // ② 无证据：不调用模型，返回固定降级答案
        if (retrieval.citations().isEmpty()) {
            String requestId = UUID.randomUUID().toString();
            log.info("ai.knowledge-answer completed operation=ai.knowledge-answer requestId={} grounded=false "
                            + "retrievedCitationCount=0 usedCitationCount=0 success=true durationMs={}",
                    requestId, elapsedMillis(startedAt));
            return new KnowledgeAnswerResult(requestId, NO_EVIDENCE_ANSWER, false, List.of(), retrieval);
        }

        // ③ 有证据：生成 requestId → 构造提示词 → 调用模型一次
        String requestId = UUID.randomUUID().toString();
        List<String> allowedCitationIds = retrieval.citations().stream()
                .map(citation -> citation.citationId())
                .toList();
        KnowledgeAnswerPrompt prompt = KnowledgeAnswerPromptBuilder.build(
                retrievalQuery(command), retrieval.citations());

        String rawAnswer;
        try {
            rawAnswer = this.deepSeekChatClient.prompt()
                    .system(prompt.systemPrompt())
                    .user(prompt.userPrompt())
                    .call()
                    .content();
        }
        catch (RuntimeException ex) {
            logFailure(requestId, GroundedAnswerFailure.MODEL_CALL_FAILED, ex, startedAt);
            throw new AiProviderException(requestId, ex);
        }
        if (rawAnswer == null || rawAnswer.strip().isEmpty()) {
            logFailure(requestId, GroundedAnswerFailure.ANSWER_EMPTY, null, startedAt);
            throw new AiProviderException(requestId, null);
        }

        // ④ 引用校验：失败即 502（不修正、不重试）
        List<String> usedCitationIds;
        try {
            usedCitationIds = GroundedCitationValidator.requireValidCitations(rawAnswer, allowedCitationIds);
        }
        catch (GroundedAnswerException ex) {
            logFailure(requestId, ex.failure(), ex, startedAt);
            throw new AiProviderException(requestId, ex);
        }

        String answer = rawAnswer.strip();
        log.info("ai.knowledge-answer completed operation=ai.knowledge-answer requestId={} grounded=true "
                        + "retrievedCitationCount={} usedCitationCount={} success=true durationMs={}",
                requestId, retrieval.citations().size(), usedCitationIds.size(), elapsedMillis(startedAt));
        return new KnowledgeAnswerResult(requestId, answer, true, usedCitationIds, retrieval);
    }

    /**
     * @param command 原始命令
     * @return 用于提示词的规范化问题；命令缺失时为空串
     */
    private static String retrievalQuery(KnowledgeAnswerCommand command) {
        return command == null || command.query() == null ? "" : command.query();
    }

    /**
     * 只记录稳定失败类别与异常类名；不记录任何模型原文、提示词或被检索内容。
     *
     * @param requestId 请求标识
     * @param failure   稳定失败类别
     * @param cause     原始异常（可为 {@code null}）；只取其类名，不取 message 或堆栈
     */
    private static void logFailure(String requestId, GroundedAnswerFailure failure, RuntimeException cause,
            long startedAt) {

        log.warn("ai.knowledge-answer failed operation=ai.knowledge-answer requestId={} failure={} exception={} "
                        + "success=false durationMs={}",
                requestId, failure.name(), cause == null ? "none" : cause.getClass().getName(),
                elapsedMillis(startedAt));
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
