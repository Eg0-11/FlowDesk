package com.flowdesk.bootstrap.knowledge;

import com.flowdesk.application.knowledge.port.in.RetrieveKnowledgeUseCase;
import com.flowdesk.application.knowledge.query.RetrieveKnowledgeQuery;
import com.flowdesk.application.knowledge.view.KnowledgeRetrievalView;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识检索 HTTP 接口（RAG 4/6）。
 *
 * <p>本类只做四件事：JSON → 查询对象的转换、调用检索用例、视图 → 响应体的转换、
 * 异常到错误契约的翻译。规范化、校验、向量生成与相似度检索全部在应用层与基础设施层完成。</p>
 *
 * <h2>内容协商</h2>
 * <p>与工单、知识文档接口一致：{@code consumes} 声明 {@code application/json}（因此
 * 其它 {@code Content-Type} 由框架映射为 415），{@code produces} 声明 {@code application/json}
 * （因此无法满足的 {@code Accept} 被映射为 406）。请求体无法解析（坏 JSON）走全局框架错误契约的 400。</p>
 *
 * <h2>语义边界</h2>
 * <p>本接口<b>只返回检索结果</b>：不生成自然语言答案、不调用 Chat 模型、不做 Rerank 与混合检索，
 * 也不修改任何文档状态或向量。没有命中是正常结果（200 + 空 {@code citations}），不是 404。</p>
 */
@RestController
@RequestMapping(path = KnowledgeSearchController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class KnowledgeSearchController {

    /** 统一前缀。 */
    static final String BASE_PATH = "/api/v1/knowledge";

    private final RetrieveKnowledgeUseCase retrieveKnowledgeUseCase;

    /**
     * @param retrieveKnowledgeUseCase 检索用例输入端口
     */
    public KnowledgeSearchController(RetrieveKnowledgeUseCase retrieveKnowledgeUseCase) {
        this.retrieveKnowledgeUseCase = retrieveKnowledgeUseCase;
    }

    /**
     * 检索知识库中与问题最相关的切片。
     *
     * <p>{@code query} 必填；{@code topK} 与 {@code minScore} 可省略（用服务端默认值）。</p>
     *
     * <p><b>空请求体走与「缺 query」完全相同的契约</b>（FD-0011-R1）：请求体缺失时不再抛
     * 「缺少请求体」这类通用错误，而是把 {@code query=null} 交给用例 —— 于是得到
     * 400 + {@code code=INVALID_REQUEST} + 固定 detail「检索请求不合法」，
     * 且不调用查询向量端口、不访问向量检索端口。这样「空 body」和「{@code {}}」在
     * 契约上没有任何区别，调用方不需要为两种「没给 query」的写法分别分支。
     * 请求体无法解析（坏 JSON）仍然保留全局的「请求体不是合法 JSON」契约。</p>
     *
     * @param request 检索请求体（可为 {@code null}）
     * @return 200 OK + 检索结果（可能为空列表）
     */
    @PostMapping(path = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<KnowledgeSearchResponse> search(
            @RequestBody(required = false) KnowledgeSearchRequest request) {

        RetrieveKnowledgeQuery query = request == null
                ? new RetrieveKnowledgeQuery(null, null, null)
                : new RetrieveKnowledgeQuery(request.query(), request.topK(), request.minScore());
        KnowledgeRetrievalView view = this.retrieveKnowledgeUseCase.retrieve(query);
        return ResponseEntity.ok(KnowledgeSearchResponse.from(view));
    }
}
