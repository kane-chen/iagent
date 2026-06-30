package io.invest.iagent.tools.web;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.invest.iagent.service.web.WebSearchProviderFactory;
import io.invest.iagent.service.web.WebSearchService;
import io.invest.iagent.model.WebSearchResponseDTO;

public class WebSearchTool {

    private final WebSearchService service;

    public WebSearchTool() {
        this(new WebSearchService(
                WebSearchProviderFactory.fromEnv(),
                WebSearchProviderFactory.defaultMaxResults(),
                WebSearchProviderFactory.maxResultsCap()
        ));
    }

    public WebSearchTool(WebSearchService service) {
        this.service = service;
    }

    @Tool(name = "web_search", description = "搜索互联网，获取当前或外部信息。适用于近期事件、新闻、赛程、价格、政策、网页信息等不在上下文中的问题。")
    public WebSearchResponseDTO search(
            @ToolParam(name = "query", description = "搜索关键词或问题") String query,
            @ToolParam(name = "max_results", required = false, description = "返回结果数量，默认5，最大值由配置限制") Integer maxResults,
            @ToolParam(name = "country", required = false, description = "可选国家代码，例如 US、CN、HK") String country,
            @ToolParam(name = "search_language", required = false, description = "可选搜索语言，例如 en、zh") String searchLanguage,
            @ToolParam(name = "freshness", required = false, description = "可选时间范围，例如 day、week、month、year") String freshness,
            @ToolParam(name = "allowed_domains", required = false, description = "可选域名白名单，逗号分隔，例如 sec.gov,apple.com") String allowedDomains,
            @ToolParam(name = "blocked_domains", required = false, description = "可选域名黑名单，逗号分隔") String blockedDomains
    ) {
        return service.search(query, maxResults, country, searchLanguage, freshness, allowedDomains, blockedDomains);
    }
}
