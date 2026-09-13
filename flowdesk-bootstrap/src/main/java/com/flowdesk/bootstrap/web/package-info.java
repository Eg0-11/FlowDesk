/**
 * FlowDesk HTTP 公共契约包：跨接口族共享的错误体构造与请求解析异常处理。
 *
 * <p>这里只放与业务无关的 HTTP 层关注点：稳定的业务错误码、
 * RFC 9457 ProblemDetail 构造，以及 JSON／参数绑定／Bean Validation 这三类
 * 「请求本身不合法」的统一处理入口。</p>
 *
 * <p>业务异常（工单、AI）各自在自己的包里处理，本包不感知任何业务语义。</p>
 */
package com.flowdesk.bootstrap.web;
