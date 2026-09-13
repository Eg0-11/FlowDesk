package com.flowdesk.bootstrap.knowledge;

import com.flowdesk.application.knowledge.command.UploadKnowledgeDocumentCommand;
import com.flowdesk.application.knowledge.port.in.KnowledgeDocumentQueryUseCase;
import com.flowdesk.application.knowledge.port.in.UploadKnowledgeDocumentUseCase;
import com.flowdesk.application.knowledge.query.GetKnowledgeDocumentQuery;
import com.flowdesk.application.knowledge.view.KnowledgeDocumentView;
import com.flowdesk.bootstrap.web.InvalidRequestException;
import com.flowdesk.domain.knowledge.KnowledgeDocumentId;
import com.flowdesk.domain.knowledge.KnowledgeDomainException;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识文档 HTTP 接口。
 *
 * <p>本类只做四件事：multipart → 纯 Java 内容源的转换、HTTP DTO 转换、
 * 状态码与 {@code Location} 设置、异常到错误契约的翻译。
 * 格式识别、内容校验、流式落盘与补偿全部在应用层与基础设施层完成。</p>
 *
 * <h2>内容协商</h2>
 * <p>与工单接口一致：只产出 {@code application/json}，由类级 {@code produces} 声明，
 * 因此不可接受的 {@code Accept} 会在进入本类之前被映射为 406。</p>
 */
@RestController
@RequestMapping(path = KnowledgeDocumentController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class KnowledgeDocumentController {

    /** 统一前缀。 */
    static final String BASE_PATH = "/api/v1/knowledge/documents";

    private final UploadKnowledgeDocumentUseCase uploadUseCase;

    private final KnowledgeDocumentQueryUseCase queryUseCase;

    /**
     * @param uploadUseCase 上传用例输入端口
     * @param queryUseCase  查询用例输入端口
     */
    public KnowledgeDocumentController(UploadKnowledgeDocumentUseCase uploadUseCase,
            KnowledgeDocumentQueryUseCase queryUseCase) {

        this.uploadUseCase = uploadUseCase;
        this.queryUseCase = queryUseCase;
    }

    /**
     * 上传知识文档（{@code multipart/form-data}）。
     *
     * <p>两个部分都声明为可选，由本方法自己判定缺失并给出固定文案的 400：
     * 这样错误契约统一由项目控制，而不是依赖框架对不同缺失情形的默认行为。</p>
     *
     * @param title 文档标题
     * @param file  原始文件
     * @return 201 Created，带 {@code Location}
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KnowledgeDocumentResponse> upload(
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "file", required = false) MultipartFile file) {

        if (title == null) {
            throw new InvalidRequestException("缺少必填部分：title");
        }
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("缺少必填部分：file（且内容不能为空）");
        }

        KnowledgeDocumentView view = this.uploadUseCase.upload(
                new UploadKnowledgeDocumentCommand(title, new MultipartContentSource(file)));

        return ResponseEntity.created(URI.create(BASE_PATH + "/" + view.id()))
                .body(KnowledgeDocumentResponse.from(view));
    }

    /**
     * 查询知识文档元数据。
     *
     * <p>本任务不提供原文件下载接口；响应里也不会有内容键或任何磁盘路径。</p>
     *
     * @param documentId 文档标识
     * @return 200 OK
     */
    @GetMapping("/{documentId}")
    public ResponseEntity<KnowledgeDocumentResponse> get(@PathVariable String documentId) {
        return ResponseEntity.ok(KnowledgeDocumentResponse.from(
                this.queryUseCase.get(new GetKnowledgeDocumentQuery(parseDocumentId(documentId)))));
    }

    /**
     * 严格解析路径中的文档标识。
     *
     * <p>绑定为 {@code String} 而不是 {@code UUID}：Spring 默认的 UUID 转换会把
     * {@code 1-1-1-1-1} 这类缩写形式解析成一个看起来正常的标识，这里要求规范形式。</p>
     */
    private static KnowledgeDocumentId parseDocumentId(String rawDocumentId) {
        try {
            return KnowledgeDocumentId.parse(rawDocumentId);
        } catch (KnowledgeDomainException ex) {
            throw new InvalidRequestException("documentId 必须是规范的 36 位 UUID");
        }
    }
}
