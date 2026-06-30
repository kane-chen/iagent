package io.invest.iagent.service.filing.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.model.FinanceQueryParam;
import io.invest.iagent.model.FinancialIndexValueDTO;
import io.invest.iagent.utils.TextUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SecXbrlMetricExtractor {

    private static final String SOURCE_LOCAL_XBRL = "LOCAL_XBRL_LIMITED:";
    private static final String SOURCE_LOCAL_6K_HTML = "LOCAL_6K_HTML:";

    private final Path workspace;
    private final SecFilingDataUtil secFilingDataUtil;

    public SecXbrlMetricExtractor(Path workspace, SecFilingDataUtil secFilingDataUtil) {
        this.workspace = workspace;
        this.secFilingDataUtil = secFilingDataUtil;
    }

    public List<FinancialIndexValueDTO> extract(String ticker, FinanceQueryParam query) throws IOException {
        String normalizedTicker = ticker.toUpperCase(Locale.ROOT);
        Map<String, Pair<String, String>> dict = secFilingDataUtil.buildTickerDict(normalizedTicker, null);
        if(CollectionUtils.isEmpty(dict)){
            return List.of();
        }
        Set<String> requestedConcepts = requestedConcepts(query, dict);
        if(requestedConcepts.isEmpty()){
            return List.of();
        }
        Path filingsDir = WorkspacePaths.filingsDir(workspace, normalizedTicker);
        if(!Files.isDirectory(filingsDir)){
            return List.of();
        }
        List<FinancialIndexValueDTO> values = new ArrayList<>();
        try(var stream = Files.list(filingsDir)){
            for(Path filingDir : stream.filter(Files::isDirectory).toList()){
                values.addAll(extractFiling(normalizedTicker, filingDir, query, dict, requestedConcepts));
            }
        }
        return SecFilingFilterUtil.dedupe(values);
    }

    private Set<String> requestedConcepts(FinanceQueryParam query, Map<String, Pair<String, String>> dict){
        Set<String> concepts = new HashSet<>();
        dict.forEach((concept,pair)->{
            if(Objects.nonNull(pair) && TextUtils.contains(query.getIndexCodes(), pair.getLeft())){
                concepts.add(concept);
            }
        });
        return concepts;
    }

    private List<FinancialIndexValueDTO> extractFiling(String ticker, Path filingDir, FinanceQueryParam query,
                                                       Map<String, Pair<String, String>> dict,
                                                       Set<String> requestedConcepts) throws IOException {
        Path metaFile = filingDir.resolve("meta.json");
        if(!Files.exists(metaFile)){
            return List.of();
        }
        JSONObject meta = JSON.parseObject(Files.readString(metaFile));
        if(!isActiveCompleteFiling(meta)){
            return List.of();
        }
        String documentId = StringUtils.defaultIfBlank(meta.getString("document_id"), filingDir.getFileName().toString());
        String formType = meta.getString("form_type");
        List<FinancialIndexValueDTO> values = new ArrayList<>();
        if(isXbrlFiling(meta, formType)){
            for(Path file : xbrlFiles(filingDir, meta)){
                values.addAll(extractXbrlFile(ticker, file, query, dict, requestedConcepts, documentId, formType, meta));
            }
        }
        if(StringUtils.equalsIgnoreCase("6-K", formType)){
            for(Path file : sixKHtmlFiles(filingDir, meta)){
                values.addAll(extractSixKHtmlFile(ticker, file, query, documentId, formType, meta));
            }
        }
        return values;
    }

    private boolean isActiveCompleteFiling(JSONObject meta){
        if(Boolean.FALSE.equals(meta.getBoolean("ingest_complete"))){
            return false;
        }
        return !meta.getBooleanValue("is_deleted") && !meta.getBooleanValue("deleted");
    }

    private boolean isXbrlFiling(JSONObject meta, String formType){
        return meta.getBooleanValue("has_xbrl")
                && StringUtils.equalsAnyIgnoreCase(formType,
                "10-K", "10-Q", "20-F", "40-F", "10-K/A", "10-Q/A", "20-F/A", "40-F/A");
    }

    private List<Path> xbrlFiles(Path filingDir, JSONObject meta){
        List<Path> instanceFiles = new ArrayList<>();
        List<Path> inlineFiles = new ArrayList<>();
        for(String name : metaFileNames(meta)){
            String lower = StringUtils.lowerCase(name);
            if(isSupportFile(lower)){
                continue;
            }
            Path path = filingDir.resolve(name);
            if(StringUtils.endsWith(lower,"_htm.xml")){
                instanceFiles.add(path);
            }else if(StringUtils.endsWithAny(lower,".htm", ".html") && looksLikeInlineXbrl(path)){
                inlineFiles.add(path);
            }
        }
        List<Path> instances = distinctExisting(instanceFiles);
        if(!instances.isEmpty()){
            return instances;
        }
        return distinctExisting(inlineFiles);
    }

    private List<Path> sixKHtmlFiles(Path filingDir, JSONObject meta){
        List<Path> exhibits = new ArrayList<>();
        List<Path> htmlFiles = new ArrayList<>();
        for(String name : metaFileNames(meta)){
            String lower = StringUtils.lowerCase(name);
            if(!StringUtils.endsWithAny(lower,".htm", ".html")){
                continue;
            }
            Path path = filingDir.resolve(name);
            if(StringUtils.containsAny(lower,"ex99", "ex-99", "exhibit99")){
                exhibits.add(path);
            }else{
                htmlFiles.add(path);
            }
        }
        List<Path> result = new ArrayList<>();
        result.addAll(exhibits);
        result.addAll(htmlFiles);
        return distinctExisting(result);
    }

    private List<String> metaFileNames(JSONObject meta){
        List<String> names = new ArrayList<>();
        JSONArray files = meta.getJSONArray("files");
        if(Objects.nonNull(files)){
            for(int i=0;i<files.size();i++){
                JSONObject item = files.getJSONObject(i);
                String name = item.getString("name");
                if(StringUtils.isNotBlank(name)){
                    names.add(name);
                }
            }
        }
        String primaryDocument = meta.getString("primary_document");
        if(StringUtils.isNotBlank(primaryDocument)){
            names.add(primaryDocument);
        }
        return names;
    }

    private boolean isSupportFile(String lowerName){
        return StringUtils.equalsAny(lowerName,"filingsummary.xml", "meta.json", "filing_manifest.json")
                || StringUtils.endsWithAny(lowerName,"_cal.xml", "_def.xml", "_lab.xml", "_pre.xml", ".xsd", ".pdf");
    }

    private boolean looksLikeInlineXbrl(Path file){
        try{
            String content = Files.readString(file);
            return StringUtils.containsAnyIgnoreCase(content, "ix:nonFraction", "ix:nonNumeric", "inlineXBRL");
        }catch (IOException e){
            return false;
        }
    }

    private List<Path> distinctExisting(List<Path> files){
        LinkedHashSet<Path> distinct = new LinkedHashSet<>();
        files.stream().filter(Files::exists).forEach(distinct::add);
        return new ArrayList<>(distinct);
    }

    private List<FinancialIndexValueDTO> extractXbrlFile(String ticker, Path file, FinanceQueryParam query,
                                                         Map<String, Pair<String, String>> dict,
                                                         Set<String> requestedConcepts,
                                                         String documentId, String formType, JSONObject meta) throws IOException {
        Document document = Jsoup.parse(Files.readString(file), "", Parser.xmlParser());
        FiscalFocus focus = fiscalFocus(document, meta, formType);
        if(!TextUtils.contains(query.getFiscalYears(), String.valueOf(focus.fiscalYear))
                || !TextUtils.contains(query.getFiscalPeriods(), focus.fiscalPeriod)){
            return List.of();
        }
        Map<String, ContextPeriod> contexts = contexts(document);
        Map<String, String> units = units(document);
        List<FinancialIndexValueDTO> result = new ArrayList<>();
        for(Element element : document.getAllElements()){
            FactCandidate fact = factCandidate(element, requestedConcepts);
            if(Objects.isNull(fact)){
                continue;
            }
            Pair<String,String> pair = dict.get(fact.concept);
            if(Objects.isNull(pair)){
                continue;
            }
            ContextPeriod context = contexts.get(fact.contextRef);
            if(Objects.isNull(context) || context.segmented){
                continue;
            }
            if(!SecFilingFilterUtil.durationMatches(context.start, context.end, focus.fiscalPeriod, pair.getLeft())){
                continue;
            }
            String value = parseNumber(element.text(), fact.inlineFact ? attrIgnoreCase(element,"scale") : null, attrIgnoreCase(element,"sign"));
            if(StringUtils.isBlank(value)){
                continue;
            }
            String unitRef = attrIgnoreCase(element,"unitRef");
            String unit = Optional.ofNullable(units.get(unitRef)).orElse(unitRef);
            result.add(FinancialIndexValueDTO.builder()
                    .ticker(ticker)
                    .tableType(formType)
                    .index(pair.getLeft())
                    .value(value)
                    .currency(unit)
                    .units(unit)
                    .period(null)
                    .date(meta.getString("filing_date"))
                    .fiscalYear(focus.fiscalYear)
                    .fiscalPeriod(focus.fiscalPeriod)
                    .startDate(context.start)
                    .endDate(context.end)
                    .source(SOURCE_LOCAL_XBRL + documentId)
                    .build());
        }
        return result;
    }

    private FactCandidate factCandidate(Element element, Set<String> requestedConcepts){
        if(StringUtils.equalsAnyIgnoreCase(attrIgnoreCase(element,"xsi:nil"),"true","1")){
            return null;
        }
        String tag = StringUtils.lowerCase(element.tagName());
        if(StringUtils.endsWith(tag, "nonfraction") || StringUtils.endsWith(tag, "nonnumeric")){
            String concept = normalizeConcept(attrIgnoreCase(element,"name"));
            if(!requestedConcepts.contains(concept)){
                return null;
            }
            String contextRef = attrIgnoreCase(element,"contextRef");
            if(StringUtils.isBlank(contextRef)){
                return null;
            }
            return new FactCandidate(concept, contextRef, true);
        }
        String contextRef = attrIgnoreCase(element,"contextRef");
        if(StringUtils.isBlank(contextRef)){
            return null;
        }
        String concept = normalizeConcept(element.tagName());
        if(!requestedConcepts.contains(concept)){
            return null;
        }
        return new FactCandidate(concept, contextRef, false);
    }

    private FiscalFocus fiscalFocus(Document document, JSONObject meta, String formType){
        Integer fiscalYear = null;
        String fiscalPeriod = null;
        for(Element element : document.getAllElements()){
            String concept = normalizeConcept(element.tagName());
            if(StringUtils.equalsIgnoreCase(concept,"DocumentFiscalYearFocus")){
                try{
                    fiscalYear = Integer.parseInt(element.text().trim());
                }catch (Exception ignored){
                    // fall through to metadata
                }
            }else if(StringUtils.equalsIgnoreCase(concept,"DocumentFiscalPeriodFocus")){
                fiscalPeriod = StringUtils.trimToNull(element.text());
            }
        }
        if(Objects.isNull(fiscalYear)){
            fiscalYear = meta.getInteger("fiscal_year");
        }
        fiscalPeriod = StringUtils.firstNonBlank(fiscalPeriod, meta.getString("fiscal_period"), inferFiscalPeriod(formType, meta.getString("report_date")));
        return new FiscalFocus(fiscalYear, fiscalPeriod);
    }

    private Map<String, ContextPeriod> contexts(Document document){
        Map<String, ContextPeriod> contexts = new HashMap<>();
        for(Element element : document.getAllElements()){
            if(!StringUtils.endsWithIgnoreCase(element.tagName(), "context")){
                continue;
            }
            String id = attrIgnoreCase(element,"id");
            if(StringUtils.isBlank(id)){
                continue;
            }
            ContextPeriod period = new ContextPeriod();
            period.segmented = element.getAllElements().stream().anyMatch(child -> StringUtils.endsWithIgnoreCase(child.tagName(), "segment"));
            for(Element child : element.getAllElements()){
                String tag = child.tagName();
                if(StringUtils.endsWithIgnoreCase(tag, "startDate")){
                    period.start = child.text();
                }else if(StringUtils.endsWithIgnoreCase(tag, "endDate") || StringUtils.endsWithIgnoreCase(tag, "instant")){
                    period.end = child.text();
                }
            }
            contexts.put(id, period);
        }
        return contexts;
    }

    private Map<String, String> units(Document document){
        Map<String, String> units = new HashMap<>();
        for(Element element : document.getAllElements()){
            if(!StringUtils.endsWithIgnoreCase(element.tagName(), "unit")){
                continue;
            }
            String id = attrIgnoreCase(element,"id");
            String measure = null;
            for(Element child : element.getAllElements()){
                if(StringUtils.endsWithIgnoreCase(child.tagName(), "measure")){
                    measure = normalizeConcept(child.text()).toUpperCase(Locale.ROOT);
                    break;
                }
            }
            if(StringUtils.isNotBlank(id)){
                units.put(id, StringUtils.firstNonBlank(measure, id));
            }
        }
        return units;
    }

    private List<FinancialIndexValueDTO> extractSixKHtmlFile(String ticker, Path file, FinanceQueryParam query,
                                                             String documentId, String formType, JSONObject meta) throws IOException {
        String html = Files.readString(file);
        if(!looksLikeFinancialSixK(html)){
            return List.of();
        }
        Document document = Jsoup.parse(html);
        List<FinancialIndexValueDTO> tableValues = extractSixKIncomeStatementTable(ticker, document, query, documentId, formType, meta);
        if(!tableValues.isEmpty()){
            return tableValues;
        }
        return extractSixKNarrativeValues(ticker, document, query, documentId, formType, meta);
    }

    private boolean looksLikeFinancialSixK(String html){
        String text = normalizeText(Jsoup.parse(html).text()).toLowerCase(Locale.ROOT);
        return StringUtils.contains(text, "three months ended")
                || StringUtils.contains(text, "financial results")
                || StringUtils.contains(text, "quarterly results")
                || (StringUtils.contains(text, "total revenues") && StringUtils.contains(text, "net income"));
    }

    private List<FinancialIndexValueDTO> extractSixKIncomeStatementTable(String ticker, Document document, FinanceQueryParam query,
                                                                         String documentId, String formType, JSONObject meta){
        for(Element table : document.select("table")){
            String tableText = normalizeText(table.text());
            if(!StringUtils.containsIgnoreCase(tableText,"For the three months ended")
                    || !StringUtils.containsIgnoreCase(tableText,"Revenues")
                    || !StringUtils.containsIgnoreCase(tableText,"Net income")){
                continue;
            }
            PeriodInfo period = periodInfo(tableText, meta);
            if(Objects.isNull(period) || !periodMatches(query, period)){
                continue;
            }
            long multiplier = 1000L;
            Map<String, FinancialIndexValueDTO> values = new HashMap<>();
            for(Element row : table.select("tr")){
                List<Element> cells = row.children().stream()
                        .filter(e -> StringUtils.equalsAnyIgnoreCase(e.tagName(),"td","th"))
                        .toList();
                if(cells.isEmpty()){
                    continue;
                }
                String metric = metricFromLabel(cells.get(0).text());
                if(StringUtils.isBlank(metric) || !TextUtils.contains(query.getIndexCodes(), metric)){
                    continue;
                }
                List<BigDecimal> numbers = rowNumbers(cells);
                if(numbers.size() < 2){
                    continue;
                }
                BigDecimal rawValue = numbers.get(1).abs().multiply(BigDecimal.valueOf(multiplier));
                values.put(metric, dto(ticker, formType, metric, rawValue, "CNY", period, meta.getString("filing_date"), SOURCE_LOCAL_6K_HTML + documentId));
            }
            return new ArrayList<>(values.values());
        }
        return List.of();
    }

    private List<FinancialIndexValueDTO> extractSixKNarrativeValues(String ticker, Document document, FinanceQueryParam query,
                                                                    String documentId, String formType, JSONObject meta){
        String text = normalizeText(document.text());
        PeriodInfo period = periodInfo(text, meta);
        if(Objects.isNull(period) || !periodMatches(query, period)){
            return List.of();
        }
        Map<String, Pattern> patterns = Map.of(
                "Revenue", Pattern.compile("Total revenues\\s+were\\s+RMB([0-9,]+(?:\\.[0-9]+)?)\\s+million", Pattern.CASE_INSENSITIVE),
                "CostOfRevenue", Pattern.compile("Total costs? of revenues\\s+were\\s+RMB([0-9,]+(?:\\.[0-9]+)?)\\s+million", Pattern.CASE_INSENSITIVE),
                "OperatingExpenses", Pattern.compile("Total operating expenses\\s+were\\s+RMB([0-9,]+(?:\\.[0-9]+)?)\\s+million", Pattern.CASE_INSENSITIVE),
                "OperatingIncomeLoss", Pattern.compile("Operating profit\\s+in the quarter was\\s+RMB([0-9,]+(?:\\.[0-9]+)?)\\s+million", Pattern.CASE_INSENSITIVE),
                "NetIncomeLoss", Pattern.compile("Net income attributable to ordinary shareholders\\s+in the quarter was\\s+RMB([0-9,]+(?:\\.[0-9]+)?)\\s+million", Pattern.CASE_INSENSITIVE)
        );
        List<FinancialIndexValueDTO> values = new ArrayList<>();
        patterns.forEach((metric, pattern)->{
            if(!TextUtils.contains(query.getIndexCodes(), metric)){
                return;
            }
            Matcher matcher = pattern.matcher(text);
            if(matcher.find()){
                BigDecimal value = new BigDecimal(matcher.group(1).replace(",", "")).multiply(BigDecimal.valueOf(1_000_000L));
                values.add(dto(ticker, formType, metric, value, "CNY", period, meta.getString("filing_date"), SOURCE_LOCAL_6K_HTML + documentId));
            }
        });
        return values;
    }

    private List<BigDecimal> rowNumbers(List<Element> cells){
        List<BigDecimal> numbers = new ArrayList<>();
        for(int i=1;i<cells.size();i++){
            String text = normalizeText(cells.get(i).text());
            if(StringUtils.isBlank(text) || StringUtils.equals(text,"&nbsp;")){
                continue;
            }
            boolean negative = text.startsWith("(");
            String cleaned = text.replace(",", "").replace("(", "").replace(")", "").trim();
            if(!cleaned.matches("-?\\d+(\\.\\d+)?")){
                continue;
            }
            if(i + 1 < cells.size() && StringUtils.equals(normalizeText(cells.get(i + 1).text()), ")")){
                negative = true;
            }
            BigDecimal value = new BigDecimal(cleaned);
            numbers.add(negative ? value.negate() : value);
        }
        return numbers;
    }

    private String metricFromLabel(String label){
        String normalized = normalizeText(label).toLowerCase(Locale.ROOT);
        if(StringUtils.equalsAny(normalized,"revenues", "total revenues")){
            return "Revenue";
        }
        if(StringUtils.equalsAny(normalized,"cost of revenues", "costs of revenues", "total costs of revenues", "total cost of revenues")){
            return "CostOfRevenue";
        }
        if(StringUtils.equals(normalized,"total operating expenses")){
            return "OperatingExpenses";
        }
        if(StringUtils.equalsAny(normalized,"operating profit", "operating income", "operating income (loss)", "operating profit (loss)")){
            return "OperatingIncomeLoss";
        }
        if(StringUtils.equalsAny(normalized,"net income", "net income attributable to ordinary shareholders")){
            return "NetIncomeLoss";
        }
        return null;
    }

    private PeriodInfo periodInfo(String text, JSONObject meta){
        String normalized = StringUtils.defaultString(text).replace(' ',' ');
        String monthName = null;
        Matcher monthMatcher = Pattern.compile("three months ended\\s+([A-Za-z]+)\\s+([0-9]{1,2})", Pattern.CASE_INSENSITIVE).matcher(normalized);
        if(monthMatcher.find()){
            monthName = monthMatcher.group(1);
        }else{
            Matcher quarterMatcher = Pattern.compile("(First|Second|Third|Fourth) Quarter\\s+([0-9]{4})", Pattern.CASE_INSENSITIVE).matcher(normalized);
            if(quarterMatcher.find()){
                int quarter = switch (quarterMatcher.group(1).toLowerCase(Locale.ROOT)){
                    case "first" -> 1;
                    case "second" -> 2;
                    case "third" -> 3;
                    default -> 4;
                };
                return periodInfo(quarter, Integer.parseInt(quarterMatcher.group(2)));
            }
        }
        if(StringUtils.isBlank(monthName)){
            return null;
        }
        Integer fiscalYear = meta.getInteger("fiscal_year");
        if(Objects.isNull(fiscalYear)){
            return null;
        }
        int month = monthNumber(monthName);
        if(month <= 0){
            return null;
        }
        int quarter = ((month - 1) / 3) + 1;
        return periodInfo(quarter, fiscalYear);
    }

    private PeriodInfo periodInfo(int quarter, int year){
        int endMonth = quarter * 3;
        YearMonth endYm = YearMonth.of(year, endMonth);
        LocalDate end = endYm.atEndOfMonth();
        LocalDate start = LocalDate.of(year, endMonth - 2, 1);
        return new PeriodInfo(year, "Q" + quarter, start.toString(), end.toString());
    }

    private int monthNumber(String monthName){
        return switch (monthName.toLowerCase(Locale.ROOT)){
            case "january" -> 1;
            case "february" -> 2;
            case "march" -> 3;
            case "april" -> 4;
            case "may" -> 5;
            case "june" -> 6;
            case "july" -> 7;
            case "august" -> 8;
            case "september" -> 9;
            case "october" -> 10;
            case "november" -> 11;
            case "december" -> 12;
            default -> -1;
        };
    }

    private boolean periodMatches(FinanceQueryParam query, PeriodInfo period){
        return TextUtils.contains(query.getFiscalYears(), String.valueOf(period.fiscalYear))
                && TextUtils.contains(query.getFiscalPeriods(), period.fiscalPeriod);
    }

    private FinancialIndexValueDTO dto(String ticker, String formType, String metric, BigDecimal value, String unit,
                                       PeriodInfo period, String filingDate, String source){
        return FinancialIndexValueDTO.builder()
                .ticker(ticker)
                .tableType(formType)
                .index(metric)
                .value(value.stripTrailingZeros().toPlainString())
                .currency(unit)
                .units(unit)
                .date(filingDate)
                .fiscalYear(period.fiscalYear)
                .fiscalPeriod(period.fiscalPeriod)
                .startDate(period.startDate)
                .endDate(period.endDate)
                .source(source)
                .build();
    }

    private String normalizeConcept(String value){
        if(StringUtils.isBlank(value)){
            return value;
        }
        int index = value.indexOf(':');
        return index >= 0 ? value.substring(index + 1) : value;
    }

    private String parseNumber(String text, String scaleText, String sign){
        if(StringUtils.isBlank(text)){
            return null;
        }
        String normalized = text.replace(",", "")
                .replace("$", "")
                .replace(" ", "")
                .trim();
        boolean negative = normalized.startsWith("(") && normalized.endsWith(")");
        normalized = normalized.replace("(", "").replace(")", "");
        if(StringUtils.isBlank(normalized) || StringUtils.equals(normalized,"—")){
            return null;
        }
        try{
            BigDecimal value = new BigDecimal(normalized);
            if(StringUtils.isNotBlank(scaleText)){
                value = value.scaleByPowerOfTen(Integer.parseInt(scaleText));
            }
            if(negative || StringUtils.equals(sign,"-")){
                value = value.negate();
            }
            return value.stripTrailingZeros().toPlainString();
        }catch (Exception e){
            return null;
        }
    }

    private String inferFiscalPeriod(String formType, String reportDate){
        if(StringUtils.equalsAnyIgnoreCase(formType,"10-K","20-F","40-F")){
            return "FY";
        }
        if(!StringUtils.equalsIgnoreCase(formType,"10-Q") || StringUtils.isBlank(reportDate)){
            return null;
        }
        try{
            int month = LocalDate.parse(reportDate).getMonthValue();
            if(month <= 3){
                return "Q1";
            }else if(month <= 6){
                return "Q2";
            }else if(month <= 9){
                return "Q3";
            }
            return "Q4";
        }catch (Exception e){
            return null;
        }
    }

    private String attrIgnoreCase(Element element, String name){
        String value = element.attr(name);
        if(StringUtils.isNotBlank(value)){
            return value;
        }
        for(Attribute attribute : element.attributes()){
            if(StringUtils.equalsIgnoreCase(attribute.getKey(), name)){
                return attribute.getValue();
            }
        }
        return null;
    }

    private String normalizeText(String text){
        return StringUtils.defaultString(text).replace(' ',' ').replaceAll("\\s+", " ").trim();
    }

    private record FactCandidate(String concept, String contextRef, boolean inlineFact) {}
    private record FiscalFocus(Integer fiscalYear, String fiscalPeriod) {}
    private record PeriodInfo(Integer fiscalYear, String fiscalPeriod, String startDate, String endDate) {}

    private static class ContextPeriod{
        private String start;
        private String end;
        private boolean segmented;
    }
}
