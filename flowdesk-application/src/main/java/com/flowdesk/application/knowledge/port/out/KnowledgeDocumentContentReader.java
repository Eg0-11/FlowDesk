package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.application.knowledge.KnowledgeApplicationErrorCode;
import com.flowdesk.application.knowledge.KnowledgeApplicationException;
import java.io.InputStream;

/**
 * 原始内容读取端口（FD-0009）。
 *
 * <p>与 {@link KnowledgeDocumentContentStore} 是一对：写入侧负责发布不可变对象，
 * 读取侧负责按内容键取回。两者<b>都只接受服务端生成的不透明内容键</b>，
 * 从不接受客户端路径。</p>
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>只接收领域中的内容键；实现必须做安全路径解析，并保证解析后的绝对路径仍在存储根目录内；</li>
 *   <li>只读取普通文件，且<b>不跟随符号链接</b>（避免用链接把根目录指到外部）；</li>
 *   <li>返回的流由<b>调用方</b>用 try-with-resources 关闭；</li>
 *   <li>绝不把真实路径返回给应用层；异常只携带稳定的应用层错误码，
 *       消息中不得出现绝对路径、存储根、SQL 或驱动信息；</li>
 *   <li>对象不存在、不可读或路径逃逸，一律以
 *       {@link KnowledgeApplicationErrorCode#DOCUMENT_CONTENT_UNREADABLE} 失败。</li>
 * </ul>
 */
public interface KnowledgeDocumentContentReader {

    /**
     * 打开内容流。
     *
     * @param contentKey 服务端生成的内容键
     * @return 内容输入流，由调用方关闭
     * @throws KnowledgeApplicationException 对象不存在、不可读或内容键非法
     */
    InputStream openStream(String contentKey);
}
