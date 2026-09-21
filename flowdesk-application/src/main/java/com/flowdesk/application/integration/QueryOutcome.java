package com.flowdesk.application.integration;

/**
 * 查询结果的三种可能（FD-0016）。
 *
 * <p>刻意做成三态而不是「成功/失败」两态，也不用 {@code null} 表示没找到：
 * 「查到了」「查过了但没有」「这次没查成」需要三种不同的后续动作。</p>
 */
public enum QueryOutcome {

    /** 查到了数据。 */
    FOUND,

    /** 查询本身成功，但远端明确回答「没有这条数据」（远端会同时给出来源）。 */
    NOT_FOUND,

    /** 查询没有成功，原因见 {@link QueryFailure}。 */
    FAILED
}
