package com.flowdesk.agent.ai;

import com.flowdesk.application.integration.port.out.AssetQueryPort;
import com.flowdesk.application.integration.port.out.MonitoringSnapshotQueryPort;
import com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 编排装配。
 *
 * <p>整个配置只在 {@code flowdesk.ai.enabled=true} 时生效：默认 profile 下不会创建任何
 * AI Bean，因此没有 API Key 也能启动，也不可能发起模型调用。</p>
 *
 * <p>这里只接收一个按名称限定的 {@link ChatClient}，不感知它是如何构造的 ——
 * 具体模型适配器属于 infrastructure 的职责。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "flowdesk.ai.enabled", havingValue = "true")
public class FlowDeskAiConfiguration {

    /**
     * 注册本地只读工具集。它不实现 {@code ToolCallbackProvider}，
     * 因此不会被自动注册为全局默认工具。
     */
    @Bean
    public SupportPolicyTools supportPolicyTools() {
        return new SupportPolicyTools();
    }

    /**
     * 注册 AI 用例实现；该 Bean 同时实现 {@code AiChatUseCase} 与 {@code AiToolSmokeUseCase}，
     * 上游按接口类型注入即可，无需知道具体实现类。
     */
    @Bean
    public ChatClientAiService chatClientAiService(
            @Qualifier("deepSeekChatClient") ChatClient deepSeekChatClient,
            SupportPolicyTools supportPolicyTools) {
        return new ChatClientAiService(deepSeekChatClient, supportPolicyTools);
    }

    /**
     * 基于检索证据的知识库问答实现（RAG 5/6）。
     *
     * <p>依赖两件事：检索用例（输入的<b>合法性</b>校验唯一入口，也是「无证据不调用模型」的判断依据；
     * NFC + strip 则由检索与问答共用的 {@code KnowledgeQueryNormalizer} 完成）
     * 与命名明确的 {@code deepSeekChatClient}。本 Bean 只在 {@code flowdesk.ai.enabled=true}
     * 时存在，因此默认 profile 下不会有任何问答能力，也不会有出网可能。</p>
     *
     * <p>注意：<b>Chat 走 DeepSeek，Embedding 走 DashScope</b>，两者职责不混用 ——
     * 本实现不接触任何 Embedding 适配器，查询向量由检索用例在 knowledge 链路内完成。</p>
     *
     * @param retrieveKnowledgeUseCase 检索用例（含输入校验与 503 开关判断）
     * @param deepSeekChatClient       DeepSeek 对话客户端
     * @return 知识库问答用例实现
     */
    @Bean
    public GroundedKnowledgeAnswerService groundedKnowledgeAnswerService(
            RetrieveKnowledgeUseCase retrieveKnowledgeUseCase,
            @Qualifier("deepSeekChatClient") ChatClient deepSeekChatClient) {
        return new GroundedKnowledgeAnswerService(retrieveKnowledgeUseCase, deepSeekChatClient);
    }

    /**
     * 资产诊断编排（FD-0017-A）。
     *
     * <p>依赖两个 application 层查询端口（资产查询、监控快照查询）与命名明确的
     * {@code deepSeekChatClient}。流程由 {@link AssetDiagnosisService} 确定性控制：
     * 两个端口各调用一次、顺序固定、模型不能选工具也不能改编号 ——
     * 这里<b>不</b>注册任何 {@code ToolCallback}，远端能力不会变成模型可见的工具。</p>
     *
     * <p>本 Bean 只在 {@code flowdesk.ai.enabled=true} 时存在，因此默认 profile 下没有诊断能力，
     * 也没有任何出网可能。</p>
     *
     * @param assetQueryPort              资产查询端口
     * @param monitoringSnapshotQueryPort 监控快照查询端口
     * @param deepSeekChatClient          DeepSeek 对话客户端
     * @return 资产诊断用例实现
     */
    @Bean
    public AssetDiagnosisService assetDiagnosisService(
            AssetQueryPort assetQueryPort,
            MonitoringSnapshotQueryPort monitoringSnapshotQueryPort,
            @Qualifier("deepSeekChatClient") ChatClient deepSeekChatClient) {
        return new AssetDiagnosisService(assetQueryPort, monitoringSnapshotQueryPort, deepSeekChatClient);
    }

    /**
     * 事件研判 Agent Graph（FD-0018-A）。
     *
     * <p>三个证据来源（知识检索、资产查询、监控快照查询）+ 命名的 {@code deepSeekChatClient}。
     * Graph 在<b>装配期编译一次</b>（构造 {@link IncidentTriageGraph} 时），之后每次调用复用同一个
     * 编译产物、各自使用独立状态；因此这里既没有每次调用重复建图的开销，也没有跨请求状态。</p>
     *
     * <p>仍然只在 {@code flowdesk.ai.enabled=true} 时创建：默认 profile 下没有图、没有用例 Bean，
     * 也就没有任何模型调用可能。这里同样<b>不</b>注册任何 {@code ToolCallback} ——
     * 远端能力不会变成模型可见的工具。</p>
     *
     * @param retrieveKnowledgeUseCase    知识检索用例（输入合法性唯一入口）
     * @param assetQueryPort              资产查询端口
     * @param monitoringSnapshotQueryPort 监控快照查询端口
     * @param deepSeekChatClient          DeepSeek 对话客户端
     * @return 事件研判用例实现
     */
    @Bean
    public IncidentTriageService incidentTriageService(
            RetrieveKnowledgeUseCase retrieveKnowledgeUseCase,
            AssetQueryPort assetQueryPort,
            MonitoringSnapshotQueryPort monitoringSnapshotQueryPort,
            @Qualifier("deepSeekChatClient") ChatClient deepSeekChatClient) {
        return new IncidentTriageService(retrieveKnowledgeUseCase, assetQueryPort, monitoringSnapshotQueryPort,
                deepSeekChatClient);
    }
}
