package io.invest.iagent.service.filingrag.chunker;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extracts sections from SEC-style HTML filings using jsoup.
 * <p>
 * Strategy:
 * <ul>
 *   <li>Strip script, style, nav, header, footer and obvious TOC link blocks.</li>
 *   <li>Walk body in DOM order, recognizing section titles by h1-h4 or bold short lines.</li>
 *   <li>Paragraph separation on p/div/tr/li.</li>
 *   <li>Tables are converted to pipe-delimited rows; navigation-style tables (too many columns or too wide) are skipped.</li>
 * </ul>
 */
@Slf4j
public class HtmlTextExtractor implements FilingTextExtractor {

    private static final Pattern HEADER_TAG = Pattern.compile("^h[1-4]$", Pattern.CASE_INSENSITIVE);
    /** Bold short lines (SEC style: {@code <B><FONT size=+1>Item 1.</FONT></B>}) */
    private static final Pattern BOLD_TITLE = Pattern.compile(
            "^(PART\\s+[IVX]+|Item\\s+\\d+[A-Za-z]?\\.|[一二三四五六七八九十]+[、．.]|（[一二三四五六七八九十]+）|第[一二三四五六七八九十]+[节章部分])",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_TABLE_COLS = 15;
    private static final int MAX_TABLE_CHARS = 4000;

    @Override
    public boolean supports(String contentType, Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xhtml")) {
            return true;
        }
        return contentType != null && (contentType.contains("html") || contentType.contains("xml"));
    }

    @Override
    public List<RawSectionVO> extract(Path file) throws Exception {
        String html = Files.readString(file, StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(html);

        // strip boilerplate
        doc.select("script, style, nav, header, footer, noscript").remove();
        // strip TOC-like blocks: elements that are mostly anchor links to #anchors
        for (Element el : doc.select("div, p, td, tr")) {
            if (isTocBlock(el)) {
                el.remove();
            }
        }

        // body walk
        Element body = ObjectUtils.firstNonNull(doc.body(),doc);
        List<RawSectionVO> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentTitle = null;

        // node walk
        for (Node node : body.childNodes()) {
            if (node instanceof Element el) {
                String tag = el.tagName().toLowerCase();

                // Table handling
                if ("table".equals(tag)) {
                    String tableText = renderTable(el);
                    if (tableText != null) {
                        current.append(tableText).append("\n\n");
                    }
                    continue;
                }

                // text check
                String text = el.text();
                if (StringUtils.isBlank(text)) {
                    continue;
                }
                String trimmed = text.trim();

                // section heading
                if (isSectionHeading(el, trimmed, tag)) {
                    // flush current section
                    if (!current.isEmpty() || currentTitle != null) {
                        sections.add(buildSection(currentTitle, current.toString()));
                    }
                    currentTitle = trimmed;
                    current = new StringBuilder();
                    continue;
                }

                if (isParagraphContainer(tag)) {
                    current.append(trimmed).append("\n\n");
                } else {
                    // inline / unknown container
                    current.append(trimmed).append(" ");
                }
            } else if (node instanceof TextNode tn) {
                String t = tn.text().trim();
                if (StringUtils.isNotBlank(t)) {
                    current.append(t).append(" ");
                }
            }
        }

        if (!current.isEmpty() || currentTitle != null) {
            sections.add(buildSection(currentTitle, current.toString()));
        }

        // remove empty sections
        sections.removeIf(s -> StringUtils.isBlank(s.getContent()) && StringUtils.isBlank(s.getTitle()));
        return sections;
    }

    private RawSectionVO buildSection(String title, String content) {
        return RawSectionVO.builder()
                .title(StringUtils.trimToNull(title))
                .content(normalizeWhitespace(content))
                .pageNumber(null)
                .build();
    }

    private String normalizeWhitespace(String s) {
        if (s == null) return "";
        // collapse 3+ newlines to 2
        String t = s.replaceAll("[ \t]+", " ");
        t = t.replaceAll("\\n{3,}", "\n\n");
        return t.trim();
    }

    private boolean isSectionHeading(Element el, String text, String tag) {
        if (text == null || text.length() > 200) {
            return false;
        }
        if (HEADER_TAG.matcher(tag).matches()) {
            return true;
        }
        // bold short line
        boolean isBold = el.selectFirst("b, strong") != null
                || "b".equalsIgnoreCase(tag) || "strong".equalsIgnoreCase(tag);
        // check font size +1
        boolean isEnlarged = el.selectFirst("font[size*=+]") != null;
        if ((isBold || isEnlarged) && text.length() <= 120) {
            if (BOLD_TITLE.matcher(text.trim()).find()) {
                return true;
            }
            // All caps short line (likely a heading)
            if (text.length() <= 60 && text.equals(text.toUpperCase()) && text.matches(".*[A-Z]{3,}.*")) {
                return true;
            }
        }
        return false;
    }

    private boolean isParagraphContainer(String tag) {
        return "p".equals(tag) || "div".equals(tag) || "li".equals(tag)
                || "tr".equals(tag) || "ul".equals(tag) || "ol".equals(tag)
                || "blockquote".equals(tag) || "pre".equals(tag);
    }

    private boolean isTocBlock(Element el) {
        Elements links = el.select("a[href]");
        String text = el.text();
        if (links.size() < 3) return false;
        if (StringUtils.isBlank(text)) return false;
        // most of the element's direct text nodes are empty; links dominate
        int linkTextLen = links.stream().mapToInt(a -> a.text().length()).sum();
        int totalLen = text.length();
        if (totalLen == 0) return false;
        return (double) linkTextLen / totalLen > 0.8 && links.size() >= 5;
    }

    private String renderTable(Element table) {
        Elements rows = table.select("tr");
        if (rows.isEmpty()) return null;
        List<List<String>> grid = new ArrayList<>();
        int maxCols = 0;
        int totalChars = 0;
        for (Element tr : rows) {
            List<String> cells = new ArrayList<>();
            for (Element c : tr.select("td, th")) {
                String ct = c.text().trim().replaceAll("\\s+", " ");
                cells.add(ct);
                totalChars += ct.length();
            }
            if (!cells.isEmpty()) {
                grid.add(cells);
                maxCols = Math.max(maxCols, cells.size());
            }
        }
        if (grid.isEmpty()) return null;
        if (maxCols > MAX_TABLE_COLS || totalChars > MAX_TABLE_CHARS) {
            // Likely a navigation / TOC table
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (List<String> row : grid) {
            sb.append(String.join(" | ", row)).append("\n");
        }
        return sb.toString();
    }
}
