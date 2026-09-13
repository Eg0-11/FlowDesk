package com.flowdesk.bootstrap.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * multipart 到纯 Java 内容源的适配测试。
 *
 * <p>重点验证两条契约：只允许打开一次、关闭是无害的；以及声明信息如实透传
 * （但都<b>不可信</b>，真正的限制由读取路径负责）。</p>
 */
class MultipartContentSourceTest {

    private static final byte[] CONTENT = "知识库文档".getBytes(StandardCharsets.UTF_8);

    @Test
    void exposesDeclaredMetadataAndStreamsTheContent() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", CONTENT);
        MultipartContentSource source = new MultipartContentSource(file);

        assertThat(source.declaredFileName()).isEqualTo("notes.txt");
        assertThat(source.declaredContentType()).isEqualTo("text/plain");
        assertThat(source.declaredSize()).isEqualTo(CONTENT.length);
        assertThat(source.opened()).isFalse();

        try (InputStream stream = source.openStream()) {
            assertThat(stream.readAllBytes()).isEqualTo(CONTENT);
        }

        assertThat(source.opened()).isTrue();
    }

    @Test
    void refusesToOpenTheSameContentTwice() throws IOException {
        MultipartContentSource source = new MultipartContentSource(
                new MockMultipartFile("file", "notes.txt", "text/plain", CONTENT));

        try (InputStream stream = source.openStream()) {
            assertThat(stream.readAllBytes()).isEqualTo(CONTENT);
        }

        assertThatThrownBy(source::openStream)
                .as("重复打开必须明确失败，而不是静默返回空内容")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closingIsHarmlessAndRepeatable() {
        MultipartContentSource source = new MultipartContentSource(
                new MockMultipartFile("file", "notes.txt", "text/plain", CONTENT));

        source.close();
        source.close();

        assertThat(source.opened()).isFalse();
    }

    @Test
    void rejectsNullFile() {
        assertThatThrownBy(() -> new MultipartContentSource(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void toleratesMissingOriginalFilenameAndContentType() {
        MultipartContentSource source = new MultipartContentSource(
                new MockMultipartFile("file", null, null, CONTENT));

        // Spring 的 MockMultipartFile 把 null 文件名规范成空串；适配器只如实透传，
        // 「空文件名意味着没有可用文件名」由应用层的规范化负责
        assertThat(source.declaredFileName()).isBlank();
        assertThat(source.declaredContentType()).isNull();
    }
}
