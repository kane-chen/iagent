package io.invest.iagent.rag.chunking.reader;

import io.invest.iagent.rag.model.Document;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 复合文档读取器：根据文件类型将 HTML/PDF/文本转为带标题层级的 Markdown 格式纯文本。
 */
@Slf4j
public class CompositeDocumentReader implements DocumentReader {

    private static final Pattern SEC_HEADING = Pattern.compile(
            "^(PART\\s+[IVX]+|Item\\s+\\d+[A-Za-z]?\\.?|第[一二三四五六七八九十]+[节章节]|[A-Z][A-Z /&\\-]{4,})$");

    @Override
    public String read(Document doc) {
        String path = doc.getFilePath();
        if (StringUtils.isBlank(path)) {
            throw new IllegalArgumentException("Document filePath is blank");
        }

        Path file = Path.of(path);
        String lower = path.toLowerCase();
        try {
            if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml")) {
                return readHtml(file);
            } else if (lower.endsWith(".pdf")) {
                return readPdf(file);
            } else {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read document: " + path, e);
        }
    }

    /**
     * HTML 解析：保留 h1-h4 标题结构，表格转为管道分隔，去除脚本样式等噪声。
     */
    private String readHtml(Path file) throws Exception {
        org.jsoup.nodes.Document html = Jsoup.parse(file.toFile(), StandardCharsets.UTF_8.name());

        // 去除噪声标签
        html.select("script, style, nav, header, footer, noscript").remove();

        StringBuilder sb = new StringBuilder();
        Element body = html.body();
        if (body == null) return "";

        walkHtml(body, sb);
        return sb.toString();
    }

    private void walkHtml(Node node, StringBuilder sb) {
        if (node instanceof TextNode textNode) {
            String text = textNode.text().trim();
            if (!text.isEmpty()) {
                sb.append(text);
                if (!text.endsWith("\n")) sb.append(" ");
            }
            return;
        }
        if (!(node instanceof Element el)) return;

        String tag = el.tagName().toLowerCase();
        switch (tag) {
            case "h1" -> sb.append("\n# ").append(el.text().trim()).append("\n\n");
            case "h2" -> sb.append("\n## ").append(el.text().trim()).append("\n\n");
            case "h3" -> sb.append("\n### ").append(el.text().trim()).append("\n\n");
            case "h4" -> sb.append("\n#### ").append(el.text().trim()).append("\n\n");
            case "p", "div" -> {
                for (Node child : el.childNodes()) walkHtml(child, sb);
                sb.append("\n");
            }
            case "br" -> sb.append("\n");
            case "tr" -> {
                Elements cells = el.select("td, th");
                if (!cells.isEmpty()) {
                    sb.append(String.join(" | ", cells.eachText().stream()
                            .map(String::trim).toList()));
                    sb.append("\n");
                }
            }
            case "li" -> {
                sb.append("- ");
                for (Node child : el.childNodes()) walkHtml(child, sb);
                sb.append("\n");
            }
            default -> {
                for (Node child : el.childNodes()) walkHtml(child, sb);
            }
        }
    }

    /**
     * PDF 解析：按页提取文本，检测 SEC 文档标题模式并转为 Markdown 标题。
     */
    private String readPdf(Path file) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String raw = stripper.getText(pdf);
            return markPdfHeadings(raw);
        }
    }

    private String markPdfHeadings(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && SEC_HEADING.matcher(trimmed).matches()) {
                sb.append("\n## ").append(trimmed).append("\n\n");
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
