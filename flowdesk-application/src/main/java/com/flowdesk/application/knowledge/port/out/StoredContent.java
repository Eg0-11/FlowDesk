package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.domain.knowledge.KnowledgeDocument;
import com.flowdesk.domain.knowledge.Sha256Digest;
import java.util.Objects;

/**
 * 一次内容存储的结果。
 *
 * <p>三个值都由存储适配器<b>根据实际写入的字节</b>得出：键是它生成的不透明标识，
 * 大小与摘要是它边写边算出来的。因此上层不需要（也不应该）自己再算一遍摘要 ——
 * 那会变成「两次读取、两份真相」。</p>
 *
 * <h2>构造即校验</h2>
 * <p>这是「成功结果」的类型，不允许适配器构造出一个自相矛盾的成功：</p>
 * <ul>
 *   <li>{@code contentKey} 非空、无首尾空白、不含路径分隔符或控制字符
 *       （它会被用于拼路径，因此「安全」是它的定义的一部分）、长度不超过
 *       {@link KnowledgeDocument#MAX_CONTENT_KEY_LENGTH}；</li>
 *   <li>{@code sizeBytes > 0}；</li>
 *   <li>{@code sha256} 非空。</li>
 * </ul>
 *
 * <p>这一层保证的是<b>端口契约</b>：键可以安全地用作路径片段、大小为正、摘要存在。
 * 领域聚合仍会用自己的不变量再判一次（例如内容键的长度上限来自领域常量），
 * 因此端口返回值依然可能被领域拒绝 —— 服务端必须把这种情况当成内部错误处理，
 * 而不是当成调用方输入错误。</p>
 *
 * @param contentKey 不透明的内容键（由适配器生成，绝不来自原始文件名）
 * @param sizeBytes  实际写入的字节数，必须大于 0
 * @param sha256     实际写入内容的 SHA-256
 */
public record StoredContent(String contentKey, long sizeBytes, Sha256Digest sha256) {

    public StoredContent {
        if (contentKey == null || contentKey.isBlank()) {
            throw new IllegalArgumentException("contentKey 不能为空白");
        }
        if (!contentKey.equals(contentKey.strip())) {
            throw new IllegalArgumentException("contentKey 不能含首尾空白");
        }
        for (int index = 0; index < contentKey.length(); index++) {
            char character = contentKey.charAt(index);
            if (character == '/' || character == '\\' || character == '\0'
                    || Character.isISOControl(character)) {
                throw new IllegalArgumentException("contentKey 不能包含路径分隔符或控制字符");
            }
        }
        if (sizeBytes <= 0L) {
            throw new IllegalArgumentException("sizeBytes 必须大于 0");
        }
        Objects.requireNonNull(sha256, "sha256 不能为 null");
    }
}
