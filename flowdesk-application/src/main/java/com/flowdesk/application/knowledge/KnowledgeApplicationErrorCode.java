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

    /** 查询条件本身不合法：查询为 {@code null} 或标识缺失。 */
    INVALID_QUERY,

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
    METADATA_STORAGE_FAILURE
}
