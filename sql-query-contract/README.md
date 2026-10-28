# SQL 清单格式与读取模块

本模块定义查询和受控 DML 的交接格式，并提供 Java 模型、JSON 读取和校验。手工准备清单、对接其他工具或开发清单生成程序时使用；它不连接数据库，也不依赖 MyBatis、Spring 或 JDBC 驱动。

查询文件顶层使用 `queries`，写入文件使用 `cases`，两种清单分别交给验证器运行。数据库地址、筛选和比较声明放在验证配置中，不写入清单。

## 查询清单

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

`id` 是清单内唯一的非空字符串，`sql` 为非空字符串；`parameters` 可省略，默认空数组。参数按数组顺序对应问号，每项必须同时包含 `jdbcType` 和 `value`。显式 `null` 合法，缺少 `value` 是错误。

## 参数类型

| `jdbcType` | 非空值格式 | 示例 |
| --- | --- | --- |
| `VARCHAR` | JSON 字符串 | `"示例"` |
| `INTEGER` | 32 位有符号 JSON 整数 | `42` |
| `BIGINT` | 64 位有符号十进制整数字符串 | `"9223372036854775806"` |
| `DECIMAL` | 十进制字符串 | `"12.50"` |
| `BOOLEAN` | JSON 布尔值 | `true` |
| `DATE` | ISO 本地日期 | `"2026-10-15"` |
| `TIME` | ISO 本地时间 | `"09:30:00.123"` |
| `TIMESTAMP` | ISO 本地日期时间，不带时区偏移 | `"2026-10-15T09:30:00.123"` |

类型名必须大写，未知类型或不符合编码的值不会自动转换。八种类型均允许 null；时间最多保留 9 位小数秒。大整数和小数用字符串传递，避免经过浮点数损失精度。

## 受控 DML 清单

下面的用例将已有记录 1 的状态从 ACTIVE 改为 CLOSED，并声明影响 1 行。是否允许执行仍由验证器检查表、键和事务条件。

```json
{
  "cases": [
    {
      "id": "record.close",
      "sql": "UPDATE demo_record SET status = ? WHERE id = ?",
      "parameters": [
        {"jdbcType": "VARCHAR", "value": "CLOSED"},
        {"jdbcType": "INTEGER", "value": 1}
      ],
      "expectedAffectedRows": 1,
      "probe": {
        "sql": "SELECT id, status FROM demo_record WHERE id = ?",
        "parameters": [{"jdbcType": "INTEGER", "value": 1}],
        "columnTypes": ["INTEGER", "VARCHAR"],
        "expectedBefore": [[1, "ACTIVE"]],
        "expectedAfter": [[1, "CLOSED"]]
      }
    }
  ]
}
```

`probe` 是写入前、写入后和回滚后的回查定义。`columnTypes` 按投影列顺序声明类型，预期行中的每个值沿用上表编码；行宽必须与类型数一致。没有行用 `[]`，一行中某列为空则在该位置填 `null`。

`expectedAffectedRows` 接受非负 int。格式层允许多行，但执行器仍要求受控 SQL 子集：等值定位限 0 或 1 行，多行 UPDATE 需满足完整单列唯一键 IN 等规则。格式有效不能代替写入预检。

## Java 接入

从仓库根目录构建 JAR：

```bash
./gradlew :sql-query-contract:jar
```

产物在 `sql-query-contract/build/libs/`。读取入口位于 `com.kairui.tools.sqlquery`：

```java
import com.kairui.tools.sqlquery.QueryManifest;
import com.kairui.tools.sqlquery.QueryManifestReader;
import java.nio.file.Path;

QueryManifest manifest = new QueryManifestReader().read(Path.of("queries.json"));
// issues() 保存逐条输入错误；不能仅检查 queries() 中的有效条目。
System.out.println(manifest.queries().size());
System.out.println(manifest.issues().size());
```

DML 使用 `DmlManifestReader.read(Path)`，返回 `DmlManifest`，通过 `cases()` 和 `issues()` 分别读取有效用例与诊断。提取器、生成器和验证器均为根工程子模块，通过 Gradle 项目依赖复用本模块。

## 校验原理与错误处理

读取器先检查整体 JSON、字段白名单和 ID，再逐条解码 SQL、参数与预期值，避免一条内容错误阻断其他有效输入。

| 错误类别 | 行为 |
| --- | --- |
| JSON 结构错误、未知字段、缺失或重复 ID | 终止整份清单读取 |
| 单条 SQL、参数编码、DML 预期类型或行宽无效 | 放入 `issues()`，其他有效条目保留 |
| 空 `queries` / `cases` 数组 | 可以读取，但验证器不会据此判定通过 |

本模块不检查占位符数量、SQL 执行范围、真实表结构或数据库语法。前两者由验证器预检，数据库行为由 JDBC 执行确认。清单只携带固定输入，不包含业务 TypeHandler、来源对象或比较规则。

需要更多输入样例时，可直接查看[基础查询](../examples/verifier/queries.basic.json)与[INSERT / UPDATE / DELETE 用例](../examples/verifier/dml-cases.design.json)。
