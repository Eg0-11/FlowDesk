package com.flowdesk.bootstrap.ai;

import jakarta.validation.constraints.NotBlank;

/**
 * 普通聊天请求体。
 *
 * <p>这里只校验「非空」这一层。<b>长度校验刻意不放在这里</b>：
 * HTTP 入口必须与直接调用 use case 的语义完全一致，而规范化规则是
 * 「先 {@code strip()}，再判空，最后判长度」，只有应用层能按这个顺序判断。
 * 若在此处加 {@code @Size(max = 4000)}，则「4000 个有效字符 + 首尾空白」会在 HTTP 层被误拒，
 * 而直接调用 use case 却会通过，两条入口就此分叉。</p>
 *
 * @param message 用户消息
 */
public record ChatRequest(

        @NotBlank(message = "不能为空")
        String message) {
}
