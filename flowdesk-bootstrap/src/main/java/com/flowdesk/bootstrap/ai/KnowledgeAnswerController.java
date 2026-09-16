package com.flowdesk.bootstrap.ai;

import com.flowdesk.application.ai.KnowledgeAnswerCommand;
import com.flowdesk.application.ai.KnowledgeAnswerResult;
import com.flowdesk.application.ai.KnowledgeAnswerUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库问答 HTTP 接口（RAG 5/6）。
 *
 * <p>独立于 {@link AiController}：问答有自己的请求/响应结构与错误语义，
 * 混在一起会让两个接口的契约互相牵制。</p>
 *
 * <p>本类只做 DTO 转换：不注入 {@code ChatClient}、不注入检索适配器、不做任何输入校验 ——
 * 校验属于检索用例，检索与生成属于 agent 编排实现。</p>
 *
 * <h2>开关</h2>
 * <p>整个控制器只在 {@code flowdesk.ai.enabled=true} 时注册：默认 profile 下
 * <b>不存在</b>这个端点（404），也不可能发起任何模型调用。</p>
 *
 * <h2>错误契约</h2>
 * <ul>
 *   <li>空请求体与 {@code {}} → 与检索接口相同的 400「检索请求不合法」；</li>
 *   <li>请求体无法解析（坏 JSON）→ 全局的「请求体不是合法 JSON」400；</li>
 *   <li>未启用向量化 → 503 {@code KNOWLEDGE_EMBEDDING_DISABLED}；</li>
 *   <li>检索内部失败 → 500；</li>
 *   <li>模型调用失败或答案引用校验失败 → 502 {@code AI_PROVIDER_ERROR} + {@code requestId}。</li>
 * </ul>
 */
@RestController
@RequestMapping(path = KnowledgeAnswerController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "flowdesk.ai.enabled", havingValue = "true")
public class KnowledgeAnswerController {

    /** 统一前缀（与 {@link AiController} 相同的前缀，不同路径）。 */
    static final String BASE_PATH = "/api/v1/ai";

    private final KnowledgeAnswerUseCase knowledgeAnswerUseCase;

    /**
     * @param knowledgeAnswerUseCase 知识库问答用例
     */
    public KnowledgeAnswerController(KnowledgeAnswerUseCase knowledgeAnswerUseCase) {
        this.knowledgeAnswerUseCase = knowledgeAnswerUseCase;
    }

    /**
     * 依据知识库检索结果回答问题。
     *
     * <p>空请求体按 {@code query=null} 交给用例：于是得到与 {@code {}} 完全相同的
     * 400「检索请求不合法」，而不是「缺少请求体」这类通用错误。</p>
     *
     * @param request 问答请求体（可为 {@code null}）
     * @return 200 OK + 答案、实际引用编号与完整检索证据
     */
    @PostMapping(path = "/knowledge-answer", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<KnowledgeAnswerResponse> answer(
            @RequestBody(required = false) KnowledgeAnswerRequest request) {

        KnowledgeAnswerCommand command = request == null
                ? new KnowledgeAnswerCommand(null, null, null)
                : new KnowledgeAnswerCommand(request.query(), request.topK(), request.minScore());
        KnowledgeAnswerResult result = this.knowledgeAnswerUseCase.answer(command);
        return ResponseEntity.ok(KnowledgeAnswerResponse.from(result));
    }
}
