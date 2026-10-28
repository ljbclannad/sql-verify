# 双库 SQL 验证器

验证器将清单中的**同一 SQL 和固定参数**分别用于两个 JDBC 数据源，比较实际结果，生成 JSON 证据和离线 HTML 报告。运行不需要业务源码、Mapper、DTO、MyBatis 或 Spring。

支持两种独立模式：查询比较，以及受控 INSERT / UPDATE 的前后回查、回滚和恢复检查。先从查询模式开始；已有清单即可运行，不必使用提取器或生成器。

## 获取运行包

运行需要 Java 17。ZIP / TAR.GZ 包自带全部运行依赖及 MySQL Connector/J 8.0.33；解压后无需 Gradle。源码用户在仓库根目录构建：

```bash
./gradlew :sql-verifier:distZip :sql-verifier:distTar
./gradlew :sql-verifier:installDist
```

归档位于 `sql-verifier/build/distributions/`；可直接运行的目录位于 `sql-verifier/build/install/sql-verifier/`。

```text
sql-verifier-<版本>/
├── bin/                 macOS / Linux 和 Windows 启动脚本
├── lib/                 验证器及依赖
├── drivers/             额外 JDBC 驱动及依赖 JAR 放在这里
├── config/verifier/     查询和 DML 配置示例
└── examples/verifier/   清单和人工报告示例
```

脚本优先使用 `JAVA_HOME`，否则从 `PATH` 查找 Java。额外驱动放入 `drivers/` 后，填写对应 `driverClassName` 和 `jdbcUrl`；只加载 `lib/*` 与 `drivers/*`，不递归，不应混放同一驱动的多个版本。

## 跑通一次查询

### 1. 准备清单

在 `config/verifier/` 下创建 `queries.json`。以下查询不依赖业务表，适合先核对连接和绑定：

```json
{
  "queries": [
    {
      "id": "example.echo",
      "sql": "SELECT ? AS value",
      "parameters": [{"jdbcType": "VARCHAR", "value": "示例"}]
    }
  ]
}
```

参数数组按顺序对应 SQL 中的 `?`。无参数时可省略 `parameters`，ID 必须唯一。已有生成器清单时直接使用，无需重新编辑。

### 2. 准备配置和密码

在同目录创建 `verification.json`，替换为实际测试数据源：

```json
{
  "queries": "queries.json",
  "output": "../../reports/queries",
  "rows": 30,
  "concurrency": 16,
  "datasources": {
    "source": {
      "driverClassName": "com.mysql.cj.jdbc.Driver",
      "jdbcUrl": "jdbc:mysql://localhost:3306/source_test",
      "username": "readonly",
      "passwordEnv": "SQL_SOURCE_PASSWORD"
    },
    "target": {
      "driverClassName": "com.mysql.cj.jdbc.Driver",
      "jdbcUrl": "jdbc:mysql://localhost:3307/target_test",
      "username": "readonly",
      "passwordEnv": "SQL_TARGET_PASSWORD"
    }
  }
}
```

在启动终端的环境中设置两个密码变量。Bash 可交互输入，避免把密码字面量写进命令：

```bash
read -r -s SQL_SOURCE_PASSWORD
read -r -s SQL_TARGET_PASSWORD
export SQL_SOURCE_PASSWORD SQL_TARGET_PASSWORD
```

其他 Shell 使用各自的输入方式。PowerShell 可使用：

```powershell
$env:SQL_SOURCE_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host '源库密码' -AsSecureString)).Password
$env:SQL_TARGET_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host '目标库密码' -AsSecureString)).Password
```

也可将 `passwordEnv` 替换为 `passwordFile`，值为密码文件路径。每侧必须二选一，密码不能为空；文件只移除末尾一个换行，保留密码中的空格。不支持直接填写 `password`，JDBC URL 不得携带凭据。

### 3. 运行并打开报告

在运行包根目录执行：

```bash
./bin/sql-verifier --config config/verifier/verification.json
```

Windows 使用 `bin\sql-verifier.bat --config config\verifier\verification.json`。打开终端打印的 `verification.html`，看结论并点击 ID 查看双侧详情。

源码用户可把配置和清单放在仓库的 `config/local/`，将 `output` 设为 `../../build/sql-verification`，从仓库根目录运行：

```bash
./gradlew :sql-verifier:verify -PsqlVerifyConfig=config/local/verification.json
```

根目录 `./gradlew run` 是提取器入口。验证器也接受 `--args='--config ...'`，与 `-PsqlVerifyConfig` 二选一。

## 配置和清单规则

### 路径与筛选

命令行 `--config` / `--report` 相对于启动目录；通过仓库 Gradle 启动时以仓库根目录为基准。JSON 中的 `queries`、`dmlCases`、`output`、`passwordFile` 都相对于 JSON 所在目录。

`queryIds` 精确选择查询；省略或 `[]` 表示全清单。未知或重复 ID 报错，不支持模糊匹配。使用生成清单时，应清除演示配置的 `smoke.*` 筛选和不适用的比较声明。

### 常用配置

| 字段 | 默认值或要求 | 含义 |
| --- | --- | --- |
| `queries` | 查询模式必填 | 固定查询清单，与 `dmlCases` 二选一 |
| `output` | 必填 | 报告父目录，每轮创建独立子目录 |
| `rows` | `30`，正整数 | 每侧保存的最大行数，再额外探测一行 |
| `concurrency` | 查询 `16`，范围 1—32 | 查询工作线程数；设为 1 串行执行 |
| `queryTimeoutSeconds` | `30`，正整数 | JDBC 查询超时秒数，实际效果由驱动决定 |
| `maxResultBytes` | `5242880`，正整数 | 每条每侧结果值的 UTF-8 字节限额 |
| `queryIds` | 空数组 | 精确筛选查询 |
| `comparison` | 可省略 | 顺序、稳定范围、预期空结果声明 |
| `datasources.source/target` | 必填 | 两侧驱动、URL、用户名和外部密码引用 |
| 数据源内 `connectTimeoutMillis` | `10000` | 传给驱动的 `connectTimeout`，毫秒 |
| 数据源内 `socketTimeoutMillis` | `30000` | 传给驱动的 `socketTimeout`，毫秒 |

未知字段会报错。行数和体积限制约束客户端取数，不保证减少数据库扫描、排序或驱动内部缓冲；网络超时属性也需驱动支持。

参数支持 `VARCHAR` 字符串、`INTEGER` 整数、`BIGINT/DECIMAL` 十进制字符串、`BOOLEAN` 布尔值及 ISO 本地格式的 `DATE/TIME/TIMESTAMP`。每种类型均可显式填 `null`，不能缺少 `value`；日期时间不带时区偏移。完整查询和 DML 格式集中在[清单契约](../sql-query-contract/README.md)。

## 查询结果比较

默认按两库数据已对齐处理，工具不会同步或核验数据基线。先明确要比较哪些数据，再解释结果；会话时区、SQL 模式和目标兼容模式也应保持可比较。

默认 `UNORDERED` 将整行按无序集合比较，并保留重复次数；例如两侧各有两条相同行，与一侧只有一条不同。`ORDERED` 则按返回位置逐行比较。列名和 JDBC 列类型不作独立核验，行内值仍按列位置比较。

数值 `1.0` 与 `1.00` 等价；字符串保留大小写及尾空格，null 与空字符串不同。日期、时间、时间戳保留类别和小数秒，按本地值比较，不自动转换时区。

### 顺序、分页和空结果

以下是可加入验证配置的片段，所有 ID 必须存在于完整清单：

```json
{
  "comparison": {
    "orderedQueryIds": ["record.list"],
    "stableRangeQueryIds": ["record.list", "record.page"],
    "expectedEmptyQueryIds": ["record.noMatch"]
  }
}
```

| 字段 | 什么时候填写 |
| --- | --- |
| `orderedQueryIds` | 返回顺序本身也必须一致 |
| `stableRangeQueryIds` | 已人工核实排序和取数范围稳定，例如排序末尾有唯一键，分页参数与数据一致 |
| `expectedEmptyQueryIds` | 明确预期该查询为空；两侧均空才满足预期 |

工具不会因为有 `ORDER BY` 就自动认定稳定。**分页、截断或顺序比较须同时有外层 `ORDER BY` 和稳定范围声明。** 不满足时，即使已读行一致也不能通过。

| 报告范围 | 含义 |
| --- | --- |
| `FULL` | 两侧均完整读取 SQL 返回结果 |
| `PAGE` | SQL 含限行或分页，且返回结果完整读完 |
| `PREFIX` | 两侧仍有后续行，只比较已保存的前 N 行 |
| `UNKNOWN` | 无法确认共同范围 |

分页识别是有限语法检查，不是完整方言解析。两侧均空且未声明预期为空时，判定内容覆盖不足；`COUNT=0` 是一行聚合值，可以正常比较。

### 结论怎么读

`comparison.observedStatus` 只描述已读行值；`comparison.status` 才是纳入执行状态、读取范围和空结果预期后的结论：

- `MATCH`：确认范围内一致。
- `DIFFERENT`：确认范围内存在差异。
- `INCONCLUSIVE`：执行失败、范围不确定或证据不足，不能判断通过。

每条最多展示 5 个差异样例，全部差异数量及原始行仍保留。前 N 行一致只代表前缀，不代表全部结果一致。

## 工作原理与运行限制

查询流程依次完成清单读取、SQL 预检、两侧参数绑定与执行、结果读取、整行比较和落盘。只接受保守识别的单条 SELECT 或查询型 WITH；拒绝多语句、写入、锁定、未展开模板、可执行注释和优化器提示。语法检查不能证明任意 SQL 无副作用，应使用查询权限账号。

并发时实际线程数为配置值与有效查询数的较小值。每个线程独占一对连接，同条查询先 source 后 target，最多使用两倍线程数的连接。主线程写出并同步该条结果后，工作线程才领取下一条，限制未落盘结果占用。

并发适用于互相独立的查询，不提供共同事务快照；依赖会话变量或执行顺序时设为 1。并发报告按落盘顺序保存，用 `id` 和逻辑序号 `sequence` 定位。

输入有误的条目标记 `NOT_EXECUTED`，其他有效条目继续。初始化时任一线程某侧连接失败，该侧整体不可用；运行中 SQLState `08` 会停止该侧的新执行，另一侧继续。不自动重连、重试或中断续跑；写盘和调度失败会停止本轮。

## 报告与退出码

| 文件 | 用途 |
| --- | --- |
| `queries.jsonl` / `cases.jsonl` | 逐条同步保存结果；中断后已完整写入的行仍可读取 |
| `verification.json` | 正常收尾后生成的正式汇总和完整证据 |
| `verification.html` | 离线首页，支持筛选和分页 |
| `html/<生成批次>/` | 独立条目详情，须与首页一起保存 |

报告保存 SQL、参数、双侧行值、执行错误、读取范围和比较结论。DML 另有前后预期、回滚、恢复与清理状态。配置密码会脱敏，但业务数据仍需妥善保管。临时文件不表示运行完成，旧 HTML 批次不自动清理。

| Java 退出码 | 含义 |
| --- | --- |
| `0` | 非空所选清单全部通过，DML 同时完成清理 |
| `2` | 完整报告中存在错误、差异、无法判定、未执行或空清单；DML 清理已确认 |
| `1` | 启动、配置、清单级错误，或调度、清理、写盘、HTML 生成等运行故障 |

Gradle 将非零 Java 退出码显示为任务失败。没有有效查询时不连接数据库。公开基础示例中的 `smoke.rows31` 未声明稳定范围，预期返回 2；`smoke.error` 是故意失败的查询，默认未选中。

已有完整 JSON 可离线重新生成页面，不需要配置或数据库：

```bash
./bin/sql-verifier --report reports/queries/<本轮目录>/verification.json
```

源码入口为 `./gradlew :sql-verifier:verify --args='--report build/sql-verification/<本轮目录>/verification.json'`。只有 JSONL 时不能转换。转换返回 0 仅代表页面生成成功，原数据库结论不变；失败时保留 JSON 和已有首页。

## 受控 DML 验证

### 准备和运行

DML 每条包含一条写 SQL、固定参数、预期影响行数，以及回查 SQL 和完整前后数据。可使用[写入配置](../config/verifier/verification.dml.example.json)和[三类用例](../examples/verifier/dml-cases.design.json)。

示例要求两库都已建好 `demo_order(id, status, amount)`：`id` 为 INTEGER 主键，`status` 为 VARCHAR，`amount` 为 DECIMAL；记录 1001 为 `PENDING / 12.50`，1002 不存在。工具不建表或初始化数据。

复制配置到 `config/local/verification-dml.json`，核对连接、表和凭据后，在仓库根目录运行：

```bash
./gradlew :sql-verifier:verify -PsqlVerifyConfig=config/local/verification-dml.json
```

| 写入专用配置 | 要求 |
| --- | --- |
| `dmlCases` | 替代 `queries`，两者必须二选一 |
| `allowedTables` | 非空精确表名列表，按实际 catalog/schema 核对 |
| `caseIds` | 精确筛选，省略或空数组执行全部 |
| `concurrency` | 默认且只能为 1 |
| 公共配置 | 沿用数据源、output、rows、超时和体积限制；不能填写 `queryIds` 或 `comparison` |

### 支持什么写入

验证器不执行 DELETE；即使 `caseIds` 显式选择删除用例，也会在建连前记录为 `NOT_EXECUTED`，错误阶段为 `INPUT`，原因为“验证器已禁用 DELETE，不执行删除用例”。其他有效 INSERT / UPDATE 用例继续执行。选中清单含 DELETE 时，未执行数计入汇总，正常收尾返回退出码 2；不将跳过视为验证通过。生成器仍可生成 DELETE 清单。

| 操作 | 允许范围 |
| --- | --- |
| INSERT | 显式列名单行 VALUES，使用参数绑定 |
| UPDATE | 完整主键或唯一键等值定位；支持参数赋值、精确数值列的固定整数、同列加一或限定的 null→1 CASE |
| DELETE | 禁止执行，在建连前记录为未执行 |
| 多行 UPDATE | 完整单列主键或唯一键的非空 `IN (?,...)`，不得混用其他 WHERE 条件或修改任何唯一键字段 |

UPDATE 的整组 WHERE 条件允许一层外括号，例如 `UPDATE demo_order SET status=? WHERE (id=?)`，用于接收 MP Wrapper 结构；原有唯一键、条件语法与恢复检查保持不变，不支持 OR 或任意嵌套表达式。

普通 UPDATE 等值路径可附加 AND 连接的 `列 IS NOT NULL`、`列 != 整数` 或 `<>` 条件；固定整数须在有符号 64 位范围内。CASE 仅限 `CASE WHEN 列 IS NULL THEN 1 ELSE 列 + 1 END`，各处必须是同一列。不接受注释、多语句、任意函数或运算、OR，以及字符串/小数字面量赋值。

回查必须投影完整定位键和所有显式写入字段，只用同一定位键及相同类型和值的参数筛选，不带业务过滤；不接受星号、聚合、别名、LIMIT 或锁定。IN 参数顺序须一致，数量不得超过 `rows`。前置、后置、恢复结果都必须读全，截断不能通过。

### 事务和恢复原理

每侧每例使用新连接，关闭自动提交并使用 `READ_COMMITTED`：**检查环境 → 前置回查 → 执行写入 → 后置回查 → 无条件回滚 → 恢复回查 → 回滚恢复读取事务并关闭**。两侧各自核对预期，再比较结果；相同报错或相同错误修改均不通过。

MySQL 协议下只接受引擎明确为 `InnoDB` 或 `YashanDB` 的事务表，并核查唯一键、可见触发器和外键级联。未知元数据或核查失败会拒绝写入。YashanDB 还在每例写入前核对服务端 `@@autocommit=0`、`@@tx_isolation=READ-COMMITTED`、`@@tx_read_only=0`。H2 只作为测试夹具支持。

UPDATE 向 Connector/J 请求 `useAffectedRows=true`，URL 中显式冲突会被拒绝。运行期间应保持表结构、索引和引擎稳定；表元数据仅在本轮按侧缓存，业务行每例重新读取。

普通 SQL 失败且清理通过时可继续另一侧。仅当写入已回滚、恢复检查通过、恢复读取事务已成功回滚，且此前无事务断连或清理异常时，关闭连接的通信异常（SQLState 以 `08` 开头）不停止后续用例。报告保留 `CLOSE` 错误和 `close: FAILED`，`cleanup: PASS` 表示事务恢复已确认，`cleanupReason` 说明允许继续的原因；该用例仍为 `FAIL`，整轮正常结束返回 2。

其他关闭错误、活动事务断连、回滚或恢复未确认仍停止后续写入。回滚调用成功不等于恢复成功，应看报告的 `runStatus` 和逐侧清理证据。

不支持提交、XA、重试或自动补偿。自增、序列及外部副作用不保证恢复，提交阶段和其他连接可见性不在验证范围；实际事务行为以指定环境的报告为准。
