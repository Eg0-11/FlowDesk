package com.flowdesk.infrastructure.knowledge;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识文档内容存储配置。
 *
 * <p>{@code flowdesk.knowledge.storage.root} 指定本地文件系统适配器的根目录；
 * 未配置时使用工作目录下的 {@code data/knowledge}（仅便于本地起服务，
 * 生产环境应当显式指定一个独立的数据目录）。</p>
 *
 * <p>目录结构由适配器决定：内容在 {@code <root>/documents} 下，
 * 临时文件在 {@code <root>/tmp} 下 —— 同一根目录保证「临时文件 → 最终对象」
 * 的移动可以在同一个文件系统内原子完成。</p>
 */
@ConfigurationProperties(prefix = "flowdesk.knowledge.storage")
public class KnowledgeStorageProperties {

    private Path root = Path.of("data", "knowledge");

    /**
     * @return 存储根目录
     */
    public Path getRoot() {
        return this.root;
    }

    /**
     * @param root 存储根目录；不得为 {@code null}
     */
    public void setRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("flowdesk.knowledge.storage.root 不能为空");
        }
        this.root = root;
    }
}
