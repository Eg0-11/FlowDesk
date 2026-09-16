package com.flowdesk.mcp.asset.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 拒绝带 {@code Origin} 的 MCP 请求（FD-0014）。
 *
 * <p>本服务面向<b>本机 MCP 客户端</b>（IDE、CLI、桌面 Agent），不面向浏览器：
 * 请求只要带 {@code Origin} 头就说明它来自某个网页上下文（或有人故意伪造），
 * 一律返回 <b>403</b>，请求不会进入 MCP 端点。</p>
 *
 * <h2>为什么不是 CORS</h2>
 * <p>用宽松的 CORS 配置「允许浏览器访问」等于把这个只读但可枚举的资产接口暴露给任何网页；
 * 而完全不放 CORS 头又会让浏览器请求以「跨源被拒」这种<b>语义模糊</b>的方式失败。
 * 这里选择第三条路：显式 403 + 明确文案，让「浏览器不能调用本服务」成为一条可断言、可解释的契约。</p>
 *
 * <p>响应体是固定的 problem 形状，不含请求内容、Origin 值或任何内部信息。</p>
 */
public class McpOriginRejectionFilter extends OncePerRequestFilter {

    /** 固定响应体（不含 Origin 值、路径与其它请求内容）。 */
    static final String PROBLEM_BODY = "{\"type\":\"urn:flowdesk:problem:origin-not-allowed\","
            + "\"title\":\"跨源请求被拒绝\",\"status\":403,"
            + "\"detail\":\"本服务不支持浏览器跨源调用，请使用不带 Origin 的 MCP 客户端\","
            + "\"code\":\"ORIGIN_NOT_ALLOWED\"}";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (request.getHeader(HttpHeaders.ORIGIN) != null) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(PROBLEM_BODY);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
