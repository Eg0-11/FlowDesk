package com.flowdesk.infrastructure.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * 知识文档上传配置。
 *
 * <p>默认上限 {@value #DEFAULT_MAX_SIZE_LABEL}，可用
 * {@code flowdesk.knowledge.upload.max-size} 覆盖（支持 {@code 20MB}、{@code 512KB} 这类写法）。</p>
 *
 * <p><b>这个上限是应用层自己的判断依据</b>：Spring 的 multipart 上限（
 * {@code spring.servlet.multipart.max-file-size}）只是容器侧的第一道栅栏，
 * 可能被伪造的 {@code Content-Length} 绕过，而且不同入口（将来可能的命令行导入）
 * 根本不经过容器。因此真正的限制落在读取路径上，按实际读到的字节数计算。</p>
 */
@ConfigurationProperties(prefix = "flowdesk.knowledge.upload")
public class KnowledgeUploadProperties {

    /** 默认上限的展示文案。 */
    public static final String DEFAULT_MAX_SIZE_LABEL = "20MB";

    private DataSize maxSize = DataSize.ofMegabytes(20);

    /**
     * @return 允许的最大上传大小
     */
    public DataSize getMaxSize() {
        return this.maxSize;
    }

    /**
     * @param maxSize 允许的最大上传大小；必须为正
     */
    public void setMaxSize(DataSize maxSize) {
        if (maxSize == null || maxSize.isNegative() || maxSize.toBytes() <= 0L) {
            throw new IllegalArgumentException("flowdesk.knowledge.upload.max-size 必须为正");
        }
        this.maxSize = maxSize;
    }
}
