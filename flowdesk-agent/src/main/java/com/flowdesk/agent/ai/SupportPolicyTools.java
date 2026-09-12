package com.flowdesk.agent.ai;

import com.flowdesk.application.ai.IssueType;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * FlowDesk 本地只读工具集。
 *
 * <p>该对象只是一个普通 Bean：它不实现 {@code ToolCallbackProvider}，
 * 因此不会被 Spring AI 的自动配置注册成全局默认工具，
 * 只能在 {@code /api/v1/ai/tool-smoke} 单次请求中通过 {@code .tools(...)} 显式注册，
 * 普通聊天路径看不到它。</p>
 */
public class SupportPolicyTools {

    /** 工具名，固定不可更改。 */
    public static final String TOOL_NAME = "lookup_support_policy";

    private static final Logger log = LoggerFactory.getLogger(SupportPolicyTools.class);

    /**
     * 查询工单支持策略：只读、确定性、不访问网络/数据库/MCP。
     *
     * @param issueType   工单类型，允许值 {@code ACCOUNT_LOCK}、{@code VPN_FAILURE}、{@code DEVICE_OFFLINE}
     * @param toolContext Spring AI 注入的工具上下文，携带 requestId 与请求级调用记录器
     * @return 该类型的固定支持策略
     * @throws com.flowdesk.application.ai.AiRequestException 工单类型不在允许值内时明确失败，不返回兜底结果
     */
    @Tool(name = TOOL_NAME,
            description = "查询 FlowDesk 内部工单支持策略。输入工单类型，返回该类型的优先级、处理组与首个处理动作。"
                    + "只读且结果确定，不访问任何外部系统。")
    public SupportPolicy lookupSupportPolicy(
            @ToolParam(description = "工单类型，允许值：ACCOUNT_LOCK、VPN_FAILURE、DEVICE_OFFLINE") String issueType,
            ToolContext toolContext) {

        long startedAt = System.nanoTime();
        try {
            SupportPolicy policy = SupportPolicy.lookup(IssueType.require(issueType));
            record(toolContext, true);
            logCompletion(toolContext, true, startedAt);
            return policy;
        } catch (RuntimeException ex) {
            record(toolContext, false);
            logCompletion(toolContext, false, startedAt);
            throw ex;
        }
    }

    /**
     * 把本次调用写入请求级记录器。记录器缺失时静默跳过，绝不创建全局状态。
     */
    private void record(ToolContext toolContext, boolean success) {
        if (toolContext == null) {
            return;
        }
        Map<String, Object> context = toolContext.getContext();
        if (context == null) {
            return;
        }
        Object candidate = context.get(ToolInvocationRecorder.CONTEXT_KEY);
        if (candidate instanceof ToolInvocationRecorder recorder) {
            recorder.record(TOOL_NAME, success);
        }
    }

    /**
     * 只记录 requestId、工具名、结果状态与耗时；不记录提示词、工具参数、工具结果与模型思维内容。
     */
    private void logCompletion(ToolContext toolContext, boolean success, long startedAt) {
        log.info("ai.tool completed requestId={} tool={} success={} durationMs={}",
                requestId(toolContext), TOOL_NAME, success, elapsedMillis(startedAt));
    }

    private String requestId(ToolContext toolContext) {
        if (toolContext == null) {
            return "unknown";
        }
        Map<String, Object> context = toolContext.getContext();
        if (context == null) {
            return "unknown";
        }
        Object requestId = context.get(ToolInvocationRecorder.REQUEST_ID_KEY);
        return requestId instanceof String value ? value : "unknown";
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
