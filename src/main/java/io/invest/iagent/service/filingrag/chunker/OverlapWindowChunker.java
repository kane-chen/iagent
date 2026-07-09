package io.invest.iagent.service.filingrag.chunker;

import io.invest.iagent.service.filingrag.model.FilingChunk;
import io.invest.iagent.service.filingrag.model.FilingDocumentMeta;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sliding-window chunker with overlap.
 * <p>
 * Token estimation: ~1.5 CJK characters ≈ 1 token; ~4 ASCII characters ≈ 1 token.
 * A prefix "Section: {title}\n" is prepended to every chunk within a section.
 * Paragraphs exceeding maxTokens are split on sentence boundaries before windowing.
 */
public class OverlapWindowChunker implements FilingChunker {

    private final int targetTokens;
    private final int maxTokens;
    private final int overlapTokens;

    public OverlapWindowChunker(int targetTokens, int maxTokens, int overlapTokens) {
        this.targetTokens = targetTokens;
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    @Override
    public List<FilingChunk> chunk(FilingDocumentMeta meta, String sourceFileName, List<RawSection> sections) {
        List<FilingChunk> result = new ArrayList<>();
        if (sections == null || sections.isEmpty()) {
            return result;
        }
        int sectionIdx = 0;
        for (RawSection section : sections) {
            String secTitle = section.getTitle();
            String secContent = section.getContent();
            if (StringUtils.isBlank(secContent) && StringUtils.isBlank(secTitle)) {
                sectionIdx++;
                continue;
            }
            String body = StringUtils.defaultString(secContent);
            List<String> units = splitToUnits(body);
            List<String> windows = buildWindows(units);
            int idx = 0;
            for (String window : windows) {
                String chunkContent = StringUtils.isNotBlank(secTitle)
                        ? "Section: " + secTitle + "\n" + window
                        : window;
                String chunkId = deterministicChunkId(meta.getDocumentId(),
                        StringUtils.defaultString(secTitle) + "#" + sectionIdx, idx);
                Map<String, Object> cmeta = new HashMap<>();
                cmeta.put("sectionIndex", sectionIdx);
                cmeta.put("chunkIndexInSection", idx);
                FilingChunk chunk = FilingChunk.builder()
                        .chunkId(chunkId)
                        .ticker(meta.getTicker())
                        .documentId(meta.getDocumentId())
                        .formType(meta.getFormType())
                        .fiscalYear(meta.getFiscalYear())
                        .fiscalPeriod(meta.getFiscalPeriod())
                        .filingDate(meta.getFilingDate())
                        .sourceFileName(sourceFileName)
                        .sectionTitle(secTitle)
                        .content(chunkContent)
                        .pageNumber(section.getPageNumber())
                        .metadata(cmeta)
                        .build();
                result.add(chunk);
                idx++;
            }
            sectionIdx++;
        }
        return result;
    }

    /** Split section body into small units (sentences/clauses), each guaranteed ≤ maxTokens. */
    private List<String> splitToUnits(String body) {
        List<String> units = new ArrayList<>();
        String[] paragraphs = body.split("\\n{2,}");
        for (String para : paragraphs) {
            String p = para.trim();
            if (StringUtils.isBlank(p)) continue;
            if (estimateTokens(p) <= maxTokens) {
                units.add(p);
            } else {
                for (String s : splitSentences(p)) {
                    if (StringUtils.isNotBlank(s)) units.add(s.trim());
                }
            }
        }
        return units;
    }

    /**
     * Build sliding windows over units using a simple greedy approach with overlap.
     * Algorithm: start at position 0, greedily add units until targetTokens reached (capped at maxTokens).
     * Emit window. Next start = last position in window such that the tokens from that position
     * to the end of the window total ≤ overlapTokens (ensuring next window starts with ~overlapTokens of prefix).
     */
    private List<String> buildWindows(List<String> units) {
        List<String> windows = new ArrayList<>();
        int n = units.size();
        int start = 0;
        while (start < n) {
            List<String> window = new ArrayList<>();
            int tokens = 0;
            int end = start;
            boolean oversized = false;
            while (end < n) {
                String u = units.get(end);
                int ut = estimateTokens(u);
                if (ut > maxTokens) {
                    // Hard-split this unit into smaller pieces
                    List<String> pieces = hardSplit(u);
                    units.remove(end);
                    for (int p = pieces.size() - 1; p >= 0; p--) {
                        units.add(end, pieces.get(p));
                    }
                    n = units.size();
                    oversized = true;
                    break;
                }
                if (tokens + ut > maxTokens && !window.isEmpty()) {
                    break;
                }
                window.add(u);
                tokens += ut;
                end++;
                if (tokens >= targetTokens) {
                    break;
                }
            }
            if (oversized) continue;
            if (!window.isEmpty()) {
                windows.add(String.join(" ", window).trim());
            }
            // Determine next start: walk back from (end-1) until accumulated tokens ≤ overlapTokens
            int nextStart = end;
            int backTokens = 0;
            for (int j = end - 1; j > start; j--) {
                int jt = estimateTokens(units.get(j));
                if (backTokens + jt > overlapTokens) break;
                backTokens += jt;
                nextStart = j;
            }
            if (nextStart <= start) {
                // No overlap possible; advance by 1 to guarantee progress
                start = Math.max(end, start + 1);
            } else {
                start = nextStart;
            }
        }
        // Merge tiny tail window
        if (windows.size() >= 2) {
            String last = windows.get(windows.size() - 1);
            if (estimateTokens(last) < Math.max(50, targetTokens / 3)) {
                windows.set(windows.size() - 2, windows.get(windows.size() - 2) + " " + last);
                windows.remove(windows.size() - 1);
            }
        }
        return windows;
    }

    /** Hard-split a unit that exceeds maxTokens into roughly equal character-based pieces. */
    private List<String> hardSplit(String text) {
        List<String> out = new ArrayList<>();
        int pieces = (estimateTokens(text) / maxTokens) + 1;
        int chunkSize = text.length() / pieces;
        for (int i = 0; i < text.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, text.length());
            out.add(text.substring(i, end).trim());
        }
        return out;
    }

    /** Split long text on sentence terminators; hard-split on commas if still too long. */
    private List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        String[] parts = text.split("(?<=[。！？!?\\.])\\s+");
        for (String p : parts) {
            if (estimateTokens(p) > maxTokens) {
                String[] sub = p.split("(?<=[，,；;、:：])");
                StringBuilder acc = new StringBuilder();
                int accT = 0;
                for (String s : sub) {
                    int st = estimateTokens(s);
                    if (accT + st > maxTokens && acc.length() > 0) {
                        out.add(acc.toString().trim());
                        acc = new StringBuilder();
                        accT = 0;
                    }
                    acc.append(s);
                    accT += st;
                }
                if (acc.length() > 0) out.add(acc.toString().trim());
            } else {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * Estimate token count for a string.
     * Rough heuristic: 1.5 CJK chars ≈ 1 token; 4 ASCII chars ≈ 1 token; other chars ≈ 1 token each.
     */
    public static int estimateTokens(String text) {
        if (text == null) return 0;
        int cjk = 0, ascii = 0, other = 0;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (isCJK(c)) cjk++;
            else if (c < 128) { if (!Character.isWhitespace(c)) ascii++; }
            else other++;
        }
        return (int) Math.ceil(cjk / 1.5) + (ascii / 4) + other;
    }

    private static boolean isCJK(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || ub == Character.UnicodeBlock.HIRAGANA
                || ub == Character.UnicodeBlock.KATAKANA
                || ub == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }

    private static String deterministicChunkId(String documentId, String sectionKey, int idx) {
        try {
            String key = documentId + "::" + StringUtils.defaultString(sectionKey) + "::" + idx;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
    }
}
