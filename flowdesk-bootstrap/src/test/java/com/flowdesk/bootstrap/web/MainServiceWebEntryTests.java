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
        ResponseEntity<String> knowledgeScript = this.client.getForEntity("/knowledge.js", String.class);
        ResponseEntity<String> style = this.client.getForEntity("/app.css", String.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(script.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(knowledgeScript.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(style.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void thePageOnlyUsesRelativePathsAndSafeEndpoints() {
        String page = bodyOf("/");
        String ticketScript = bodyOf("/app.js");
        String knowledgeScript = bodyOf("/knowledge.js");

        assertThat(page + "\n" + ticketScript + "\n" + knowledgeScript)
                .as("不写死主机名与端口：三个资源都只用相对路径")
                .doesNotContain("http://")
                .doesNotContain("https://");

        // FD-0023-D：知识文档区有自己的脚本，因此「哪些端点允许出现」必须按文件分别断言，
        // 否则「工单脚本不得碰知识接口」这条规则会被新页面悄悄放宽。
        assertThat(ticketScript)
                .as("工单脚本不得出现知识 / 索引 / 检索 / AI 接口 —— 否则加载页面就可能写数据或产生模型费用")
                .doesNotContain("/api/v1/knowledge")
                .doesNotContain("/api/v1/ai")
                .doesNotContain("/index")
                .doesNotContain("/search");
        assertThat(ticketScript)
                .as("工单脚本只碰工单接口与健康检查")
                .contains("/api/v1/tickets")
                .contains("/actuator/health");

        assertThat(knowledgeScript)
                .as("知识脚本只允许出现文档端点：不得调索引、检索或 AI 接口")
                .contains("/api/v1/knowledge/documents")
                .doesNotContain("/api/v1/ai")
                .doesNotContain("/incident-triage")
                .doesNotContain("/asset-diagnosis")
                .doesNotContain("/search")
                .doesNotContain("/index");
        assertThat(page)
                .as("页面本身仍然只引用自己同源的静态资源")
                .contains("/app.js")
                .contains("/knowledge.js");
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
                .as("脚本里只能有两处写请求：新建工单 + 状态变更（都在用户点击之后）；列表与详情都是只读 GET")
                .isEqualTo(2);

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

    /**
     * FD-0023-C-R1：状态变更的**目标隔离与版本漂移保护**必须在脚本里留下可核对的结构。
     *
     * <p>这里只断言「脚本确实带了这些守卫」，具体的渲染与请求计数由浏览器行为测试
     * （路由注入 + 可控延迟）锁定，见交付说明。</p>
     */
    @Test
    void theScriptIsolatesTheWriteTargetAndProtectsAgainstVersionDrift() {
        String script = bodyOf("/app.js");

        assertThat(script)
                .as("切详情要清空目标：必须有专门的清空函数，并在 openDetail 里立刻调用")
                .contains("function clearCurrentTarget()")
                .contains("clearCurrentTarget();");
        assertThat(script)
                .as("清空函数必须把工单与 ETag 一并置空（否则旧工单仍可被写入）")
                .contains("currentTicket = null;")
                .contains("currentETag = null;");
        assertThat(script)
                .as("写路径要有自己的目标令牌：A 的迟到响应不得覆盖 B 的详情")
                .contains("actionToken")
                .contains("token !== actionToken");
        assertThat(script)
                .as("预检必须校验返回的工单编号与请求一致")
                .contains("probe.body.id !== ticketId");
        assertThat(script)
                .as("预检失败与版本漂移都要明确写出「写请求未发送」")
                .contains("写请求未发送");
        assertThat(script)
                .as("版本漂移判定用的是「预检 ETag 与当前显示的 ETag 不同」，与状态无关")
                .contains("requestETag !== displayedETag");
        assertThat(script)
                .as("预检的 ETag 与响应体版本必须自洽")
                .contains("probe.eTag !== '\"' + probe.body.version + '\"'");
        assertThat(script)
                .as("成功判定必须要求版本递增且 ETag 与版本一致，否则只能说「可能已生效」")
                .contains("versionOfETag")
                .contains("isStrongETag(result.eTag) || result.eTag !== '\"' + result.body.version + '\"'");
        assertThat(script)
                .as("关闭是不可逆操作：一次预检 + 两次确认")
                .contains("requestCloseConfirmation")
                .contains("sendActionRequest");
    }

    @Test
    void theScriptLocksTheActionAreaAfterAnAbnormalResult() {
        String script = bodyOf("/app.js");

        assertThat(script)
                .as("必须有独立的锁定标志与专门的锁定函数（锁定的语义要和「清空目标」分开）")
                .contains("var actionsLocked = false;")
                .contains("function lockActions(message, ticketId)");
        assertThat(script)
                .as("锁定函数必须清空按钮、输入框与确认区，并让旧 ETag 失效")
                .contains("actionsLocked = true;")
                .contains("currentETag = null;");
        assertThat(script)
                .as("两条写路径入口都要受锁定标志约束：连点旧按钮也必须一个 POST 都不发")
                .contains("if (actionsLocked || !currentTicket || !isStrongETag(currentETag)) {")
                .contains("if (actionsLocked || acting || !currentTicket || !isStrongETag(currentETag)) {");
        assertThat(script)
                .as("切换详情时立刻上锁（clearCurrentTarget 承担默认锁定）")
                .contains("actionsLocked = true;");
        assertThat(script)
                .as("解锁只发生在「用户显式刷新并取得有效单条详情响应」之后：renderActions 是唯一解锁入口")
                .contains("actionsLocked = false;")
                .contains("function renderActions(ticket)")
                .contains("这是**唯一**的解锁入口");
        assertThat(script)
                .as("版本漂移与状态变化两条分支都必须调用锁定函数，而不是只提示刷新")
                .contains("为避免覆盖别人的修改，本次操作已取消，操作区已锁住");
        assertThat(script)
                .as("出异常的判据分支（预检失败、结果不确定、不完整成功）都要走锁定函数")
                .contains("操作区已锁住")
                .contains("本次不按「完整成功」处理，操作区已锁住");
        assertThat(script)
                .as("首次打开详情时强 ETag 不可用要退化为只读详情，不渲染操作区")
                .contains("renderDetail(result.body, result.eTag, true);")
                .contains("只读详情：");
    }

    /**
     * FD-0023-D：知识文档区的脚本必须自带「只读加载」「点击才写」「按版本构造 If-Match」三类守卫。
     *
     * <p>这里只断言脚本源码里存在这些结构；真实请求次数、If-Match 的实际取值、
     * 以及各类错误的渲染文案由真实 HTTP 用例与浏览器行为测试锁定。</p>
     */
    @Test
    void theKnowledgeScriptNeverWritesAtLoadTimeAndOnlyThroughTwoClicks() {
        String script = bodyOf("/knowledge.js");

        int posts = 0;
        Matcher matcher = POST_MARKER.matcher(script);
        while (matcher.find()) {
            posts++;
        }
        assertThat(posts)
                .as("知识脚本里只能有两处写请求：上传 + 解析；查询与解析前的核对都是只读 GET")
                .isEqualTo(2);

        assertThat(script)
                .as("加载路径不得直接发请求：所有网络调用都在事件处理函数 / 点击触发的函数里")
                .contains("addEventListener('submit'")
                .contains("addEventListener('click'");
        assertThat(script)
                .as("上传只能由表单提交触发")
                .contains("function submitUpload(event)");
        assertThat(script)
                .as("解析只能由按钮点击触发")
                .contains("parseButton.addEventListener('click', parseDocument)");
        assertThat(script)
                .as("本脚本不注册 DOMContentLoaded 期的网络调用")
                .doesNotContain("DOMContentLoaded', loadHealth")
                .doesNotContain("loadHealth(");
    }

    @Test
    void theKnowledgeScriptBuildsIfMatchFromTheVerifiedVersionAndNeverHardCodesZero() {
        String script = bodyOf("/knowledge.js");

        assertThat(script)
                .as("解析前必须先重新 GET 核对")
                .contains("正在重新核对文档版本");
        assertThat(script)
                .as("If-Match 必须由核对到的版本构造，格式为带双引号的十进制")
                .contains("versionTagOf(probe.body)")
                .contains("var requestETag = versionTagOf(probe.body);");
        assertThat(script)
                .as("绝不能写死 \"0\" 作为 If-Match")
                .doesNotContain("'If-Match': '\"0\"'")
                .doesNotContain("\"If-Match\": \"\\\"0\\\"\"");
        assertThat(script)
                .as("核对必须覆盖文档 ID、版本与状态三项")
                .contains("probe.body.version !== displayedVersion")
                .contains("probe.body.status !== displayedStatus")
                .contains("String(probe.body.id).toLowerCase() !== targetId.toLowerCase()");
        assertThat(script)
                .as("任何核对失败都必须明确写出「写请求未发送」")
                .contains("写请求未发送");
        assertThat(script)
                .as("只有 UPLOADED / PARSE_FAILED 允许解析")
                .contains("PARSABLE_STATUSES = ['UPLOADED', 'PARSE_FAILED']")
                .contains("PARSABLE_STATUSES.indexOf(probe.body.status) < 0");
    }

    @Test
    void theKnowledgeScriptTreatsParseSuccessAsAuthoritativeAndNeverAssumesPlusOne() {
        String script = bodyOf("/knowledge.js");

        assertThat(script)
                .as("成功响应的 ETag 必须与响应体版本自洽，否则不认这个成功")
                .contains("result.eTag !== versionTagOf(result.body)")
                .contains("响应体版本 ");
        assertThat(script)
                .as("成功响应的文档 ID 必须与请求目标一致")
                .contains("result.body.documentId");
        assertThat(script)
                .as("版本一律取服务端返回的权威值，脚本里不得出现 +1 / ++ 这类自增推断")
                .contains("version: result.body.version")
                .doesNotContain("version + 1")
                .doesNotContain("body.version++")
                .doesNotContain("++result.body.version");
        assertThat(script)
                .as("失败要用服务端返回的 failureCode，而不是自己猜")
                .contains("failureCode")
                .contains("labelOfFailure");
    }

    @Test
    void theKnowledgeScriptSeparatesTheFourErrorStatusesAndNeverRetriesAutomatically() {
        String script = bodyOf("/knowledge.js");

        assertThat(script)
                .as("412 / 409 / 400 / 404 必须分别提示")
                .contains("result.status === 412")
                .contains("result.status === 409")
                .contains("result.status === 400")
                .contains("result.status === 404");
        assertThat(script)
                .as("网络中断与 5xx 一律给出「结果待确认，请刷新」")
                .contains("结果待确认，请刷新")
                .contains("result.transportError")
                .contains("result.status >= 500");
        assertThat(script)
                .as("明确不自动重试：不得出现重试循环或重新调用写请求的兜底")
                .doesNotContain("setTimeout(parseDocument")
                .doesNotContain("setTimeout(submitUpload")
                .doesNotContain("retry(")
                .doesNotContain("while (");
        assertThat(script)
                .as("异常结果后解析区要锁定，且锁定后不再持有可写目标")
                .contains("function lockParse(message)")
                .contains("currentDocument = null;");
        assertThat(script)
                .as("锁定必须是独立状态：冲突后刷新只读元数据不得把按钮重新点亮")
                .contains("var parseLocked = false;")
                .contains("if (parseLocked) {")
                .contains("parseLocked = true;");
        assertThat(script)
                .as("用户显式「按 ID 查询」是唯一解锁入口，写路径入口也受锁约束")
                .contains("function unlockParseArea()")
                .contains("unlockParseArea();")
                .contains("if (parsing || parseLocked || !currentDocument) {");
    }

    @Test
    void theKnowledgeScriptRendersTextOnlyAndIsHonestAboutTheMissingListApi() {
        String script = bodyOf("/knowledge.js");
        String page = bodyOf("/");

        assertThat(script)
                .as("服务端字符串只能作为文本渲染")
                .doesNotContain("innerHTML")
                .doesNotContain("outerHTML")
                .doesNotContain("insertAdjacentHTML")
                .doesNotContain("document.write");
        assertThat(page)
                .as("页面必须如实标注「按 ID 查找」，并说明后端没有列表接口")
                .contains("按 ID 查找")
                .contains("没有</b>文档列表接口")
                .contains("不能</b>列出全部文档");
        assertThat(page)
                .as("页面要给出格式与体积提示，并声明服务端是最终校验者")
                .contains("knowledge-format-hint")
                .contains("knowledge-size-hint")
                .contains("服务端始终是最终校验者");
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
