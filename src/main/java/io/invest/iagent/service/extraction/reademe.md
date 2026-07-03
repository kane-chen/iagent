# 财报业务线财务数据提取系统

## 项目简介

本项目用于从财报文件（HTML 格式的 SEC 公告 / PDF 格式的港股业绩公告）中自动提取分业务线的财务数据，包括收入、成本、费用、经营利润、EBIT 等指标。

核心入口为 `FileSegmentParser` 接口：`HtmlFileSegmentParser` 处理 SEC HTML 文件，`PdfFileSegmentParser` 处理港股 PDF 文件（通过 `extract_pdf_tables.py` 多引擎表格抽取 + `PdfLayoutHandler` 策略链），上层 `FinancialExtractionService` 通过 `supports(File)` 自动分发。

## 技术栈

- Java 11+
- Jsoup - HTML解析
- Jackson - JSON处理
- SLF4J + Logback - 日志
- JUnit 5 - 测试

## 项目结构

```
iagent/
├── src/
│   ├── main/
│   │   ├── java/io/iagent/service/extraction/
│   │   │   ├── model/          # 模型类
│   │   │   ├── parser/         # FileSegmentParser 接口 + PDF parser + layout handlers + HtmlReportParser
│   │   │   ├── recognizer/     # 业务线识别
│   │   │   ├── mapper/         # 指标映射
│   │   │   ├── extractor/      # HTML 策略 handler 链 + HtmlFileSegmentParser + orchestrator
│   │   │   ├── validator/      # 质量校验
│   │   │   ├── config/         # 配置加载
│   │   │   └── service/        # FinancialExtractionService 主服务 + 文件过滤
│   │   └── resources/
│   │       └── extraction/config/      # 公司配置文件
│   └── test/
│       └── java/.../service/   # 测试用例
└── pom.xml                     # Maven配置
```

## 核心功能

### 1. HTML财报解析
- 自动提取财务表格
- 识别表格标题、表头、表体
- 自动推断币种和单位
- 数值解析（支持括号负数、千分位等）

### 2. 业务线识别
- 基于公司配置的规则匹配
- 基于缩进和表格结构的自动识别
- 构建层级关系树

### 3. 财务指标映射
- 内置10个标准指标词典
- 精确匹配 + 模糊匹配两层策略
- 支持自定义指标

### 4. 数据提取
- 从单个表格提取分部财务数据
- 从多个表格提取并合并数据
- 计算置信度评分

### 5. 质量校验
- 横向求和校验（子分部之和 = 父分部）
- 纵向勾稽校验（收入 - 成本 - 费用 = 利润）
- 完整性校验
- 置信度评估

## 快速开始

### 编译

```bash
# 使用Maven
mvn clean compile

# 或手动编译
javac -cp "lib/*" -d target/classes $(find src/main/java -name "*.java")
```

### 运行测试

```bash
# 使用Maven
mvn test

# 或运行单个测试
java -cp "target/classes:lib/*:test-classes" org.junit.platform.console.ConsoleLauncher --select-class com.finance.extractor.service.AlibabaExtractionTest
```

### 使用示例

```java
// 1. 创建服务实例（指定公司代码，workspace 路径用于定位 filings 与 Python 脚本）
Path workspace = Path.of("workspace");
FinancialExtractionService service = new FinancialExtractionService("BABA", workspace);

// 2. 从单个文件提取数据（自动按扩展名分发给 HTML/PDF parser）
File file = new File("path/to/report.html");  // 或 .pdf
List<Segment> segments = service.extractFromFile(file);

// 3. 批量提取（按 ticker + 财年范围过滤 workspace/portfolio/<TICKER>/filings/ 下所有文件）
List<Segment> allSegments = service.extractSegments("BABA", "2022", "2025");

// 4. 从HTML内容字符串提取
List<Segment> segments = service.extractFromHtmlContent(htmlContent);

// 5. 提取并校验
FinancialExtractionService.ExtractionResult result = service.extractAndValidate(file);
ValidationResult validation = result.getValidationResult();
System.out.println("质量评分: " + validation.getOverallScore());
```

## 支持的公司

### 阿里巴巴 (alibaba)
- 淘宝天猫集团
- 国际数字商业集团
- 云智能集团
- 菜鸟
- 本地服务集团
- 数字媒体及娱乐集团
- 等...

### 谷歌 (google)
- Google Services
    - Google Advertising
        - Google Search & other
        - YouTube ads
        - Google Network
    - Google subscriptions, platforms, and devices
- Google Cloud
- Other Bets
- 等...

## 添加新公司

1. 在 `src/main/resources/companies/` 下创建 `{companyCode}.json` 配置文件
2. 定义业务分部和指标映射规则
3. 使用 `new FinancialExtractionService("{companyCode}")` 加载配置

## 标准指标体系

| 指标编码 | 标准名称 | 说明 |
|---------|---------|------|
| REVENUE | 营业收入 | Revenue/营收/收入 |
| COST_OF_REVENUE | 营业成本 | Cost of revenues |
| GROSS_PROFIT | 毛利润 | Gross Profit |
| OPERATING_EXPENSES | 运营费用 | Operating Expenses |
| RD_EXPENSES | 研发费用 | R&D expenses |
| OPERATING_INCOME | 经营利润 | Operating Income |
| EBIT | 息税前利润 | EBIT |
| EBITDA | 息税折旧摊销前利润 | EBITDA |
| ADJUSTED_EBITA | 调整后EBITA | Adjusted EBITA |
| NET_INCOME | 净利润 | Net Income |

## 质量校验规则

### 横向求和校验
- 检查子分部数据之和是否等于父分部数据
- 默认容差5%

### 纵向勾稽校验
- 检查收入 - 成本 - 费用 = 利润的勾稽关系
- 默认容差5%

### 完整性校验
- 检查关键指标是否缺失
- 一级分部必须有收入和利润数据

### 置信度评分
- 每个数据点附带0-100分置信度
- 基于数据来源、解析方式、单位明确度等因素计算

### 6. PDF 港股财报解析
- 通过 `extract_pdf_tables.py` 多引擎抽取（camelot-lattice → camelot-stream → pdfplumber），最大保留列结构
- 因港股中文字体编码乱码，采用"位置映射"策略：基于 `PdfColumnMapping` 预定义的列位置与 layout（SEGMENTS_AS_COLUMNS / SEGMENTS_AS_ROWS / SUBSEGMENT_MATRIX）把表格数据写入 Segment
- 支持 H1/H2/FY/Q1–Q4 多种报告周期，自动解析 CURRENT_Q/PRIOR_Q/CURRENT_P/PRIOR_P 占位符
- 单位自动归一化到 million（支持 thousand/千、billion/十亿）

## 演进路线

- **V1.0（当前）**：HTML + PDF 解析，规则匹配，核心指标提取，多策略 handler 链
- **V2.0**：LLM语义识别，自学习优化，自定义指标
- **企业级**：分布式架构，多租户，审计日志，高可用

## License

MIT