# 查询与 DML 生成示例

[generator.example.json](generator.example.json) 是根目录维护的综合生成示例，读取 [SQL 输入](../../examples/generator/export/sql.json)，包含从实际提取结果选取的 6 条定义，覆盖 SELECT、INSERT、UPDATE、DELETE。生成器只读源库，输出 `queries.json`、`dml-cases.json` 和 `coverage.json`，不执行生成的写入语句。

## SQL 来源与范围

SQL 来自提取器已有的 `MenuRoleRelationMapper` 输出，保留原始语句 ID、模板、参数化 SQL、变体和提取诊断，仅将 XML 来源的本机绝对路径改成资源相对路径。

| 方法 | 演示内容 |
| --- | --- |
| `findList` | 菜单权限子查询，`dto.version` 的空值／非空动态分支 |
| `selectById`、`selectBatchIds` | MyBatis-Plus 生成的主键查询与 IN 查询 |
| `insert` | MyBatis-Plus 生成的四列 INSERT |
| `updateById` | 按主键更新菜单、角色与版本字段 |
| `deleteById` | 按主键删除 |

原提取结果中的 `deleteByIds` 集合删除不属于当前生成器支持的参数化 DML 语法，未纳入默认示例；IN 查询仍通过 `selectBatchIds` 演示。

这些是历史提取的 SQL 定义，不是本次重新扫描业务项目的结果。`setup.sql` 是按所需字段构造的演示表结构与人工数据，不代表业务数据库的实际字段类型、约束或数据；接入真实数据库时必须核对其结构及生成诊断。

## 准备与运行

1. 确认源库已有 `ai_menu`、`ai_menu_role_relation` 两张表及可采样数据。仅在新建的专用演示库中，才需手动执行 [setup.sql](../../examples/generator/setup.sql) 准备人工样本；已有业务库直接使用现有表和数据。
2. 将配置复制到 `config/local/generation.json`，按环境修改数据库地址和用户名。当前配置通过 `passwordFile` 引用 `config/local/generator-password.txt`，密码文件仅本地保存且已被忽略；其他环境需自行准备。两个目录深度相同，复制后输入输出和密码文件路径均无需修改。
3. 从仓库根目录运行：

```bash
./gradlew :sql-manifest-generator:run --args='--config ../config/local/generation.json'
```

IDEA 已提供 Gradle 类型的 `GeneratorMain（查询与DML示例）` 运行配置，从根工程调用 `:sql-manifest-generator:run`，参数为 `--args='--config ../config/generator/generator.example.json'`。Java 进程的工作目录为生成器模块目录，classpath 由模块 Gradle 依赖提供，避免依赖 IDEA 临时生成的 `GeneratorMain.main()` 任务。运行前确认数据源及本地密码文件可用；使用本地配置副本时，将参数改为 `--args='--config ../config/local/generation.json'`。若 IDEA 仍显示旧的 Application 配置，重新加载 Gradle 工程后选择 Gradle 类型的同名共享配置。

本轮结果位于根目录 `build/sql-generated/run-*/`。`simpleCaseCounts` 为简单语句设置各类别的生成数量，XML 动态语句另受 `maxXmlScenariosPerStatement` 控制；数量受实际数据与生成边界约束，不保证凑满。

## 接入业务 SQL

将 `input` 改为本轮提取结果目录（其中需有 `sql.json`），同时调整数据源和 `allowedTables`。JSON 内的相对路径以配置文件目录为基准。

DML 自动生成需要完整主键或唯一键及可用样本，无法推导的定义记录在覆盖诊断中。查询与 DML 清单后续由验证器分批执行；生成成功不代表双库验证通过。
