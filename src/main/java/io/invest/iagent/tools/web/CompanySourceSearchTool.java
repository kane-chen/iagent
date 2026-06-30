package io.invest.iagent.tools.web;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.invest.iagent.model.WebSearchResponseDTO;
import io.invest.iagent.model.WebSearchResultDTO;
import io.invest.iagent.service.web.WebSearchProviderFactory;
import io.invest.iagent.service.web.WebSearchService;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

public class CompanySourceSearchTool {

    private static final String DEFAULT_MEDIA_DOMAINS = "reuters.com,bloomberg.com,ft.com,wsj.com,cnbc.com,nikkei.com,caixin.com,yicai.com,cls.cn,stcn.com";

    private final WebSearchService service;
    private final String mediaDomains;
    private final String brokerDomains;

    public CompanySourceSearchTool() {
        this(new WebSearchService(WebSearchProviderFactory.fromEnv(), WebSearchProviderFactory.defaultMaxResults(), WebSearchProviderFactory.maxResultsCap()),
                StringUtils.defaultIfBlank(System.getenv("SOURCE_MEDIA_ALLOWED_DOMAINS"), DEFAULT_MEDIA_DOMAINS),
                StringUtils.defaultString(System.getenv("SOURCE_BROKER_ALLOWED_DOMAINS")));
    }

    public CompanySourceSearchTool(WebSearchService service, String mediaDomains, String brokerDomains) {
        this.service = service;
        this.mediaDomains = mediaDomains;
        this.brokerDomains = brokerDomains;
    }

    @Tool(name = "search_media_reports", description = "搜索知名媒体报道，返回可标注为 external media report 的来源；只保留配置白名单域名。")
    public WebSearchResponseDTO searchMediaReports(
            @ToolParam(name = "query", description = "搜索关键词或问题") String query,
            @ToolParam(name = "max_results", required = false, description = "返回结果数量，默认5") Integer maxResults,
            @ToolParam(name = "country", required = false, description = "可选国家代码，例如 US、CN、HK") String country,
            @ToolParam(name = "search_language", required = false, description = "可选搜索语言，例如 en、zh") String searchLanguage,
            @ToolParam(name = "freshness", required = false, description = "可选时间范围，例如 day、week、month、year") String freshness
    ) {
        return withSourceClass(service.search(query, maxResults, country, searchLanguage, freshness, mediaDomains, null), "external media report");
    }

    @Tool(name = "search_broker_research", description = "搜索可识别券商研报来源，返回可标注为 broker research 的来源；未配置券商白名单时fail-closed。")
    public WebSearchResponseDTO searchBrokerResearch(
            @ToolParam(name = "query", description = "搜索关键词或问题") String query,
            @ToolParam(name = "max_results", required = false, description = "返回结果数量，默认5") Integer maxResults,
            @ToolParam(name = "country", required = false, description = "可选国家代码，例如 US、CN、HK") String country,
            @ToolParam(name = "search_language", required = false, description = "可选搜索语言，例如 en、zh") String searchLanguage,
            @ToolParam(name = "freshness", required = false, description = "可选时间范围，例如 day、week、month、year") String freshness
    ) {
        if(StringUtils.isBlank(brokerDomains)){
            return WebSearchResponseDTO.builder()
                    .success(true)
                    .query(query)
                    .provider("source-policy")
                    .resultCount(0)
                    .results(List.of())
                    .metadata(Map.of("source_class", "broker research", "fail_closed", true,
                            "message", "未配置SOURCE_BROKER_ALLOWED_DOMAINS，未找到可核验的券商研究报告来源"))
                    .build();
        }
        return withSourceClass(service.search(query, maxResults, country, searchLanguage, freshness, brokerDomains, null), "broker research");
    }

    private WebSearchResponseDTO withSourceClass(WebSearchResponseDTO response, String sourceClass) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if(response.getMetadata() != null){
            metadata.putAll(response.getMetadata());
        }
        metadata.put("source_class", sourceClass);
        List<WebSearchResultDTO> results = response.getResults() == null ? List.of() : response.getResults();
        return WebSearchResponseDTO.builder()
                .success(response.isSuccess())
                .query(response.getQuery())
                .provider(response.getProvider())
                .resultCount(results.size())
                .results(results)
                .error(response.getError())
                .metadata(metadata)
                .build();
    }
}
