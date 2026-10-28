package com.kairui.tools.sqlverify.report;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 顺序读取报告，条目数组逐个消费；不把整个报告交给 JSON 库载入内存。 */
final class ReportJsonStream {
    interface EntryConsumer { void accept(Map<String, Object> entry) throws IOException; }
    record Metadata(Map<String, Object> fields, String arrayName, int count) { }

    static Metadata read(Path path, EntryConsumer consumer) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            Cursor cursor = new Cursor(reader);
            cursor.expect('{');
            Map<String, Object> metadata = new LinkedHashMap<>();
            Set<String> names = new HashSet<>();
            String arrayName = null;
            int count = 0;
            if (!cursor.consume('}')) {
                do {
                    String token = cursor.value();
                    if (!token.startsWith("\"")) throw new IOException("报告字段名必须为字符串");
                    String name = JSON.parseObject(token, String.class);
                    if (!names.add(name)) throw new IOException("报告含重复顶层字段");
                    cursor.expect(':');
                    if ("queries".equals(name) || "cases".equals(name)) {
                        if (arrayName != null) throw new IOException("报告 queries 与 cases 只能出现一个");
                        arrayName = name;
                        cursor.expect('[');
                        if (!cursor.consume(']')) {
                            do {
                                Object value = parse(cursor.value());
                                if (!(value instanceof Map<?, ?>)) throw new IOException("报告条目必须为对象");
                                @SuppressWarnings("unchecked") Map<String, Object> entry = (Map<String, Object>) value;
                                consumer.accept(entry);
                                count = Math.incrementExact(count);
                            } while (cursor.consume(','));
                            cursor.expect(']');
                        }
                    } else metadata.put(name, parse(cursor.value()));
                } while (cursor.consume(','));
                cursor.expect('}');
            }
            if (cursor.peek() != -1) throw new IOException("报告结尾含多余内容");
            if (arrayName == null) throw new IOException("报告缺少 queries 或 cases 数组");
            boolean dml = "DML_ROLLBACK".equals(metadata.get("mode"));
            if (dml != "cases".equals(arrayName) || metadata.containsKey("mode") && !dml
                    && !"QUERY".equals(metadata.get("mode"))) throw new IOException("报告模式与条目数组不一致");
            return new Metadata(metadata, arrayName, count);
        } catch (RuntimeException exception) {
            // 不回显原始解析文本，避免错误输出泄漏报告内的 SQL 或参数。
            throw new IOException("报告 JSON 格式无效", exception);
        }
    }

    private static Object parse(String text) {
        return JSON.parse(text, JSONReader.Feature.UseBigDecimalForDoubles, JSONReader.Feature.UseBigDecimalForFloats);
    }

    /** 单值边界扫描器；字符串转义、括号和分隔符先校验，单条值再由 JSON 库解析。 */
    private static final class Cursor {
        private final Reader reader;
        private int next = -2;
        Cursor(Reader reader) { this.reader = reader; }
        int raw() throws IOException { if (next == -2) next = reader.read(); return next; }
        int take() throws IOException { int result = raw(); next = -2; return result; }
        int peek() throws IOException {
            while (raw() == ' ' || raw() == '\n' || raw() == '\r' || raw() == '\t') take();
            return raw();
        }
        boolean consume(char ch) throws IOException { if (peek() != ch) return false; take(); return true; }
        void expect(char ch) throws IOException { if (!consume(ch)) throw new IOException("报告 JSON 结构不完整"); }
        String value() throws IOException {
            StringBuilder result = new StringBuilder();
            appendValue(result, 0);
            return result.toString();
        }
        void appendValue(StringBuilder result, int depth) throws IOException {
            if (depth > 128) throw new IOException("报告 JSON 嵌套过深");
            int ch = peek();
            if (ch == '"') {
                result.append((char) take());
                boolean escaped = false;
                while (true) {
                    ch = take();
                    if (ch < 0 || ch < 32) throw new IOException("报告字符串不完整");
                    result.append((char) ch);
                    if (!escaped && ch == '"') break;
                    if (!escaped && ch == '\\') escaped = true;
                    else escaped = false;
                }
            } else if (ch == '{' || ch == '[') {
                boolean object = ch == '{';
                result.append((char) take());
                char end = object ? '}' : ']';
                if (!consume(end)) {
                    do {
                        if (object) {
                            if (peek() != '"') throw new IOException("报告对象字段名无效");
                            appendValue(result, depth + 1);
                            expect(':'); result.append(':');
                        }
                        appendValue(result, depth + 1);
                        if (!consume(',')) break;
                        result.append(',');
                    } while (true);
                    expect(end);
                }
                result.append(end);
            } else {
                int start = result.length();
                while ((ch = raw()) != -1 && ch != ',' && ch != ']' && ch != '}'
                        && ch != ':' && !Character.isWhitespace(ch)) result.append((char) take());
                String token = result.substring(start);
                if (!token.matches("null|true|false|-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                    throw new IOException("报告 JSON 值无效");
                }
            }
        }
    }
}
