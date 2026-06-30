package io.invest.iagent.service.kb.search;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Search constants and lexicons.
 *
 * This class defines pure data structures and constants for the search subsystem,
 * containing no business logic. Used as shared foundation by SearchEngine and services.
 */
public final class SearchConstants {

    private SearchConstants() {
        // Utility class, prevent instantiation
    }

    // ---------------------------------------------------------------------------
    // Search strategy constants
    // ---------------------------------------------------------------------------
    public static final String SEARCH_STRATEGY_EXACT = "exact";
    public static final String SEARCH_STRATEGY_PHRASE_VARIANT = "phrase_variant";
    public static final String SEARCH_STRATEGY_SYNONYM = "synonym";
    public static final String SEARCH_STRATEGY_TOKEN = "token";

    public static final Map<String, Integer> SEARCH_STRATEGY_PRIORITY = Map.of(
            SEARCH_STRATEGY_EXACT, 0,
            SEARCH_STRATEGY_PHRASE_VARIANT, 1,
            SEARCH_STRATEGY_SYNONYM, 2,
            SEARCH_STRATEGY_TOKEN, 3
    );

    public static final String SEARCH_RANKING_VERSION = "adaptive_bm25f_v1.0.0";

    // ---------------------------------------------------------------------------
    // Search mode constants
    // ---------------------------------------------------------------------------
    public static final String SEARCH_MODE_AUTO = "auto";
    public static final String SEARCH_MODE_EXACT = "exact";
    public static final String SEARCH_MODE_KEYWORD = "keyword";
    public static final String SEARCH_MODE_SEMANTIC = "semantic";

    public static final Set<String> VALID_SEARCH_MODES = Set.of(
            SEARCH_MODE_AUTO,
            SEARCH_MODE_EXACT,
            SEARCH_MODE_KEYWORD,
            SEARCH_MODE_SEMANTIC
    );

    // ---------------------------------------------------------------------------
    // Precompiled regex patterns (shared by search subsystem)
    // ---------------------------------------------------------------------------
    public static final Pattern WORD_SPLIT_PATTERN = Pattern.compile("[a-z0-9]+");
    public static final Pattern SPACE_NORMALIZE_PATTERN = Pattern.compile("\\s+");

    // ---------------------------------------------------------------------------
    // Token stop words
    // ---------------------------------------------------------------------------
    public static final Set<String> TOKEN_STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "into", "about", "this", "that",
            "have", "has", "had", "are", "was", "were", "or", "but"
    );

    // ---------------------------------------------------------------------------
    // Synonym / terminology mapping groups
    // ---------------------------------------------------------------------------
    public static final String[][] SEARCH_SYNONYM_GROUPS = {
            // Revenue and sales
            {"revenue", "revenues", "sales", "营业收入", "營業收入"},
            // Net income and profit
            {"net income", "net profit", "净利润", "淨利潤"},
            // Cash flow
            {"cash flow", "现金流", "現金流"},
            // Risk
            {"risk", "risks", "risk factors", "风险", "風險"},
            // Guidance and outlook
            {"guidance", "outlook", "指引", "展望"},
            // Share repurchase
            {"share repurchase", "repurchase", "buyback", "回购", "回購"},
            // Board of directors
            {"board of directors", "board", "董事会", "董事會"},
            // Management
            {"management", "executive officers", "管理层", "管理層"},
            // Legal proceedings
            {"legal proceedings", "litigation", "诉讼", "訴訟"},
            // Competition and market
            {"compete", "competition", "competitor", "competitive", "competitiveness"},
            {"market share", "market position", "market penetration"},
            {"monopoly", "dominant position", "market dominance"},
            // M&A and transactions
            {"acquisition", "merger", "deal", "buyout", "takeover"},
            // Products and services
            {"product", "offering", "solution", "service", "platform"},
            // Threats and challenges
            {"threat", "headwind", "challenge", "pressure"},
            // Intellectual property
            {"intellectual property", "patent", "proprietary"},
            // Supply chain
            {"supply chain", "sourcing", "procurement", "vendor", "supplier"},
            // Customer retention
            {"customer retention", "churn", "loyalty"},
            // Profitability
            {"profit", "profitability", "margin", "earnings"},
            {"operating income", "operating profit", "ebit"},
            // Debt and capital
            {"debt", "borrowing", "leverage", "liabilities"},
            {"equity", "shareholders equity", "stockholders equity"},
            // R&D
            {"research and development", "r&d", "innovation"},
            // Regulation
            {"regulation", "regulatory", "compliance"},
            // Dividends
            {"dividend", "dividends", "distribution", "payout"}
    };

    // ---------------------------------------------------------------------------
    // High ambiguity token set
    // ---------------------------------------------------------------------------
    public static final Set<String> GENERIC_AMBIGUOUS_TOKENS = Set.of(
            "competition", "competitor", "competitive", "market", "business",
            "strategy", "policy", "growth", "performance", "management",
            "risk", "compliance"
    );

    // ---------------------------------------------------------------------------
    // Intent keyword lexicons
    // ---------------------------------------------------------------------------
    public static final Map<String, Set<String>> INTENT_KEYWORDS = Map.of(
            "business_competition", Set.of(
                    "competitor", "competition", "competitive", "market", "marketshare",
                    "share", "customer", "industry", "peer", "supplier", "product",
                    "service", "lithography", "semiconductor"
            ),
            "financial", Set.of(
                    "revenue", "income", "earnings", "cash", "margin", "asset",
                    "liability", "equity", "guidance", "profit"
            ),
            "governance", Set.of(
                    "board", "director", "governance", "compensation", "executive",
                    "committee", "ethics", "compliance", "anti", "bribery"
            ),
            "people", Set.of(
                    "employee", "talent", "hiring", "students", "league", "recruit",
                    "workforce", "training", "employer"
            ),
            "risk", Set.of(
                    "risk", "threat", "uncertainty", "vulnerability", "cybersecurity",
                    "litigation", "exposure"
            )
    );

    // ---------------------------------------------------------------------------
    // Intent noise / support context lexicons
    // ---------------------------------------------------------------------------
    public static final Map<String, Set<String>> NOISE_CONTEXT_TOKENS_BY_INTENT = Map.of(
            "business_competition", Set.of(
                    "antitrust", "compliance", "ethics", "students", "league",
                    "robotics", "employer", "universum", "human", "rights"
            )
    );

    public static final Map<String, Set<String>> SUPPORT_CONTEXT_TOKENS_BY_INTENT = Map.of(
            "business_competition", Set.of(
                    "market", "industry", "customer", "supplier", "peer", "product",
                    "service", "technology", "lithography", "semiconductor"
            )
    );

    // ---------------------------------------------------------------------------
    // Semantic bucket mappings (adaptive solution)
    // ---------------------------------------------------------------------------

    /**
     * Topic → Bucket direct mapping.
     * SectionType.value → semantic bucket, covering legal Item semantic types of SEC forms.
     */
    public static final Map<String, String> TOPIC_TO_BUCKET = Map.<String, String>ofEntries(
            // business domain: company overview, main business, operating environment
            Map.entry("business", "business"),
            Map.entry("company_information", "business"),
            Map.entry("properties", "business"),
            Map.entry("operating_review", "business"),
            // risk domain: risk factors, market risk, cybersecurity
            Map.entry("risk_factors", "risk"),
            Map.entry("market_risk", "risk"),
            Map.entry("cybersecurity", "risk"),
            // financial domain: financial reports, MD&A, quantitative disclosures
            Map.entry("mda", "financial"),
            Map.entry("financial_statements", "financial"),
            Map.entry("financial_information", "financial"),
            Map.entry("selected_financial_data", "financial"),
            Map.entry("quantitative_disclosures", "financial"),
            Map.entry("key_information", "financial"),
            // governance domain: governance, executive compensation, control procedures
            Map.entry("directors", "governance"),
            Map.entry("governance", "governance"),
            Map.entry("executive_compensation", "governance"),
            Map.entry("security_ownership", "governance"),
            Map.entry("certain_relationships", "governance"),
            Map.entry("principal_accountant", "governance"),
            Map.entry("controls_procedures", "governance"),
            // people domain: employees, human capital
            Map.entry("directors_employees", "people"),
            // legal domain: legal proceedings
            Map.entry("legal_proceedings", "legal"),
            // other domain: appendices, signatures, mine safety, etc.
            Map.entry("exhibits", "other"),
            Map.entry("signature", "other"),
            Map.entry("mine_safety", "other"),
            Map.entry("other_information", "other"),
            Map.entry("unresolved_staff_comments", "other"),
            Map.entry("offer_listing", "other"),
            Map.entry("additional_information", "other"),
            Map.entry("market_for_equity", "financial"),
            Map.entry("securities_description", "other"),
            Map.entry("defaults_arrearages", "other"),
            Map.entry("material_modifications", "other"),
            Map.entry("changes_disagreements", "other")
    );

    /**
     * Bucket keyword signals (for fallback only).
     * When topic is not in TOPIC_TO_BUCKET, score based on keywords in title/path/item.
     */
    public static final Map<String, Set<String>> BUCKET_KEYWORD_SIGNALS = Map.of(
            "business", Set.of(
                    "business", "operating", "market", "product", "service",
                    "customer", "industry", "company", "overview", "operations"
            ),
            "risk", Set.of(
                    "risk", "risks", "threat", "uncertainty", "cybersecurity"
            ),
            "financial", Set.of(
                    "financial", "income", "revenue", "earnings", "assets",
                    "liabilities", "equity", "cash", "mda", "discussion",
                    "analysis", "quantitative"
            ),
            "governance", Set.of(
                    "governance", "director", "directors", "compensation",
                    "committee", "board", "audit", "shareholder", "ethics"
            ),
            "people", Set.of(
                    "employee", "employees", "workforce", "personnel",
                    "staff", "talent", "headcount"
            ),
            "legal", Set.of(
                    "legal", "proceeding", "proceedings", "litigation",
                    "lawsuit", "compliance"
            )
    );

    /**
     * Intent → expected bucket set.
     * Priority buckets for query intent; alignment score is 1.0 when matched, otherwise 0.0.
     */
    public static final Map<String, Set<String>> EXPECTED_BUCKETS_BY_INTENT = Map.of(
            "business_competition", Set.of("business", "risk", "financial"),
            "financial", Set.of("financial", "business"),
            "governance", Set.of("governance", "legal", "people"),
            "people", Set.of("people", "governance"),
            "risk", Set.of("risk", "legal", "business")
    );

    // ---------------------------------------------------------------------------
    // Exact priority capping constants
    // ---------------------------------------------------------------------------

    /**
     * When exact + expansion coexist, maximum proportion of expansion results.
     */
    public static final double EXPANSION_RATIO_WHEN_EXACT_EXISTS = 0.3;

    /**
     * Minimum entry threshold for capping to trigger (below this quantity, no trimming).
     */
    public static final int CAP_MIN_TRIGGER = 8;

    // ---------------------------------------------------------------------------
    // Evidence structure building constants
    // ---------------------------------------------------------------------------

    /**
     * Maximum characters for matched_text - used to extract query hit sentence from snippet.
     */
    public static final int MATCHED_TEXT_MAX_CHARS = 120;
}
