package com.kairui.tools.sqlverify.jdbc;

import com.kairui.tools.sqlquery.QueryCase;
import com.kairui.tools.sqlquery.QueryParameter;
import com.kairui.tools.sqlverify.jdbc.QueryExecution.QueryColumn;
import com.kairui.tools.sqlverify.jdbc.QueryExecution.QueryError;
import com.kairui.tools.sqlverify.jdbc.QueryExecution.Stage;
import com.kairui.tools.sqlverify.jdbc.QueryExecution.Status;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 执行一条已通过清单校验的查询。连接由调用方管理，语句与结果集在此关闭。
 * 限额只保存前 rows 行，额外探测一行以区分完整结果与截断；不读取探测行的字段。
 */
public final class JdbcQueryExecutor {

    public QueryExecution execute(Connection connection, QueryCase query, int rows,
                                  int timeoutSeconds, long maxResultBytes) {
        return execute(connection, query, rows, timeoutSeconds, maxResultBytes, false);
    }

    /** 查询比较保留时间值类别；原 execute 的 ISO 文本行为继续用于 DML 和已有调用方。 */
    public QueryExecution executeTyped(Connection connection, QueryCase query, int rows,
                                      int timeoutSeconds, long maxResultBytes) {
        return execute(connection, query, rows, timeoutSeconds, maxResultBytes, true);
    }

    private QueryExecution execute(Connection connection, QueryCase query, int rows,
                                   int timeoutSeconds, long maxResultBytes, boolean typedTemporal) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(query, "query");
        if (rows < 1 || rows == Integer.MAX_VALUE || timeoutSeconds < 1 || maxResultBytes < 1) {
            throw new IllegalArgumentException("rows 必须在 1 至 Integer.MAX_VALUE - 1 范围内，timeoutSeconds 和 maxResultBytes 必须大于 0");
        }
        Stage stage = Stage.PREPARE;
        List<QueryColumn> columns = new ArrayList<>();
        List<List<Object>> resultRows = new ArrayList<>();
        long executeStart = 0;
        long readStart = 0;
        long executeMillis = 0;
        long readMillis = 0;
        // 步骤 1：使用带占位符的原 SQL 创建语句；不会执行拼接参数后的展示 SQL。
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            statement.setQueryTimeout(timeoutSeconds);
            // JDBC 最多返回取数范围加一行，仍能判断 hasMore，避免驱动缓存无界结果。
            statement.setMaxRows(rows + 1);
            // 步骤 2：按清单顺序与显式 JDBC 类型绑定，Java 数组下标从 0 开始，JDBC 参数位置从 1 开始。
            stage = Stage.BIND;
            for (int i = 0; i < query.parameters().size(); i++) {
                bind(statement, i + 1, query.parameters().get(i));
            }
            // 步骤 3：executeQuery 才发起查询执行；执行耗时与随后遍历结果的读取耗时分开记录。
            stage = Stage.EXECUTE;
            executeStart = System.nanoTime();
            try (ResultSet resultSet = statement.executeQuery()) {
                executeMillis = elapsedMillis(executeStart);
                stage = Stage.READ;
                readStart = System.nanoTime();
                // 步骤 4：列元数据用于按类型读取和报告诊断；查询比较不独立核验列名、列类型是否一致。
                readColumns(resultSet.getMetaData(), columns);
                ResultBudget budget = new ResultBudget(maxResultBytes);
                boolean exhausted = false;
                for (int i = 0; i < rows; i++) {
                    if (!resultSet.next()) {
                        exhausted = true;
                        break;
                    }
                    List<Object> values = new ArrayList<>(columns.size());
                    for (QueryColumn column : columns) {
                        values.add(readValue(resultSet, column, budget, typedTemporal));
                    }
                    resultRows.add(values);
                }
                // 步骤 5：保存前 rows 行后再探测一行，不读取探测行字段；恰好 rows 行且无下一行仍是完整结果。
                boolean hasMore = !exhausted && resultSet.next();
                readMillis = elapsedMillis(readStart);
                return new QueryExecution(Status.SUCCESS, null, columns, resultRows,
                        hasMore, !hasMore, executeMillis, readMillis);
            }
        } catch (SQLException | IOException | RuntimeException exception) {
            if (stage == Stage.EXECUTE && executeStart != 0) {
                executeMillis = elapsedMillis(executeStart);
            }
            if (stage == Stage.READ && readStart != 0) {
                readMillis = elapsedMillis(readStart);
            }
            // 失败保留已取得的列和部分行作为证据，但 exhausted=false，比较器不得将部分结果判为通过。
            SQLException sqlException = exception instanceof SQLException sql ? sql : null;
            QueryError error = new QueryError(stage,
                    sqlException == null ? null : sqlException.getSQLState(),
                    sqlException == null ? 0 : sqlException.getErrorCode(), exception.getMessage());
            return new QueryExecution(Status.FAILED, error, columns, resultRows,
                    false, false, executeMillis, readMillis);
        }
    }

    /** 每次调用均使用同一参数快照，避免类型推测或两库分别生成值。 */
    public void bind(PreparedStatement statement, int position, QueryParameter parameter) throws SQLException {
        JDBCType type = parameter.jdbcType();
        Object value = parameter.value();
        if (value == null) {
            statement.setNull(position, type.getVendorTypeNumber());
            return;
        }
        switch (type) {
            case CHAR, VARCHAR, LONGVARCHAR, NCHAR, NVARCHAR, LONGNVARCHAR ->
                    statement.setString(position, (String) value);
            case TINYINT, SMALLINT, INTEGER -> statement.setInt(position, (Integer) value);
            case BIGINT -> statement.setLong(position, (Long) value);
            case DECIMAL, NUMERIC -> statement.setBigDecimal(position, (BigDecimal) value);
            case BOOLEAN, BIT -> statement.setBoolean(position, (Boolean) value);
            case DATE -> statement.setObject(position, (LocalDate) value, Types.DATE);
            case TIME -> statement.setObject(position, (LocalTime) value, Types.TIME);
            case TIMESTAMP -> statement.setObject(position, (LocalDateTime) value, Types.TIMESTAMP);
            default -> throw new IllegalArgumentException("不支持的参数类型：" + type);
        }
    }

    private void readColumns(ResultSetMetaData metadata, List<QueryColumn> columns) throws SQLException {
        for (int position = 1; position <= metadata.getColumnCount(); position++) {
            columns.add(new QueryColumn(position, metadata.getColumnLabel(position),
                    metadata.getColumnType(position), metadata.getColumnTypeName(position)));
        }
    }

    /** 按 JDBC 类型读取，结果只包含可直接写入 JSON 的标量，不对未知类型调用 toString。 */
    private Object readValue(ResultSet resultSet, QueryColumn column, ResultBudget budget, boolean typedTemporal)
            throws SQLException, IOException {
        int position = column.position();
        Object value = switch (column.jdbcType()) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR,
                    Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB ->
                    readText(resultSet, position, budget);
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> resultSet.getInt(position);
            case Types.BIGINT -> resultSet.getLong(position);
            case Types.NUMERIC, Types.DECIMAL -> resultSet.getBigDecimal(position);
            case Types.FLOAT, Types.DOUBLE -> finite(resultSet.getDouble(position));
            case Types.REAL -> finite(resultSet.getFloat(position));
            case Types.BOOLEAN, Types.BIT -> resultSet.getBoolean(position);
            case Types.DATE -> resultSet.getObject(position, LocalDate.class);
            case Types.TIME -> resultSet.getObject(position, LocalTime.class);
            case Types.TIMESTAMP -> resultSet.getObject(position, LocalDateTime.class);
            case Types.NULL -> null;
            default -> throw new SQLException("第一版不支持结果类型：列 " + position + "，类型 "
                    + column.typeName() + "（" + column.jdbcType() + "）", "0A000");
        };
        if (resultSet.wasNull()) {
            value = null;
        }
        // 文本已在读取过程中计数；其他标量转换后体积有限。
        if (!isText(column.jdbcType())) {
            budget.add(value == null ? 4 : value.toString().getBytes(StandardCharsets.UTF_8).length);
        }
        return !typedTemporal && (value instanceof LocalDate || value instanceof LocalTime || value instanceof LocalDateTime)
                ? value.toString() : value;
    }

    /** 使用字符流逐段读取并累计 UTF-8 字节，超限即终止，避免先构造巨大 String。 */
    private String readText(ResultSet resultSet, int position, ResultBudget budget) throws SQLException, IOException {
        try (Reader reader = resultSet.getCharacterStream(position)) {
            if (reader == null) {
                budget.add(4);
                return null;
            }
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[1024];
            boolean previousHighSurrogate = false;
            int count;
            while ((count = reader.read(buffer, 0,
                    (int) Math.min(buffer.length, Math.min(budget.remaining(), 1023) + 1))) != -1) {
                for (int i = 0; i < count; i++) {
                    char character = buffer[i];
                    int bytes = character <= 0x7f ? 1 : character <= 0x7ff ? 2
                            : Character.isLowSurrogate(character) && previousHighSurrogate ? 1 : 3;
                    budget.add(bytes);
                    builder.append(character);
                    previousHighSurrogate = Character.isHighSurrogate(character);
                }
            }
            return builder.toString();
        }
    }

    private boolean isText(int type) {
        return type == Types.CHAR || type == Types.VARCHAR || type == Types.LONGVARCHAR
                || type == Types.NCHAR || type == Types.NVARCHAR || type == Types.LONGNVARCHAR
                || type == Types.CLOB || type == Types.NCLOB;
    }

    private double finite(double value) throws SQLException {
        if (!Double.isFinite(value)) {
            throw new SQLException("结果含无法编码为 JSON 数值的非有限浮点值", "22003");
        }
        return value;
    }

    private long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** 限额统计结果值的 UTF-8 字节，包含 null；不包含报告格式与列元数据。 */
    private static final class ResultBudget {
        private final long limit;
        private long used;

        private ResultBudget(long limit) {
            this.limit = limit;
        }

        private long remaining() {
            return limit - used;
        }

        private void add(long bytes) throws SQLException {
            if (bytes > remaining()) {
                throw new SQLException("查询结果值超过 maxResultBytes 限额（" + limit + " 字节）", "54000");
            }
            used += bytes;
        }
    }
}
