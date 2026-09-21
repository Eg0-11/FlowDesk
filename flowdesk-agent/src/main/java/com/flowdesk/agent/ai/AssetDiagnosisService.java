package com.flowdesk.agent.ai;

import com.flowdesk.application.ai.AiProviderException;
import com.flowdesk.application.ai.AiRequestException;
import com.flowdesk.application.ai.AssetDiagnosisCommand;
import com.flowdesk.application.ai.AssetDiagnosisResult;
import com.flowdesk.application.ai.AssetDiagnosisUseCase;
import com.flowdesk.application.integration.AssetIdentifier;
import com.flowdesk.application.integration.AssetQueryResult;
import com.flowdesk.application.integration.MonitoringSnapshotQueryResult;
import com.flowdesk.application.integration.QueryOutcome;
import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 资产诊断编排（FD-0017-A）。
 *
 * <h2>确定性编排，而不是让模型选工具</h2>
 * <p>本类是流程的<b>唯一</b>决策者：</p>
 * <ul>
 *   <li>两个查询端口各调用<b>一次</b>，顺序固定为<b>资产 → 监控</b>；</li>
 *   <li>第一个查询失败或未命中，<b>仍然</b>执行第二个（两次查询在编排层无条件串行执行，
 *       没有短路、没有重试、没有并行、没有缓存）；</li>
 *   <li>模型不能选择工具名、不能修改 assetId、不能跳过某个查询；</li>
 *   <li>送给模型的请求里<b>没有</b>工具（不注册 {@code ToolCallback}、不注册 {@code @Tool}），
 *       也没有会话记忆、没有流式输出、没有内部重试；</li>
 *   <li>本类只依赖 application 层的两个查询端口，<b>不</b>依赖 MCP SDK 或任何传输实现。</li>
 * </ul>
 *
 * <h2>三条路径</h2>
 * <table border="1">
 *   <caption>证据与模型调用</caption>
 *   <tr><th>情形</th><th>是否调用模型</th><th>结果</th></tr>
 *   <tr><td>至少一个查询命中</td><td><b>调用一次</b></td>
 *       <td>{@code grounded=true} + 模型诊断（引用受校验）+ 两个查询的真实状态</td></tr>
 *   <tr><td>两边都是 {@code NOT_FOUND}</td><td>不调用</td>
 *       <td>{@code grounded=false} + 固定回答「未查询到该资产或可用的监控快照。」</td></tr>
 *   <tr><td>两边都没有命中，且至少一侧是失败（{@code FAILED}/{@code DISABLED}/{@code TIMEOUT}…）</td>
 *       <td>不调用</td>
 *       <td>{@code grounded=false} + 固定降级回答
 *           「当前无法获得足够的资产与监控证据，暂时不能生成诊断结论。」</td></tr>
 * </table>
 *
 * <p>部分命中（一侧命中、另一侧未命中或失败）走第一条路径：允许基于<b>可用证据</b>作答，
 * 但响应里保留另一侧的<b>真实状态</b>，绝不把部分证据包装成完整数据。</p>
 *
 * <h2>失败不等于未找到</h2>
 * <p>{@code FAILED}（连接失败、超时、非法响应…）与 {@code NOT_FOUND}（查过了没有）是两种不同结论，
 * 因此本类<b>从不</b>把失败改写成「没有数据」，也从不把 {@code DISABLED} 改写成「没有数据」：
 * 它们各自以原始 {@code QueryOutcome} + {@code QueryFailure} 出现在结果里。</p>
 *
 * <h2>输入校验</h2>
 * <p>不合法输入（{@code null} 命令、{@code null}/空白/不满足 {@code AST-[0-9]{6}} 的编号）
 * 在<b>任何端口调用与模型调用之前</b>抛出 {@link AiRequestException}：
 * 不 trim、不修正、不猜测。规则只来自 application 层的
 * {@link AssetIdentifier}，这里不复制第二套。</p>
 *
 * <h2>模型调用与引用校验</h2>
 * <p>模型调用失败、返回空答案、或引用校验失败，一律抛
 * {@link AiProviderException}（携带本次 {@code requestId}，固定安全文案，不含模型原文）。
 * 校验失败时<b>不</b>修正、<b>不</b>补引用、<b>不</b>重新调用模型。</p>
 *
 * <h2>日志</h2>
 * <p>每次诊断只记录固定元数据：{@code operation}、{@code requestId}、{@code assetOutcome}、
 * {@code monitoringOutcome}、{@code modelCalled}、{@code evidenceCount}、{@code usedEvidenceCount}、
 * {@code success}、{@code durationMs}，失败时再加稳定失败类别。
 * <b>不</b>记录 assetId、资产详情、监控数值、提示词、模型回答、MCP 原始响应、
 * 异常消息或堆栈、端点与密钥。</p>
 */
public class AssetDiagnosisService implements AssetDiagnosisUseCase {

    private static final Logger log = LoggerFactory.getLogger(AssetDiagnosisService.class);

    /** 操作名（日志固定字段之一）。 */
    static final String OPERATION = "ai.asset-diagnosis";

    /** 输入不合法时的固定安全文案。 */
    static final String INVALID_INPUT_MESSAGE = "assetId 必须形如 AST-000001（AST- 加 6 位数字）";

    /** 两边都没有命中、且都不是失败时的固定回答。 */
    static final String NO_RECORD_ANSWER = "未查询到该资产或可用的监控快照。";

    /** 无可用证据（含失败/未启用）时的固定降级回答。 */
    static final String INSUFFICIENT_EVIDENCE_ANSWER = "当前无法获得足够的资产与监控证据，暂时不能生成诊断结论。";

    /** 输入不合法时日志里的固定占位（此时没有任何查询发生过）。 */
    static final String NOT_QUERIED = "NOT_QUERIED";

    private final AssetQueryPort assetQueryPort;

    private final MonitoringSnapshotQueryPort monitoringSnapshotQueryPort;

    private final ChatClient deepSeekChatClient;

    /**
     * @param assetQueryPort             资产查询端口（application 层契约）
     * @param monitoringSnapshotQueryPort 监控快照查询端口（application 层契约）
     * @param deepSeekChatClient         命名明确的 DeepSeek ChatClient（不注册工具、无会话记忆）
     */
    public AssetDiagnosisService(AssetQueryPort assetQueryPort,
            MonitoringSnapshotQueryPort monitoringSnapshotQueryPort, ChatClient deepSeekChatClient) {

        this.assetQueryPort = assetQueryPort;
        this.monitoringSnapshotQueryPort = monitoringSnapshotQueryPort;
        this.deepSeekChatClient = deepSeekChatClient;
    }

    @Override
    public AssetDiagnosisResult diagnose(AssetDiagnosisCommand command) {
        long startedAt = System.nanoTime();
        String assetId = command == null ? null : command.assetId();

        // ① 输入校验：发生在两个端口与模型之前；规则来自 application 层，不在这里复制第二套
        if (!AssetIdentifier.isValid(assetId)) {
            logFailure("none", NOT_QUERIED, NOT_QUERIED, false, 0, 0, AssetDiagnosisFailure.INVALID_INPUT, null,
                    startedAt);
            throw new AiRequestException(INVALID_INPUT_MESSAGE);
        }

        String requestId = UUID.randomUUID().toString();

        // ② 两次查询：顺序固定（资产 → 监控），各一次；第一次失败/未命中也要执行第二次
        //    端口按契约只返回三态结果，这里仍然兜住「违约抛异常」的情况：
        //    即使违约也会先完成第二次查询，然后整次诊断按 502 失败，绝不把违约伪装成某种查询状态
        AssetQueryResult asset = null;
        RuntimeException assetViolation = null;
        try {
            asset = this.assetQueryPort.findAsset(assetId);
        }
        catch (RuntimeException ex) {
            assetViolation = ex;
        }

        MonitoringSnapshotQueryResult monitoring = null;
        RuntimeException monitoringViolation = null;
        try {
            monitoring = this.monitoringSnapshotQueryPort.findLatestSnapshot(assetId);
        }
        catch (RuntimeException ex) {
            monitoringViolation = ex;
        }

        RuntimeException violation = assetViolation != null ? assetViolation : monitoringViolation;
        if (violation != null) {
            logFailure(requestId, outcomeOf(asset), outcomeOf(monitoring), false, 0, 0,
                    AssetDiagnosisFailure.PORT_CONTRACT_VIOLATION, violation, startedAt);
            throw new AiProviderException(requestId, violation);
        }

        List<String> allowedEvidenceIds = new ArrayList<>(AssetDiagnosisResult.foundEvidenceIds(asset, monitoring));

        // ③ 无命中证据：不调用模型，按「都没查到」与「有失败」给出两种固定回答
        if (allowedEvidenceIds.isEmpty()) {
            String answer = bothNotFound(asset, monitoring) ? NO_RECORD_ANSWER : INSUFFICIENT_EVIDENCE_ANSWER;
            logCompleted(requestId, outcomeOf(asset), outcomeOf(monitoring), false, 0, 0, startedAt);
            return new AssetDiagnosisResult(requestId, answer, false, List.of(), asset, monitoring);
        }

        // ④ 有命中证据：构造提示词 → 调用模型一次（无工具、无会话记忆、无流式、无重试）
        AssetDiagnosisPrompt prompt = AssetDiagnosisPromptBuilder.build(assetId, allowedEvidenceIds, asset,
                monitoring);

        String rawAnswer;
        try {
            rawAnswer = this.deepSeekChatClient.prompt()
                    .system(prompt.systemPrompt())
                    .user(prompt.userPrompt())
                    .call()
                    .content();
        }
        catch (RuntimeException ex) {
            logFailure(requestId, outcomeOf(asset), outcomeOf(monitoring), true, allowedEvidenceIds.size(), 0,
                    AssetDiagnosisFailure.MODEL_CALL_FAILED, ex, startedAt);
            throw new AiProviderException(requestId, ex);
        }
        if (rawAnswer == null || rawAnswer.strip().isEmpty()) {
            logFailure(requestId, outcomeOf(asset), outcomeOf(monitoring), true, allowedEvidenceIds.size(), 0,
                    AssetDiagnosisFailure.ANSWER_EMPTY, null, startedAt);
            throw new AiProviderException(requestId, null);
        }

        // ⑤ 引用校验：失败即 502（不修正、不补引用、不重试）
        List<String> usedEvidenceIds;
        try {
            usedEvidenceIds = AssetDiagnosisCitationValidator.requireValidEvidenceCitations(rawAnswer,
                    allowedEvidenceIds);
        }
        catch (AssetDiagnosisException ex) {
            logFailure(requestId, outcomeOf(asset), outcomeOf(monitoring), true, allowedEvidenceIds.size(), 0,
                    ex.failure(), ex, startedAt);
            throw new AiProviderException(requestId, ex);
        }

        String answer = rawAnswer.strip();
        logCompleted(requestId, outcomeOf(asset), outcomeOf(monitoring), true, allowedEvidenceIds.size(),
                usedEvidenceIds.size(), startedAt);

        return new AssetDiagnosisResult(requestId, answer, true, usedEvidenceIds, asset, monitoring);
    }

    /**
     * 两次查询是否都是「查过了但没有」。
     *
     * @param asset      资产查询结果
     * @param monitoring 监控查询结果
     * @return 两者都是 {@code NOT_FOUND} 时为 {@code true}
     */
    private static boolean bothNotFound(AssetQueryResult asset, MonitoringSnapshotQueryResult monitoring) {
        return asset.outcome() == QueryOutcome.NOT_FOUND && monitoring.outcome() == QueryOutcome.NOT_FOUND;
    }

    /**
     * 成功路径：只记录固定元数据（操作、请求标识、两侧结果、是否调用模型、证据数量、耗时）。
     *
     * @param requestId         请求标识
     * @param assetOutcome      资产查询结果三态
     * @param monitoringOutcome 监控查询结果三态
     * @param modelCalled       本次是否调用过模型
     * @param evidenceCount     命中证据数量
     * @param usedEvidenceCount 实际引用数量
     * @param startedAt         {@code System.nanoTime()} 起点
     */
    private static void logCompleted(String requestId, String assetOutcome, String monitoringOutcome,
            boolean modelCalled, int evidenceCount, int usedEvidenceCount, long startedAt) {

        log.info("{} completed operation={} requestId={} assetOutcome={} monitoringOutcome={} modelCalled={} "
                        + "evidenceCount={} usedEvidenceCount={} success=true durationMs={}",
                OPERATION, OPERATION, requestId, assetOutcome, monitoringOutcome, modelCalled, evidenceCount,
                usedEvidenceCount, elapsedMillis(startedAt));
    }

    /**
     * 失败路径：只记录稳定失败类别与异常类名；不记录 assetId、提示词、证据内容、模型回答或异常消息。
     *
     * @param requestId         请求标识（输入不合法时为 {@code none}）
     * @param assetOutcome      资产查询结果三态（未查询时为 {@code NOT_QUERIED}）
     * @param monitoringOutcome 监控查询结果三态
     * @param modelCalled       本次是否调用过模型
     * @param evidenceCount     命中证据数量
     * @param usedEvidenceCount 实际引用数量
     * @param failure           稳定失败类别
     * @param cause             原始异常（可为 {@code null}）；只取类名，不取 message 与堆栈
     * @param startedAt         {@code System.nanoTime()} 起点
     */
    private static void logFailure(String requestId, String assetOutcome, String monitoringOutcome,
            boolean modelCalled, int evidenceCount, int usedEvidenceCount, AssetDiagnosisFailure failure,
            RuntimeException cause, long startedAt) {

        log.warn("{} failed operation={} requestId={} assetOutcome={} monitoringOutcome={} modelCalled={} "
                        + "evidenceCount={} usedEvidenceCount={} failure={} exception={} success=false durationMs={}",
                OPERATION, OPERATION, requestId, assetOutcome, monitoringOutcome, modelCalled, evidenceCount,
                usedEvidenceCount, failure.name(), cause == null ? "none" : cause.getClass().getName(),
                elapsedMillis(startedAt));
    }

    private static String outcomeOf(AssetQueryResult asset) {
        return asset == null ? "UNKNOWN" : asset.outcome().name();
    }

    private static String outcomeOf(MonitoringSnapshotQueryResult monitoring) {
        return monitoring == null ? "UNKNOWN" : monitoring.outcome().name();
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
