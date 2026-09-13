package com.flowdesk.bootstrap.knowledge;

import com.flowdesk.application.knowledge.port.out.ContentSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.springframework.web.multipart.MultipartFile;

/**
 * 把 Spring 的 {@link MultipartFile} 适配成应用层的纯 Java {@link ContentSource}。
 *
 * <p>这是 HTTP 与用例之间<b>唯一</b>涉及 multipart 的地方：应用层与领域层都不知道
 * multipart 的存在，换成命令行导入或对象存储同步时不需要改动它们。</p>
 *
 * <h2>为什么不用 {@code MultipartFile.getBytes()}</h2>
 * <p>它会一次性把整个文件读进内存。一个 20 MiB 的上传乘以几个并发请求就足以把堆吃满，
 * 而上传大小本质上由客户端控制 —— 这正是最不该整块载入内存的场景。
 * 这里只暴露 {@link #openStream()}，让内容存储用固定缓冲区边读边算。</p>
 *
 * <h2>只允许打开一次</h2>
 * <p>同一份 multipart 内容被读两遍（例如「先算摘要再保存」）在某些实现下会得到空内容
 * 而不是报错，属于最难排查的一类问题。这里把「重复打开」变成明确的失败。</p>
 */
final class MultipartContentSource implements ContentSource {

    private final MultipartFile file;

    private boolean opened;

    MultipartContentSource(MultipartFile file) {
        this.file = Objects.requireNonNull(file, "file 不能为 null");
    }

    @Override
    public String declaredFileName() {
        return this.file.getOriginalFilename();
    }

    @Override
    public String declaredContentType() {
        return this.file.getContentType();
    }

    @Override
    public long declaredSize() {
        return this.file.getSize();
    }

    @Override
    public InputStream openStream() throws IOException {
        if (this.opened) {
            throw new IllegalStateException("上传内容只能被读取一次");
        }
        this.opened = true;
        return this.file.getInputStream();
    }

    @Override
    public boolean opened() {
        return this.opened;
    }

    @Override
    public void close() {
        // MultipartFile 的内容由容器/临时文件生命周期管理，这里没有需要显式释放的句柄；
        // 打开出来的 InputStream 由调用方（内容存储）关闭。
    }
}
