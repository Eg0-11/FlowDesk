package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.application.knowledge.parse.DocumentChunkingException;
import java.util.List;

/**
 * 文档切片端口。
 *
 * <p><b>确定性</b>是本端口最重要的契约：相同输入与相同配置必须产生<b>逐字节相同</b>的结果。
 * 它带来三个好处：切片可复现（便于排查与审计）、重复解析不会造成内容漂移、
 * 将来做向量化时「同一文档同一版本的向量」是稳定的。</p>
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>实现负责文本规范化（换行统一、NFC、去首尾空白、合并连续空行）与切片两步；</li>
 *   <li>按 Unicode <b>code point</b> 操作：不得切断代理对或 emoji；</li>
 *   <li>每片非空，顺序稳定，单片长度不超过配置的 chunk-size；</li>
 *   <li>相邻片按配置保留 overlap；算法每轮必须前进（不得因 overlap 死循环）；</li>
 *   <li>切片数超过上限时以 {@link DocumentChunkingException} 安全失败。</li>
 * </ul>
 */
public interface DocumentChunker {

    /**
     * 规范化并切片。
     *
     * @param extractedText 解析器提取出的原始文本
     * @return 切片列表，顺序即 chunkIndex 顺序；文本为空时返回空列表
     * @throws DocumentChunkingException 切片数量或单片的容量超过配置/存储上限
     */
    List<String> chunk(String extractedText);
}
