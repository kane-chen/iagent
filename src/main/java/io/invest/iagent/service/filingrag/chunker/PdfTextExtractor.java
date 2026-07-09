package io.invest.iagent.service.filingrag.chunker;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extracts sections from PDF filings using Apache PDFBox.
 * <p>
 * Strategy:
 * <ul>
 *   <li>Extract text page-by-page via {@link PDFTextStripper}.</li>
 *   <li>Heuristically detect section titles (line-start patterns like PART X, Item X., （一）, 第X节).</li>
 *   <li>Merge sections that span multiple pages, recording starting page number.</li>
 * </ul>
 */
@Slf4j
public class PdfTextExtractor implements FilingTextExtractor {

    private static final Pattern SECTION_TITLE = Pattern.compile(
            "^\\s*(PART\\s+[IVX0-9]+|Item\\s+\\d+[A-Za-z]?\\.?" +
                    "|[一二三四五六七八九十百零〇]+[、．.节章部分]" +
                    "|（[一二三四五六七八九十百零〇]+）" +
                    "|\\([一二三四五六七八九十]+\\)" +
                    "|第[一二三四五六七八九十百零〇0-9]+[节章部分条]" +
                    "|[A-Z][A-Z /&-]{4,})",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(String contentType, Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".pdf")) {
            return true;
        }
        return contentType != null && contentType.toLowerCase().contains("pdf");
    }

    @Override
    public List<RawSection> extract(Path file) throws Exception {
        byte[] pdfBytes = Files.readAllBytes(file);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int pages = doc.getNumberOfPages();
            List<RawSection> sections = new ArrayList<>();
            // Accumulators for current section
            String currentTitle = null;
            StringBuilder currentContent = new StringBuilder();
            Integer currentStartPage = null;

            for (int page = 1; page <= pages; page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                stripper.setSortByPosition(true);
                String pageText = stripper.getText(doc);
                if (StringUtils.isBlank(pageText)) {
                    continue;
                }
                String[] lines = pageText.split("\\r?\\n");
                for (String rawLine : lines) {
                    String line = rawLine.trim();
                    if (StringUtils.isBlank(line)) {
                        currentContent.append("\n");
                        continue;
                    }
                    // Detect a heading candidate: short line (<=120 chars) matching section pattern
                    if (line.length() <= 120 && SECTION_TITLE.matcher(line).find()) {
                        // flush current section
                        if (currentContent.length() > 0 || currentTitle != null) {
                            sections.add(RawSection.builder()
                                    .title(currentTitle)
                                    .content(normalize(currentContent.toString()))
                                    .pageNumber(currentStartPage)
                                    .build());
                        }
                        currentTitle = line;
                        currentContent = new StringBuilder();
                        currentStartPage = page;
                    } else {
                        currentContent.append(line).append("\n");
                    }
                }
            }

            if (currentContent.length() > 0 || currentTitle != null) {
                sections.add(RawSection.builder()
                        .title(currentTitle)
                        .content(normalize(currentContent.toString()))
                        .pageNumber(currentStartPage)
                        .build());
            }

            sections.removeIf(s -> StringUtils.isBlank(s.getContent()) && StringUtils.isBlank(s.getTitle()));
            return sections;
        } catch (IOException e) {
            throw new IOException("Failed to parse PDF " + file + ": " + e.getMessage(), e);
        }
    }

    private String normalize(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[ \t]+", " ");
        t = t.replaceAll("\\n{3,}", "\n\n");
        return t.trim();
    }
}
