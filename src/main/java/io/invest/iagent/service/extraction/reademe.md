# 财报业务线财务数据提取系统 - MVP版本

## 项目简介

本项目用于从财报文件（HTML格式）中自动提取分业务线的财务数据，包括收入、成本、费用、经营利润、EBIT等指标。

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
│   │   │   ├── parser/         # 财报解析器
│   │   │   ├── recognizer/     # 业务线识别
│   │   │   ├── mapper/         # 指标映射
│   │   │   ├── extractor/      # 数据提取
│   │   │   ├── validator/      # 质量校验
│   │   │   ├── config/         # 配置加载
│   │   │   └── service/        # 主服务
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
// 1. 创建服务实例（指定公司代码）
FinancialExtractionService service = new FinancialExtractionService("alibaba");

// 2. 从HTML文件提取数据
File htmlFile = new File("path/to/report.html");
List<Segment> segments = service.extractFromHtmlFile(htmlFile);

// 3. 打印结果
service.printResults(segments);

// 4. 提取并校验
FinancialExtractionService.ExtractionResult result = service.extractAndValidate(htmlFile);
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

## 演进路线

- **MVP（当前）**：HTML财报解析，规则匹配，核心指标提取
- **V1.0**：PDF支持，更多公司，完整质量校验
- **V2.0**：LLM语义识别，自学习优化，自定义指标
- **企业级**：分布式架构，多租户，审计日志，高可用

## License

MIT