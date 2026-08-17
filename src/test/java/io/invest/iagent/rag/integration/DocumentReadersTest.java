package io.invest.iagent.rag.integration;

import io.invest.AgentConfig4Test;
import io.invest.iagent.rag.chunking.reader.DocumentReaders;
import io.invest.iagent.rag.model.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Document Reader 集成测试：HTML / 纯文本路由。
 * PDF 用例默认跳过（需要准备 PDF 样例文件），可在 workspace 下放置样例后手动启用。
 */
@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
class DocumentReadersTest {

    @Autowired
    private DocumentReaders reader;

    @TempDir
    Path tempDir;

    @Test
    void test_html_preserves_heading_hierarchy_and_tables() throws IOException {
        Path html = tempDir.resolve("sample.html");
        Files.writeString(html, """
                <html><body>
                <script>var x = 1;</script>
                <h1>PART II</h1>
                <h2>Item 7. MD&amp;A</h2>
                <p>Revenue increased due to strong demand.</p>
                <table>
                  <tr><th>Segment</th><th>Revenue</th></tr>
                  <tr><td>Cloud</td><td>100</td></tr>
                </table>
                </body></html>
                """, StandardCharsets.UTF_8);

        Document doc = Document.builder().filePath(html.toString()).build();
        String text = reader.read(doc);

        assertThat(text).contains("# PART II");
        assertThat(text).contains("## Item 7. MD&A");
        assertThat(text).contains("Revenue increased due to strong demand.");
        // 脚本被剥离
        assertThat(text).doesNotContain("var x");
        // 表格管道化
        assertThat(text).contains("Segment | Revenue");
        assertThat(text).contains("Cloud | 100");
    }

    @Test
    void test_plain_text_passthrough() throws IOException {
        Path txt = tempDir.resolve("notes.txt");
        Files.writeString(txt, "Hello, RAG integration test.\nSecond line.", StandardCharsets.UTF_8);

        Document doc = Document.builder().filePath(txt.toString()).build();
        String text = reader.read(doc);

        assertThat(text).contains("Hello, RAG integration test.");
        assertThat(text).contains("Second line.");
    }

    /**
     * 手动启用：将 PDF 放到 workspace 下并修改路径，验证 SEC 标题识别。
     */
    // @Test
    void test_pdf_sec_headings_recognized() throws IOException {
        Path pdf = Path.of("workspace/portfolio/BABA/filings").toAbsolutePath();
        // 样例：选择一个真实 PDF 文件
        try (var stream = Files.walk(pdf)) {
            Path sample = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                    .findFirst()
                    .orElse(null);
            if (sample == null) {
                System.out.println("No PDF sample found, skipping");
                return;
            }
            Document doc = Document.builder().filePath(sample.toString()).build();
            String text = reader.read(doc);
            assertThat(text).isNotBlank();
            // 若识别到 Item/Part 标题则出现 ## 前缀
            System.out.println("PDF headings detected: "
                    + text.lines().filter(l -> l.startsWith("## ")).count());
        }
    }
}
