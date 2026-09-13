package com.flowdesk.domain.knowledge;

/**
 * 知识文档领域错误码。
 *
 * <p>稳定的对外契约：上层据此映射响应与内部错误，测试断言错误码而不是文案。
 * 异常文案只用于人工排查，<b>不得回显调用方传入的原始值</b>（标题、文件名等）。</p>
 */
public enum KnowledgeErrorCode {

    /** 文档标识为空或不是规范的 UUID。 */
    INVALID_DOCUMENT_ID,

    /** 标题为空、未 strip 或超长。 */
    INVALID_TITLE,

    /** 原始文件名不是合法文件名（含路径分隔符、控制字符、空白或超长）。 */
    INVALID_ORIGINAL_FILENAME,

    /** 文档格式为空或不支持。 */
    INVALID_FORMAT,

    /** 媒体类型为空或格式不合法。 */
    INVALID_MEDIA_TYPE,

    /** 内容大小不合法（必须大于 0）。 */
    INVALID_SIZE,

    /** 内容摘要不是 64 位小写十六进制。 */
    INVALID_DIGEST,

    /** 内容键为空、含路径分隔符或超长。 */
    INVALID_CONTENT_KEY,

    /** 状态为空。 */
    INVALID_STATUS,

    /** 时间缺失或时间链不成立（{@code createdAt > updatedAt}）。 */
    INVALID_TIMELINE,

    /** 从持久化快照恢复时发现快照自相矛盾（数据损坏，非调用方错误）。 */
    INVALID_RESTORED_STATE
}
