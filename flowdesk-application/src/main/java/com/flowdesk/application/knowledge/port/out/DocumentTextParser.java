package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.application.knowledge.parse.DocumentParsingException;
import com.flowdesk.domain.knowledge.DocumentFormat;
import java.io.InputStream;

/**
 * 文档文本提取端口。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>格式以服务端<b>已经识别并保存</b>的 {@link DocumentFormat} 为准；
 *       实现不得仅根据原始文件名选择解析器；</li>
 *   <li><b>不关闭</b>传入的流：生命周期由调用方（应用服务）负责；</li>
 *   <li>不访问网络资源，禁止解析外部实体；</li>
 *   <li>损坏、加密、伪造内容必须映射为稳定的 {@link DocumentParsingException} 失败码，
 *       不得把第三方解析器的异常消息向外传递；</li>
 *   <li>提取文本量超过上限时必须<b>立即停止</b>，不允许先构造出完整字符串再检查；</li>
 *   <li>空白文档返回空串（由应用服务判定为 {@code EMPTY_EXTRACTED_TEXT}）。</li>
 * </ul>
 */
public interface DocumentTextParser {

    /**
     * 提取纯文本。
     *
     * @param content 原始内容流（调用方关闭）
     * @param format  服务端识别出的格式
     * @return 提取出的文本；可能为空串，但绝不返回 {@code null}
     * @throws DocumentParsingException 解析失败（携带稳定失败码）
     */
    String parse(InputStream content, DocumentFormat format);
}
