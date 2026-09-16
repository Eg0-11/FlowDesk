package com.flowdesk.agent.ai;

import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.KnowledgeAnswerCommand;
import com.flowdesk.application.ai.KnowledgeAnswerResult;
import com.flowdesk.application.ai.KnowledgeAnswerUseCase;
import com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase;
import com.flowdesk.application.knowledge.query.KnowledgeQueryNormalizer;
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
 *   <li><b>检索</b>：{@link RetrieveKnowledgeUseCase#retrieve}（输入的<b>合法性</b> ——
 *       空值、code point 上限、控制字符、{@code topK} 与 {@code minScore} 范围 —— 全部由它判定，
 *       本类<b>不</b>复制第二套规则）；</li>
 *   <li><b>检索失败原样传播</b>：{@code KnowledgeApplicationException}（503 未启用向量化、
 *       502 向量服务失败、500 内部失败、400 输入不合法）直接上抛，<b>不调用模型</b>；</li>
 *   <li><b>无证据不调用模型</b>：{@code citations} 为空时返回固定降级答案、
 *       {@code grounded=false}、空引用列表与空证据；</li>
 *   <li><b>构造提示词</b>：{@link KnowledgeAnswerPromptBuilder}（系统指令与用户数据分离、
 *       数据以确定性 JSON 序列化、全局边界不可伪造）。问题的取值来自 application 层
 *       {@link KnowledgeQueryNormalizer} —— 与检索链路送给查询向量端口的是<b>同一个实现、
 *       同一个字符串</b>，因此模型看到的问题就是检索到证据的那个问题；</li>
 *   <li><b>调用 DeepSeek 一次</b>：只发送 system + user 两条消息，<b>不</b>注册工具、
 *       <b>不</b>启用会话记忆、<b>不</b>在本类内重试生成结果；</li>
 *   <li><b>校验引用</b>：{@link GroundedCitationValidator}（非空、至少一个规范引用、
 *       引用必须在本次证据内）。按 <b>ASCII 方括号引用协议</b>判定：只有
 *       {@code [K[1-9][0-9]*]} 是规范引用；K/k 前缀的<b>非纯字母记号</b>一律按畸形引用处理
 *       （{@code [K-1]}、{@code [K1a]}、{@code [K 2]}、{@code [k9]}、{@code [Kx1]}、{@code [Ka-1]}、
 *       {@code [Known1]}、{@code [ K999]}、未闭合的 {@code [K1} 等），整次作答失败；
 *       只有完整的纯 ASCII 字母单词（{@code [Known]}、{@code [KB]}、{@code [Kubernetes]}）
 *       才是普通文本。失败即 502；</li>
 *   <li><b>返回</b>：答案 + 实际引用编号 + 完整检索证据（证据是不可变快照，
 *       见 {@link KnowledgeRetrievalView}）。</li>
 * </ol>
 *
 * <h2>为什么答案必须后校验</h2>
 * <p>提示词只能「要求」模型给出规范引用，不能保证它照做 —— 结构化隔离与系统指令只是
 * <b>降低</b>注入风险，不能防止模型违背规则。真正决定响应能否返回成功的，是对模型输出的
 * <b>独立校验</b>：空答案、没有引用、协议内的畸形引用、以及本次没有给出的编号，
 * 全部判为失败（HTTP 502）。这样「答案看起来合理」不再等于「答案有据可查」。</p>
 *
 * <p>反过来说清楚边界：引用校验证明的是<b>编号来源</b>（每个引用都能回到本次检索到的具体切片），
 * 它<b>不</b>证明答案在事实上正确 —— 模型仍可能「引用了正确编号却推理错误」。
 * 校验器也只处理 ASCII 方括号协议，不解析 Markdown、不解码 HTML 实体、不做 Unicode 同形字符
 * 归一（详见 {@link GroundedCitationValidator} 的「已知边界」）。</p>
 *
 * <h2>不做的事</h2>
 * <p>不生成流式输出、不拼接多轮会话记忆、不注册任何工具；
 * 也不修改检索阶段的 SQL、排序、阈值或引用编号。</p>
 *
 * <p><b>重排（RAG 6/6）不在本层</b>：候选的二次排序由检索用例完成（见 ADR 0010），
 * 本层只是消费它给出的最终顺序 —— 因此提示词里的证据顺序、{@code allowedCitationIds}
 * 与响应里的 {@code citations} 天然一致，本层不需要（也不允许）自己再排一次。
 * 重排分也不会进入提示词：模型只需要「问题 + 候选正文 + 允许的编号」。</p>
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
     * @param retrieveKnowledgeUseCase 检索用例（输入的<b>合法性</b>校验唯一入口；NFC + strip
     *                                 由检索与问答共用的 {@link KnowledgeQueryNormalizer} 完成）
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

        // ① 检索：失败原样传播（不调用模型）；输入的规范化与合法性校验由检索用例负责
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
                normalizedQuestion(command), retrieval.citations());

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
     * 取出<b>与检索完全一致</b>的问题（FD-0012-R1）。
     *
     * <p>规范化只由 application 层的 {@link KnowledgeQueryNormalizer} 实现一次，
     * 检索链路把它的输出送给查询向量端口，这里把同一个实现作用在同一个原字符串上，
     * 因此模型看到的问题与生成查询向量的问题逐字符相同 —— 包含 NFC 折叠后的组合字符，
     * 以及被 {@code strip()} 去掉的首尾空白。</p>
     *
     * <p>为什么<b>不</b>在这里复制一套校验规则：合法性（空值、code point 上限、控制字符）与
     * {@code topK}/{@code minScore} 的范围只由检索用例判定。上面第 ① 步一旦没有抛异常，
     * 就说明原始输入已经通过全部校验，这里再做一次「检查」只会多出一份可能漂移的规则。</p>
     *
     * <p>注意：<b>不</b>使用 {@code strip} 之前的原始字符串 —— 首尾空白不计入 code point 上限，
     * 却会完整进入提示词，成为既不受长度约束、也毫无语义的内容。</p>
     *
     * @param command 原始命令（可为 {@code null}）
     * @return 与检索阶段完全相同的问题；命令缺失时为空串
     */
    private static String normalizedQuestion(KnowledgeAnswerCommand command) {
        return KnowledgeQueryNormalizer.normalize(command == null ? null : command.query());
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
