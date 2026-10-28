# 验证配置示例

复制一份对应模式的 JSON，修改双侧连接及密码引用，再运行验证器。JSON 内的输入、输出和密码文件路径均相对于配置文件所在目录。

| 模式 | 配置 | 输入 |
| --- | --- | --- |
| 查询比较 | [verification.example.json](verification.example.json) | [queries.basic.json](../../examples/verifier/queries.basic.json) |
| 受控写入 | [verification.dml.example.json](verification.dml.example.json) | [dml-cases.design.json](../../examples/verifier/dml-cases.design.json) |

查询示例默认选择 11 条正常查询，排除了故意失败的 `smoke.error`；其中 `smoke.rows31` 未声明稳定范围，预期返回退出码 2。第一次只验证连接和绑定时，可把 `queryIds` 设为 `["smoke.ping", "smoke.string"]`。换成自己的清单后，清除旧 ID；`[]` 表示执行全部。

查询默认并发 16，设置 `concurrency: 1` 可串行。DML 只能串行，必须填写 `allowedTables`，并提前准备两侧相同的示例表和数据；示例文件不是数据初始化脚本。

源码用户可把配置复制到 `config/local/`；此时目录深度相同，示例输入路径仍有效。运行包用户可保存在本目录，在包根目录执行：

```bash
./bin/sql-verifier --config config/verifier/verification.json
```

Windows 使用 `bin\sql-verifier.bat`。源码用户使用 `./gradlew :sql-verifier:verify -PsqlVerifyConfig=config/local/verification.json`。两侧密码均通过 `passwordEnv` 或 `passwordFile` 提供，每侧二选一。

报告自动生成 `verification.json`、`verification.html` 和 `html/` 详情，无需额外 HTML 配置。源码示例输出在 `build/`，运行包输出在 `reports/queries/` 或 `reports/dml/`。

只想预览报告时，可将[人工查询报告](../../examples/verifier/reports/query/verification.json)或[人工 DML 报告](../../examples/verifier/reports/dml/verification.json)复制到输出目录，执行 `bin/sql-verifier --report <复制后的verification.json>`；此操作不连接数据库，返回 0 仅表示页面生成成功。

比较声明、参数类型、写入要求和退出码见[验证器使用说明](../../sql-verifier/README.md)。

验证器不执行 DELETE。选中的删除用例在建连前记录为 `NOT_EXECUTED/INPUT`，并继续其他有效 INSERT / UPDATE；即使 `caseIds` 显式选择也不能放行。含未执行用例时，正常收尾返回退出码 2。公开三类 DML 示例中的 DELETE 用于展示该跳过行为。
