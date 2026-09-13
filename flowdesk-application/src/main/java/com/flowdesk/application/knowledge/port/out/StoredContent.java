package com.flowdesk.application.knowledge.port.out;

import com.flowdesk.domain.knowledge.Sha256Digest;

/**
 * 一次内容存储的结果。
 *
 * <p>三个值都由存储适配器<b>根据实际写入的字节</b>得出：键是它生成的不透明标识，
 * 大小与摘要是它边写边算出来的。因此上层不需要（也不应该）自己再算一遍摘要 ——
 * 那会变成「两次读取、两份真相」。</p>
 *
 * @param contentKey 不透明的内容键（由适配器生成，绝不来自原始文件名）
 * @param sizeBytes  实际写入的字节数，必须大于 0
 * @param sha256     实际写入内容的 SHA-256
 */
public record StoredContent(String contentKey, long sizeBytes, Sha256Digest sha256) {
}
