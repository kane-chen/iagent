---
name: segment-financial-report
description: 从已下载的财报HTML/PDF中提取分部业务财务数据并生成多层级Excel（支持策略模式识别 + 公司配置隔离，含YoY同比高亮）。触发词：分部财报、分部数据、业务分部、segment、分部收入、分部EBITA、分业务营收、分业务利润
---

# 分部业务财务报表 Skill

**一句话**：告诉我公司名或代码，我直接从已下载的财报里提取分部数据（收入/EBITA等），生成按业务分部层级展开的 Excel。不需要你自己敲命令。

## 快速开始（99% 场景）

### 怎么触发？

直接用自然语言说就行，不需要写命令：

- "腾讯的分部财报"
- "BABA 分业务收入和 EBITA"
- "帮我拉美团最近几年的分部数据"
- "PDD 的 segment 数据"

### 前提条件（重要！和 futu-financial-report 不一样）

⚠️ **本 skill 不联网，不直接连富途 API**。它只从**已经下载到本地**的财报文件里提取数据。所以调用本 skill 之前，必须先满足：

1. **该公司的财报已经下载到本地**（用 `futu-filing` / `filingAgent-sub` 下载到 `workspace/portfolio/<TICKER>/filings/`）
2. **该公司有分部配置**（已支持的公司见下文；不支持的需要加配置）

> 如果财报没下载，我会直接告诉你"未找到财报文件，请先用 futu-filing 下载"，不会瞎编数据。

### 已支持的公司（开箱即用）

| ticker | 公司 | 市场 | 文件类型 |
|--------|------|------|---------|
| BABA | 阿里巴巴 | US（SEC HTML） | HTML |
| PDD | 拼多多 | US | HTML |
| TCOM | 携程 | US | HTML |
| MSFT | 微软 | US | HTML |
| GOOG | 谷歌/Alphabet | US | HTML |
| BEKE | 贝壳 | US | HTML（press-release 布局） |
| 00700 | 腾讯 | HK | PDF |
| 83690 | 美团 | HK | PDF（子分部矩阵） |

### 想要新公司？

在 `config/extraction/` 下新增 `<TICKER>.json`（参考现有文件），定义 segment 别名和 PDF 列映射（港股需要）。然后冒烟测试：`python workspace/skills/segment-financial-report/scripts/extract_segments.py --ticker <TICKER> --excel`。

> 美股 HTML 通常用 GenericHtmlLayoutHandler 就能跑，只要 config 里 segments 别名配好。港股 PDF 位置映射比较麻烦，可能需要写 handler。

---

## 输出

Excel 落在 `workspace/excels/{ticker}_segments_{timestamp}.xlsx`。

### Excel 长什么样？

和 futu-financial-report 的「整体三表」不同，本 skill 的 Excel 是**按业务分部展开**的，每个分部展开多个指标，按 L1 业务组统一配色：

```
┌─────────────────────────────┬──────────────┬─────────┬─────────┬─────────┬─────────┐
│ 业务分部                    │ 指标         │ 2025FY  │ 2024FY  │ 2023FY  │ 2022FY  │  ← 深蓝表头+白字
├─────────────────────────────┼──────────────┼─────────┼─────────┼─────────┼─────────┤
│ 云智能集团                  │ 收入         │ 118,219 │ 106,510 │  94,615 │  77,203 │  ← L1 浅蓝粗体
│ 云智能集团                  │ 收入YoY(%)   │   11.0  │   12.6  │   22.6  │    3.5  │
│ 云智能集团                  │ EBITA        │  16,976 │  13,903 │  11,209 │   1,165 │
│ 云智能集团                  │ EBITAYoY(%)  │   22.1  │   24.0  │  861.8  │  -86.0  │  ← 红字-86%
│ 云智能集团                  │ EBITA利润率(%)│   14.4  │   13.1  │   11.8  │    1.5  │  ← 深橙底=利润率
│ ├─ 公共云                   │ 收入         │  ...                                                   ← L2 缩进
│ ├─ 公共云                   │ 收入YoY(%)   │  ...
│ └─ 混合云                   │ 收入         │  ...
├─────────────────────────────┼──────────────┼─────────┼─────────┼─────────┼─────────┤
│ 淘天集团                    │ 收入         │  ...                                                         ← L1 换色（浅绿）
│ 淘天集团                    │ EBITA        │  ...
│ 淘天集团                    │ EBITA利润率(%)│  ...
│ ├─ 中国商业零售              │ 收入         │  ...
│ └─ 批发                     │ 收入         │  ...
├─────────────────────────────┼──────────────┼─────────┼─────────┼─────────┼─────────┤
│ 本地生活集团                │ ...                                                                      ← L1 浅黄
└─────────────────────────────┴──────────────┴─────────┴─────────┴─────────┴─────────┘
```
（上图以阿里巴巴分部结构示意，实际数据以下载的财报为准）

**列结构**：A=分部名，B=指标名，C 列起=各期数据（按时间倒序，最新在最左）。

**行结构**（每个分部固定展示 6 类指标，全空的指标会自动隐藏）：

| 指标 | 单位 | 后面跟 YoY 行？ | 后面跟利润率行？ |
|-----|------|----------------|----------------|
| 收入 | 原币百万/千元（取决于财报） | ✅ | ❌ |
| 成本 | 原币 | ✅ | ❌ |
| 毛利 | 原币 | ✅ | ✅ 毛利率(%) |
| 营业费用 | 原币 | ✅ | ❌ |
| 营业利润 | 原币 | ✅ | ✅ 营业利润率(%) |
| EBITA | 原币 | ✅ | ✅ EBITA利润率(%) |

**层级视觉约定：**
- **L1 一级分部**：粗体，背景色按 L1 分组轮换（浅蓝→浅绿→浅黄→浅橙→浅紫→橙黄循环），不同 L1 一眼可分
- **L2 二级分部**：粗体，颜色与所属 L1 一致但缩进（`├─` / `└─` 前缀）
- **L3 三级分部**：正常字重，缩进更多（`  ├─`）
- **YoY 行**：百分比值；下降 >5% 或增长 >30% 红色加粗
- **利润率行**：深橙色背景（`#F4B084`），百分比，不跟随 L1 配色

**关键 UX 细节：**
- 表头冻结（第 1 行），分部/指标列冻结（A、B 列），左右滚动能看到指标名
- 整数不带小数，百分比保留 2 位
- 空数据显示 `-`，不会留空白让你以为漏了
- 列宽固定（A=40、B=18、C+=15），不会因某个分部名长就撑开
- 没有自动筛选（因为分部+指标组合不适合筛选）；要分析特定分部在 Excel 里搜索分部名即可

**文件大小预期**：8 家公司 × 4 年期 ≈ 50~150 行，Excel 约 15~40 KB，打开秒开。

### 关键特性

- 最多三级分部（L1→L2→L3），树状缩进 + L1 分组配色
- 每个分部固定顺序：收入 → 成本 → 毛利 → 营业费用 → 营业利润 → EBITA；空指标自动跳过
- 自动计算 3 个利润率（毛利率/营业利润率/EBITA 利润率），每行紧跟 YoY 同比
- L1 分部若本身没披露总收入/EBITA（港股常见），会用子孙分部同期值累加补全
- 多期横向对比（按财报期自动排序：FY 优先 → Q4→Q1 → H2→H1，新年份在前）
- YoY 异常值红字（下降 >5% 或增长 >30%）
- 利润率行深橙色背景突出显示

---

## 我（Agent）执行时的调用约定

> 这一节是给 Agent 看的，普通用户不用管。

### 推荐：一步到位（Agent 场景用这个）

```bash
python workspace/skills/segment-financial-report/scripts/extract_segments.py \
    --ticker BABA --excel
# stdout 最后一行是 xlsx 绝对路径
```

这会完成：找财报文件 → 提取分部数据 → 生成 Excel。**港股 PDF 同一条命令搞定**，引擎自动按扩展名分发给 PDF parser。

### 参数

| 参数 | 说明 | 默认 |
|-----|------|-----|
| `--ticker` | 股票代码（BABA / 00700 / 83690 等）；对应 `config/extraction/<TICKER>.json` | 必填 |
| `--workspace` | workspace 根目录（含 `portfolio/<TICKER>/filings/`） | 脚本位置自动推断 |
| `--fiscal-year-start` / `--fiscal-year-end` | 财年闭区间，例如 `--fiscal-year-start 2022 --fiscal-year-end 2025` | 不限 |
| `--excel` | 提取后直接生成 Excel；stdout 输出 xlsx 路径 | 关闭 |
| `--excel-output` | 自定义 xlsx 输出路径 | `workspace/excels/<TICKER>_segments_<ts>.xlsx` |
| `--no-flat` | 输出树状 Segment（调试用，Excel 需要 flat 格式） | flat 模式 |
| `--print-preview` | stderr 打印前 5 条 segment 便于快速核对 | 关闭 |

### 分阶段（调试场景）

```bash
# Stage 1：只提取到 JSON
python workspace/skills/segment-financial-report/scripts/extract_segments.py \
    --ticker BABA --output ./baba_segments.json

# Stage 2：JSON → Excel（单独渲染）
python workspace/skills/segment-financial-report/scripts/generate_segment_excel.py BABA \
    --json ./baba_segments.json --workspace workspace
```

### 退出码

| code | 含义 |
|------|------|
| 0 | 成功 |
| 1 | 执行失败（看 stderr 的错误提示） |
| 2 | 未找到任何可处理的财报文件（看 stderr 的诊断信息） |

### stdout/stderr 长什么样（样例）

**成功运行**（`--excel` 模式）时的输出大致是这样：

```
[extract] Excel written to D:/.../workspace/excels/logs/BABA_segments_20260101_120000.xlsx
D:/.../workspace/excels/BABA_segments_20260101_120000.xlsx    ← 最后一行是 xlsx 绝对路径（stdout）
```

带 `--print-preview` 时 stderr 额外打印前 5 条数据快速核对：

```
[extract] preview:
  Taobao and Tmall Group | Revenue | 2025FY | 123456 million
  Taobao and Tmall Group | Adjusted EBITA | 2025FY | 4567 million
  Cloud Intelligence Group | Revenue | 2025FY | 78901 million
  ...
[extract] total records: 142
```

**失败**时 stderr 直接打印原因+提示，stdout 不输出路径（exit code=1 或 2）：

```
[extract] 失败: 未找到 BABA 的可提取财报文件
提示: 未找到 BABA 的财报目录（workspace/portfolio/BABA/filings/ 不存在）。请先用 futu-filing skill 下载该公司的财报。
```

### stdout 约定

- **stdout**：成功时最后一行是文件绝对路径（xlsx 或 json）；失败时 stdout 为空
- **stderr**：所有日志/错误/进度信息。不要从 stdout 解析日志，路径只取最后一行
- 错误信息格式：`[extract] 失败: <原因>` 后跟 `提示: <怎么办>`

### Agent 必须遵循的规则

1. **调用前先确认财报已下载**：如果不确定，先看 `workspace/portfolio/<TICKER>/filings/` 有没有东西；空的就先调 `futu-filing` 下载。
2. **用户给公司中文名**：先调 `stock-ticker` 查代码，注意区分美股/港股上市主体（如 BABA=US.9988=HK 双重上市，默认走美股代码 `BABA`）。
3. **失败时把 stderr 的「提示」部分直接转述给用户**，不要让用户翻日志。如果提示里说"请先用 futu-filing 下载"，就告诉用户需要先下载。
4. **不要重复发明下载逻辑**：本 skill 只读本地文件，不碰网络；缺文件就引导用户触发下载。
5. **拿到 xlsx 路径后**，告诉用户文件位置 + 覆盖的期数范围（从 stderr `[extract]` 日志或 Excel 自身判断），不要把表格全贴回对话。

---

## 附录 A：常见错误排查（出问题再看）

脚本现在会**直接告诉你为什么失败以及怎么修**，stderr 的 `提示:` 行就是解决方案。下面整理常见场景：

### "未找到 XXX 的财报目录" / "财报目录为空"

**原因**：`workspace/portfolio/<TICKER>/filings/` 不存在或没有子目录，说明财报根本没下载。

**怎么办**：
1. 先用 futu-filing skill 下载该公司财报：
   ```bash
   python workspace/skills/futu-filing/scripts/download_announcement.py --ticker <TICKER> --workspace ./workspace
   ```
2. 如果 futu-filing 也提示没 cookies，先跑 `python workspace/skills/futu-filing/scripts/login.py --skip-if-valid`
3. 港股/A 股第一次下载会比较慢（PDF 较多），耐心等

### "缺少公司配置: XXX"

**原因**：`config/extraction/` 下没有 `<TICKER>.json`，引擎不知道怎么识别这个公司的分部。

**怎么办**：
1. 如果是类似 BABA 结构的美股（SEC 10-K/10-Q），最快的办法是复制 `BABA.json` 改名，然后把 `segments` 列表替换成目标公司的分部名和别名。
2. 港股 PDF 更复杂，需要配置 `pdfColumnMappings`（位置映射 + layout）。参考 `00700.json`（腾讯）或 `83690.json`（美团）。
3. 配完跑 `--print-preview` 冒烟：前 5 条数据正确就说明别名匹配对了。

### "XXX 的财报文件已找到，但未解析出分部数据"

**原因**：文件找到了，也能读，但所有表格都没匹配到 segment。常见子原因：
- 公司配置的 segment 别名没覆盖到该期财报的实际叫法（改名了/中英文差异）
- 表格布局特殊，现有 handler 不认（美股 press-release 格式、新 PDF layout）
- PDF 是扫描件（图片型），无法抽取文本

**怎么办**：
1. 加 `--print-preview` 重跑，看 stderr 里前 5 条是空的还是有东西
2. 去 `workspace/excels/logs/` 找最新的日志，看 `No parser supports file` 或 `no segments extracted` 的警告
3. 如果是别名问题，在 config 的 `aliases` 数组里加新词
4. 如果是新布局，可能需要写新 handler（`engine/handlers/` 或 `engine/pdf_handlers/`）

### "解析 N 个财报文件全部失败"

**原因**：每个文件单独处理都抛异常。错误样例会打印在 stderr。

**常见子原因对应表**：

| 错误样例关键词 | 原因 | 处理 |
|--------------|------|------|
| `FileNotFoundError` / "No such file" | meta.json 里登记的 PDF/HTML 实际不存在 | 重新跑 futu-filing 下载（加 `--overwrite`）|
| `JSONDecodeError` / "Expecting value" | meta.json 损坏 | 删除该 docId 目录重下 |
| `pdfminer` / `FlateDecode` 大量 stderr | PDF 嵌入字体有坏流（正常警告，非错误） | 忽略即可，引擎已屏蔽这些噪声 |
| `KeyError: 'field_id'` / `'item_list'` | HTML 结构变化（SEC 改版） | 反馈给开发同学，需适配新结构 |
| `PermissionError` on xlsx | Excel 被 WPS/Office 占用 | 关掉打开的 Excel 再跑 |

### 财年范围过滤掉了所有文件

**症状**：指定了 `--fiscal-year-start 2023 --fiscal-year-end 2025`，提示"N 个目录在指定财年范围外"。

**原因**：
- meta.json 里 `fiscalYear` 没正确识别（比如 filings 目录名格式不对）
- 下载的财报都是 2026 年的，你筛了 2023-2025 当然没数据

**怎么办**：去掉 fiscal-year 参数重跑看默认能拉到多少期，再决定怎么筛。

---

## 附录 B：反模式（不要这样做）

1. **不要在没下财报时直接调用本 skill**。它不联网，不调富途 API；没文件就是没文件，会立刻报错。**正确顺序：先 `futu-filing` 下载 → 再本 skill 提取。**
2. **不要传公司中文名给 `--ticker`**。脚本不做名称解析，只认代码。中文名先走 `stock-ticker` 查。
3. **不要传带市场前缀的代码给不支持前缀的配置**。当前配置文件按 ticker 命名（`BABA.json` 不是 `US.BABA.json`），`--ticker US.BABA` 会找不到配置。**直接写 `BABA` / `00700` / `83690`**。
4. **不要把 Excel 打开着重跑**。Windows 下 WPS/Excel 加文件锁，会 PermissionError。先关文件。
5. **不要假设所有美股都能用 GenericHtmlLayoutHandler**。BEKE 这类用 press-release 格式的有专门 handler；其他公司如果是 earnings release HTML 而非 SEC 10-Q/10-K 主文档，可能需要新 handler。
6. **不要忽略 stderr**。本脚本的诊断信息全部在 stderr，stdout 只打印最终路径。如果 Agent 只看 stdout，会错过所有错误提示。
7. **不要一次拉太多年**。港股 PDF 每份都要跑 pdfplumber/camelot，单份耗时 10-30 秒，拉 10 年就是几分钟。先试默认范围，确认结果对再加。
8. **不要混用 futu-financial-report 的财务指标和本 skill 的分部数据做精确对齐**。两者数据源不同：futu-financial-report 走富途 API（数字经过富途标准化），本 skill 直接从 SEC PDF/HTML 里解析（单位/口径可能有差异，特别是 press-release 与 10-K 修订版）。
9. **不要在配置里把所有别名都塞进去**。别名只放官方分部名/常见简称；太宽泛的别名会误匹配到其他行（比如 "Revenue" 做别名会匹配所有收入行，而不是某个分部的收入）。
10. **港股 PDF 第一次跑慢是正常的**。camelot 做表格检测 + 位置映射，每份 10-30 秒，不是卡死。看 `workspace/excels/logs/` 里的日志确认进度。

---

## 附录 C：边界情况经验

- **BABA 2026Q1 有 EBITA 行拆分括号**：引擎已经处理 `mergeSplitParentheses`，如果看到某期 EBITA 值是空的但其他期都有，检查该期 HTML 里是否有括号跨行情况（需要加 handler 规则）。
- **腾讯 PDF 分两种 layout**：单期的收入/毛利块用 `SegmentsAsColumnsHandler`，多期单指标块用 `SegmentsAsRowsHandler`，引擎会按 consumed-table 算法自动分派，不需要手动指定。
- **美团 L2 子分部是矩阵**：`SubsegmentMatrixHandler` 专门处理"列是 L1 分部、行里混着 L2 收入行和 L1 成本/经营利润行"的布局。其他港股子分部如果类似，复用它。
- **美股 6-K/20-F 没有主文档命名规则**：引擎会优先找 `<ticker>_<YYYYMMDD>.htm`（10-K/10-Q 主文档），找不到再回退到 `ex99-1.htm`（earnings release 附件）。如果 6-K 挂的是其他命名，可能要在 meta.json 里调整 `primaryFile`。
- **财年判断依赖目录名**：引擎从 filing 目录名解析 `fiscalYear`，如果目录名是 `fil_xxx_2024_FY/` 这种格式能识别；如果是其他命名可能全部判定"out of range"。下载脚本生成的目录是合规的，自己挪动目录要小心。
- **多文件同 segment/metric/period 会去重**：同一指标重复出现只保留第一个；所以如果你看到某期数据是更早一份的值而不是最新的，可能是目录排序问题（按名字排序，最早的文件先处理）。
- **PDF 表格跨页**：camelot 会把跨页表格拆成两个独立表格，`SegmentsAsRowsHandler` 能处理大部分列头重复的续表，但非常复杂的跨页（比如 L1 分部名只在第一页出现）可能丢失数据。
- **货币单位**：引擎不做货币换算，所有值保留财报原单位（百万/千元）；BABA 是百万人民币，Tencent 是百万人民币，美团是百万人民币，美股公司通常是百万美元。跨公司对比时要注意。

---

## 附录 D：文件结构

```
segment-financial-report/
├── SKILL.md                         # 本文档
├── config/extraction/               # 公司配置（<TICKER>.json + metric_dict.json）
│   ├── BABA.json
│   ├── 00700.json
│   └── ...
├── scripts/
│   ├── extract_segments.py          # 主入口：提取 → 可选生成 Excel
│   ├── generate_segment_excel.py    # Excel 渲染器（被 extract_segments 同进程调用）
│   ├── extract_pdf_tables.py        # PDF 多引擎表格抽取（camelot/pdfplumber）
│   ├── engine/                      # 纯 Python 提取引擎
│   │   ├── extraction_service.py    # 主服务入口
│   │   ├── file_filter.py           # 候选文件过滤（带诊断）
│   │   ├── config_loader.py         # 公司配置加载
│   │   ├── model.py                 # 数据模型（Segment/FinancialTable 等）
│   │   ├── html_parser.py           # BeautifulSoup HTML 表格解析
│   │   ├── html_segment_parser.py   # HTML FileSegmentParser 实现
│   │   ├── html_orchestrator.py     # HTML handler 策略分派
│   │   ├── html_support.py          # HTML 数值归一化等公共工具
│   │   ├── pdf_parser.py            # PDF FileSegmentParser 实现
│   │   ├── pdf_layout_handler.py    # PDF handler 策略接口
│   │   ├── pdf_support.py           # PDF 公共工具
│   │   ├── handlers/                # HTML 各 layout handler
│   │   └── pdf_handlers/            # PDF 各 layout handler
│   └── requirements.txt
└── tests/                           # pytest 冒烟测试（需要本地有对应 filings）
```
