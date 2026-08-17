package io.invest.iagent.rag.chunking.reader;

import io.invest.iagent.rag.model.Document;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

@Service
public class HtmlDocumentReader implements DocumentReader{

    @Override
    public List<String> supportTypes() {
        return List.of("html","htm","xhtml");
    }

    @Override
    public String read(Document doc) {
        try{
            // parse
            Path file = Path.of(doc.getFilePath());
            org.jsoup.nodes.Document html = Jsoup.parse(file.toFile(), StandardCharsets.UTF_8.name());
            // 去除噪声标签
            html.select("script, style, nav, header, footer, noscript").remove();

            StringBuilder sb = new StringBuilder();
            walkHtml(html.body(), sb);
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
}
