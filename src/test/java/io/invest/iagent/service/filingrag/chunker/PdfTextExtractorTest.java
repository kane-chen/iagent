package io.invest.iagent.service.filingrag.chunker;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    void supportsPdf() {
        assertTrue(extractor.supports("application/pdf", Path.of("x.pdf")));
        assertTrue(extractor.supports(null, Path.of("X.PDF")));
        assertFalse(extractor.supports("text/html", Path.of("x.html")));
    }

    @Test
    void extractsSectionsWithPageNumbers(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("sample.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page1 = new PDPage();
            doc.addPage(page1);
            PDType1Font helvetica = new PDType1Font(FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.beginText();
                cs.setFont(helvetica, 12);
                cs.newLineAtOffset(50, 750);
                cs.showText("Item 1. Business");
                cs.newLineAtOffset(0, -20);
                cs.showText("We operate a global platform serving consumers and merchants.");
                cs.newLineAtOffset(0, -20);
                cs.showText("Our core segments include cloud commerce and media.");
                cs.endText();
            }
            PDPage page2 = new PDPage();
            doc.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                cs.beginText();
                cs.setFont(helvetica, 12);
                cs.newLineAtOffset(50, 750);
                cs.showText("Item 7. MD&A");
                cs.newLineAtOffset(0, -20);
                cs.showText("Revenue increased by 12% year over year.");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        List<RawSection> sections = extractor.extract(pdf);
        assertFalse(sections.isEmpty());
        // At least one section should have pageNumber = 1 (first page)
        assertTrue(sections.stream().anyMatch(s -> s.getPageNumber() != null && s.getPageNumber() >= 1),
                "Expected page numbers to be recorded");
        boolean hasItem1 = sections.stream().anyMatch(s -> s.getTitle() != null && s.getTitle().contains("Item 1"));
        assertTrue(hasItem1, "Expected Item 1 section heading");
    }
}
