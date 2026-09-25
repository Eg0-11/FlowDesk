package com.flowdesk.bootstrap.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 主服务同源网页入口的真实 HTTP 验证（FD-0023-A）。
 *
 * <p>这些用例走**真实 HTTP**（随机端口上的真实 Tomcat），验证三件事：</p>
 * <ol>
 *   <li>{@code GET /} 返回<b>可见页面</b>而不是 404（这是本次改动的核心验收点）；</li>
 *   <li>页面加载<b>不会</b>调用索引、检索或 AI 接口 —— 从页面与脚本的源码上就不存在这些请求，
 *       因此不可能产生模型费用；</li>
 *   <li>既有 API 行为<b>不变</b>：健康、工单列表、创建工单、以及 Basic 模式下 AI 端点仍然 404。</li>
 * </ol>
 *
 * <p>本类不调用任何付费模型：Basic 模式下 AI 端点本来就没有注册（404）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MainServiceWebEntryTests {

    private static final Pattern POST_MARKER = Pattern.compile("method: 'POST'");

    @Autowired
    private TestRestTemplate client;

    @Test
    void theRootPathServesAVisiblePageInsteadOf404() {
        ResponseEntity<String> response = this.client.getForEntity("/", String.class);

        assertThat(response.getStatusCode())
                .as("根路径必须是可见页面，而不是 404")
                .isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(response.getHeaders().getContentType()))
                .as("应当是 HTML")
                .contains("text/html");

        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body)
                .as("页面要有可见的标题与三个区块")
                .contains("<h1>FlowDesk 本机演示入口</h1>")
                .contains("首页")
                .contains("服务状态")
                .contains("工单列表");
    }

    @Test
    void theStaticAssetsOfTheEntryPageAreServed() {
        ResponseEntity<String> page = this.client.getForEntity("/index.html", String.class);
        ResponseEntity<String> script = this.client.getForEntity("/app.js", String.class);
        ResponseEntity<String> style = this.client.getForEntity("/app.css", String.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(script.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(style.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void thePageOnlyUsesRelativePathsAndSafeEndpoints() {
        String page = bodyOf("/");
        String script = bodyOf("/app.js");
        String combined = page + "\n" + script;

        assertThat(combined)
                .as("不写死主机名与端口：页面只用相对路径")
                .doesNotContain("http://")
                .doesNotContain("https://");
        assertThat(combined)
                .as("页面不得出现索引 / 检索 / AI 接口 —— 否则加载页面就可能写数据或产生模型费用")
                .doesNotContain("/api/v1/knowledge")
                .doesNotContain("/api/v1/ai")
                .doesNotContain("/index")
                .doesNotContain("/search");
        assertThat(combined)
                .as("工单列表入口与健康检查使用相对路径")
                .contains("/api/v1/tickets")
                .contains("/actuator/health");
    }

    @Test
    void theScriptWritesOnlyThroughTheSingleCreateRequestAndNeverAtLoadTime() {
        String script = bodyOf("/app.js");

        int posts = 0;
        Matcher matcher = POST_MARKER.matcher(script);
        while (matcher.find()) {
            posts++;
        }
        assertThat(posts)
                .as("脚本里只能有一处写请求：新建工单（POST）；列表与详情都是只读 GET")
                .isEqualTo(1);

        int domReady = script.indexOf("DOMContentLoaded");
        assertThat(domReady).as("脚本要有 DOMContentLoaded 处理器").isGreaterThan(0);
        String loadPath = script.substring(domReady);
        assertThat(loadPath)
                .as("加载路径只调健康检查，不直接发任何请求、更不发写请求；其余请求都在事件处理函数里")
                .doesNotContain("requestJson(")
                .doesNotContain("method: 'POST'")
                .contains("loadHealth();")
                .contains("addEventListener('submit'");
    }

    @Test
    void theScriptRendersEverythingAsTextInsteadOfHtml() {
        String script = bodyOf("/app.js");

        assertThat(script)
                .as("用户输入与服务端数据只能作为文本渲染：不得出现 innerHTML / outerHTML / insertAdjacentHTML")
                .doesNotContain("innerHTML")
                .doesNotContain("outerHTML")
                .doesNotContain("insertAdjacentHTML")
                .doesNotContain("document.write");
    }

    @Test
    void theScriptCarriesTheGuardsTheBehaviourIsVerifiedAgainstInTheBrowser() {
        String script = bodyOf("/app.js");

        assertThat(script)
                .as("翻页依据服务端返回的 page / hasNext / hasPrevious")
                .contains("hasNext")
                .contains("hasPrevious")
                .contains("page=");
        assertThat(script)
                .as("提交与列表加载都要有在飞标记（防重复提交 / 防止翻页期间重复导航）")
                .contains("creating")
                .contains("listLoading");
        assertThat(script)
                .as("过时响应要靠自增令牌丢弃，不能覆盖最新页面")
                .contains("listToken")
                .contains("detailToken");
        assertThat(script)
                .as("格式异常要有明确文案")
                .contains("响应格式异常");

        // 行为证据不在这里：畸形/空/缺字段/延迟响应的实际渲染结果由浏览器验收脚本
        // （用 Playwright 路由注入可控响应）验证，见交付说明。
    }

    @Test
    void theHealthEndpointIsUnchanged() {
        ResponseEntity<String> response = this.client.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void theTicketListApiIsUnchanged() {
        ResponseEntity<String> response =
                this.client.getForEntity("/api/v1/tickets?size=5", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(response.getHeaders().getContentType()))
                .contains(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getBody())
                .as("分页响应形状不变")
                .contains("\"items\"")
                .contains("\"totalElements\"");
    }

    @Test
    void creatingATicketIsUnchanged() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String payload = "{\"title\":\"网页入口验证用工单\",\"description\":\"FD-0023-A 的 API 未变更验证\","
                + "\"category\":\"OTHER\",\"priority\":\"P3\",\"requesterId\":\"alice\"}";

        ResponseEntity<String> response = this.client.exchange(
                "/api/v1/tickets", HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);

        assertThat(response.getStatusCode())
                .as("创建工单仍然是 201（本任务没有改 API 行为）")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getBody()).contains("\"id\"");
    }

    @Test
    void theAiEndpointsAreStillAbsentInBasicMode() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String payload = "{\"assetId\":\"AST-900001\",\"question\":\"不会被调用\",\"topK\":1,\"minScore\":0.0}";

        ResponseEntity<String> response = this.client.exchange(
                "/api/v1/ai/incident-triage", HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);

        assertThat(response.getStatusCode())
                .as("Basic 模式下 AI 端点没有注册：仍然 404（页面也从不请求它们）")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownPathsStillReturn404() {
        List<String> unknownPaths = List.of("/api/v1/does-not-exist", "/favicon.ico-not-used");

        for (String path : unknownPaths) {
            ResponseEntity<String> response = this.client.getForEntity(path, String.class);
            assertThat(response.getStatusCode())
                    .as("path=%s", path)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    /**
     * @param path 相对路径
     * @return 响应体
     */
    private String bodyOf(String path) {
        ResponseEntity<String> response = this.client.getForEntity(path, String.class);
        assertThat(response.getStatusCode()).as("path=%s", path).isEqualTo(HttpStatus.OK);

        String body = response.getBody();
        assertThat(body).as("path=%s 的响应体不应为空", path).isNotNull();
        return body;
    }
}
