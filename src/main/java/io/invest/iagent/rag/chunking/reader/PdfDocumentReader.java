package io.invest.iagent.rag.chunking.reader;

import io.invest.iagent.rag.model.Document;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class PdfDocumentReader implements DocumentReader{

    private static final Pattern SEC_HEADING = Pattern.compile(
            "^(PART\\s+[IVX]+|Item\\s+\\d+[A-Za-z]?\\.?|第[一二三四五六七八九十]+[节章节]|[A-Z][A-Z /&\\-]{4,})$");

    @Override
    public List<String> supportTypes() {
        return List.of("pdf");
    }

    @Override
    public String read(Document doc) {
        try{
            // parse
            Path file = Path.of(doc.getFilePath());
            return readPdf(file);
        } catch (Exception e) {
            throw new RuntimeException(e);
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
