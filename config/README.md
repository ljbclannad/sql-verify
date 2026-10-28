# 选择配置示例

按要运行的工具选择模板，再复制为自己的本地配置。示例中的数据库地址、业务路径和表名需要替换；工具不会自动准备业务表或数据。

| 要做什么 | 从哪份配置开始 |
| --- | --- |
| 离线试用 SQL 提取 | [extractor/demo.json](extractor/demo.json) |
| 接入业务 Gradle 项目 | 按[提取器接入示例](../sql-extractor/README.md#接入自己的项目)填写项目、模块和扫描范围 |
| 已有提取结果，显式提供参数 | [generator.offline.json](../sql-manifest-generator/config/generator.offline.json) |
| 同时生成查询与 DML 用例 | [generator/generator.example.json](generator/generator.example.json)，准备与运行见[综合示例说明](generator/README.md) |
| 从源库只读采样参数 | [generator.sampling.mysql.example.json](../sql-manifest-generator/config/generator.sampling.mysql.example.json) |
| 执行查询比较 | [verification.example.json](verifier/verification.example.json) |
| 执行受控写入验证 | [verification.dml.example.json](verifier/verification.dml.example.json) |
| 串联提取、生成和验证 | [pipeline.example.json](pipeline/pipeline.example.json) |

## 保存本地配置

仓库本地配置建议放在 `config/local/`，该目录已忽略。生成器也可使用自身 `config/local/` 或工程外目录。密码使用 `passwordEnv` 或 `passwordFile`，不能直接填在数据源 JSON 或 JDBC URL 中。

相对路径以**包含该字段的 JSON 所在目录**为基准。提取和验证示例复制到根 `config/local/` 时目录深度不变；生成器示例换目录后需调整 `input/output` 和参数文件路径。classpath 文本清单里的相对条目则按清单自身目录解析。

统一入口只引用各组件配置，并为本轮生成副本、注入输入输出路径。`pipeline.*.json` 属于流程模板；验证模板省略了由入口填写的输入字段，不能直接当作独立验证配置运行。

## 示例与运行结果

源码示例通常输出到 `build/`，独立运行包输出到 `reports/`。`examples/verifier/reports/` 是人工构造的报告阅读样例，不是数据库验证记录；转换 HTML 前先复制到自己的输出目录。

生成器综合示例在根目录 `config/generator/` 维护，配套 SQL 输入和建表脚本在 `examples/generator/`；模块内的 `config/`、`examples/` 保留各专题示例及运行包资料。提取和验证资料在仓库对应目录维护。打包任务从模块所属来源组装并调整输出路径，不包含本地配置和运行产物；根目录综合示例仅随完整源码提供。
