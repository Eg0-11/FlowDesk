package com.flowdesk.bootstrap.knowledge;

/**
 * 知识检索请求体（RAG 4/6）。
 *
 * <p>三个字段都声明为可空的对象类型，由<b>应用层</b>统一判定合法性与套用默认值：</p>
 * <ul>
 *   <li>{@code query} 缺失/空白/超长/含控制字符 → 400（固定 detail「检索请求不合法」）；</li>
 *   <li>{@code topK} / {@code minScore} 缺失 → 使用服务端配置的默认值。</li>
 * </ul>
 *
 * <p>刻意<b>不</b>在这里加 Bean Validation：HTTP 入口必须与直接调用 use case 的语义完全一致，
 * 而规范化顺序（NFC → strip → 判空 → code point 上限 → 控制字符）只有应用层能按同一顺序执行；
 * 在这里预先判长度会让「2000 个有效字符 + 首尾空白」被误拒，两条入口就此分叉。</p>
 *
 * @param query    用户问题
 * @param topK     期望返回的引用条数上限；缺省用服务端默认值
 * @param minScore 余弦相似度下限（含边界）；缺省用服务端默认值
 */
public record KnowledgeSearchRequest(String query, Integer topK, Double minScore) {
}
