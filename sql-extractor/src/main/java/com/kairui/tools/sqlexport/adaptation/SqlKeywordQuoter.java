package com.kairui.tools.sqlexport.adaptation;

import cn.hutool.core.util.StrUtil;
import com.kairui.tools.sqlexport.model.ExtractedSql;
import com.kairui.tools.sqlexport.template.SqlTemplateLanguageDriver;
import com.kairui.tools.sqlexport.template.XmlSqlExpander;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.SimpleNode;
import net.sf.jsqlparser.parser.Token;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 为 MySQL 保留字标识符补反引号；仅修改语法树确认的表名、列名位置，保留原 SQL 排版。 */
public final class SqlKeywordQuoter {
    // 来源：https://dev.mysql.com/doc/refman/8.0/en/keywords.html，包含历史保留字及 _FILENAME。
    private static final Set<String> RESERVED = Arrays.stream("""
            ACCESSIBLE ADD ALL ALTER ANALYZE AND AS ASC ASENSITIVE BEFORE BETWEEN BIGINT BINARY BLOB BOTH BY
            CALL CASCADE CASE CHANGE CHAR CHARACTER CHECK COLLATE COLUMN CONDITION CONSTRAINT CONTINUE CONVERT
            CREATE CROSS CUBE CUME_DIST CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER CURSOR
            DATABASE DATABASES DAY_HOUR DAY_MICROSECOND DAY_MINUTE DAY_SECOND DEC DECIMAL DECLARE DEFAULT
            DELAYED DELETE DENSE_RANK DESC DESCRIBE DETERMINISTIC DISTINCT DISTINCTROW DIV DOUBLE DROP DUAL
            EACH ELSE ELSEIF EMPTY ENCLOSED ESCAPED EXCEPT EXISTS EXIT EXPLAIN FALSE FETCH FIRST_VALUE FLOAT
            FLOAT4 FLOAT8 FOR FORCE FOREIGN FROM FULLTEXT FUNCTION GENERATED GET GRANT GROUP GROUPING GROUPS
            HAVING HIGH_PRIORITY HOUR_MICROSECOND HOUR_MINUTE HOUR_SECOND IF IGNORE IN INDEX INFILE INNER
            INOUT INSENSITIVE INSERT INT INT1 INT2 INT3 INT4 INT8 INTEGER INTERSECT INTERVAL INTO IO_AFTER_GTIDS
            IO_BEFORE_GTIDS IS ITERATE JOIN JSON_TABLE KEY KEYS KILL LAG LAST_VALUE LATERAL LEAD LEADING LEAVE
            LEFT LIKE LIMIT LINEAR LINES LOAD LOCALTIME LOCALTIMESTAMP LOCK LONG LONGBLOB LONGTEXT LOOP
            LOW_PRIORITY MASTER_BIND MASTER_SSL_VERIFY_SERVER_CERT MATCH MAXVALUE MEDIUMBLOB MEDIUMINT
            MEDIUMTEXT MIDDLEINT MINUTE_MICROSECOND MINUTE_SECOND MOD MODIFIES NATURAL NOT NO_WRITE_TO_BINLOG
            NTH_VALUE NTILE NULL NUMERIC OF ON OPTIMIZE OPTIMIZER_COSTS OPTION OPTIONALLY OR ORDER OUT OUTER
            OUTFILE OVER PARTITION PERCENT_RANK PRECISION PRIMARY PROCEDURE PURGE RANGE RANK READ READS
            READ_WRITE REAL RECURSIVE REFERENCES REGEXP RELEASE RENAME REPEAT REPLACE REQUIRE RESIGNAL
            RESTRICT RETURN REVOKE RIGHT RLIKE ROW ROWS ROW_NUMBER SCHEMA SCHEMAS SECOND_MICROSECOND SELECT
            SENSITIVE SEPARATOR SET SHOW SIGNAL SMALLINT SPATIAL SPECIFIC SQL SQLEXCEPTION SQLSTATE SQLWARNING
            SQL_BIG_RESULT SQL_CALC_FOUND_ROWS SQL_SMALL_RESULT SSL STARTING STORED STRAIGHT_JOIN SYSTEM TABLE
            TERMINATED THEN TINYBLOB TINYINT TINYTEXT TO TRAILING TRIGGER TRUE UNDO UNION UNIQUE UNLOCK
            UNSIGNED UPDATE USAGE USE USING UTC_DATE UTC_TIME UTC_TIMESTAMP VALUES VARBINARY VARCHAR
            VARCHARACTER VARYING VIRTUAL WHEN WHERE WHILE WINDOW WITH WRITE XOR YEAR_MONTH ZEROFILL _FILENAME
            """.split("\\s+")).filter(StrUtil::isNotBlank).collect(Collectors.toUnmodifiableSet());
    private static final Pattern PARAMETER = Pattern.compile("[#$]\\{[^}]*}");

    private SqlKeywordQuoter() { }

    /** 在业务表名适配之后同步处理模板与全部结果，保证生成器重新求值时继续使用已引用标识符。 */
    public static void quote(ExtractedSql sql) {
        sql.template = template(sql.template);
        sql.parameterizedSql = quote(sql.parameterizedSql);
        sql.sql = quote(sql.sql);
        sql.variants = sql.variants.stream().map(variant -> new XmlSqlExpander.Variant(
                quote(variant.parameterizedSql()), quote(variant.sql()))).toList();
    }

    /** 只在能够完整解析的 SQL 中添加引号；未知语法原样保留，不猜测其标识符含义。 */
    public static String quote(String sql) {
        if (StrUtil.isBlank(sql)) return sql;
        Set<Integer> positions = positions(sql);
        return positions == null ? sql : insert(sql, positions);
    }

    /** XML 先投影成 SQL 定位，失败时按文本节点解析常见条件/赋值片段；属性与参数名不参与改写。 */
    private static String template(String script) {
        if (StrUtil.isBlank(script)) return script;
        if (!script.stripLeading().startsWith("<")) return quote(script);
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(script))).getDocumentElement();
            StringBuilder projected = new StringBuilder();
            List<Text> texts = new ArrayList<>();
            project(root, projected, texts);
            Set<Integer> positions = positions(projected.toString());
            boolean changed = false;
            for (Text text : texts) {
                String original = text.node().getNodeValue();
                String rewritten;
                if (positions == null) rewritten = fragment(original);
                else {
                    Set<Integer> local = new TreeSet<>();
                    for (int position : positions) {
                        if (position >= text.start() && position < text.start() + original.length()) {
                            local.add(position - text.start());
                        }
                    }
                    rewritten = insert(original, local);
                }
                if (!original.equals(rewritten)) {
                    text.node().setNodeValue(rewritten);
                    changed = true;
                }
            }
            return changed ? SqlTemplateLanguageDriver.xml(root) : script;
        } catch (Exception e) {
            // 不能安全解析的自定义模板仍由原有框架流程负责，不把模板错误转成引号修复。
            return script;
        }
    }

    private record Text(Node node, int start) { }

    /** 动态控制标签不进入 SQL；拼装标签使用其固定前后缀，所有文本节点保留自己的字符位置。 */
    private static void project(Node node, StringBuilder sql, List<Text> texts) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            texts.add(new Text(node, sql.length()));
            sql.append(node.getNodeValue());
        } else if (node instanceof Element element) {
            String tag = element.getTagName();
            sql.append(' ');
            switch (tag) {
                case "where" -> sql.append("WHERE ");
                case "set" -> sql.append("SET ");
                case "trim" -> sql.append(element.getAttribute("prefix")).append(' ');
                case "foreach" -> sql.append(element.getAttribute("open"));
                case "bind" -> { return; }
                default -> { }
            }
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) project(child, sql, texts);
            if (tag.equals("foreach")) sql.append(element.getAttribute("close"));
            if (tag.equals("trim")) sql.append(' ').append(element.getAttribute("suffix"));
            sql.append(' ');
        }
    }

    /** 片段仅作为解析上下文补齐，输出仍是原文本；解析不成功则不改写。 */
    private static String fragment(String text) {
        char[] masked = text.toCharArray();
        int first = 0, last = masked.length - 1;
        while (first <= last && Character.isWhitespace(masked[first])) first++;
        while (last >= first && Character.isWhitespace(masked[last])) last--;
        if (first > last) return text;
        if (masked[first] == ',') masked[first] = ' ';
        if (masked[last] == ',') masked[last] = ' ';
        String body = new String(masked);
        for (String prefix : List.of("", "SELECT * FROM sql_keyword_fixture ",
                "SELECT * FROM sql_keyword_fixture WHERE 1=1 ",
                "SELECT * FROM sql_keyword_fixture WHERE ", "UPDATE sql_keyword_fixture SET ", "SELECT ")) {
            Set<Integer> positions = positions(prefix + body);
            if (positions == null) continue;
            Set<Integer> local = new TreeSet<>();
            for (int position : positions) if (position >= prefix.length()) local.add(position - prefix.length());
            return insert(text, local);
        }
        return text;
    }

    /** 参数先等长屏蔽；语法树 token 的行列位置映射回原文，字符串、注释、函数与已有引号不命中。 */
    private static Set<Integer> positions(String sql) {
        char[] masked = sql.toCharArray();
        Matcher matcher = PARAMETER.matcher(sql);
        while (matcher.find()) {
            for (int i = matcher.start(); i < matcher.end(); i++) {
                if (masked[i] != '\n' && masked[i] != '\r') masked[i] = ' ';
            }
            masked[matcher.start()] = '?';
        }
        try {
            SimpleNode root = (SimpleNode) CCJSqlParserUtil.parseAST(new String(masked));
            Set<Integer> positions = new TreeSet<>();
            collect(root, sql, positions);
            return positions;
        } catch (JSQLParserException e) {
            return null;
        }
    }

    private static void collect(SimpleNode node, String sql, Set<Integer> positions) {
        if (node.toString().equals("Column") || node.toString().equals("TableName")) {
            Token end = node.jjtGetLastToken();
            for (Token token = node.jjtGetFirstToken(); token != null; token = token.next) {
                if (RESERVED.contains(token.image.toUpperCase(Locale.ROOT))) {
                    int start = offset(sql, token.beginLine, token.beginColumn);
                    int finish = start + token.image.length();
                    if (start >= 0 && finish <= sql.length() && sql.regionMatches(start, token.image, 0, token.image.length())) {
                        positions.add(start);
                        positions.add(finish);
                    }
                }
                if (token == end) break;
            }
        }
        for (int i = 0; i < node.jjtGetNumChildren(); i++) collect((SimpleNode) node.jjtGetChild(i), sql, positions);
    }

    /** JSQLParser 的列号按字符计数；CRLF 作为一次换行，保留制表符与原排版。 */
    private static int offset(String sql, int line, int column) {
        int currentLine = 1, currentColumn = 1;
        for (int i = 0; i < sql.length(); i++) {
            char value = sql.charAt(i);
            if (currentLine == line && currentColumn == column) return i;
            if (value == '\n' || value == '\r') {
                if (value == '\r' && i + 1 < sql.length() && sql.charAt(i + 1) == '\n') i++;
                currentLine++;
                currentColumn = 1;
            } else currentColumn++;
        }
        return -1;
    }

    private static String insert(String sql, Set<Integer> positions) {
        if (positions.isEmpty()) return sql;
        StringBuilder result = new StringBuilder(sql);
        for (int position : new TreeSet<>(positions).descendingSet()) result.insert(position, '`');
        return result.toString();
    }
}
