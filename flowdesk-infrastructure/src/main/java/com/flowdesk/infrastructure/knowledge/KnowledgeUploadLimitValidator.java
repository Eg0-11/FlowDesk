package com.flowdesk.infrastructure.knowledge;

import java.util.Objects;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.util.unit.DataSize;

/**
 * 上传上限的启动期一致性校验器。
 *
 * <h2>要解决的问题</h2>
 * <p>知识上传实际涉及<b>两个</b>上限：应用自己的
 * {@code flowdesk.knowledge.upload.max-size}（按实际读取字节数判断），
 * 以及容器侧的 {@code spring.servlet.multipart.max-file-size}（在进入 Controller 之前拦截）。
 * 它们是两个互不相关的配置项，因此存在一类非常隐蔽的漂移：</p>
 * <pre>
 * 运维把应用上限从 20MB 改成 25MB，但忘了改容器上限
 *   → 21MB 的上传被容器提前拒绝
 *   → 应用层那个「25MB」永远不可达，而配置看起来"改成功了"
 * </pre>
 * <p>反过来，容器上限远大于应用上限是<b>安全</b>的（应用层仍会按实际字节数拦住），
 * 因此这里只强制「容器不比应用小」，不要求两者相等。</p>
 *
 * <h2>校验规则</h2>
 * <ol>
 *   <li>{@code multipart.max-file-size >= upload.max-size}：否则应用上限不可达（静默漂移）；</li>
 *   <li>{@code multipart.max-request-size >= multipart.max-file-size + 1KB}：
 *       否则连"恰好达到文件上限"的请求都会因为 multipart 边框与其它字段而被容器拒绝，
 *       应用上限同样不可达；</li>
 *   <li>两者都必须为正。</li>
 * </ol>
 *
 * <p>校验在装配阶段执行（Bean 创建时），因此<b>配置冲突会让应用启动失败</b>，
 * 而不是在生产流量里表现为「明明配了 25MB 却传不上去」。</p>
 */
public final class KnowledgeUploadLimitValidator {

    /** 给 multipart 边框、头部与其它表单字段预留的余量。 */
    public static final DataSize MULTIPART_OVERHEAD = DataSize.ofKilobytes(1);

    /**
     * 校验两个上限不会冲突。
     *
     * @param applicationLimit 应用层上限（按实际读取字节数）
     * @param containerFileLimit 容器单文件上限
     * @param containerRequestLimit 容器整个请求上限
     * @throws IllegalStateException 配置冲突时抛出，使应用启动失败
     */
    public static void validate(DataSize applicationLimit, DataSize containerFileLimit,
            DataSize containerRequestLimit) {

        Objects.requireNonNull(applicationLimit, "applicationLimit 不能为 null");
        Objects.requireNonNull(containerFileLimit, "containerFileLimit 不能为 null");
        Objects.requireNonNull(containerRequestLimit, "containerRequestLimit 不能为 null");

        if (applicationLimit.toBytes() <= 0L) {
            throw new IllegalStateException("flowdesk.knowledge.upload.max-size 必须为正");
        }
        if (containerFileLimit.toBytes() <= 0L) {
            throw new IllegalStateException("spring.servlet.multipart.max-file-size 必须为正");
        }
        if (containerFileLimit.toBytes() < applicationLimit.toBytes()) {
            throw new IllegalStateException("上传上限配置冲突：spring.servlet.multipart.max-file-size ("
                    + containerFileLimit + ") 小于 flowdesk.knowledge.upload.max-size (" + applicationLimit
                    + ")，应用上限将永远不可达；请把容器上限调整为不小于应用上限");
        }
        if (containerRequestLimit.toBytes() < containerFileLimit.toBytes() + MULTIPART_OVERHEAD.toBytes()) {
            throw new IllegalStateException("上传上限配置冲突：spring.servlet.multipart.max-request-size ("
                    + containerRequestLimit + ") 不足以容纳 max-file-size (" + containerFileLimit
                    + ") 加上 multipart 元数据（至少 " + MULTIPART_OVERHEAD + "）；请调大 max-request-size");
        }
    }

    /**
     * 用容器配置对象做一次校验。
     *
     * @param applicationLimit 应用层上限
     * @param multipartProperties Spring Boot 的 multipart 配置（含默认值）
     */
    public static void validate(DataSize applicationLimit, MultipartProperties multipartProperties) {
        Objects.requireNonNull(multipartProperties, "multipartProperties 不能为 null");
        validate(applicationLimit, multipartProperties.getMaxFileSize(), multipartProperties.getMaxRequestSize());
    }

    /**
     * 便于以 Bean 形式在装配阶段触发校验。
     *
     * @param upload 知识上传配置
     * @param multipart Spring Boot 的 multipart 配置
     * @return 无意义的占位值，仅为触发构造期校验
     */
    public static boolean validateOrFail(KnowledgeUploadProperties upload, MultipartProperties multipart) {
        validate(upload.getMaxSize(), multipart);
        return true;
    }

    private KnowledgeUploadLimitValidator() {
    }
}
