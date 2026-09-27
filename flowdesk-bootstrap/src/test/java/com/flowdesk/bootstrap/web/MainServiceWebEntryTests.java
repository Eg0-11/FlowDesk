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
        ResponseEntity<String> searchScript = this.client.getForEntity("/search.js", String.class);
        ResponseEntity<String> style = this.client.getForEntity("/app.css", String.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(script.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(knowledgeScript.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(searchScript.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(style.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void thePageOnlyUsesRelativePathsAndSafeEndpoints() {
        String page = bodyOf("/");
        String ticketScript = bodyOf("/app.js");
        String knowledgeScript = bodyOf("/knowledge.js");
        String searchScript = bodyOf("/search.js");

        assertThat(page + "\n" + ticketScript + "\n" + knowledgeScript + "\n" + searchScript)
                .as("不写死主机名与端口：四个资源都只用相对路径")
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
                .as("知识脚本只允许文档端点：不得调检索或 AI 接口；索引端点是 FD-0023-E 显式放开的唯一新增")
                .contains("/api/v1/knowledge/documents")
                .contains("+ '/index'")
                .doesNotContain("/api/v1/ai")
                .doesNotContain("/incident-triage")
                .doesNotContain("/asset-diagnosis")
                .doesNotContain("/search");

        // FD-0023-F：检索有自己的脚本，只允许检索端点；文档、AI 接口与浏览器持久存储都不得出现。
        assertThat(searchScript)
                .as("检索脚本只允许检索端点：不得调文档或 AI 接口，问题不进浏览器持久存储")
                .contains("/api/v1/knowledge/search")
                .doesNotContain("/api/v1/knowledge/documents")
                .doesNotContain("/api/v1/ai")
                .doesNotContain("/incident-triage")
                .doesNotContain("/asset-diagnosis")
                .doesNotContain("localStorage")
                .doesNotContain("sessionStorage");

        assertThat(page)
                .as("页面本身仍然只引用自己同源的静态资源")
                .contains("/app.js")
                .contains("/knowledge.js")
                .contains("/search.js");
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
    void theKnowledgeScriptNeverWritesAtLoadTimeAndOnlyThroughExplicitClicks() {
        String script = bodyOf("/knowledge.js");

        int posts = 0;
        Matcher matcher = POST_MARKER.matcher(script);
        while (matcher.find()) {
            posts++;
        }
        assertThat(posts)
                .as("知识脚本里只能有三处写请求：上传 + 解析 + 索引（FD-0023-E）；查询与核对都是只读 GET")
                .isEqualTo(3);

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

    /**
     * FD-0023-D-R1：响应体读取中断必须被结构化处理，而不是变成未处理的 Promise 拒绝。
     *
     * <p>「拿到响应头」与「读到响应体」是两件事：连接在响应头之后被重置时，
     * {@code fetch} 会成功、{@code response.text()} 会拒绝。若脚本不就地接住它，
     * 整个 Promise 会拒绝，调用方没有 {@code .catch} 就既留下未处理拒绝、
     * 又让「进行中」标记永久为真 —— 按钮再也点不动。这里断言脚本从结构上排除了这两种后果。</p>
     */
    @Test
    void theKnowledgeScriptNeverLeavesAnUnhandledRejectionOrAStuckBusyState() {
        String script = bodyOf("/knowledge.js");

        assertThat(script)
                .as("请求包装必须自成一个永不拒绝的结构：body 读取失败要表达成 bodyReadFailed 标记")
                .contains("bodyReadFailed")
                .contains("bodyReadFailed: true");
        assertThat(script)
                .as("只有 response.text() 就地被接住，才不会让整个 Promise 拒绝")
                .contains("var readBody = response.text().then(function (text) {")
                .contains("return { text: '', failed: true };");
        assertThat(script)
                .as("请求包装标注了自己的契约：永不拒绝")
                .contains("永不拒绝");

        assertThat(script)
                .as("写/核对路径都必须挂上收尾兜底：上传、解析核对、解析写、索引核对、索引写各一次 guard，查询路径用自己的失败回调")
                .contains("function guard(promise, onFinish, onCrash)")
                .contains("guard(requestJson(DOCUMENTS_PATH, {")
                .contains("guard(requestJson(DOCUMENTS_PATH + '/' + encodeURIComponent(targetId), {")
                .contains("guard(requestJson(DOCUMENTS_PATH + '/' + encodeURIComponent(targetId) + '/parse', {")
                .contains("guard(requestJson(DOCUMENTS_PATH + '/' + encodeURIComponent(targetId) + '/index', {")
                .contains("查询在页面内异常终止，未能取得文档状态。请稍后重试。");

        // guard 必须在使用者之前复位 busy：断言 onFinish 被调用一次且与 onCrash 并存。
        int guardFn = script.indexOf("function guard(promise, onFinish, onCrash)");
        assertThat(guardFn).as("guard 必须存在").isGreaterThan(0);
        String guardBody = script.substring(guardFn, script.indexOf("// ---- 静态提示文本", guardFn));
        assertThat(guardBody)
                .as("guard 的两条出口都必须调用 onFinish —— 否则 busy 仍会永久为真")
                .contains("onFinish();");
        assertThat((guardBody.length() - guardBody.replace("onFinish();", "").length()) / "onFinish();".length())
                .as("成功与失败两条出口都要收尾")
                .isEqualTo(2);

        assertThat(script)
                .as("写请求（上传）读取失败必须按「结果待确认」处理，且不能把响应体当成上传成功")
                .contains("但读取响应体失败：服务端可能已经创建了文档");
        assertThat(script)
                .as("解析写入读取失败必须锁住写路径，并且一个字都不提「重试」的好处")
                .contains("但读取响应体失败，无法确认文档是否已解析成功或已进入失败状态");
        assertThat(script)
                .as("解析前重查读取失败同样必须停手：写请求未发送")
                .contains("但读取响应体失败，无法取得当前版本，因此没有发送解析请求");
        assertThat(script)
                .as("查询读取失败也要收尾，不能把查询按钮永久禁用")
                .contains("但读取响应体失败，无法确认该文档的当前状态。请稍后重试。");
    }

    /**
     * FD-0023-D-R1：422 / 413 的响应体是 problem+json，必须按错误体渲染，
     * 不得当成 {@code ParsedDocumentResponse} 渲染。
     *
     * <p>两类响应体都有 {@code title}/{@code status} 字段，但语义完全不同：
     * 按解析结果渲染会把「文档无法解析」当成文档标题，并凭空显示出空的
     * 「切片数」「解析完成时间」。</p>
     */
    @Test
    void theKnowledgeScriptRendersParseFailuresAsProblemsInsteadOfParsedResults() {
        String script = bodyOf("/knowledge.js");

        assertThat(script)
                .as("必须有一个专门的 problem 渲染函数，并且它的入参是 status + 错误体")
                .contains("function renderParseProblem(status, problemBody)");
        assertThat(script)
                .as("422 / 413 分支必须走 problem 渲染，而不是 renderParseResult")
                .contains("renderParseProblem(result.status, result.body)");
        assertThat(script)
                .as("problem 渲染要显式声明「这是错误响应，不是解析结果」")
                .contains("这是错误响应，不是解析结果");
        assertThat(script)
                .as("problem 渲染要展示固定错误标题/说明与稳定 failureCode")
                .contains("appendRow(box, '错误标题'")
                .contains("appendRow(box, '错误说明'")
                .contains("appendRow(box, '失败代码'");
        assertThat(script)
                .as("problem 渲染必须点明它不含切片数与解析完成时间，避免读者误以为是快照")
                .contains("不含切片数与解析完成时间");

        // 断言 422 / 413 分支附近没有 renderParseResult，防止以后被改回去。
        int branch = script.indexOf("if (result.status === 422 || result.status === 413) {");
        assertThat(branch).as("422 / 413 分支必须存在").isGreaterThan(0);
        String branchBody = script.substring(branch, script.indexOf("if (result.status >= 500) {", branch));
        assertThat(branchBody)
                .as("422 / 413 分支里绝不能出现 renderParseResult —— 那是给 200 成功体用的")
                .doesNotContain("renderParseResult(");
    }

    /**
     * FD-0023-D-R1：冲突后的只读刷新不得重建可写目标。
     *
     * <p>刷新拿到的版本可能又被第三方改过；若把它写回 {@code currentDocument}，
     * 就等于用「刚刷新到的版本」重新武装了写路径。刷新只应更新展示。</p>
     */
    @Test
    void theKnowledgeScriptKeepsReadOnlyRefreshFromReArmingTheWritePath() {
        String script = bodyOf("/knowledge.js");

        assertThat(script)
                .as("刷新必须走专门的只读渲染函数")
                .contains("function renderDocumentFromRefresh(documentBody)")
                .contains("renderDocumentFromRefresh(result.body)");
        assertThat(script)
                .as("只读刷新函数必须明确不写可写目标")
                .contains("刻意不写 {@code currentDocument}")
                .contains("写目标保持为空");

        // 刷新函数体内不得出现 currentDocument 赋值。
        int fn = script.indexOf("function renderDocumentFromRefresh(documentBody)");
        int fnEnd = script.indexOf("// ---- 解析区", fn);
        assertThat(fn).as("刷新函数必须存在").isGreaterThan(0);
        assertThat(fnEnd).as("刷新函数应当有明确的结束边界").isGreaterThan(fn);
        String fnBody = script.substring(fn, fnEnd);
        assertThat(fnBody)
                .as("只读刷新绝不能重建可写目标")
                .doesNotContain("currentDocument =");

        // refreshAfterConflict 的 then 回调里也不得有 currentDocument 赋值。
        int refresh = script.indexOf("function refreshAfterConflict(targetId)");
        int refreshEnd = script.indexOf("// ---- 事件绑定", refresh);
        assertThat(refresh).as("refreshAfterConflict 必须存在").isGreaterThan(0);
        assertThat(refreshEnd).isGreaterThan(refresh);
        String refreshBody = script.substring(refresh, refreshEnd);
        assertThat(refreshBody)
                .as("冲突后刷新不得写 currentDocument：锁住 = 没有可写目标")
                .doesNotContain("currentDocument =");
        assertThat(refreshBody)
                .as("冲突后刷新也要认读 body 失败，不能把它当成有效快照")
                .contains("result.bodyReadFailed");
    }

    /**
     * FD-0023-D-R2：解析失败后的锁定窗口不得留下可写目标。
     *
     * <p>422 / 413 之后页面会异步刷新元数据；如果锁定发生在刷新回调里，
     * 那么「刷新在途」这段时间解析按钮仍可点、可写目标仍在，用户连点就会再发写请求。
     * 因此必须在**发起刷新之前**同步收口，并且刷新成功或失败都不得自动解锁。</p>
     */
    @Test
    void theKnowledgeScriptLocksTheParseWritePathBeforeTheConflictRefresh() {
        String script = bodyOf("/knowledge.js");

        int branch = script.indexOf("if (result.status === 422 || result.status === 413) {");
        assertThat(branch).as("422 / 413 分支必须存在").isGreaterThan(0);
        String branchBody = script.substring(branch, script.indexOf("if (result.status >= 500) {", branch));

        int lockAt = branchBody.indexOf("lockParse(");
        int refreshAt = branchBody.indexOf("refreshAfterConflict(");
        assertThat(lockAt)
                .as("422 / 413 分支必须在发起异步刷新之前就锁住写路径")
                .isGreaterThan(0);
        assertThat(refreshAt)
                .as("422 / 413 分支仍然要刷新展示")
                .isGreaterThan(0);
        assertThat(lockAt)
                .as("锁定必须早于刷新：刷新在途时按钮已禁用、可写目标已为空")
                .isLessThan(refreshAt);

        // 锁定必须先于刷新 —— 上面的顺序断言就是这条不变量的唯一保证手段，
        // 因此这里同时确认分支里没有再往刷新回调里塞解锁动作。
        assertThat(branchBody)
                .as("解析失败分支不得出现解锁动作")
                .doesNotContain("unlockParseArea(");

        // lockParse 自身必须是同步收口的：清空可写目标 + 禁用按钮。
        int lockFn = script.indexOf("function lockParse(message)");
        int lockFnEnd = script.indexOf("function parseDocument()", lockFn);
        assertThat(lockFn).as("lockParse 必须存在").isGreaterThan(0);
        assertThat(lockFnEnd).isGreaterThan(lockFn);
        String lockBody = script.substring(lockFn, lockFnEnd);
        assertThat(lockBody)
                .as("锁定时必须清空可写目标")
                .contains("currentDocument = null;");
        assertThat(lockBody)
                .as("锁定时必须同步禁用解析按钮")
                .contains("setDisabled(element('knowledge-parse'), true);");
        assertThat(lockBody)
                .as("锁定后不得再解析")
                .contains("parseLocked = true;");

        // 刷新成功后也仍然锁定：只读渲染函数不解锁，且刷新函数不触碰锁定标志。
        int refresh = script.indexOf("function refreshAfterConflict(targetId)");
        int refreshEnd = script.indexOf("// ---- 事件绑定", refresh);
        assertThat(refresh).as("refreshAfterConflict 必须存在").isGreaterThan(0);
        assertThat(refreshEnd).isGreaterThan(refresh);
        String refreshBody = script.substring(refresh, refreshEnd);
        assertThat(refreshBody)
                .as("刷新成功或失败都不得自动解锁")
                .doesNotContain("parseLocked =")
                .doesNotContain("unlockParseArea(")
                .doesNotContain("currentDocument =");

        int refreshFn = script.indexOf("function renderDocumentFromRefresh(documentBody)");
        int refreshFnEnd = script.indexOf("// ---- 解析区", refreshFn);
        assertThat(refreshFn).as("只读刷新渲染函数必须存在").isGreaterThan(0);
        assertThat(refreshFnEnd).isGreaterThan(refreshFn);
        assertThat(script.substring(refreshFn, refreshFnEnd))
                .as("只读刷新渲染函数不得解锁")
                .doesNotContain("parseLocked =")
                .doesNotContain("unlockParseArea(");

        // 刷新必须作废判断，而且只能占用预留号，绝不能推进 lookupToken ——
        // 否则用户在刷新在途时发起的新查询会被旧刷新误当作「已过期」。
        assertThat(refreshBody)
                .as("刷新必须做作废判断")
                .contains("if (token <= lookupToken)")
                .as("刷新不得递增 lookupToken（那会作废用户刚发起的新查询）")
                .doesNotContain("lookupToken += 1;");
        assertThat(script)
                .as("预留号必须存在，供刷新与下一次查询比较")
                .contains("var lookupReserved = 0;");

        // 唯一解锁入口仍然是用户显式查询。
        int lookup = script.indexOf("function lookupDocument()");
        int lookupEnd = script.indexOf("// ---- 解析", lookup);
        assertThat(lookup).as("lookupDocument 必须存在").isGreaterThan(0);
        assertThat(lookupEnd).isGreaterThan(lookup);
        String lookupBody = script.substring(lookup, lookupEnd);
        assertThat(lookupBody)
                .as("解锁只能发生在用户显式按 ID 查询时")
                .contains("unlockParseArea()");
        assertThat(lookupBody)
                .as("显式查询必须先解锁再看这次查询的结果")
                .contains("用户显式发起查询 = 唯一解锁入口");
    }

    /**
     * FD-0023-E / FD-0023-E-R1：手动索引必须在用户显式确认费用后、经版本核对才能发生。
     *
     * <p>页面加载、查询、解析成功都<b>不会</b>自动索引；按钮只在确认费用后才会启用。
     * 费用确认是一次性授权（FD-0023-E-R1）：索引发起即消耗（勾选框同步清空），
     * 重新按 ID 查询或切换文档必须重新确认；在途请求不读确认状态，不受勾选变化影响。</p>
     *
     * <p>503 只有在 problem.code 明确为 KNOWLEDGE_EMBEDDING_DISABLED（Basic 模式，
     * Embedding 未启用）时结果才是确定的（请求没执行、文档未动），不宣称「结果未知」；
     * 其余 503（响应体非 JSON / 缺错误码 / 错误码不同）必须按「结果待确认」锁定。</p>
     */
    @Test
    void theKnowledgeScriptIndexesOnlyAfterExplicitCostConfirmation() {
        String script = bodyOf("/knowledge.js");
        String page = bodyOf("/");

        // 页面必须明确费用提示与确认闸门，并写明不会自动索引。
        assertThat(page)
                .as("页面必须明确提示索引可能产生 DashScope Embedding 费用")
                .contains("索引可能调用 DashScope Embedding 并产生费用")
                .as("页面必须写明不会自动索引")
                .contains("绝不会自动索引")
                .contains("knowledge-index-confirm")
                .contains("knowledge-index-note")
                .contains("knowledge-index-result")
                .contains("PARSED</code> / <code>INDEX_FAILED")
                .contains("最多发送一次");

        // 结构：索引流程与解析流程同一套不变量。
        assertThat(script)
                .as("只有 PARSED / INDEX_FAILED 允许索引")
                .contains("var INDEXABLE_STATUSES = ['PARSED', 'INDEX_FAILED'];")
                .contains("INDEXABLE_STATUSES.indexOf(probe.body.status) < 0");
        assertThat(script)
                .as("索引写请求必须由核对到的版本构造 If-Match，且 URL 由路径拼接而来")
                .contains("guard(requestJson(DOCUMENTS_PATH + '/' + encodeURIComponent(targetId) + '/index', {")
                .contains("'If-Match': requestETag");
        assertThat(script)
                .as("索引前必须有完整的重新核对（ID / 版本 / 状态），任一不符都不发写请求")
                .contains("String(probe.body.id).toLowerCase() !== targetId.toLowerCase()")
                .contains("probe.body.version !== displayedVersion")
                .contains("probe.body.status !== displayedStatus")
                .contains("写请求未发送");
        assertThat(script)
                .as("索引成功也必须自洽：状态是 INDEXED 且 ETag 与响应体版本一致，否则锁定")
                .contains("result.body.status !== 'INDEXED'")
                .contains("索引返回 200，但响应头 ETag 与响应体版本不一致，响应不自洽。");
        assertThat(script)
                .as("400 / 404 / 409 / 412 / 428 / 503 必须分别提示；503 必须以 problem.code 门控（FD-0023-E-R1）")
                .contains("result.status === 503 && problemCode(result.body) === 'KNOWLEDGE_EMBEDDING_DISABLED'")
                .contains("Embedding 服务未启用（Basic 模式）")
                .contains("result.status === 412")
                .contains("result.status === 409")
                .contains("result.status === 400")
                .contains("result.status === 404")
                .contains("result.status === 428");
        assertThat(script)
                .as("索引失败必须锁定索引区：清空可写目标并置锁定标志")
                .contains("function lockIndex(message, outcomeUncertain)")
                .contains("function indexDocument()")
                .contains("function renderIndexArea(documentBody)")
                .contains("function renderIndexResult(indexed)")
                .contains("function clearIndexResult()");
        int lockFn = script.indexOf("function lockIndex(message, outcomeUncertain)");
        int lockFnEnd = script.indexOf("function indexDocument()", lockFn);
        assertThat(lockFn).as("lockIndex 必须存在").isGreaterThan(0);
        assertThat(lockFnEnd).isGreaterThan(lockFn);
        String lockBody = script.substring(lockFn, lockFnEnd);
        assertThat(lockBody)
                .as("锁定时必须清空可写目标并禁用索引按钮")
                .contains("indexLocked = true;")
                .contains("currentDocument = null;")
                .contains("setDisabled(element('knowledge-index'), true);");

        // FD-0023-E 浏览器验收抓到并已修复的缺陷：预检 GET 的收尾回调会把忙标志复位，
        // 因此发送写请求的入口必须重新置位，否则 POST 在途期间连点能发出重复写请求。
        int sendFn = script.indexOf("function sendIndexRequest(targetId, displayedStatus, requestETag)");
        assertThat(sendFn).as("sendIndexRequest 必须存在").isGreaterThan(0);
        int sendFnEnd = script.indexOf("function handleIndexResult(", sendFn);
        assertThat(sendFnEnd).as("handleIndexResult 必须紧跟 sendIndexRequest").isGreaterThan(sendFn);
        assertThat(script.substring(sendFn, sendFnEnd))
                .as("POST 在途期间忙标志必须为真：sendIndexRequest 入口要重新置位忙标志")
                .contains("setIndexBusy(true);");

        // 网络中断 / 读体失败 / 5xx / 成功响应不自洽的提示必须包含任务规定的固定短语。
        assertThat(script)
                .as("不确定结果的提示必须包含「结果待确认，请重新查询」")
                .contains("结果待确认，请重新查询");

        // 唯一解锁入口必须同时解锁解析区与索引区。
        int unlockFn = script.indexOf("function unlockParseArea()");
        int unlockFnEnd = script.indexOf("function appendRow(", unlockFn);
        assertThat(script.substring(unlockFn, unlockFnEnd))
                .as("显式查询必须同时解锁两个写区")
                .contains("parseLocked = false;")
                .contains("indexLocked = false;");

        // 只读刷新不得给索引按钮可写语义。
        int refreshFn = script.indexOf("function renderDocumentFromRefresh(documentBody)");
        int refreshFnEnd = script.indexOf("// ---- 解析区", refreshFn);
        assertThat(script.substring(refreshFn, refreshFnEnd))
                .as("只读刷新必须禁用索引按钮，且不得重建可写目标")
                .contains("setDisabled(element('knowledge-index'), true);")
                .doesNotContain("currentDocument =");

        // 确认闸门：未确认时 indexDocument 必须拒绝执行；按钮与确认框都要绑定事件。
        // FD-0023-E-R1：确认以授权标志（indexArmed）为准，勾选/取消经唯一写入口落标志。
        assertThat(script)
                .as("indexDocument 必须先检查费用确认")
                .contains("if (!isIndexConfirmed()) {")
                .contains("indexButton.addEventListener('click', indexDocument)")
                .contains("indexConfirm.addEventListener('change', function () {")
                .contains("setIndexConfirmation(indexConfirm.checked);");

        // FD-0023-E-R1：费用确认是一次性授权，授权标志是唯一事实来源。
        assertThat(script)
                .as("确认必须以授权标志承载，勾选框只经唯一写入口同步")
                .contains("var indexArmed = false;")
                .contains("function setIndexConfirmation(confirmed)");
        int confirmedFn = script.indexOf("function isIndexConfirmed()");
        int confirmedFnEnd = script.indexOf("function setIndexConfirmation(", confirmedFn);
        assertThat(confirmedFn).as("isIndexConfirmed 必须存在").isGreaterThan(0);
        assertThat(confirmedFnEnd).isGreaterThan(confirmedFn);
        assertThat(script.substring(confirmedFn, confirmedFnEnd))
                .as("isIndexConfirmed 必须读授权标志而不是勾选框（发起即消耗不依赖 DOM 同步时序）")
                .contains("return indexArmed;")
                .doesNotContain("box.checked");

        // 发起即消耗：indexDocument 通过守卫后、发预检 GET 前必须消耗确认（并清勾选框）。
        int indexFn = script.indexOf("function indexDocument()");
        int indexFnEnd = script.indexOf("function sendIndexRequest(", indexFn);
        assertThat(indexFn).as("indexDocument 必须存在").isGreaterThan(0);
        assertThat(indexFnEnd).isGreaterThan(indexFn);
        String indexBody = script.substring(indexFn, indexFnEnd);
        assertThat(indexBody)
                .as("发起索引必须消耗一次性费用确认")
                .contains("setIndexConfirmation(false);");
        assertThat(indexBody.indexOf("if (!isIndexConfirmed()) {"))
                .as("消耗必须发生在确认守卫之后（先核对、再消耗）")
                .isLessThan(indexBody.indexOf("setIndexConfirmation(false);"));

        // 重新查询或切换文档必须重新确认：lookupDocument 里清掉授权与勾选框。
        int lookupFn = script.indexOf("function lookupDocument()");
        int lookupFnEnd = script.indexOf("// ---- 解析", lookupFn);
        assertThat(lookupFn).as("lookupDocument 必须存在").isGreaterThan(0);
        assertThat(lookupFnEnd).isGreaterThan(lookupFn);
        assertThat(script.substring(lookupFn, lookupFnEnd))
                .as("重新按 ID 查询必须清掉上一次的费用确认授权")
                .contains("setIndexConfirmation(false);");

        // 绝不自动索引：indexDocument 只能有一个定义；标识符引用总数必须是 2
        // （定义 1 次 + 按钮绑定以函数引用形式出现 1 次）。绑定是 addEventListener
        // 传引用而不是调用，因此若任何加载/查询/解析路径触发索引，总数会大于 2。
        int definitionCount = script.split("function indexDocument\\(", -1).length - 1;
        assertThat(definitionCount).as("indexDocument 只能定义一次").isEqualTo(1);
        int referenceCount = script.split("indexDocument", -1).length - 1;
        assertThat(referenceCount)
                .as("indexDocument 只允许出现在定义与按钮点击绑定各一次 —— 加载、查询、解析路径都不得触发索引")
                .isEqualTo(2);
    }

    /**
     * FD-0023-F：知识检索必须在用户显式确认费用后才能发生，且请求只走 JSON body。
     *
     * <p>页面加载、上传、解析或索引成功都<b>不会</b>自动检索；每次检索都要单独确认，
     * 提交即消耗确认（FD-0023-E-R1 同款不变量）；topK / minScore 留空时不下发字段，
     * 由服务端套用默认值。错误分类：400 确定拒绝（校验先于向量调用）、503 以
     * {@code KNOWLEDGE_EMBEDDING_DISABLED} 门控、502 / 其它 5xx / 网络中断 / 读体失败
     * 提示「可能已产生费用、结果未取得、不要盲目重试」。200 + 空 citations 是正常
     * 「无命中」；FD-0023-F-R1：200 响应在渲染前校验实际展示字段的类型与取值
     * （生效参数合法、rankingMode 只能是两种既有模式、引用按返回顺序对应连续 K1… 与
     * rank=1…、分数必须有限数字、重排字段与模式配对），任何错配都按「结果不完整」
     * 处理，不渲染为成功、不本地重排或猜测修正。渲染只走 textContent / replaceChildren。</p>
     */
    @Test
    void theSearchScriptSearchesOnlyAfterExplicitCostConfirmation() {
        String script = bodyOf("/search.js");
        String page = bodyOf("/");

        // 页面必须明确费用提示（Query Embedding + 重排）与一次性确认语义。
        assertThat(page)
                .as("页面必须明确提示检索可能产生 Query Embedding 与重排费用，且绝不自动检索")
                .contains("检索可能调用 Query Embedding 并产生费用")
                .contains("开启重排时还可能产生重排费用")
                .contains("绝不会自动检索")
                .contains("knowledge-search-confirm")
                .contains("knowledge-search-submit")
                .contains("knowledge-search-result");

        // 确认以授权标志承载；isSearchConfirmed 读标志而不是勾选框（提交即消耗不依赖 DOM 时序）。
        assertThat(script)
                .as("确认以授权标志承载，勾选框只经唯一写入口同步")
                .contains("var searchArmed = false;")
                .contains("function setSearchConfirmation(confirmed)");
        int confirmedFn = script.indexOf("function isSearchConfirmed()");
        int confirmedFnEnd = script.indexOf("function setSearchConfirmation(", confirmedFn);
        assertThat(confirmedFn).as("isSearchConfirmed 必须存在").isGreaterThan(0);
        assertThat(confirmedFnEnd).isGreaterThan(confirmedFn);
        assertThat(script.substring(confirmedFn, confirmedFnEnd))
                .as("isSearchConfirmed 必须读授权标志而不是勾选框")
                .contains("return searchArmed;")
                .doesNotContain("box.checked");

        // 提交流程：先输入校验、再确认守卫、后消耗确认，最后发出唯一的 POST。
        int submitFn = script.indexOf("function submitSearch(event)");
        int bindFn = script.indexOf("function bindSearch()", submitFn);
        assertThat(submitFn).as("submitSearch 必须存在").isGreaterThan(0);
        assertThat(bindFn).isGreaterThan(submitFn);
        String submitBody = script.substring(submitFn, bindFn);
        assertThat(submitBody)
                .as("提交必须先校验、后消耗确认，再发唯一的写请求")
                .contains("if (searching) {")
                .contains("if (!isSearchConfirmed()) {")
                .contains("setSearchConfirmation(false);")
                .contains("method: 'POST'")
                .contains("'Content-Type': 'application/json'");
        assertThat(submitBody.indexOf("if (!isSearchConfirmed()) {"))
                .as("消耗必须发生在确认守卫之后（先核对、再消耗）")
                .isLessThan(submitBody.indexOf("setSearchConfirmation(false);"));

        // 请求只走 JSON body：topK / minScore 留空时不下发字段（交给服务端默认值）。
        assertThat(script)
                .as("检索请求必须是 JSON body 的 POST，端点由常量承载")
                .contains("guard(requestJson(SEARCH_PATH, {")
                .contains("if (topK !== undefined) {")
                .contains("if (minScore !== undefined) {");

        // 错误分类：400 确定拒绝；503 以 problem.code 门控；502/5xx/网络/读体提示结果未取得。
        assertThat(script)
                .as("错误分类必须区分费用语义")
                .contains("result.status === 503 && problemCode(result.body) === 'KNOWLEDGE_EMBEDDING_DISABLED'")
                .contains("服务端在调用向量化服务之前就拒绝了请求")
                .contains("未产生费用，知识库也没有被修改")
                .contains("可能已产生 Query Embedding 或重排费用，但结果未取得")
                .contains("页面不会自动重试");

        // 200 + 空 citations 是正常无命中；FD-0023-F-R1：渲染前校验实际展示字段的类型与取值，
        // 任何错配都按「结果不完整」处理，不本地重排或猜测修正；FD-0023-F-R2：citations 的
        // null / 数组 / 非对象元素按「结果不完整」处理（绝不抛页面异常），并按后端既有契约
        // 校验取值范围（topK 1..20、minScore 0..1、条数不超过 topK、相似度 [minScore,1]）；
        // 重排分保持相对分语义、不做范围校验；渲染只走 textContent。
        assertThat(script)
                .as("空 citations 是正常无命中；200 校验收紧到类型与取值与后端范围契约；渲染安全")
                .contains("这是正常结果，不是失败")
                .contains("Array.isArray(body.citations)")
                .contains("body.rankingMode !== 'VECTOR_SIMILARITY' && body.rankingMode !== 'RERANK'")
                .contains("rankingMode 为 RERANK 但缺少非空字符串的重排模型 rerankModel")
                .contains("rankingMode 为 VECTOR_SIMILARITY 但出现了重排模型 rerankModel")
                .contains("的编号不是按返回顺序连续的 K' + (i + 1)")
                .contains("的 rank 不是连续的 ' + (i + 1)")
                .contains("的相似度 score 不是有限数字")
                .contains("在 RERANK 模式下缺少有限数字的重排分 rerankScore")
                .contains("在非重排模式下出现了重排分 rerankScore")
                .contains("topK 不是 1..20 之间的正整数")
                .contains("minScore 不是 0..1 之间的有限数值")
                .contains("超过生效 topK")
                .contains("不是 JSON 对象（可能是 null、数组或其他原始值）")
                .contains("的相似度 score 不在 [minScore, 1] 范围内")
                .doesNotContain("innerHTML")
                .doesNotContain("outerHTML")
                .doesNotContain("insertAdjacentHTML")
                .doesNotContain("document.write");

        // 绝不自动检索：submitSearch 只有一个定义，只被表单提交绑定引用一次。
        int definitionCount = script.split("function submitSearch\\(", -1).length - 1;
        assertThat(definitionCount).as("submitSearch 只能定义一次").isEqualTo(1);
        int referenceCount = script.split("submitSearch", -1).length - 1;
        assertThat(referenceCount)
                .as("submitSearch 只允许出现在定义与表单提交绑定各一次 —— 加载、上传、解析、索引路径都不得触发检索")
                .isEqualTo(2);
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
