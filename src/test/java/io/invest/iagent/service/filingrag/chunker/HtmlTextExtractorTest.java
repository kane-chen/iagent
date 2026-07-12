package io.invest.iagent.service.filingrag.chunker;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HtmlTextExtractorTest {

    private final HtmlTextExtractor extractor = new HtmlTextExtractor();

    @Test
    void supportsHtml() {
        assertTrue(extractor.supports("text/html", Path.of("test.html")));
        assertTrue(extractor.supports(null, Path.of("a.HTM")));
        assertFalse(extractor.supports("application/pdf", Path.of("a.pdf")));
    }

    @Test
    void test_extract_baba() throws Exception {
        Path file = Paths.get(System.getProperty("user.dir"))
                .resolve("workspace/portfolio/BABA/filings/fil_0001104659-26-032060/tm269353d1_ex99-1.htm") ;
        List<RawSectionVO> sections = extractor.extract(file) ;
        Assertions.assertNotNull(sections);
    }

    @Test
    void extractsSectionsByHeading(@TempDir Path dir) throws Exception {
        String html = """
                <html><head><title>Test</title></head><body>
                <script>var x = 1;</script>
                <style>p{color:red}</style>
                <nav><a href="#s1">Link1</a> <a href="#s2">Link2</a> <a href="#s3">Link3</a> <a href="#s4">Link4</a> <a href="#s5">Link5</a></nav>
                <h2>Item 1. Business</h2>
                <p>We are a global technology company. Our products include software and services.</p>
                <p>Revenue increased year over year.</p>
                <h2>Item 7. MD&amp;A</h2>
                <p>Results of operations: revenue grew 12%.</p>
                <table><tr><th>Segment</th><th>Revenue</th></tr><tr><td>Cloud</td><td>100</td></tr><tr><td>Retail</td><td>200</td></tr></table>
                </body></html>
                """;
        Path f = dir.resolve("sec.html");
        Files.writeString(f, html, StandardCharsets.UTF_8);
        List<RawSectionVO> sections = extractor.extract(f);
        assertFalse(sections.isEmpty());
        // Should find at least the two h2 sections
        boolean hasBusiness = sections.stream().anyMatch(s -> "Item 1. Business".equals(s.getTitle()));
        boolean hasMDA = sections.stream().anyMatch(s -> s.getTitle() != null && s.getTitle().contains("MD&A"));
        assertTrue(hasBusiness, "Expected 'Item 1. Business' section");
        assertTrue(hasMDA, "Expected MD&A section");
        // Boilerplate script/style/nav content should not appear
        for (RawSectionVO s : sections) {
            String c = s.getContent() == null ? "" : s.getContent();
            assertFalse(c.contains("var x = 1"), "script text should be stripped");
            assertFalse(c.contains("color:red"), "style text should be stripped");
        }
        // Table content should be pipe-delimited
        boolean tableRendered = sections.stream().anyMatch(s -> s.getContent() != null && s.getContent().contains("|"));
        assertTrue(tableRendered, "Expected table to be rendered with pipes");
    }
}
