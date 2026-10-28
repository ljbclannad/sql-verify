package com.kairui.tools.sqlverify.report;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** 单条执行证据立即持久化，结束时流式汇总；内存只保留当前条目和小型报告元数据。 */
public final class ExecutionReportWriter implements AutoCloseable {
    private final Path directory;
    private final FileChannel entries;
    private final UnaryOperator<Object> sanitizer;
    private final String arrayName;
    private int completedQueries;
    private boolean unavailable;

    private ExecutionReportWriter(Path directory, FileChannel entries, UnaryOperator<Object> sanitizer, String arrayName) {
        this.directory = directory;
        this.entries = entries;
        this.sanitizer = sanitizer;
        this.arrayName = arrayName;
    }

    /** 每轮建立独立目录并立即创建证据文件，连接或执行中断时已写条目仍可查看。 */
    public static ExecutionReportWriter open(Path output, UnaryOperator<Object> sanitizer) throws IOException {
        return open(output, sanitizer, "queries");
    }

    /** 查询和 DML 共用落盘机制，文件和数组只在两个固定模式之间选择。 */
    public static ExecutionReportWriter open(Path output, UnaryOperator<Object> sanitizer, String arrayName) throws IOException {
        if (!"queries".equals(arrayName) && !"cases".equals(arrayName)) throw new IllegalArgumentException("未知报告模式");
        Objects.requireNonNull(sanitizer, "sanitizer");
        Files.createDirectories(output);
        Path directory = Files.createTempDirectory(output,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + "-");
        FileChannel channel = FileChannel.open(directory.resolve(arrayName + ".jsonl"),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new ExecutionReportWriter(directory, channel, sanitizer, arrayName);
    }

    public Path directory() { return directory; }

    public int completedQueries() { return completedQueries; }

    /** 一条查询的双侧结果统一脱敏后写为一行；force 成功后才计为已落盘。写失败后终止写入。 */
    public void append(Map<String, Object> entry) throws IOException {
        ensureAvailable();
        try {
            // 持久化步骤 1：比较已经结束，此时才脱敏并序列化；每条证据独立一行，不缓存整轮结果。
            String line = serialize(sanitized(entry));
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(line + "\n");
            while (bytes.hasRemaining()) entries.write(bytes);
            // 持久化步骤 2：同步文件后才增加已完成数；并发调度器也必须等 append 返回后才能确认该条。
            entries.force(true);
            completedQueries++;
        } catch (IOException | RuntimeException exception) {
            unavailable = true;
            throw exception;
        }
    }

    /** 按行复制已持久化条目，生成兼容原结构的 queries 数组；不重新加载整轮结果。 */
    public Path finish(Map<String, Object> metadata) throws IOException {
        ensureAvailable();
        Object sanitizedMetadata = sanitized(metadata);
        if (!(sanitizedMetadata instanceof Map<?, ?> fields)) {
            throw new IllegalArgumentException("报告元数据必须为 JSON 对象");
        }
        // 汇总步骤：流式复制已落盘 JSONL 到临时报告，全部写完再发布正式文件；中途失败不伪装完成。
        Path temporary = directory.resolve("verification.json.tmp");
        Path report = directory.resolve("verification.json");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
             BufferedWriter writer = new BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8));
             BufferedReader reader = Files.newBufferedReader(directory.resolve(arrayName + ".jsonl"), StandardCharsets.UTF_8)) {
            writer.write("{\n");
            for (Map.Entry<?, ?> field : fields.entrySet()) {
                if (arrayName.equals(field.getKey())) continue;
                writer.write(serialize(field.getKey()));
                writer.write(":");
                writer.write(serialize(field.getValue()));
                writer.write(",\n");
            }
            writer.write("\"" + arrayName + "\":[\n");
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) writer.write(",\n");
                writer.write(line);
                first = false;
            }
            writer.write("\n]}\n");
            writer.flush();
            channel.force(true);
        } catch (IOException exception) {
            unavailable = true;
            throw exception;
        }
        unavailable = true;
        try {
            Files.move(temporary, report, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, report);
        }
        return report;
    }

    /** 先转换记录、枚举等对象，再按值脱敏，保留显式 null 和高精度数值。 */
    private Object sanitized(Object value) {
        Object normalized = JSON.parse(serialize(value), JSONReader.Feature.UseBigDecimalForDoubles,
                JSONReader.Feature.UseBigDecimalForFloats);
        return sanitizer.apply(normalized);
    }

    private static String serialize(Object value) {
        return JSON.toJSONString(value, JSONWriter.Feature.WriteBigDecimalAsPlain,
                JSONWriter.Feature.WriteMapNullValue);
    }

    private void ensureAvailable() throws IOException {
        if (unavailable || !entries.isOpen()) throw new IOException("执行报告已结束或写入失败，不能继续写入");
    }

    @Override
    public void close() throws IOException {
        unavailable = true;
        entries.close();
    }
}
