package io.invest.iagent.service.kb.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public class FilingSourceSelector {

    public List<Path> selectSourceFiles(Path filingDir, JSONObject meta){
        List<Path> primary = new ArrayList<>();
        List<Path> exhibits = new ArrayList<>();
        List<Path> pdfs = new ArrayList<>();
        for(String name : fileNames(meta)){
            String lower = StringUtils.lowerCase(name);
            if(skip(lower)){
                continue;
            }
            Path path = filingDir.resolve(name);
            if(!Files.exists(path)){
                continue;
            }
            if(StringUtils.endsWithAny(lower, ".htm", ".html")){
                if(StringUtils.containsAny(lower, "ex99", "ex-99", "exhibit99")){
                    exhibits.add(path);
                }else{
                    primary.add(path);
                }
            }else if(StringUtils.endsWith(lower, ".pdf")){
                pdfs.add(path);
            }
        }
        List<Path> result = new ArrayList<>();
        if(StringUtils.equalsIgnoreCase(meta.getString("form_type"), "6-K")){
            result.addAll(exhibits);
            result.addAll(primary);
        }else{
            result.addAll(primary);
            result.addAll(exhibits);
        }
        result.addAll(pdfs);
        return new ArrayList<>(new LinkedHashSet<>(result));
    }

    private List<String> fileNames(JSONObject meta){
        List<String> names = new ArrayList<>();
        String primaryDocument = meta.getString("primary_document");
        if(StringUtils.isNotBlank(primaryDocument)){
            names.add(primaryDocument);
        }
        JSONArray files = meta.getJSONArray("files");
        if(Objects.nonNull(files)){
            for(int i=0;i<files.size();i++){
                String name = files.getJSONObject(i).getString("name");
                if(StringUtils.isNotBlank(name)){
                    names.add(name);
                }
            }
        }
        return names;
    }

    private boolean skip(String lowerName){
        return StringUtils.equalsAny(lowerName, "meta.json", "filing_manifest.json", "filingsummary.xml")
                || StringUtils.endsWithAny(lowerName,
                "_htm.xml", "_cal.xml", "_def.xml", "_lab.xml", "_pre.xml", ".xsd",
                ".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".zip", ".xlsx", ".xls", ".doc", ".docx");
    }
}
