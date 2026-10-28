# SQL Tool

SQL Tool 用于数据库迁移或替换时的 SQL 核对：从 MyBatis / MyBatis-Plus 项目中提取 SQL，为它们准备固定参数，再在两个数据库执行并比较结果。各工具可以单独使用，也可以由一条命令串联。

```text
业务项目 ──提取器──→ SQL 模板 ──生成器──→ SQL + 固定参数 ──验证器──→ 双库比较报告
                                    ↑
                         显式参数或源库只读采样
```

提取器不接受业务参数文件，内部样例仅用于恢复结构；真实参数统一由生成器准备。Wrapper 支持查询及受限 `set/eq` 更新源码恢复，不新增 Wrapper 删除支持。

提取无需启动完整 Spring 应用或连接数据库；生成器可离线使用显式参数，也可连接一个源库取样；验证器只需要清单、JDBC 驱动和两个数据源，不需要业务源码。

## 先选择使用方式

| 你已有的资料或目标 | 使用方式 | 得到什么 |
| --- | --- | --- |
| 想盘点项目中的 SQL | 只运行提取器 | SQL 模板、条件变体、来源和跳过原因 |
| 已有提取结果，需要补齐参数 | 只运行生成器 | `queries.json`、`dml-cases.json`、覆盖诊断 |
| 已有 SQL 和固定参数 | 只运行验证器 | 双库执行结果、差异和离线 HTML 报告 |
| 从业务项目开始做一次完整核对 | 使用统一入口 | 本轮提取、生成、验证产物及汇总 |

查询验证默认无序比较整行，保留重复记录次数；也可声明顺序、稳定取数范围和预期空结果。受控 INSERT / UPDATE 单独运行；DELETE 在建连前标记为未执行，每侧独立事务，检查修改前后数据，成功或失败均回滚并核对恢复。

工具不负责同步两库数据、还原所有业务执行路径或验证整个应用。**两库比较前，需要准备可比较的数据和会话条件。** 写入验证仅支持受限的单表操作，不覆盖提交、跨库事务或外部副作用。

## 先试用离线示例

源码运行需要 **JDK 17**；统一脚本还需要 **Python 3.9 或以上**。使用仓库自带的 Gradle Wrapper，首次构建需要下载依赖。下文命令均从仓库根目录执行，Windows 将 `./gradlew` 换为 `gradlew.bat`。

启动前确认 `java -version` 显示 Java 17；如果设置了 `JAVA_HOME`，也应指向 JDK 17。下面的文件复制命令可在 macOS / Linux 终端或 Windows Git Bash 中执行。

先提取仓库中的示例，不连接数据库：

```bash
./gradlew run -PsqlExportConfig=config/extractor/demo.json
```

打开终端打印的 `build/sql-export/<本轮目录>/`：先看 `all.sql` 了解提取内容，再看 `errors.json` 和 `exclusions.json` 判断哪些定义未提取成功。SQL 中的 `?` 会保留。

再用生成器自带的提取样本演示参数绑定，两个示例相互独立：

```bash
./gradlew :sql-manifest-generator:run --args='--config config/generator.offline.json'
```

结果位于 `sql-manifest-generator/build/sql-generated/run-*/`。其中 `queries.json` 是带类型和有序参数的执行清单，`coverage.json` 说明生成范围；此步骤没有执行双库验证。

需要同时生成查询及 INSERT／UPDATE／DELETE 用例时，使用根目录的 [生成器综合示例](config/generator/README.md)。该示例提供一份生成配置、来自真实提取结果的 6 条 SQL 定义及演示建表数据脚本，需要准备专用源库并使用只读连接，结果输出到根目录 `build/sql-generated/`。

## 从业务项目运行完整流程

以下示例使用与本仓库同级的 `target-project/`，目标 Gradle 模块为 `:app`。替换项目路径、包名、数据库地址和表名后使用。自动接入会调用目标项目自己的 Wrapper 编译业务类，更新构建输出与缓存；已有编译产物也可以通过提取器的 `classpath` 接入。

**提取 MyBatis-Plus 自动生成的 SQL，必须让工具加载业务运行时 classpath。** MP 的通用 CRUD 通常不写在 XML 中，而是在注册 Mapper 时根据实体映射和注入器生成。`project-root/module` 会自动编译并准备 classpath，无需手工整理 JAR；只有 `packages` 或 `source-root` 无法替代业务类和依赖。具体过程、所需文件和排错方法见[提取器的 classpath 说明](sql-extractor/README.md#重点为什么提取-mybatis-plus-sql-必须有-classpath)。

### 1. 从仓库示例复制配置

仓库已提供三份工具配置示例和一份统一入口示例。先按下表复制，再修改本地副本；**右侧的文件名是本教程约定的复制目标，仓库不会预先提供这些本地文件。**

| 用途 | 仓库已有的源文件 | 复制到仓库根目录下 |
| --- | --- | --- |
| 提取 | [config/extractor/pipeline.extractor.example.json](config/extractor/pipeline.extractor.example.json) | `config/local/extraction.json` |
| 生成清单 | [sql-manifest-generator/config/pipeline.generator.example.json](sql-manifest-generator/config/pipeline.generator.example.json) | `config/local/generation.json` |
| 查询验证 | [config/verifier/pipeline.verification.example.json](config/verifier/pipeline.verification.example.json) | `config/local/verification.json` |
| 统一入口 | [config/pipeline/pipeline.example.json](config/pipeline/pipeline.example.json) | `config/local/pipeline.json` |

在仓库根目录执行以下复制命令。`cp -i` 遇到同名文件时会询问是否覆盖；已有配置需继续使用时，保留它或另取目标文件名，并同步调整统一入口中的引用。

```bash
mkdir -p config/local
cp -i config/extractor/pipeline.extractor.example.json config/local/extraction.json
cp -i sql-manifest-generator/config/pipeline.generator.example.json config/local/generation.json
cp -i config/verifier/pipeline.verification.example.json config/local/verification.json
cp -i config/pipeline/pipeline.example.json config/local/pipeline.json
```

`config/local/` 被 Git 忽略，需要在克隆后按上述命令创建。`build/`、契约 JAR 和工具运行目录由后续构建自动生成；首次使用不需要复制其他人的本地配置、classpath 清单或历史运行结果。

复制完成后的布局如下，后续修改都针对 `config/local/` 中的副本：

```text
workspace/
├── target-project/                 业务项目
│   └── app/src/main/               Java 源码与 Mapper XML
└── sql-tool/                       本工具仓库
    ├── config/local/
    │   ├── extraction.json         提取配置
    │   ├── generation.json         生成配置
    │   ├── verification.json       查询验证配置
    │   └── pipeline.json           统一入口配置
    └── build/                      运行产物
```

JSON 中的相对路径以**该 JSON 所在目录**为基准。按上述布局，`../../../target-project` 指向同级业务项目，`../../build` 指向本工具的构建目录。复制后重点调整这些路径：

| 本地副本 | 从源文件复制后要调整的字段 |
| --- | --- |
| `extraction.json` | 将 `xml-root` 从仓库演示目录改为业务 Mapper 目录；添加 `project-root/module` 自动准备业务 classpath，按需添加 `source-root/source-packages` |
| `generation.json` | 将 `output` 从 `../build/sql-generated` 改为 `../../build/sql-generated`；`input` 仍是提取轮次占位路径，统一入口会自动替换 |
| `verification.json` | `output: ../../build/sql-verification` 可保持；该流程模板没有 `queries`，由统一入口填写 |
| `pipeline.json` | 将三项组件引用改为本地文件名，具体内容见第 2 步；`output: ../../build/sql-pipeline` 可保持 |

生成器源文件位于 `sql-manifest-generator` 模块的 `config/`，其 `../build` 原本指向生成器自己的构建目录；复制到根 `config/local/` 后，需改成上述路径。其余三份源文件与复制目标的目录深度相同，但业务目录和组件引用仍需按用途修改。

**修改 `config/local/extraction.json`：从演示项目改为自己的业务项目。** 同时将 `packages` 替换为实际 Mapper 包。下面给出修改后的示例：

```json
{
  "project-root": "../../../target-project",
  "module": ":app",
  "packages": "example.mapper",
  "xml-root": "../../../target-project/app/src/main/resources/mapper",
  "source-root": "../../../target-project/app/src/main/java",
  "source-packages": "example",
  "sources": "xml",
  "output": "../../build/sql-export"
}
```

此配置先提取 XML。需要其他来源时再调整 `sources`；Java 静态片段只用于定位，不能直接进入执行清单，生成器会记录相应缺口。

**修改 `config/local/generation.json`：设置源库采样。** 修改 `datasource` 中的连接地址、账号及密码引用，按需用 `excludedTables` 指定禁止采样的表；不填或空数组不额外限制表。以下给出核心字段，其余采样预算可沿用复制的示例：

```json
{
  "input": "../../build/sql-export/replace-with-run-directory",
  "output": "../../build/sql-generated",
  "datasource": {
    "driverClassName": "com.mysql.cj.jdbc.Driver",
    "jdbcUrl": "jdbc:mysql://localhost:3306/source_test",
    "username": "readonly",
    "passwordEnv": "SQL_SAMPLE_PASSWORD"
  },
  "excludedTables": [],
  "maxXmlScenariosPerStatement": 10
}
```

统一入口会自动替换 `input`；单独运行生成器时，必须改为真实提取轮次目录。若本轮只做查询，使用仅含查询定义的提取输入；简单 DML 配额设为 0 不能禁用 XML DML 生成。

**修改 `config/local/verification.json`：设置待比较的两个数据源。** 修改 `datasources.source/target` 的连接地址、账号及密码引用；源库须与生成器采样源库对应。这份流程模板的 `queries` 由入口自动填写，单独运行验证器时需自行补上清单路径。以下给出核心字段，比较配置及超时等可沿用复制的示例：

```json
{
  "output": "../../build/sql-verification",
  "rows": 30,
  "concurrency": 16,
  "queryIds": [],
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

在启动工具的同一个终端中设置密码变量。上例的采样与查询使用同一个源库账号，Bash 中可交互输入两次密码，再复用源库密码：

```bash
read -r -s -p '源库密码：' SQL_SOURCE_PASSWORD; echo
read -r -s -p '目标库密码：' SQL_TARGET_PASSWORD; echo
export SQL_SOURCE_PASSWORD SQL_TARGET_PASSWORD
export SQL_SAMPLE_PASSWORD="$SQL_SOURCE_PASSWORD"
```

这段输入命令适用于 Bash（包括 Windows Git Bash）；其他 Shell 使用各自的环境变量设置方式。如果采样使用独立账号，需单独设置 `SQL_SAMPLE_PASSWORD`。工具不会自动读取 `.env` 文件，仅创建该文件不会设置上述变量。

每个数据源也可用 `passwordFile` 引用自己创建的本地密码文件，和 `passwordEnv` 二选一；不直接填写 `password`，不在 JDBC URL 中放凭据。**切换源库时，同时修改生成器的 `datasource` 和验证器的 `datasources.source`。**

### 2. 连接配置并运行

修改第 1 步复制得到的 `config/local/pipeline.json`。源文件引用的是仓库公共示例；现在改为本地副本：

| 字段 | 复制时的值 | 改为 |
| --- | --- | --- |
| `extractorConfig` | `../extractor/pipeline.extractor.example.json` | `extraction.json` |
| `generatorConfig` | `../../sql-manifest-generator/config/pipeline.generator.example.json` | `generation.json` |
| `queryVerificationConfig` | `../verifier/pipeline.verification.example.json` | `verification.json` |

三项引用都相对于 `pipeline.json` 所在目录。修改后的完整配置如下；只填文件名即可指向同目录的 JSON：

```json
{
  "extractorConfig": "extraction.json",
  "generatorConfig": "generation.json",
  "queryVerificationConfig": "verification.json",
  "output": "../../build/sql-pipeline",
  "partialPolicy": "stop"
}
```

```bash
python3 scripts/sql-pipeline.py --config config/local/pipeline.json
```

命令中的 `--config config/local/pipeline.json` 从仓库根目录解析；JSON 内的配置引用则从 `config/local/` 解析。

入口自动构建工具，并在独立轮次目录中传递文件，不改写原配置。**统一运行时，实际产物全部放到 `build/sql-pipeline/<本轮目录>/`，各工具示例中的独立输入、输出路径会被覆盖：**

| 工具配置字段 | 统一入口实际填写的位置 |
| --- | --- |
| 提取器 `output` | 本轮目录下的 `extraction/` |
| 生成器 `input` / `output` | 本轮提取结果目录 / 本轮目录下的 `generation/` |
| 验证器 `queries` / `output` | 本轮生成的 `queries.json` / 本轮目录下的 `query-verification/` |

业务工程、XML、源码和密码文件等路径仍以各自原配置目录为基准。上例只运行查询验证；写入验证需另行提供 `dmlVerificationConfig`，并使用串行、表白名单和完整前后预期。

### 3. 查看结果

先看终端打印的汇总和报告路径，再按下面的顺序检查：

| 文件 | 重点看什么 |
| --- | --- |
| `pipeline.json` | 流程停在哪一步、退出码和各产物位置 |
| `extraction/<本轮目录>/errors.json`、`exclusions.json` | 是否存在未提取或被排除的 SQL |
| `generation/<本轮目录>/coverage.json` | 参数缺口、采样范围、未选中及源侧空结果 |
| `query-verification/<本轮目录>/verification.html` | 双库结论、问题筛选和单条详情 |

HTML 可直接离线打开；移动或分享时保留同级 `html/` 详情目录。报告含 SQL、参数和数据，应按业务数据要求保管。

统一入口退出码 `0` 表示生成完成、至少一个非空批次执行且所有已执行批次通过；`2` 表示生成有缺口、验证未通过或没有可验证用例；`1` 表示配置、构建或运行故障；`130` 表示中断。

生成有真实缺口时默认停止。确认接受缺口后，可设置 `partialPolicy: "continue"` 验证本轮有效清单，但整轮仍返回 `2`。达到人工数量或读取预算会记录截断，不单独算生成失败；`COMPLETE` 不表示组合已穷尽。

## 重跑时从哪一步开始

源码未变化时，无需每次都重新提取。先构建可直接运行的目录：

```bash
python3 scripts/sql_workspace.py build
```

- **源码或 Mapper 变化**：重新运行统一入口。
- **只调整参数或采样数据**：将 `generation.json` 的 `input` 指向保留的提取目录，然后运行生成器。
- **SQL 与参数均不变**：将 `verification.json` 的 `queries` 指向保留的 `queries.json`，只运行验证器。

```bash
java -cp 'sql-manifest-generator/build/install/sql-manifest-generator/lib/*' \
  com.kairui.tools.sqlgenerate.GeneratorMain --config config/local/generation.json
```

生成后先检查本轮 `READY`、`coverage.json` 和清单数量，再把验证配置的 `queries` 指向本轮文件。不要用历史目录代替本轮失败产物。

```bash
java -cp 'sql-verifier/build/install/sql-verifier/lib/*' \
  com.kairui.tools.sqlverify.SqlVerifyMain --config config/local/verification.json
```

生成器退出码 `2` 仍可能有可验证的有效清单，因此两个命令不宜直接用 `&&` 串接。`queryIds: []` 执行整份查询清单；保留旧示例 ID 会造成筛选错误。

## 如何理解“结果一致”

默认每侧保存前 `30` 行，再探测一行判断是否还有数据。完整且非空的结果可直接比较；分页、截断或顺序比较还需要外层 `ORDER BY` 及 `stableRangeQueryIds` 声明。两侧均空只有声明 `expectedEmptyQueryIds` 才能通过。声明方式和例子在验证器 README 中集中说明。

`MATCH` 表示确认范围内一致，`DIFFERENT` 表示有差异，`INCONCLUSIVE` 表示证据不足。前 30 行一致不代表全部结果一致；提取成功、生成 `READY` 或 HTML 生成成功，也都不能代替数据库比较结论。

## 模块与进一步使用

基础流程无需阅读设计文档。需要调整某个工具时，直接阅读对应模块；每份说明包含启动方式、配置、原理、产物和排错方法。

| 模块 | 职责与说明 |
| --- | --- |
| [sql-extractor](sql-extractor/README.md) | 提取来源、业务项目接入、动态条件和 Wrapper 处理 |
| [sql-manifest-generator](sql-manifest-generator/README.md) | 显式参数、只读采样、场景选择和覆盖诊断 |
| [sql-verifier](sql-verifier/README.md) | 独立运行包、比较规则、受控 DML 和报告 |
| [sql-query-contract](sql-query-contract/README.md) | 编写或集成查询 / DML 清单时使用的格式与类型 |

[统一入口说明](docs/统一入口使用说明.md)集中介绍流程参数、额外驱动、缺口处理和失败定位；[配置目录](config/README.md)帮助选择现有示例。

## 统一构建与检查

```bash
python3 scripts/sql_workspace.py build
python3 scripts/sql_workspace.py check
```

`build` 通过根工程构建全部模块，准备生成器和验证器的运行目录；`check` 执行全部模块检查、H2 组件兼容检查及 Python 入口测试，不连接业务数据库。缓存齐全时可加 `--offline`。

根 Gradle 工程包含提取器、生成器、验证器和契约模块，共用根目录 Wrapper、JDK 与版本配置。根目录 `./gradlew compileJava`、`./gradlew test` 包含生成器；仅检查生成器可执行 `./gradlew :sql-manifest-generator:test`。

JAR 位于各模块 `build/libs/`；生成器和验证器的归档在各自 `build/distributions/`，运行目录在 `build/install/`。仅使用独立运行包时只需要 Java 17，无需 Gradle 或 Python。
