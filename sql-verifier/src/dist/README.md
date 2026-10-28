# 双库 SQL 验证器运行包

将清单中的相同 SQL 和参数分别用于两个 JDBC 数据源，比较结果并生成离线报告。支持查询比较，以及受控写入的回查、回滚和恢复检查；无需业务源码、MyBatis 或 Gradle。

## 1. 准备环境和配置

需要 Java 17。先用 `java -version` 核对版本；脚本优先使用 `JAVA_HOME`，否则从 `PATH` 查找。下文命令均在解压后的包根目录执行。

```text
sql-verifier-<版本>/
├── bin/                 macOS / Linux 和 Windows 启动脚本
├── lib/                 运行依赖，含 MySQL Connector/J 8.0.33
├── drivers/             额外驱动及依赖 JAR
├── config/verifier/     配置示例
└── examples/verifier/   查询、DML 清单和人工报告样例
```

复制[查询配置示例](../../../config/verifier/verification.example.json)为 `config/verifier/verification.json`，修改两侧的 `driverClassName`、`jdbcUrl` 和 `username`。第一次建议将 `queryIds` 改为 `["smoke.ping", "smoke.string"]`，先检查连接与参数绑定。

示例默认选中 11 条查询，其中 `smoke.rows31` 未声明稳定范围，预期返回 2；`smoke.error` 是故意失败的查询，默认排除。换成自己的清单后，更新 `queries` 并清除演示 ID；`queryIds: []` 表示全部执行。

### 提供密码

每侧通过 `passwordEnv` 或 `passwordFile` 提供密码，必须二选一。不支持直接写 `password`，JDBC URL 不得包含凭据。

Bash 可交互输入密码：

```bash
read -r -s SQL_SOURCE_PASSWORD
read -r -s SQL_TARGET_PASSWORD
export SQL_SOURCE_PASSWORD SQL_TARGET_PASSWORD
```

其他 Shell 使用相应输入方式。PowerShell：

```powershell
$env:SQL_SOURCE_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host '源库密码' -AsSecureString)).Password
$env:SQL_TARGET_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host '目标库密码' -AsSecureString)).Password
```

也可替换为 `"passwordFile": "source-password.txt"`。密码文件相对于配置目录，不能为空，仅移除末尾一个换行。使用查询权限账号，并准备两侧可比较的数据和会话条件；工具不会同步数据。

## 2. 运行并看报告

macOS / Linux：

```bash
./bin/sql-verifier --config config/verifier/verification.json
```

Windows PowerShell：

```powershell
.\bin\sql-verifier.bat --config config\verifier\verification.json
$LASTEXITCODE
```

CMD 使用同一 `.bat`，通过 `%ERRORLEVEL%` 查看退出码。脚本不切换当前目录；`--config` 相对于调用目录，JSON 内的 `queries/dmlCases/output/passwordFile` 相对于配置目录，含空格的路径加引号。

打开终端打印的 `verification.html`，先看摘要，再筛选问题并点击 ID 查看详情。页面离线可用；移动或分享时保留同级 `html/` 目录。正式证据保存在 `verification.json`，逐条结果保存在 `queries.jsonl` 或 `cases.jsonl`。中断时仅已完整写入的 JSONL 行可用，重新运行会创建新轮次，不续跑。

| 退出码 | 含义 |
| --- | --- |
| `0` | 非空所选清单全部通过；DML 已完成清理 |
| `2` | 报告完整但存在错误、差异、无法判定、未执行或空清单；DML 清理已确认 |
| `1` | 配置、清单级输入、调度、清理、写盘或 HTML 生成失败 |

报告隐藏配置密码，但仍含 SQL、参数和数据，需要按业务数据要求保管。

## 3. 编写查询与比较声明

```json
{
  "queries": [
    {
      "id": "record.byId",
      "sql": "SELECT id, name FROM demo_record WHERE id = ?",
      "parameters": [{"jdbcType": "INTEGER", "value": 1}]
    }
  ]
}
```

ID 必须唯一，参数数组按顺序对应 `?`。支持 VARCHAR 字符串、INTEGER 整数、BIGINT/DECIMAL 十进制字符串、BOOLEAN 布尔值、ISO 本地格式的 DATE/TIME/TIMESTAMP；各类型都允许显式 null，不能省略 value。未知字段或类型报错。

默认无序比较整行并保留重复次数；数值按值比较，字符串保留大小写与尾空格，null 与空字符串不同。日期时间精确比较本地值，不转换时区。列名与 JDBC 列类型不作独立核验。

在验证配置中按需要加入以下片段，ID 必须存在于清单：

```json
{
  "comparison": {
    "orderedQueryIds": ["record.ordered"],
    "stableRangeQueryIds": ["record.ordered", "record.page"],
    "expectedEmptyQueryIds": ["record.noMatch"]
  }
}
```

三个数组分别声明按顺序比较、已经核实的稳定取数范围和预期空结果。分页、截断或顺序比较须有外层 ORDER BY 及稳定范围声明；两侧均空但未声明预期为空时不通过。

结论 `MATCH/DIFFERENT/INCONCLUSIVE` 分别表示一致、差异和无法判定；`observedStatus` 只描述已读行值。范围 `FULL/PAGE/PREFIX/UNKNOWN` 分别为完整、分页、前缀和未知；前 N 行一致不代表全部数据一致。差异最多展示 5 个样例，完整计数和原始行保留在 JSON 中。

## 4. 常用配置与执行原理

| 配置 | 默认值与用途 |
| --- | --- |
| `rows` | 30，每侧保留行数，再探测一条 |
| `concurrency` | 查询 16，范围 1—32；DML 只能为 1 |
| `queryTimeoutSeconds` | 30，查询超时秒数 |
| `maxResultBytes` | 5242880，每条每侧结果值字节限额 |
| 数据源内 `connectTimeoutMillis/socketTimeoutMillis` | 10000 / 30000，驱动连接与读取超时毫秒数 |

查询经预检后，工作线程各自持有双侧连接，依次执行 source 和 target，比较并等待结果同步落盘后继续下一条。最多使用实际线程数两倍的连接，报告按落盘顺序保存，用 ID 和 sequence 定位。

只对独立查询启用并发；依赖连接状态或顺序时设为 1。工具不提供统一事务快照、自动重连或重试。仅接受有限范围检查通过的单条 SELECT / 查询型 WITH，拒绝多语句、写入、锁定、未展开模板和可执行注释等内容。行数和超时限制不保证降低数据库扫描成本，具体超时效果由驱动决定。

额外 JDBC 驱动及其依赖直接放入 `drivers/`，再修改驱动类与 URL。脚本加载 `lib/*`、`drivers/*`，不递归；避免同一驱动多个版本并存。

## 5. 受控写入

复制[DML 配置示例](../../../config/verifier/verification.dml.example.json)，改为自己的连接和凭据，再使用同一启动命令。配置用 `dmlCases` 替代 `queries`，要求 `allowedTables` 非空、`concurrency: 1`；不能混用 `queryIds` 或 `comparison`，筛选用 `caseIds`。

[写入用例](../../../examples/verifier/dml-cases.design.json)要求两库已有 `demo_order(id, status, amount)`：id 为 INTEGER 主键，status 为 VARCHAR，amount 为 DECIMAL；1001 为 PENDING / 12.50，1002 不存在。工具不建表或初始化数据。每例 `probe` 定义回查 SQL、参数、列类型及完整前后行数组。

每侧每例使用新连接，关闭自动提交并设为 READ_COMMITTED，按“前置回查 → 写入 → 后置回查 → 回滚 → 恢复检查 → 关闭”执行。普通 SQL 失败且清理确认后可继续。写入回滚、恢复检查、恢复读事务回滚均成功，且此前无事务断连或清理异常时，仅关闭连接的通信异常（SQLState 以 `08` 开头）允许继续；保留 `CLOSE` 错误、`close: FAILED` 和允许继续的 `cleanupReason`，事务恢复状态为 `cleanup: PASS`，当前用例仍记为失败。其他关闭错误、事务断连、回滚或恢复未确认时停止后续写入。

支持显式列名单行 INSERT、完整主键/唯一键等值定位的 UPDATE，以及受控单列唯一键 IN 的 UPDATE。UPDATE 可用参数、固定整数、同列加一或 `CASE WHEN 列 IS NULL THEN 1 ELSE 列 + 1 END`；普通等值路径允许限定的 AND 非空或整数不等条件。IN 路径不能混用其他条件或修改唯一键，参数数不超过 rows。

写 SQL 不接受注释、多语句、任意函数和表达式。回查必须用相同定位参数，投影完整定位键和写入列，不带额外业务过滤、星号、聚合、别名或 LIMIT；全部前后及恢复行必须读全。

MySQL 协议下要求事务表引擎明确为 InnoDB 或 YashanDB，并核查唯一键、触发器和外键级联；未知或无法读取的元数据会阻止写入。YashanDB 还核对服务端自动提交为 0、隔离级别为 READ-COMMITTED 且会话可写。UPDATE 请求 `useAffectedRows=true`，URL 显式冲突会拒绝。运行期间保持表结构和引擎稳定。

回滚成功不等于恢复通过，应看逐侧清理证据。不支持提交、XA、重试或补偿；自增、序列和外部副作用不保证恢复。

## 6. 已有 JSON 重新生成 HTML

```bash
./bin/sql-verifier --report reports/queries/<本轮目录>/verification.json
```

无需配置或数据库，`--report` 与 `--config` 二选一；页面写入 JSON 所在目录。只有 JSONL 时不能转换。转换成功返回 0 只代表页面生成成功，原数据库结论不变；失败时保留原 JSON 和已有首页，旧详情批次不自动清理。

可先把[人工查询报告](../../../examples/verifier/reports/query/verification.json)或[人工 DML 报告](../../../examples/verifier/reports/dml/verification.json)复制到自己的输出目录再转换。这些是阅读样例，不是数据库验证记录。

验证器不执行 DELETE。选中的删除用例在建连前记录为 `NOT_EXECUTED/INPUT`，并继续其他有效 INSERT / UPDATE；即使 `caseIds` 显式选择也不能放行。含未执行用例时，正常收尾返回退出码 2。公开三类 DML 示例中的 DELETE 用于展示该跳过行为。
