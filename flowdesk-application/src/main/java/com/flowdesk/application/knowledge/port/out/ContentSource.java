package com.flowdesk.application.knowledge.port.out;

import java.io.IOException;
import java.io.InputStream;

/**
 * 上传内容的<b>纯 Java 内容源</b>抽象。
 *
 * <p>应用层不认识 {@code MultipartFile}、不认识 Servlet、也不认识任何具体的传输方式：
 * 它只需要「能拿到文件名、声明的类型与大小，以及一个可读一次的内容流」。
 * 把 HTTP 的 multipart 适配成这个接口是输入适配器（bootstrap）的职责，
 * 换成别的入口（命令行导入、对象存储同步）不需要改动应用层。</p>
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>{@link #openStream()} <b>只能成功调用一次</b>：重复调用必须失败，
 *       避免「同一个流被读两遍」这种在某些实现下会静默返回空内容的错误；</li>
 *   <li>调用方负责关闭：内容源本身实现 {@link AutoCloseable}，
 *       应用服务用 try-with-resources 包住它，因此无论成功失败都会关闭；</li>
 *   <li>{@link #declaredSize()} 与 {@link #declaredContentType()} 都是<b>客户端声明</b>的，
 *       不可信，只能用于早期拒绝；真正的限制依据是一路读到的实际字节数。</li>
 * </ul>
 */
public interface ContentSource extends AutoCloseable {

    /**
     * @return 客户端声明的原始文件名，可能为 {@code null}
     */
    String declaredFileName();

    /**
     * @return 客户端声明的媒体类型，可能为 {@code null} 或空
     */
    String declaredContentType();

    /**
     * @return 客户端声明的大小；未知时返回 {@code -1}。不可信。
     */
    long declaredSize();

    /**
     * 打开内容流。只能成功调用一次。
     *
     * @return 内容输入流，由调用方关闭
     * @throws IOException 打开失败
     * @throws IllegalStateException 已被打开过
     */
    InputStream openStream() throws IOException;

    /**
     * 是否已经打开过内容流。
     *
     * @return 已打开过返回 {@code true}
     */
    boolean opened();

    /**
     * 释放底层资源；重复调用必须是无害的。
     */
    @Override
    void close();
}
