# 验证清单生成器

生成器把提取器的 SQL 模板变成**可执行的 SQL 和固定参数**，输出查询清单、受控 DML 用例及覆盖诊断。参数可以由你明确提供，也可以从一个源库只读采样。生成器不访问目标库，也不执行生成的写入语句。

当前提取器已移除参数配置入口，仅交付 `SAMPLE` 结构；业务参数在本生成器配置。不再提供历史 `CONFIGURED` 基准或 shared/sets 参数文件的兼容处理，顶层 `parameters` 不再读取。源码恢复的 Wrapper 更新按固定 SQL 及变体接入，支持 `SET 列=? WHERE (键列=?)` 等受控结构；可用 `writeValues` 指定更新值，并通过源库只读资料生成前后预期，完整唯一定位键仍是必要条件。

本模块与 `sql-extractor` 一样属于 `sql-tool` 根工程，通过项目依赖复用 `sql-query-contract` 读写清单，不依赖提取器或验证器的实现。运行需要 Java 17。

## 快速开始

在完整仓库根目录运行离线示例，Gradle 自动构建所需契约模块：

```bash
./gradlew :sql-manifest-generator:run --args='--config config/generator.offline.json'
```

示例使用自带提取样本，生成 4 条查询，展示精确数值及 XML 条件参数，不连接数据库。打开终端打印的 `sql-manifest-generator/build/sql-generated/run-*/`，先看 `queries.json`，再看 `coverage.json`。

在 IntelliJ IDEA 中，以 Gradle 工程打开仓库根目录并完成同步后，选择 Gradle 类型的 `GeneratorMain（查询与DML示例）` 运行配置。该配置调用 `:sql-manifest-generator:run`，由模块启动 `com.kairui.tools.sqlgenerate.GeneratorMain`；Java 工作目录为生成器模块目录，参数为 `--config ../config/generator/generator.example.json`。运行前需准备对应源库及其 `passwordFile` 引用的本地密码文件，使用 JDK 17。要改跑上面的离线示例，将 Gradle 参数改为 `--args='--config config/generator.offline.json'`。

**下文源码命令均在仓库根目录执行；生成任务的工作目录为 `sql-manifest-generator/`，`--config` 相对路径以该模块目录为基准。** 若使用已解压的运行包，则在包根目录执行：

```bash
./bin/sql-manifest-generator --config config/generator.offline.json
```

Windows 使用 `bin\sql-manifest-generator.bat`。运行包带有全部依赖和 MySQL 驱动，示例输出位于包内 `reports/run-*/`。

## 选择参数来源

| 你手里的资料 | 使用方式 | 配置示例 |
| --- | --- | --- |
| 已知每个 `?` 的值和类型 | 固定 `scenarios[].parameters`，离线生成 | [离线生成](config/generator.offline.json) |
| 已知 MyBatis 参数路径和值 | 类型化 `scenarios[].values`，求值动态模板 | [多组场景](config/generator.scenarios.json) |
| 没有现成参数，但源库有数据 | 配置只读数据源，按需设置排除名单，自动采样 | [查询采样](config/generator.sampling.mysql.example.json) |
| 需要准备受控写入用例 | 只读推导写入参数与前后预期 | [DML 采样](config/generator.dml.sampling.mysql.example.json) |

同一定义可以有多组显式场景；工具不会把不同场景的值混在一起。

## 接入自己的提取结果

`input` 指向**某一轮提取目录**，其中必须有 `sql.json`。生成器不读取 `errors.json` 和 `java-sql.json`，提取失败和静态片段请查看提取结果；有效定义自身的 `error`、`combinationWarnings` 仍参与生成诊断。`output` 为结果父目录，不能位于输入目录内。

下面的配置可放在本模块的 `config/local/generation.json`。替换提取轮次、连接信息和实际表名：

```json
{
  "input": "../../../build/sql-export/replace-with-run-directory",
  "output": "../../build/sql-generated",
  "datasource": {
    "driverClassName": "com.mysql.cj.jdbc.Driver",
    "jdbcUrl": "jdbc:mysql://localhost:3306/source_test",
    "username": "readonly",
    "passwordEnv": "SQL_SAMPLE_PASSWORD"
  },
  "excludedTables": [],
  "maxXmlScenariosPerStatement": 10,
  "sampleRows": 30
}
```

```bash
./gradlew :sql-manifest-generator:run --args='--config config/local/generation.json'
```

`--config` 按当前工作目录解析；JSON 中的所有路径按 JSON 所在目录解析。移动公开配置到 `config/local/` 后，需要重新核对其相对路径。

配置顶层的未知字段会被忽略；已知字段仍校验类型、范围和必填要求，嵌套配置仍拒绝未知字段。

采样凭据通过 `passwordEnv` 或 `passwordFile` 提供，必须二选一；JDBC URL 不允许携带凭据。`excludedTables` 为可选排除名单：不填或空数组不额外限制表；非空时禁止采样列出的表，其他表允许访问。表身份按实际 catalog/schema/table 匹配。旧生成配置中的 `allowedTables` 已不再生效，作为未知顶层字段忽略；迁移时应按禁止访问的表重新填写 `excludedTables`，不能直接复制原白名单。验证器的 DML `allowedTables` 规则不变。生成器使用只读提示和 `REPEATABLE_READ` 事务，结束后回滚；仍需使用只读账号。它不创建表、不造测试记录，也不自动读取验证器的数据源配置。

## 显式参数怎么写

### 固定 SQL：按问号顺序绑定

以下为 `statements` 配置片段，ID 必须对应提取结果。`parameters` 只绑定该定义顶层的 `parameterizedSql`，不会自动套到其他变体：

```json
{
  "statements": {
    "example.mapper.RecordMapper.find": {
      "scenarios": [
        {
          "name": "按编号查询",
          "parameters": [{"jdbcType": "BIGINT", "value": "42"}]
        }
      ]
    }
  }
}
```

### 动态 XML：提供原参数路径

使用 `values` 让 MyBatis 求值动态条件；`expand: false` 表示只求值这一组完整原值，默认 `true` 会继续探索有限组合：

```json
{
  "statements": {
    "example.mapper.RecordMapper.search": {
      "scenarios": [
        {
          "name": "指定状态",
          "expand": false,
          "values": {
            "query.status": {"jdbcType": "VARCHAR", "value": "ACTIVE"},
            "ids": [{"jdbcType": "INTEGER", "value": 1}]
          }
        }
      ]
    }
  }
}
```

每个场景只能选择 `parameters` 或 `values`。类型化值支持 `VARCHAR` 字符串、`INTEGER` 整数、`BIGINT/DECIMAL` 十进制字符串、`BOOLEAN` 布尔值，以及 ISO 本地格式的 `DATE/TIME/TIMESTAMP`。所有类型都可显式填 `null`，不能省略 `value`。集合用类型化值数组；标量根参数用 `$value`，不自动同步参数别名。

## 工作原理

### 1. 读取定义，得到候选 SQL

普通动态 XML 使用 Map 参数和内置 MyBatis 标签求值，记录实际触达的条件。自足模板可直接处理；无法可靠执行的业务方法、TypeHandler 或复杂表达式会形成缺口。

MyBatis-Plus 生成 SQL 和 Wrapper 定义优先消费已提取的顶层 SQL 与全部变体，再按实际列和占位符位置恢复参数，不重放 Wrapper 对象。`coverage.json` 中的 `extractedSql` 记录来源，`templateReplayed: false` 表示没有重放原模板。

### 2. 只读采样并核对源侧结果

自动采样从 SQL 中识别参数列、表关系及固定条件，从真实关联记录取值，再绑定回原业务 SQL。辅助取样可以弱化参数条件，但交付的业务 SQL 保持原条件、投影和占位符顺序；不会把 `字段 = ?` 的 null 参数改写成 `IS NULL`。

当前支持直接列比较、IN、BETWEEN、常见 LIKE、受限 INNER/LEFT JOIN、可恢复的子查询和 UNION 作用域，以及普通 GROUP BY / HAVING 聚合参数。整数、精确小数和日期时间可补充有限的相邻阈值。无键表允许有界前缀采样，但不承诺重复采样顺序相同。复杂关系或无法可靠反推的参数会留下缺口，可用显式场景补充。

有数据源时，还会在预算内执行候选查询的最终 SQL 和参数，只探测是否有行。已确认零行的候选从双库清单移除，原 SQL、参数及证据保存在 `coverage.json.skippedQueries`，标为 `SOURCE_EMPTY` / `NO_EXECUTION_REQUIRED`。这只说明本轮源侧为空，不代表两库一致；单列 COUNT 的 0 值另行记录，不当成业务记录命中。失败、未检查和预算不足也不会冒充空结果。

### 3. 去重、选择并冻结输入

相同 SQL 和有序参数合并来源；不同参数仍是不同用例。兼顾分支、结构、集合及页面种类；有源侧探测结果时优先保留有数据的查询。

最终清单写出后用共享契约重新读取校验，确认完整才生成 `READY`。验证器使用冻结的 SQL 和参数，不会重新采样。

## 数量和采样预算

| 配置项 | 默认值 | 作用 |
| --- | --- | --- |
| `maxXmlScenariosPerStatement` | `10`，范围 1—4096 | 每个 XML / 复杂定义的最终有效组合上限 |
| `maxCandidateAttempts` | `2048`，范围 1—65536 | 每定义结构探索与候选求值预算 |
| `simpleCaseCounts` | 四类各 `3` | 简单 SQL 按实际表和 insert/update/delete/select 共享配额，0 禁用对应简单类别 |
| `sampleRows` | `30` | 每表或关联作用域的样本行数上限 |
| `pageSampleSize` | `3` | 可绑定页大小的代表值，范围 1—1000 |
| `queryTimeoutSeconds` | `30` | 只读查询超时秒数 |
| `maxReadQueries` | `512` | 显式取样、回查和探测的总读取次数 |
| `maxSampleBytes` | `5242880` | 累计样本及回查值的体积预算 |
| `maxGeneratedCases` | `10000` | 全局唯一候选上限 |

所有表统一采用 `simpleCaseCounts`，仍按每张表和操作类别分别计数。旧配置中的 `tableCaseCounts` 不再生效，会作为顶层未知字段被忽略。

**最终条数与探索次数是两回事。** 只想每个 XML 生成 10 条时，设置最终上限即可，不必把探索预算也降到 10。简单配额不会裁剪 XML 或复杂查询，不能用它筛除所有写入定义。

达到人工预算、组合上限或简单配额不足会记录截断，例如 `STRUCTURE_LIMIT`、`SCENARIO_LIMIT`、`READ_QUERY_LIMIT`、`QUOTA_SHORTFALL`，不单独导致 `PARTIAL`。参数缺失、非法 SQL、读库失败等真实错误仍影响状态。数量不足时按实际交付，不复制用例凑数。

## 按需使用的高级配置

### 补充 XML include 资料

只有模板仍含未展开的 `include` 等资料缺口时，才需提供同版本 Mapper 和依赖片段文件：

```json
{
  "xmlResources": [
    {"path": "mappers/Mapper.xml", "sha256": "替换为该文件的64位小写SHA-256"}
  ]
}
```

用 `shasum -a 256 mappers/Mapper.xml` 取得指纹。工具在连接数据库前固定文件内容，核对 namespace、语句 ID、databaseId 和模板，再恢复同 namespace、跨资源及嵌套 include。最多 64 个资源、总量 8 MiB；DOCTYPE 离线读取，不访问外部实体。

指纹和模板匹配不能自动证明整个业务环境同版本。已改写表名或补过保留字引号的模板，与原 XML 不一致时可能报 `XML_VERSION_MISMATCH`；自足模板无需额外提供旧 XML。可运行 [include 示例](config/generator.includes.json)了解配置。

### 分页查询

自动分页支持外层 `LIMIT size OFFSET start`、`LIMIT start, size` 及固定 JDBC 问号形式。排序须包含相关表的完整非空唯一键，或全部明确分组列。可绑定 offset 时选取首、中、末和越界页，重复页合并；固定 offset 只生成对应页。

最终源侧零行页面仍按空结果规则排除。交给验证器时，须按生成的 ID 核对 `stableRangeQueryIds`、`orderedQueryIds` 和需要的空结果声明；生成器不会替你修改比较配置。

### 受控 DML 用例

生成器通过源侧读取推导前后预期，不试写。INSERT 使用真实字段样本及可核对的新键；UPDATE 优先用 `writeValues`，否则取同列不同真实值；DELETE 使用已有完整定位键。自动候选以正向单行变化为主，同值或无匹配用例需显式设置 `expectedAffectedRows: 0`。

支持单行显式列 INSERT、完整主键/唯一键定位的 UPDATE / DELETE；UPDATE 还支持受控单列唯一键 IN、固定整数赋值、同列加一及限定的 null→1 CASE。普通等值路径允许受限的 AND 附加条件，IN 路径不能混用其他条件。回查覆盖完整键和写入列，不能用业务过滤掩盖恢复结果。

自动生成比验证执行更保守：生成列、自增列、CHECK、外键、触发器、未知引擎或无法可靠确定的新值会形成缺口，不支持生成键或 selectKey 编排。新键仍需符合业务约束。元数据与完整回查在本轮只读事务内复用，失败、截断和超时结果不作为有效缓存；不跨轮共享数据。

### 单定义的其他选项

`statements.<完整ID>` 还支持 `source/databaseId` 消除同名来源歧义、`bindings` 映射参数路径到单表列、`writeValues` 按列提供类型化写入值，`parameterTypes` 为自动查询采样提供参数路径的 JDBC 类型提示。两库最终使用同一 SQL，不按目标库重新选择变体。

## 结果与退出码

| 文件 | 作用 |
| --- | --- |
| `queries.json` | 固定查询清单，交给验证器的 `queries` |
| `dml-cases.json` | 写入及完整前后预期，交给验证器的 `dmlCases` |
| `coverage.json` | 生成状态、定义去向、选择原因、采样证据与缺口 |
| `invalid-queries.json` | 空 IN、悬空 AND/OR、空条件括号等明确无效结构 |
| `READY` | 文件完整交付并通过格式校验的标记 |
| `generation-failure.json` | 写出失败时可能保留的诊断，不是成功产物 |

看报告时先检查 `status` 和 `diagnostics`，再用 `definitionOutcomes` 找到缺失定义；`statementSelections` 说明为何选中或排除候选，`xmlCoverage` / `autoSampling` 说明已观察的分支和采样范围。无效结构不会交给数据库执行，也不计为目标库兼容性失败。

| Java 退出码 | 含义与下一步 |
| --- | --- |
| `0` | 本轮规则和预算内生成完成；核对清单数量后再验证 |
| `2` | 有真实缺口；查看诊断，完整交付的有效清单仍可单独验证 |
| `1` | 配置、连接或交付失败；先修复，不能沿用失败目录 |

`READY` 与 `COMPLETE` 都不代表双库通过或路径穷尽。仅因预算截断或确认源侧为空，可能交付 `COMPLETE` 的空清单；空清单无需启动验证器，也不能算通过。Gradle 会把非零 Java 退出码显示为任务失败。

交接时使用明确的本轮目录，查询与 DML 分批验证。默认统一入口在 `PARTIAL` 时停止；设置 `partialPolicy: "continue"` 才会继续有效清单，整轮仍返回 2。

## 常见问题与能力边界

| 问题 | 处理方式 |
| --- | --- |
| 提取成功却无法生成参数 | `FILLED` 只说明 SQL 结构可生成；补充显式场景或真实采样依据 |
| 采样成功但查询未进清单 | 查 `skippedQueries` 和 `statementSelections`，区分源侧为空、数量选择和真实缺口 |
| 出现 `INVALID_SQL_STRUCTURE` | 检查原模板的集合和条件保护，不删除条件或填虚构值绕过 |
| 生成的分页仍无法比较 | 在验证器中声明经核实的稳定范围，检查外层排序和两库数据 |
| 业务类型、复杂 OGNL 或 SQL 关系无法恢复 | 提供等价显式参数；工具不完整重建业务 classpath、DTO、TypeHandler、自定义语言驱动或插件链 |

生成数量、标签触达和源侧命中均不证明所有路径已覆盖。CTE、复杂派生表关系、扩展分组、复杂分页及运行 SQL 捕获不在自动恢复范围。

## 构建与打包

使用 [项目说明](../README.md#统一构建与检查)中的统一构建入口，或从仓库根目录直接构建本模块。契约由 Gradle 项目依赖自动构建，无需指定外部 JAR：

```bash
./gradlew :sql-manifest-generator:distZip
```

源码需在完整 `sql-tool` 工程内构建。归档在 `sql-manifest-generator/build/distributions/`；`:sql-manifest-generator:installDist` 创建 `sql-manifest-generator/build/install/sql-manifest-generator/`。运行包包含公开配置及样本，不包含 H2 测试驱动和跨组件流程模板；使用其他驱动时需将驱动及依赖加入 Java classpath。
