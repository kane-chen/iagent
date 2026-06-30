package io.invest.iagent.service.web;

import io.invest.iagent.model.WebSearchResponseDTO;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

public final class WebSearchProviderFactory {

    private WebSearchProviderFactory() {}

    public static WebSearchProvider fromEnv(){
        String provider = StringUtils.defaultIfBlank(System.getenv("WEB_SEARCH_PROVIDER"), "brave");
        if(StringUtils.equalsIgnoreCase(provider, "brave")){
            return new BraveWebSearchProvider(
                    System.getenv("BRAVE_SEARCH_API_KEY"),
                    parseInt(System.getenv("WEB_SEARCH_TIMEOUT_SECONDS"), 10),
                    System.getenv("WEB_SEARCH_COUNTRY"),
                    System.getenv("WEB_SEARCH_LANGUAGE"),
                    StringUtils.defaultIfBlank(System.getenv("WEB_SEARCH_SAFE_SEARCH"), "moderate")
            );
        }
        if(StringUtils.equalsIgnoreCase(provider, "tavily")){
            return new TavilyWebSearchProvider(
                    System.getenv("TAVILY_API_KEY"),
                    parseInt(System.getenv("WEB_SEARCH_TIMEOUT_SECONDS"), 10),
                    StringUtils.defaultIfBlank(System.getenv("TAVILY_SEARCH_DEPTH"), "basic"),
                    StringUtils.defaultIfBlank(System.getenv("TAVILY_SEARCH_TOPIC"), "general"),
                    BooleanUtils.toBooleanDefaultIfNull(Boolean.parseBoolean(System.getenv("TAVILY_INCLUDE_ANSWER")), false),
                    BooleanUtils.toBooleanDefaultIfNull(Boolean.parseBoolean(System.getenv("TAVILY_INCLUDE_RAW_CONTENT")), false)
            );
        }
        return new UnsupportedProvider(provider);
    }

    public static int defaultMaxResults(){
        return parseInt(System.getenv("WEB_SEARCH_DEFAULT_MAX_RESULTS"), 5);
    }

    public static int maxResultsCap(){
        return parseInt(System.getenv("WEB_SEARCH_MAX_RESULTS"), 10);
    }

    private static int parseInt(String value, int defaultValue){
        try{
            return StringUtils.isBlank(value) ? defaultValue : Integer.parseInt(value);
        }catch (Exception e){
            return defaultValue;
        }
    }

    private record UnsupportedProvider(String provider) implements WebSearchProvider {
        @Override
        public String name() {
            return provider;
        }

        @Override
        public WebSearchResponseDTO search(WebSearchRequest request) {
            return WebSearchResponseDTO.builder()
                    .success(false)
                    .query(request.getQuery())
                    .provider(provider)
                    .resultCount(0)
                    .results(List.of())
                    .error("Unsupported WEB_SEARCH_PROVIDER: " + provider)
                    .build();
        }
    }
}
