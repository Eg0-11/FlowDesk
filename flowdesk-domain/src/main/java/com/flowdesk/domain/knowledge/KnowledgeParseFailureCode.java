package com.flowdesk.domain.knowledge;

/**
 * 解析失败的<b>稳定错误码</b>。
 *
 * <p>只有枚举常量会进入数据库与响应：解析器抛出的异常消息、绝对路径、堆栈与第三方库内部信息
 * <b>一律不得</b>被持久化或返回给调用方 —— 那些内容既不稳定（换版本就变），
 * 又可能泄漏服务端内部结构。</p>
 *
 * <p>诊断用的原始异常只保留在服务端日志与被补偿的异常链里。</p>
 */
public enum KnowledgeParseFailureCode {

    /** 文件内容损坏，解析器无法读取结构。 */
    CORRUPTED_DOCUMENT,

    /** 文件被加密，缺少口令无法解析。 */
    ENCRYPTED_DOCUMENT,

    /** 解析成功但提取文本为空（空白文档）。 */
    EMPTY_EXTRACTED_TEXT,

    /** 提取文本量超过配置上限。 */
    EXTRACTED_TEXT_TOO_LARGE,

    /** 内容与声明的格式不一致（伪造或改扩展名）。 */
    UNSUPPORTED_DOCUMENT_CONTENT,

    /** 切片数量超过上限。 */
    TOO_MANY_CHUNKS,

    /** 其它解析器失败（不区分具体原因，避免泄漏内部细节）。 */
    PARSER_FAILURE
}
