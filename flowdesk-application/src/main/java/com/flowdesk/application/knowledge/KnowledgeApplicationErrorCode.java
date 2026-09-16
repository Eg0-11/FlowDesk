package com.flowdesk.application.knowledge;

/**
 * 知识文档应用层错误码。
 *
 * <p>稳定契约：输入适配器据此映射 HTTP 响应，测试断言错误码而不是文案。
 * 与工单应用层一样，领域层自身的失败（字段非法等）不使用这里的错误码，
 * 而是原样抛出 {@code com.flowdesk.domain.knowledge.KnowledgeDomainException}。</p>
 */
public enum KnowledgeApplicationErrorCode {

    /** 上传命令本身不合法：标题为空、文件名缺失、命令为 {@code null} 等。 */
    INVALID_UPLOAD_COMMAND,

    /** 列表查询条件本身不合法：查询为 {@code null} 或标识缺失。 */
    INVALID_QUERY,

    /** 解析命令本身不合法：命令为 {@code null}、标识缺失或期望版本为负。 */
    INVALID_PARSE_COMMAND,

    /** 索引命令本身不合法：命令为 {@code null}、标识缺失或期望版本为负。 */
    INVALID_INDEX_COMMAND,

    /** 调用方持有的版本已过期（CAS 失败或领取前版本比对不通过）。 */
    KNOWLEDGE_DOCUMENT_VERSION_CONFLICT,

    /** 当前状态不允许解析（重复领取、已解析完成等）。 */
    KNOWLEDGE_DOCUMENT_NOT_PARSABLE,

    /** 当前状态不允许索引（未解析、已在索引中、已索引完成等）。 */
    KNOWLEDGE_DOCUMENT_NOT_INDEXABLE,

    /** 当前环境未启用文档向量化（默认 profile 就是这种状态）。 */
    KNOWLEDGE_EMBEDDING_DISABLED,

    /** 上游向量服务失败：超时、限流、5xx、连接失败等。 */
    EMBEDDING_PROVIDER_ERROR,

    /**
     * 上游重排服务失败（RAG 6/6）：超时、限流、5xx、鉴权失败等。
     *
     * <p>与 {@link #EMBEDDING_PROVIDER_ERROR} 分开是刻意的：两者是<b>不同的上游</b>
     * （向量化与重排），合并成一个错误码后「哪一段上游出问题」就只能在日志里猜。
     * 两者都是 502，且同样<b>不会</b>静默降级成别的排序方式。</p>
     */
    RERANK_PROVIDER_ERROR,

    /** 检索请求本身不合法：query 缺失/空白/超长/含控制字符，或 topK、minScore 越界。 */
    INVALID_RETRIEVAL_QUERY,

    /**
     * 检索链路内部失败（RAG 4/6）：模型响应结构非法、查询向量不满足不变量、
     * 数据库检索失败，或向量检索端口返回的结果违反自身契约。
     *
     * <p>与 {@link #INVALID_RETRIEVAL_QUERY} 严格区分：那是「调用方输入有问题」（400），
     * 这一类是「服务端自己有问题」（500），绝不能落进 400 的映射里。</p>
     */
    KNOWLEDGE_RETRIEVAL_FAILURE,

    /** 原始内容不可读：对象缺失、不可读或内容键非法（绝不暴露真实路径）。 */
    DOCUMENT_CONTENT_UNREADABLE,

    /** 实际读取到的内容为空（0 字节）。 */
    EMPTY_DOCUMENT_CONTENT,

    /** 实际读取到的内容超过配置的大小上限。 */
    DOCUMENT_TOO_LARGE,

    /** 扩展名、媒体类型或文件内容互相不一致，或格式不受支持。 */
    UNSUPPORTED_DOCUMENT_FORMAT,

    /** 目标知识文档不存在。 */
    KNOWLEDGE_DOCUMENT_NOT_FOUND,

    /** 文档标识已存在（标识由服务端生成，出现即内部问题）。 */
    KNOWLEDGE_DOCUMENT_ALREADY_EXISTS,

    /** 持久化的知识文档快照不自洽（数据损坏，服务端内部错误）。 */
    INVALID_PERSISTED_DOCUMENT,

    /** 内容存储失败（写入、移动或删除原文件时出错）。 */
    CONTENT_STORAGE_FAILURE,

    /** 元数据存储失败（数据库写入或读取失败）。 */
    METADATA_STORAGE_FAILURE,

    /**
     * 端口返回值违反自身契约，或内部依赖在调用时失败（服务端内部问题）。
     *
     * <p>与 {@code INVALID_*} 系错误码分开是<b>必须</b>的：来自存储端口或时间端口的不一致
     * 绝不能落进领域异常的 400 映射里 —— 那是「调用方输入有问题」，而这一类是「服务端自己有问题」。</p>
     */
    KNOWLEDGE_INTERNAL_ERROR
}
