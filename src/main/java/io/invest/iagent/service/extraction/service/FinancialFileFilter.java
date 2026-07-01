package io.invest.iagent.service.extraction.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.collect.Lists;
import io.invest.iagent.service.filing.util.WorkspacePaths;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public class FinancialFileFilter {

    private final Path workspace;

    public FinancialFileFilter(Path workspace) {
        this.workspace = workspace;
    }

    public List<Path> filter(String ticker,String fiscalYearStart,String fiscalYearEnd) throws IOException {
        String normalizedTicker = ticker.toUpperCase(Locale.ROOT);
        Path filingsDir = WorkspacePaths.filingsDir(workspace, normalizedTicker);
        if(!Files.isDirectory(filingsDir)){
            return List.of();
        }
        List<Path> values = new ArrayList<>();
        try(Stream<Path> stream = Files.list(filingsDir)){
            for(Path filingDir : stream.filter(Files::isDirectory).toList()){
                values.addAll(doFilter(filingDir, fiscalYearStart,fiscalYearEnd));
            }
        }
        return values ;
    }


    private List<Path> doFilter(Path filingDir,String fiscalYearStart,String fiscalYearEnd) throws IOException {
        Path metaFile = filingDir.resolve("meta.json");
        if(!Files.exists(metaFile)){
            return List.of();
        }
        JSONObject meta = JSON.parseObject(Files.readString(metaFile));
        if(!isActiveCompleteFiling(meta)){
            return List.of();
        }
        if(!within(meta, fiscalYearStart, fiscalYearEnd)){
            return List.of() ;
        }
        String formType = meta.getString("form_type");
        // 港股格式兼容
        if(StringUtils.isBlank(formType)){
            formType = meta.getString("formType");
        }
        List<Path> values = new ArrayList<>();
        // 10-K \ 10-Q
        if(isXbrlFiling(meta, formType)){
            values.addAll(xbrlFiles(filingDir, meta));
        }
        // 6-K
        if(StringUtils.equalsIgnoreCase("6-K", formType)){
            for(Path file : sixKHtmlFiles(filingDir, meta)){
                String html = Files.readString(file);
                if(!looksLikeFinancialSixK(html)){
                    continue ;
                }
                values.add( file);
            }
        }
        // PDF财报（港股等）
        values.addAll(pdfFiles(filingDir, meta));
        return values;
    }

    private boolean within(JSONObject meta, String fiscalYearStart, String fiscalYearEnd){
        String theYear = meta.getString("fiscal_year");
        if(StringUtils.isBlank(theYear)){
            theYear = meta.getString("fiscalYear");
        }
        if(StringUtils.isBlank(theYear)){
            return false ;
        }
        if(StringUtils.isNotBlank(fiscalYearStart) && fiscalYearStart.compareTo(theYear) > 0) {
            return false ;
        }
        if(StringUtils.isNotBlank(fiscalYearEnd) && fiscalYearEnd.compareTo(theYear) < 0){
            return false ;
        }
        return true ;
    }

    private boolean isActiveCompleteFiling(JSONObject meta){
        // 港股财报没有ingest_complete字段，默认视为已完成
        if(!meta.containsKey("ingest_complete")){
            // 检查港股的deleted标志（如果有）
            return !meta.getBooleanValue("is_deleted") && !meta.getBooleanValue("deleted");
        }
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

    private boolean isHkFiling(JSONObject meta, String formType){
        // 港股财报通常是PDF格式，通过form_type或source判断
        boolean isHk = StringUtils.equalsIgnoreCase(meta.getString("source"), "hkex") ||
               StringUtils.containsIgnoreCase(formType, "HK") ||
               StringUtils.containsIgnoreCase(formType, "FY") ||
               StringUtils.containsIgnoreCase(formType, "H1") ||
               StringUtils.containsIgnoreCase(formType, "ANN");

        // 检查是否有PDF文件
        if(isHk){
            List<String> fileNames = metaFileNames(meta);
            for(String name : fileNames){
                if(StringUtils.lowerCase(name).endsWith(".pdf")){
                    return true;
                }
            }
        }
        return false;
    }

    private List<Path> xbrlFiles(Path filingDir, JSONObject meta){
        List<Path> instanceFiles = new ArrayList<>();
        for(String name : metaFileNames(meta)){
            String lower = StringUtils.lowerCase(name);
            if(!StringUtils.endsWithAny(lower,".htm",".html", ".pdf")){
                continue;
            }
            if(lower.contains("ex")){
                continue ;
            }
            Path path = filingDir.resolve(name);
            instanceFiles.add(path);
        }
        List<Path> instances = distinctExisting(instanceFiles);
        return Optional.of(instances).orElse(Lists.newArrayList()) ;
    }

    private List<Path> sixKHtmlFiles(Path filingDir, JSONObject meta){
        List<Path> exhibits = new ArrayList<>();
        List<Path> htmlFiles = new ArrayList<>();
        for(String name : metaFileNames(meta)){
            String lower = StringUtils.lowerCase(name);
            if(!StringUtils.endsWithAny(lower,".htm")){
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

        // SEC/美股格式: files数组
        JSONArray files = meta.getJSONArray("files");
        if(Objects.nonNull(files)){
            for(int i=0;i<files.size();i++){
                String name = files.getString(i);
                if(StringUtils.isNotBlank(name)){
                    names.add(name);
                }
            }
        }

        // SEC格式: primary_document
        String primaryDocument = meta.getString("primary_document");
        if(StringUtils.isNotBlank(primaryDocument)){
            names.add(primaryDocument);
        }

        // 港股格式: primaryFile对象
        JSONObject primaryFile = meta.getJSONObject("primaryFile");
        if(primaryFile != null){
            String name = primaryFile.getString("name");
            if(StringUtils.isNotBlank(name)){
                names.add(name);
            }
        }

        return names;
    }

    private List<Path> distinctExisting(List<Path> files){
        LinkedHashSet<Path> distinct = new LinkedHashSet<>();
        files.stream().filter(Files::exists).forEach(distinct::add);
        return new ArrayList<>(distinct);
    }

    /**
     * 获取目录中的PDF文件（港股财报通常是PDF格式）
     */
    private List<Path> pdfFiles(Path filingDir, JSONObject meta){
        List<Path> pdfFiles = new ArrayList<>();
        for(String name : metaFileNames(meta)){
            String lower = StringUtils.lowerCase(name);
            if(lower.endsWith(".pdf")){
                Path path = filingDir.resolve(name);
                pdfFiles.add(path);
            }
        }
        return distinctExisting(pdfFiles);
    }

    private boolean looksLikeFinancialSixK(String html){
        String text = normalizeText(Jsoup.parse(html).text()).toLowerCase(Locale.ROOT);
        return StringUtils.contains(text, "three months ended")
                || StringUtils.contains(text, "financial results")
                || StringUtils.contains(text, "quarterly results")
                || (StringUtils.contains(text, "total revenues") && StringUtils.contains(text, "net income"));
    }

    private String normalizeText(String text){
        return StringUtils.defaultString(text).replace(' ',' ').replaceAll("\\s+", " ").trim();
    }
}
