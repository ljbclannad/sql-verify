# SQL 提取器

从 MyBatis / MyBatis-Plus 项目中整理 SQL，输出参数化语句、动态条件变体和来源诊断。它适合盘点 SQL、排查提取缺口，以及为清单生成器提供输入；无需启动完整 Spring 应用，也不连接数据库。

**提取结果保留 `?`，不包含完整的有序执行参数。** `all.sql` 用于阅读，不能直接当作双库验证清单或整份执行脚本。

## 重点：为什么提取 MyBatis-Plus SQL 必须有 classpath

MyBatis-Plus 的 `selectById`、`insert`、`updateById` 等通用方法，通常只有 `RecordMapper extends BaseMapper<RecordEntity>` 这样的接口声明，项目 XML 中并没有对应 SQL。理解它们怎样生成，才能理解为什么本工具需要业务 classpath。

### 先理解 MP：SQL 分两步形成

**第一步，注册 Mapper 时生成 SQL 定义。** MP 从 `BaseMapper<RecordEntity>` 解析出实体类型，结合 `@TableName`、`@TableId`、`@TableField` 等注解、字段和命名配置建立 `TableInfo`。这里的“表信息”是从 Java 映射得到的元数据，不是通过连接数据库读取表结构。

SQL 注入器再根据这份元数据，为通用方法创建 SQL 脚本，并注册为 `MappedStatement`。可以把它理解为“方法 ID 对应的 SQL 定义”，其中的 `SqlSource` 负责根据参数得到实际 SQL。`BaseMapper` 提供通用方法约定；具体表名、列名、主键条件由实体映射和注入规则决定，所以业务接口不必逐条写 CRUD SQL。自定义注入器可以增减这些定义，实际方法集合以注册结果为准。

**第二步，调用 Mapper 时按参数形成 SQL。** 业务调用由 Mapper 代理转交 MyBatis，通过方法 ID 找到已注册的定义；动态脚本结合实体参数或 Wrapper 条件求值得到 `BoundSql`，其中包含带 `?` 的 SQL 和参数映射。随后才进入参数绑定、JDBC 执行和结果处理。注册时有了模板，并不意味着每一次调用的最终 SQL 已经固定。

例如，实体映射到 `demo_record`，主键为 `id`，另有 `name` 字段。在没有逻辑删除等额外规则的简单情况下，`example.mapper.RecordMapper.selectById` 求值后可以得到：

```sql
SELECT id, name FROM demo_record WHERE id = ?
```

这里的表名、投影列和主键来自实体映射，问号对应本次调用的主键参数。实际执行时由 JDBC 绑定值，不需要把参数拼成 SQL 字符串。

### 通用 CRUD、Wrapper 和拦截器不是同一层

| 机制 | 作用时机与内容 | 对提取的影响 |
| --- | --- | --- |
| 通用 CRUD / SQL 注入器 | 注册 Mapper 时，根据实体映射生成方法的 SQL 定义 | 需要加载 Mapper、实体和注入器 |
| Wrapper 条件 | 业务调用时携带筛选、投影、排序等内容，参与动态 SQL 求值 | 只有 classpath 不能知道每次调用的条件；需受支持的源码恢复，执行参数由生成器提供 |
| 分页、租户、动态表名等拦截器 | 在执行流程中按插件和上下文进一步处理 SQL | 生成模板不代表已执行整条业务插件链，提取器只处理明确支持的适配 |

例如，同一个 `selectList`，传入不同 Wrapper 可以得到不同 WHERE 条件；启用分页或租户插件后，执行 SQL 还可能继续变化。不能把“提取到了 `selectList`”理解为“所有业务查询都已覆盖”。上述框架流程按本项目使用的 MP 3.5.7 核对，相关实现可查 [SQL 注入器](https://github.com/baomidou/mybatis-plus/blob/v3.5.7/mybatis-plus-core/src/main/java/com/baomidou/mybatisplus/core/injector/AbstractSqlInjector.java)和 [Mapper 方法调用](https://github.com/baomidou/mybatis-plus/blob/v3.5.7/mybatis-plus-core/src/main/java/com/baomidou/mybatisplus/core/override/MybatisMapperMethod.java)。

### 为什么源码和包名不能代替 classpath

直接解析 `.java` 生成 SQL 在技术上可行；这里的 classpath 要求针对本工具复用 MP 注册与求值机制的实现路径。选择类加载是为了复用框架规则，减少重复实现，并不表示源码缺少类型、泛型或注解信息。完整原理、替代方案与成本见 [SQL 提取的源码解析与类加载取舍](../docs/SQL提取的源码解析与类加载取舍.md)。

classpath 是 Java 查找类和资源的路径集合，可以包含编译输出目录、资源目录和普通 JAR。**本工具需要借助 MP 完成注册和 SQL 求值，因此必须先让 Java 加载业务 Mapper、实体及其依赖。** 源码目录供静态扫描使用，包名只限定扫描范围，都不能直接提供框架运行所需的类。

只有 MP 依赖 JAR，工具不知道你的实体和表；只有 Mapper 的 `.class`，却缺少实体或父接口，也无法完成解析。**需要的是包含业务类及其依赖的运行时 classpath，而不只是 MyBatis-Plus 本身。**

### 工具怎样从 classpath 得到 SQL

```text
业务编译产物 + 依赖 JAR
          ↓ 加入提取进程的 classpath
扫描 packages 下的 Mapper .class
          ↓ 加载接口、解析 BaseMapper 的实体类型
注册到独立 MybatisConfiguration
          ↓ MP 读取实体映射，调用默认或指定的 SQL 注入器
取得 MappedStatement 和动态 SQL 模板
          ↓ 使用参数求值，得到 BoundSql
导出保留 ? 的 SQL、模板与诊断
```

1. **准备加载路径。** Gradle 启动任务将业务 classpath 追加到工具依赖之后。自动接入时，从目标模块的 `sourceSets.main.runtimeClasspath` 导出；手工接入时，读取配置中的目录和 JAR。
2. **发现具体 Mapper。** 按 `packages` 扫描 classpath 中的 `.class`，识别继承 `BaseMapper`、带 `@Mapper` 或含 MyBatis 注解的接口，并加载它们。不能解析出具体实体类型的通用 `BaseMapper<T>` 不作为业务 Mapper 注入。
3. **让 MP 生成映射语句。** 工具建立独立 `MybatisConfiguration` 并注册 Mapper。MP 根据实体映射等信息建立表元数据，再由注入器注册 `MappedStatement`，即每个 SQL 方法的框架定义；模板捕获器同时保存生成的动态脚本。
4. **求值得到 SQL。** 按方法参数和实体类型准备内部示例值，取得 `BoundSql` 中的参数化 SQL。Wrapper 查询还可以结合业务源码恢复受支持的条件结构；classpath 本身不包含某次运行时的 Wrapper 值。

这条流程只完成框架解析和 SQL 生成，不调用 Mapper 去执行数据库操作。工具提供的数据源会拒绝获取连接，所以不需要数据库地址；但注入器、Provider 等扩展仍可能执行自身代码。

### classpath、扫描包和源码目录各管什么

| 配置 | 解决的问题 | 不能替代什么 |
| --- | --- | --- |
| `project-root/module` 或 `classpath/classpath-file` | 让提取进程能够加载业务类、资源和依赖 | 不等于指定要输出哪些 Mapper |
| `packages` | 在已加载的 classpath 中限定 Mapper 扫描范围 | 只写包名不会自动找到、编译或加载业务项目 |
| `source-root/source-packages` | 扫描 Java 源码，恢复可识别的 Wrapper 调用或收集原文 | `.java` 目录不能代替编译产物 |
| `xml-root` | 读取 Mapper XML 及其引用资料 | XML 不包含 MP 自动注入的全部 CRUD SQL |

**必须有 classpath，不等于必须手工填写它。** Gradle 项目使用下文的自动接入即可；其他构建方式或已有产物使用手工接入。这个要求针对 MP 框架 SQL 提取：只扫描 Java 原文时不需要业务 classpath，自足的简单 XML 也可能独立提取；XML 引用 DTO 或 TypeHandler 时仍需相关类。

## 快速开始

需要 JDK 17。以下命令均在仓库根目录执行：

```bash
./gradlew run -PsqlExportConfig=config/extractor/demo.json
```

内置示例读取 `examples/extractor/project/`，结果写到 `build/sql-export/<本轮目录>/`。先打开 `all.sql`，再检查 `errors.json` 和 `exclusions.json`。定义级失败可能被记录后继续处理，因此命令结束不表示所有 SQL 均已提取。

根目录 `run` 和 `:sql-extractor:run` 是同一能力的两个入口，不必重复执行。

## 接入自己的项目

### Gradle 项目：自动编译并加载业务类

假设业务项目 `target-project/` 与 `sql-tool/` 同级，业务模块为 `:app`。创建 `config/local/extraction.json`：

```json
{
  "project-root": "../../../target-project",
  "module": ":app",
  "packages": "example.mapper",
  "xml-root": "../../../target-project/app/src/main/resources/mapper",
  "source-root": "../../../target-project/app/src/main/java",
  "source-packages": "example",
  "output": "../../build/sql-export",
  "sources": "mybatis-plus",
  "max-combinations": 256
}
```

```bash
./gradlew run -PsqlExportConfig=config/local/extraction.json
```

替换包名、模块和路径；单模块项目可省略 `module`，默认 `:`。工具调用目标项目自己的 Wrapper，按需编译并导出运行时 classpath，再加载 Mapper、DTO、Provider 等业务类。目标模块必须应用 Java 插件。

此配置重点提取 MP SQL；还需要 XML 时将 `sources` 改为 `xml,mybatis-plus`。仅填写 `xml` 不会执行 MP SQL 注入。当前非纯 Java 入口仍会遍历 `xml-root`，即使项目没有 Mapper XML，也应指向一个实际存在的目录。`source-root` 用来补充 Wrapper 结构，没有源码时可省略，但无法据此恢复业务中的具体条件。

多模块工程应选择运行时依赖能覆盖目标 Mapper 和实体的模块。例如 Mapper 在 `persistence`、实体在 `domain`，所选模块的运行时 classpath 必须包含它们。自动接入只导出所选模块及其依赖，不会无条件加载整个仓库。

此过程会更新目标工程构建输出和缓存，不改写源码或构建文件。每轮 classpath 清单写到 `build/target-classpath/run-*/classpath.txt`；构建失败即停止，不复用历史清单。自动模式不能以工具自身或其模块为目标。目标构建沿用自身 JDK 配置，提取进程使用 JDK 17。

### 已有编译产物：手工提供 classpath

Maven 项目或已经编译好的项目，先准备包含业务类和依赖的运行产物。下面是一份可放在 `config/local/extraction.json` 的手工接入配置：

```json
{
  "packages": "example.mapper",
  "xml-root": "../../../target-project/src/main/resources/mapper",
  "output": "../../build/sql-export",
  "sources": "mybatis-plus",
  "classpath": [
    "../../../target-project/target/classes",
    "../../../target-project/lib/*"
  ]
}
```

路径需按实际产物替换，启动仍使用 `./gradlew run -PsqlExportConfig=config/local/extraction.json`。`lib/` 是你准备的依赖目录，不是 Maven 自动提供的固定目录；工具不会自动编译或补齐依赖。

| classpath 中需要的内容 | 常见位置与作用 |
| --- | --- |
| 业务 Mapper、实体和父类 | Maven 的 `target/classes`、Gradle 的 `build/classes/java/main`，或普通业务 JAR |
| 其他业务模块 | 对应模块的类目录或 JAR，例如实体所在的 `domain` 模块 |
| 业务类依赖 | 包含所需注解、基础接口、字段类型、注入器及其依赖的 JAR |
| 运行资源 | Maven 通常在 `target/classes`；Gradle 通常另有 `build/resources/main`，按需加入实际存在的目录 |

填写的是**包目录的根目录**。例如类文件位于 `target/classes/example/mapper/RecordMapper.class`，应填写 `target/classes`，不能填写 `example/mapper`。源码更新后须重新编译；手工模式读取旧 `.class` 时，得到的仍是旧映射。

路径较多时，用 `"classpath-file": "target-classpath.txt"` 指定文本清单，每行一个类目录、资源目录或 JAR；支持空行及 `#` 注释。数组与清单可以合并使用。非空数组或显式清单优先于 `project-root`，空数组不算手工接入。

`目录/*` 只展开该目录直接包含的 JAR，不包含子目录；Spring Boot 可执行 JAR 的 `BOOT-INF` 嵌套类和依赖不能直接作为普通 classpath 使用。可用 `-PsqlExportClasspathFile=...` 覆盖 JSON 中的清单路径。

如需从 Gradle 项目手工导出清单，在工具根目录运行：

```bash
../target-project/gradlew -p ../target-project \
  -I "$PWD/scripts/export-classpath.gradle" sqlToolExportClasspath \
  -PsqlToolTargetModule=:app \
  -PsqlToolClasspathOutput="$PWD/config/local/target-classpath.txt"
```

输出路径必须为绝对路径。脚本会执行必要编译，但不修改目标构建文件。

### 自定义注入器和 TypeHandler 怎样接入

classpath 让类可以被加载，配置决定哪些扩展被启用。若项目通过自定义注入器增加通用方法，在提取配置中明确指定它；用到自定义 TypeHandler 时指定扫描包：

```json
{
  "injector": "example.mybatis.CustomSqlInjector",
  "handlers": "example.mybatis.handler"
}
```

这是加入完整配置的可选片段，类名和包名须替换。注入器实现及依赖必须在 classpath 中，并能通过无参构造创建。仅把带有 `@Bean` 的 Spring 配置类放进 classpath，不会自动启用其中的注入器；依赖 Spring 注入或应用上下文的扩展，也不会因此自动满足依赖。

工具默认使用自己的 MP 配置，开启下划线转驼峰，并将全局插入策略设为 `ALWAYS`，以生成参考 SQL。它不会完整加载业务的 `application.yml`、Spring Bean 或拦截器链。因此，有 classpath 是生成 MP SQL 的必要条件，但不是完整复现业务运行环境的保证；自定义全局映射、插件及运行时上下文需逐项核对。

### 只扫描 Java 源码

仅查找 SQL 字符串、SQL 构造器和 Wrapper 原文时，不需要业务 classpath：

```bash
./gradlew run --args='--packages example.mapper --xml-root examples/extractor/project/src/main/resources/mapper --source-root examples/extractor/project/src/main/java --source-packages example --output build/java-sql-export --sources java'
```

当前 CLI 始终要求 `packages`、`xml-root`、`output`，纯 Java 模式不会用前两项扫描 Mapper 或 XML。`--args` 和 `-PsqlExportConfig` 二选一。IDE 入口为 `com.kairui.tools.sqlexport.SqlExportMain`；直接启动时自行设置 classpath 并传 CLI 参数，JSON 转换与自动构建由 Gradle 入口负责。

## 配置参考

### 路径规则

| 位置 | 相对路径基准 |
| --- | --- |
| JSON 内的目录、文件和 `classpath` | JSON 文件所在目录 |
| classpath 清单的每一行 | 清单文件所在目录 |
| Gradle 的 `-PsqlExportConfig`、`-PsqlExportClasspathFile` 和 CLI 路径 | 工具仓库根目录 |
| `exclude-paths` | 按绝对路径或完整路径段后缀匹配，不拼接 JSON 目录 |

例如 `config/local/extraction.json` 中的 `../../build/sql-export` 指向工具的输出目录；配置路径不会自动相对于 `project-root` 拼接。

### 常用字段

| 字段 | 默认值或要求 | 用途 |
| --- | --- | --- |
| `project-root` / `module` | 可选 / `:` | 自动接入 Gradle 项目；指定模块时须提供项目根目录 |
| `classpath` / `classpath-file` | 可选 | 手工指定业务编译产物和依赖 |
| `packages` | 必填 | Mapper 扫描包，逗号分隔，包含子包 |
| `xml-root` | 必填 | 递归读取 XML 的根目录 |
| `output` | 必填 | 输出父目录，每轮新建时间戳目录 |
| `sources` | `all` | 来源筛选，支持逗号组合，见下表 |
| `source-root` | 可选 | Java 源码根目录；省略则不扫描源码 |
| `source-packages` | 空 | Java 扫描包；空表示源码根目录下全部包 |
| `exclude-paths` | 空 | 排除文件或目录，逗号分隔，不支持通配符 |
| `max-combinations` | `256`，正整数 | 每定义的 XML 条件组合或 Wrapper 源码场景上限 |
| `injector` | MP 默认注入器 | 自定义注入器全限定名 |
| `handlers` | 可选 | TypeHandler 扫描包，逗号分隔 |

自动接入与 classpath 字段由 Gradle JSON 配置处理；其余提取字段也可作为 `--字段名 值` 形式的 CLI 参数。未知字段会报错。

| `sources` 值 | 提取内容 |
| --- | --- |
| `xml` | XML CRUD、引用片段、databaseId 变体和 selectKey |
| `annotation` | Mapper SQL 注解 |
| `provider` | SQL Provider 生成的 SQL |
| `mybatis-plus` | MP CRUD、自定义注入器及可恢复的 Wrapper 查询、更新结构 |
| `java` | Java SQL 字符串、SQL 构造器及 Wrapper 原文 |
| `all` | 所有来源；源码扫描仍需 `source-root` |

## 参数职责

提取器不再接受 `parameters` 配置或 `--parameters`，旧配置会明确报错。内部样例仅用于生成 SQL 结构，产物保留 `parameterMode: "SAMPLE"` 和问号，不输出真实执行参数。动态 XML 使用有限条件展开；执行参数、场景及写入预期统一在生成器中配置或通过源库只读采样准备。

迁移旧配置时，删除提取配置中的 `parameters`，将参数需求改写为生成器的显式场景；生成器仍兼容历史 `CONFIGURED` 提取产物及其原参数文件，当前提取器不再生成该模式。

## 工作原理与支持边界

### 从业务定义取得参数化 SQL

MP 注册和生成流程见前文。XML 支持常见 `if/choose/where/set/trim/foreach/bind`，不同参数得到的相同 SQL 结构会去重。

这能发现多种条件结构，但不会穷举所有参数或执行路径。复杂 OGNL、任意循环、运行时上下文和无法确定的 `${...}` 会形成缺口；不能可靠生成 SQL 的定义跳过并说明原因。Provider、参数构造及业务扩展入口可能执行业务代码，因此需接入可信项目。

### Wrapper 查询如何恢复

启用 `mybatis-plus` 且提供 `source-root` 时，工具分析可明确关联到 Mapper 的源码调用，不要求同时启用 `java`。支持方法内顺序创建、链式构造、局部别名，以及 `QueryWrapper`、`LambdaQueryWrapper`、`Wrappers.query()` / `lambdaQuery()`；列名来自常量或可核对的 getter 引用。

可恢复比较、LIKE、空值、BETWEEN、IN、投影、排序及无参数 `or()`。支持明确的 Mapper 查询调用、部分 `ServiceImpl` 调用和一层简单转发；布尔条件只接受明确的 `true/false`，参数表达式不执行。分支、循环、任意跨方法传递和原始 SQL 片段不猜测。

| `wrapperStrategy` | 含义 |
| --- | --- |
| `SOURCE` | 从源码恢复的结构，`wrapperSources` 保存文件、行号和变体索引 |
| `EMPTY_FALLBACK` | 未找到可关联调用时保留的空 Wrapper 查询基线，不代表业务条件覆盖 |

已识别的调用全部无法恢复时会跳过定义；Wrapper 删除不恢复，更新只接受下述明确源码场景，不使用空 Wrapper 兜底。源码恢复仍需生成器补齐执行参数。

### Wrapper 更新如何恢复

支持方法内顺序构造的 `UpdateWrapper`、`LambdaUpdateWrapper`，以及 `Wrappers.update()` / `lambdaUpdate()`；识别明确 Mapper 的 `update(wrapper)`、`update(null, wrapper)`，以及直接继承 `ServiceImpl<具体Mapper, 实体>` 的未重写 `update` 调用。

```java
LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
wrapper.set(User::getName, name).eq(User::getId, id);
userMapper.update(null, wrapper);
```

提取结果类似 `UPDATE demo_user SET name=? WHERE (id = ?)`，记录 `wrapperStrategy: "SOURCE"`、变体和源码位置。真实 `name/id` 不执行、不捕获；生成器按明确的列映射采样，更新值可通过其 `writeValues` 指定，生成完整回查与前后预期。

首批仅恢复 `set` 赋值与 AND 连接的 `eq` 条件，布尔开关须为字面量。必须同时存在有效赋值和条件；拒绝非空实体混用、显式 null、重复列、修改主键或条件列、`setSql/apply`、运行时开关、分支循环和无法恢复的跨方法状态。自定义 Service 父类的泛型继承不递归推导。其他更新形式保留缺口，不生成无条件更新；不新增 Wrapper 删除支持。

提取成功不证明定位唯一。生成器和验证器继续核查完整主键/唯一键、表白名单、类型与前后预期；不满足受控 DML 要求的更新不会成为通过用例。普通 XML DELETE、MP `deleteById` 等非 Wrapper 删除沿用原流程。

### 表名与保留字处理

通过 `ServiceLoader` 加载 `SqlExtractionAdapter`，按定义范围改写模板及全部变体中的表名；失败时跳过，`tableNameAdaptation` 记录变化和模板指纹。内置 AIMB 适配器只作用于 `com.kairui.aimb.` 定义，复用业务动态表名处理器；普通项目不受其规则影响。它不执行完整业务插件链，也不补造未知上下文。

提取器还为识别出的未引用 MySQL 8.0 保留字表名、字段名补反引号。已有引号、字符串、注释和参数路径保持原样；别名、Java 原文及解析器无法识别的片段不自动处理。这是 SQL 整理，数据库语法是否可执行仍需后续验证。

## 输出怎么读

| 文件或字段 | 用途 |
| --- | --- |
| `all.sql` | 集中阅读模板和参数化 SQL |
| `sql/<来源>__<Mapper相对名>.sql` | 按来源和 Mapper 分类阅读 |
| `sql.json` | 生成器输入，保存成功定义、模板、模式及 `variants` |
| `java-sql.sql` / `java-sql.json` | Java 来源启用且提供源码目录时生成，含源码行号 |
| `errors.json` | 类加载、XML 解析、注册或适配等错误 |
| `exclusions.json` | 排除或跳过的定义及原因 |
| `combinationsComplete` / `combinationWarnings` | 有限候选范围内的展开情况及警告 |

`FILLED` 表示至少生成一条参数化 SQL，并不表示参数已填值、所有分支已覆盖或数据库执行通过。一个定义可以产生多个变体，所以“定义数”和“SQL 数”不同。成功条目仍可能带组合警告，应与错误、跳过记录一起查看。

## 常见问题

| 现象 | 处理方法 |
| --- | --- |
| 找不到 Wrapper 或模块 | `project-root` 指向含 `gradlew` 的根目录，`module` 使用实际 Gradle 项目路径 |
| 目标构建失败 | 按目标 Gradle 日志检查 JDK、依赖和编译错误 |
| classpath 路径不存在 | 检查路径基准及编译输出；不要手工填写未生成的资源目录 |
| 没有 MP SQL，但没有明显加载错误 | 确认 `sources` 包含 `mybatis-plus`，`packages` 对应编译后的 Mapper 包；只有源码、扫错包或选错业务模块可能找不到任何 Mapper |
| `ClassNotFoundException` / `NoClassDefFoundError` | 根据缺失类补齐实体、父接口或依赖 JAR；只有 Mapper 所在目录往往不够，查看 `errors.json` 的“加载Mapper”记录 |
| `NoSuchMethodError` / 其他 `LinkageError` | 核对业务与工具的 MyBatis / MP 版本；工具依赖排在业务依赖之前，追加另一版本 JAR 不会自动隔离冲突 |
| 有默认 CRUD，却缺少业务扩展方法 | 检查 `injector` 是否明确指定、能否独立构造；仅有 Spring Bean 声明不会自动生效 |
| SQL 仍是旧字段或旧表名 | 手工 classpath 可能指向旧编译产物；重新编译并核对实际加载目录 |
| 能生成 `selectList`，但缺少业务 WHERE 条件 | classpath 提供 Mapper 和实体映射；再提供 `source-root` 恢复支持的 Wrapper 调用，并检查 `wrapperStrategy` |
| 缺少预期 SQL | 检查 `sources`、两类扫描包、源码目录和排除规则，再看 `exclusions.json` |
| 纯 XML 仍提示缺少业务类 | XML 可能引用 DTO 或 TypeHandler；当前不保证完全无业务依赖 |

当前工具依赖 MyBatis 3.5.16、MyBatis-Plus 3.5.7 和 Spring Core 6.0.21，不自动隔离不同版本。提取不验证数据库性能、业务结果或 Mapper 返回映射。需要实际执行时，将本轮提取目录交给清单生成器，再交由验证器比较。
