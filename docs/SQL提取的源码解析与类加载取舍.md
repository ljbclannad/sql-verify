# SQL 提取的源码解析与类加载取舍

文档类型：说明文档。

本文解释提取器为何通过业务 classpath 加载编译后的类，复用 MyBatis / MyBatis-Plus（MP）生成 SQL，以及这种方式与直接解析 Java 源码的区别。具体配置见[提取器使用说明](../sql-extractor/README.md)，职责与输出约束见[阶段一设计](./阶段一-SQL提取能力与独立工程.md)。

## 1. 结论与适用范围

**直接解析 `.java` 生成 SQL 在技术上可行。当前提取器加载 `.class`，是为了复用框架已有的类型解析、实体映射和 SQL 注入机制，减少重复实现与规则维护成本。**

“必须提供业务 classpath”是当前 MP 框架提取路径的前提，不是所有 SQL 提取方案的理论限制。框架注册入口接收 `Class<?>`，不能直接接收 Java 源码或源码语法树。若改为纯源码方案，就需要自行实现受支持范围内的 SQL 生成规则，或将源码编译后继续交给框架；后一种仍然使用类加载。

当前项目同时保留源码扫描与框架提取：前者收集 SQL 字符串、构造器和 Wrapper 原文，并辅助恢复受支持的调用结构；后者注册 Mapper、获得 SQL 定义并求值。本文说明现有选择，不增加新的提取模式。

## 2. `.java`、`.class` 与 classpath 分别是什么

| 概念 | 内容与用途 |
| --- | --- |
| `.java` | Java 源码，包含类型声明、泛型、注解和方法体，可用于静态分析 |
| `.class` | 编译后的二进制类文件，保存类结构、方法字节码及相关元数据，可被 JVM 加载 |
| classpath | Java 查找类和资源的路径集合，通常包含编译输出目录、资源目录与普通依赖 JAR |
| `classpath-file` | 本工具的路径清单配置，每行描述一个类目录、资源目录或 JAR；本身不是编译产物 |

classpath 不是一种编译文件格式，也不等同于 IDE 的 `.classpath` 配置文件。只提供业务源码目录或包名，不能让当前框架注册入口直接获得业务类型。

### 2.1 空 Mapper 接口编译后包含什么

例如业务源码为：

```java
package example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import example.entity.User;

public interface UserMapper extends BaseMapper<User> {
}
```

编译得到的 `UserMapper.class` 保存接口身份、父接口引用和泛型签名。以下是便于阅读的结构示意，并非本项目一次 `javap` 运行的输出：

```text
类名：example.mapper.UserMapper
标志：ACC_PUBLIC, ACC_INTERFACE, ACC_ABSTRACT
父接口：com.baomidou.mybatisplus.core.mapper.BaseMapper
自身字段数：0
自身方法数：0
Signature：
Ljava/lang/Object;Lcom/baomidou/mybatisplus/core/mapper/BaseMapper<Lexample/entity/User;>;
```

Java 泛型擦除不意味着所有泛型声明都从类文件中消失。正常编译的这个例子会在 `Signature` 属性中保留 `BaseMapper<User>` 的声明信息，框架可通过反射读取。复杂的多层泛型继承还需要沿继承关系解析，不能一律取第一层泛型参数。

`selectById` 等父接口方法不会复制进这个空接口的类文件，它们仍由 `BaseMapper` 声明。`UserMapper.class` 中也没有自动生成的 CRUD SQL。

实体字段和映射注解位于 `User.class` 及其相关类型中。注解能否被反射读取取决于保留策略：`RUNTIME` 注解可在运行时读取，`CLASS` 注解虽可保存在类文件中，但普通反射不提供它们，`SOURCE` 注解不保留到类文件。类文件也不保留完整源码文本和注释，因此编译产物不是源码的完整替代品。

## 3. 从类型信息到 SQL 的机制

源码与类文件都能描述类型，但类型信息本身不等于 SQL。还需要框架规则解释表名、列名、主键、字段策略和方法约定。

```text
UserMapper.class + User.class + 框架及业务依赖
                         ↓ 加载类型
             注册到独立 MybatisConfiguration
                         ↓ 解析 Mapper 的实体类型
             根据实体映射建立 TableInfo
                         ↓ 默认或指定的 SQL 注入器
             生成脚本并注册 MappedStatement
                         ↓ SqlSource 根据参数求值
             得到 BoundSql：SQL 与参数映射
                         ↓ 提取器整理并导出
             参数化 SQL、动态模板、变体及诊断
```

### 3.1 注册阶段：建立 SQL 定义

提取器扫描指定包下的 `.class` 资源，读取元信息并筛选接口，再通过 `Class.forName(..., false, ...)` 加载候选类型。扫描阶段不主动初始化类，但这不代表后续参数构造和扩展调用不会执行代码。

注册的关键入口是 `configuration.addMapper(mapper)`。在启用 MP 提取时，框架解析 `BaseMapper<User>` 对应的实体类型，读取实体字段、映射注解和当前配置，建立 `TableInfo`，然后由默认或配置的 SQL 注入器创建方法对应的 SQL 定义。

`TableInfo` 来自 Java 映射与配置，不是连接数据库读取到的真实表结构。`MappedStatement` 表示某个方法 ID 对应的框架语句定义，内部的 `SqlSource` 负责提供或求值 SQL。注入器可以改变方法集合，因此不能将 `BaseMapper` 中声明的方法直接当作本轮已成功生成的 SQL 清单。

### 3.2 求值阶段：根据参数形成 SQL

注册后，动态模板还需要结合参数求值。例如实体映射到 `sys_user`，仅含 `id`、`name` 两个映射字段，`id` 为主键，并且没有逻辑删除等附加规则时，`selectById` 可形成：

```sql
SELECT id, name FROM sys_user WHERE id = ?
```

表名、字段列表与主键条件由映射和生成规则确定；问号对应参数位置。同一个动态模板可能因字段值、条件开关或 Wrapper 内容不同而产生不同 SQL。

提取器使用内部示例参数进行有限求值，导出 SQL 保留 `?`。框架内部取得 `BoundSql`，不意味着导出文件已经保存了真实执行所需的完整有序参数；参数依据与执行清单由后续生成阶段处理。

### 3.3 执行阶段：不属于离线提取

正常业务调用在求值后还会进入插件处理、JDBC 参数绑定、SQL 执行与结果映射。本工具不会沿这条完整调用链执行 Mapper，提供的数据源也拒绝获取连接。

因此，生成 SQL 不等于执行 SQL，更不等于证明数据库兼容或所有业务场景已覆盖。分页、租户等插件和运行上下文还可能改变实际发送到数据库的语句。

## 4. 为什么不全部改为解析源码

对于映射规则明确的简单实体，解析源码中的 `@TableName`、`@TableId` 和字段即可生成基础查询。这条路线成立，难点是扩大支持范围后，需要承担类型解析和框架语义的维护。

| 问题 | 纯源码生成需要处理什么 | 当前类加载方案复用什么 |
| --- | --- | --- |
| 类型与继承 | 解析导入、父类字段、多层泛型及跨模块符号；依赖可能只提供 JAR | JVM 类型加载、反射及框架的泛型解析 |
| 映射规则 | 实现表名、字段名、忽略字段、主键和字段策略等规则 | MP 的实体映射和 SQL 生成逻辑 |
| 框架版本差异 | 跟踪受支持版本的生成规则变化 | 当前提取环境实际加载的框架实现 |
| 自定义注入器 | 分析扩展代码或为受支持扩展单独建模 | 在可满足依赖的条件下调用指定注入器 |
| 动态 SQL | 解释动态脚本、参数表达式及条件组合 | MyBatis 的脚本解析与求值机制 |
| 业务调用条件 | 分析 Wrapper 构造、控制流和参数来源 | 类加载本身不能恢复，仍需源码分析或运行信息 |

尤其是自定义注入器和 Provider，其 SQL 可能由任意 Java 逻辑生成，且依赖配置或上下文。语法树能描述代码，不等于能确定该代码在所有输入下的结果。纯静态方案应限定支持的规则，对无法确定的内容报告缺口，而不能承诺恢复任意运行 SQL。

当前方案的收益是减少框架规则的重复实现，并能使用依赖 JAR 中的类型；代价是需要可加载的编译产物、兼容的依赖和可离线调用的扩展。它并不天然保证与目标应用的 SQL 完全一致。

## 5. 当前选择的成本与边界

- **编译与依赖成本。** 自动接入需要构建目标模块；手工接入需要完整的类、资源及依赖路径。缺少实体、父接口或扩展依赖会导致加载或注册失败。
- **产物一致性。** 手工 classpath 指向旧产物时，提取结果可能反映旧字段或旧表名。源码辅助恢复与编译类应来自一致版本。
- **运行环境差异。** 工具使用自己的框架配置，不完整加载目标应用的 `application.yml`、Spring Bean 或插件链。框架版本、依赖冲突和配置差异仍需核对。
- **扩展依赖。** 自定义注入器需要显式配置并满足当前构造要求。仅将 Spring 配置类加入 classpath，不会自动创建其 Bean 或补齐上下文。
- **代码执行边界。** 离线提取不执行数据库 SQL，但 Provider、注入器、参数构造等入口可能执行业务代码；它不是纯静态分析环境。
- **覆盖边界。** 类文件不提供某次调用的真实参数，也不能仅凭一个 `selectList` 定义推导全部 Wrapper 条件。有限样例与源码恢复不能证明所有运行路径均已覆盖。

纯 Java 模式无需业务 classpath；自足的简单 XML 也可能独立提取，引用 DTO、TypeHandler 等类型时仍需相关类。具体来源的失败与跳过以本轮诊断为准。

## 6. 何时选择哪条路线

| 目标 | 合适的处理方式 |
| --- | --- |
| 盘点源码中的 SQL 文本与调用位置，目标项目暂时无法编译 | 采用源码扫描，保留无法确定的动态片段 |
| 为限定注解、限定规则生成基础 SQL | 可设计纯源码生成器，并明确其规则范围；当前项目未提供这种完整替代模式 |
| 提取 MP 通用 CRUD 和可离线调用的自定义注入 SQL | 使用当前 classpath 加载与框架注册路径 |
| 恢复业务中的受支持 Wrapper 结构 | 将框架实体元数据与源码分析结合 |
| 观察特定请求经过完整插件链后的真实 SQL 与参数 | 需要运行 SQL 捕获；属于扩展设计，不能由当前离线结果替代 |

## 7. 实现阅读入口

| 源码 | 关注内容 |
| --- | --- |
| [SqlExtractor.java](../sql-extractor/src/main/java/com/kairui/tools/sqlexport/SqlExtractor.java) | `scan` 扫描与加载类型，`registerStatements` 和 `registerMappersAndCollectAnnotations` 注册定义，后续流程生成参数化结果 |
| [SqlTemplateLanguageDriver.java](../sql-extractor/src/main/java/com/kairui/tools/sqlexport/template/SqlTemplateLanguageDriver.java) | 在框架解析过程中捕获动态模板 |
| [JavaSqlExtractor.java](../sql-extractor/src/main/java/com/kairui/tools/sqlexport/source/JavaSqlExtractor.java) | Java 源码中的 SQL 线索提取 |
| [WrapperSourceScenarios.java](../sql-extractor/src/main/java/com/kairui/tools/sqlexport/source/WrapperSourceScenarios.java) | 受支持的 Wrapper 源码调用结构恢复 |

这些入口共同体现当前分工：通过框架生成映射语句，通过源码补充调用结构，通过独立验证器取得数据库执行证据。
