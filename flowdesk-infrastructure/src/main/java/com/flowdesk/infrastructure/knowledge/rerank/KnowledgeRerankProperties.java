package com.flowdesk.infrastructure.knowledge.rerank;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 知识重排配置（RAG 6/6）。
 *
 * <pre>
 * flowdesk:
 *   knowledge:
 *     rerank:
 *       enabled: false                      # 默认关闭
 *       model: qwen3-rerank                 # 本版本实现的唯一协议
 *       endpoint: ""                        # 必须显式配置（见下）
 *       connect-timeout: 3s
 *       read-timeout: 10s
 * </pre>
 *
 * <h2>为什么 Endpoint 没有默认值</h2>
 * <p>DashScope 的文本重排接口地址形如
 * {@code https://{WorkspaceId}.<region>.maas.aliyuncs.com/...</b>}，其中 {@code {WorkspaceId}}
 * 是<b>每个账号自己的业务空间 ID</b>。把它硬编码进代码或默认配置，要么写死别人的空间、
 * 要么在日志与仓库里留下一个账号标识；因此这里<b>不提供默认 Endpoint</b>：
 * 开启重排必须显式写出地址，启动期校验会拒绝空值、非法 URL 与仍含 {@code &#123;...&#125;}
 * 占位符的地址。</p>
 *
 * <h2>为什么模型名必须逐字等于 {@value #SUPPORTED_MODEL}</h2>
 * <p>本版本只实现 <b>qwen3-rerank 的扁平协议</b>（请求体里 {@code model}/{@code query}/{@code documents}
 * 同级，响应的 {@code results} 直接位于顶层）。别的模型（如 {@code gte-rerank-v2}）用的是
 * 「{@code input}/{@code parameters} 嵌套 + 响应带 {@code output}」的另一种协议，
 * 用同一份代码去调只会得到无法解释的响应。模型名还会写进检索结果用于审计，
 * 因此这里要求逐字匹配（不 trim、不改大小写），拒绝一切变体。</p>
 *
 * <h2>超时</h2>
 * <p>连接与读取都有上界：重排是<b>同步</b>链路的一部分，没有超时的上游会把请求线程一直占住。
 * 本阶段不做内部重试（见 ADR 0010）。</p>
 */
@ConfigurationProperties(prefix = "flowdesk.knowledge.rerank")
public class KnowledgeRerankProperties {

    /** 本版本唯一支持的模型，也是官方 qwen3-rerank 扁平协议的模型标识。 */
    public static final String SUPPORTED_MODEL = "qwen3-rerank";

    /** 连接超时上界。 */
    public static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(60);

    /** 读取（响应）超时上界。 */
    public static final Duration MAX_READ_TIMEOUT = Duration.ofSeconds(300);

    private boolean enabled = false;

    private String model = SUPPORTED_MODEL;

    /** 重排接口地址；刻意没有默认值（含业务空间 ID，必须由部署方显式提供）。 */
    private String endpoint = "";

    private Duration connectTimeout = Duration.ofSeconds(3);

    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * 严格校验配置，任何不合法都抛异常让应用启动失败。
     *
     * <p>关闭状态下只检查超时的合理性（它们不会生效，但一个负数仍然是配置错误）；
     * 开启状态下额外要求模型逐字等于 {@value #SUPPORTED_MODEL}、Endpoint 是绝对 http(s) URL 且
     * 不含未解析的占位符与用户信息。错误信息只提配置名与受支持取值，<b>不</b>回显 Endpoint 原值。</p>
     */
    public void validate() {
        if (!StringUtils.hasText(this.model)) {
            throw new IllegalStateException("flowdesk.knowledge.rerank.model 不能为空：当前版本只支持 "
                    + SUPPORTED_MODEL);
        }
        if (this.enabled && !SUPPORTED_MODEL.equals(this.model)) {
            throw new IllegalStateException("flowdesk.knowledge.rerank.model 不是受支持的取值："
                    + "当前版本只实现 " + SUPPORTED_MODEL + " 的请求/响应协议"
                    + "（不接受大小写变体、前后空格或空值）。"
                    + "请把该属性设为 " + SUPPORTED_MODEL + "，或关闭 flowdesk.knowledge.rerank.enabled。");
        }
        requirePositiveBoundedTimeout("connect-timeout", this.connectTimeout, MAX_CONNECT_TIMEOUT);
        requirePositiveBoundedTimeout("read-timeout", this.readTimeout, MAX_READ_TIMEOUT);

        if (this.enabled) {
            requireValidEndpoint();
        }
    }

    /**
     * 校验 Endpoint（仅在启用时）。
     *
     * <p>拒绝：空白、无法解析的 URI、非 http/https、缺少主机、带用户信息（凭证必须走 API Key，
     * 不写在 URL 里）、带查询串或片段（重排接口不需要）、以及仍含 {@code &#123;}/{@code &#125;}
     * 的地址（说明业务空间 ID 之类的占位符没有替换）。</p>
     */
    private void requireValidEndpoint() {
        if (!StringUtils.hasText(this.endpoint)) {
            throw new IllegalStateException("已启用 flowdesk.knowledge.rerank.enabled，但没有配置 "
                    + "flowdesk.knowledge.rerank.endpoint：该地址包含账号自己的业务空间 ID，"
                    + "因此没有默认值，必须由部署方显式提供（示例形如 "
                    + "https://<workspace-id>.<region>.maas.aliyuncs.com/compatible-api/v1/reranks）。");
        }
        if (this.endpoint.indexOf('{') >= 0 || this.endpoint.indexOf('}') >= 0) {
            throw new IllegalStateException("flowdesk.knowledge.rerank.endpoint 里仍有未替换的占位符"
                    + "（例如 {WorkspaceId}）：请替换为真实的业务空间 ID。");
        }
        URI uri;
        try {
            uri = new URI(this.endpoint);
        }
        catch (URISyntaxException ex) {
            throw new IllegalStateException("flowdesk.knowledge.rerank.endpoint 不是合法的 URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalStateException("flowdesk.knowledge.rerank.endpoint 必须是 http 或 https 绝对地址");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalStateException("flowdesk.knowledge.rerank.endpoint 缺少主机名");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalStateException("flowdesk.knowledge.rerank.endpoint 不得包含用户信息："
                    + "凭证只通过 API Key 传递");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("flowdesk.knowledge.rerank.endpoint 不得包含查询串或片段");
        }
    }

    /**
     * @param name  配置名（用于错误信息）
     * @param value 取值
     * @param max   上界
     */
    private static void requirePositiveBoundedTimeout(String name, Duration value, Duration max) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("flowdesk.knowledge.rerank." + name + " 必须是正数（例如 3s、10s）");
        }
        if (value.compareTo(max) > 0) {
            throw new IllegalStateException("flowdesk.knowledge.rerank." + name + " 不能超过 " + max);
        }
    }

    /**
     * @return 是否启用重排
     */
    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * @param enabled 是否启用重排
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return 重排模型标识
     */
    public String getModel() {
        return this.model;
    }

    /**
     * @param model 重排模型标识
     */
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * @return 重排接口地址
     */
    public String getEndpoint() {
        return this.endpoint;
    }

    /**
     * @param endpoint 重排接口地址
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * @return 连接超时
     */
    public Duration getConnectTimeout() {
        return this.connectTimeout;
    }

    /**
     * @param connectTimeout 连接超时
     */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * @return 读取（响应）超时
     */
    public Duration getReadTimeout() {
        return this.readTimeout;
    }

    /**
     * @param readTimeout 读取（响应）超时
     */
    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
