package com.kairui.tools.sqlverify.report;

import com.alibaba.fastjson2.JSON;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 从已脱敏的正式报告导出目标侧执行失败；不读取配置、不连接数据库。 */
public final class TargetFailureExcelGenerator {
    public static final String FILE_NAME = "target-execution-failures.xlsx";
    private static final int CELL_LIMIT = 32000;

    /** 逐条读取证据并流式写入临时文件，成功后原子发布，失败时保留已有 Excel。 */
    public Path generate(Path input) throws IOException {
        Path report = input.toAbsolutePath();
        Path output = report.resolveSibling(FILE_NAME);
        Path temporary = Files.createTempFile(report.getParent(), "target-failures-", ".xlsx.tmp");
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try (workbook) {
            var sheet = workbook.createSheet("目标库执行失败");
            sheet.setDisplayGridlines(false);
            int[] widths = {10, 16, 16, 14, 56, 14, 14, 100, 40, 80, 14, 12};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            CellStyle body = workbook.createCellStyle();
            body.setWrapText(true);
            body.setVerticalAlignment(VerticalAlignment.TOP);
            CellStyle header = workbook.createCellStyle();
            header.cloneStyleFrom(body);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = workbook.createFont();
            font.setBold(true);
            font.setColor(IndexedColors.WHITE.getIndex());
            header.setFont(font);
            write(sheet.createRow(0), List.of("目标库执行失败明细"), body);
            write(sheet.createRow(1), List.of("仅目标侧实际执行失败；不含源侧失败、结果差异、未执行及仅清理失败。"), body);
            write(sheet.createRow(2), List.of("SQL、参数或错误超过单元格限制时分行保存，按同一用例的分段序号拼接。"), body);
            write(sheet.createRow(3), List.of("来源报告", report.getFileName().toString()), body);
            Row summary = sheet.createRow(4);
            write(sheet.createRow(5), List.of("序号", "项目", "验证类型", "错误阶段", "目标库错误", "SQLState",
                    "数据库错误码", "SQL（原文）", "参数（JSON）", "用例 ID", "报告序号", "分段序号"), header);
            sheet.createFreezePane(2, 6);
            int[] counts = {0, 6};
            // 摘要行暂不写入，流式窗口外不能回填；摘要最终放在明细末尾。
            write(summary, List.of("完整运行结论与失败数见明细末尾；零条失败不代表所有用例通过。"), body);
            ReportJsonStream.Metadata metadata = ReportJsonStream.read(report, entry -> {
                Map<?, ?> target = entry.get("target") instanceof Map<?, ?> map ? map : Map.of();
                boolean dml = target.containsKey("execution");
                if (!"FAILED".equals(target.get(dml ? "execution" : "status"))) return;
                List<Map<?, ?>> errors = new ArrayList<>();
                if (dml && target.get("errors") instanceof List<?> list) {
                    for (Object value : list) {
                        if (value instanceof Map<?, ?> error && "EXECUTE".equals(error.get("stage"))) errors.add(error);
                    }
                } else if (target.get("error") instanceof Map<?, ?> error) errors.add(error);
                String id = text(entry.get("id"));
                int colon = id.indexOf(':');
                List<String> values = List.of(Integer.toString(++counts[0]), colon > 0 ? id.substring(0, colon) : "",
                        dml ? "DML" : "查询", join(errors, "stage"), join(errors, "message"), join(errors, "sqlState"),
                        join(errors, "vendorCode"), text(entry.get("sql")), JSON.toJSONString(entry.getOrDefault("parameters", List.of())),
                        id, text(entry.get("sequence")));
                List<List<String>> parts = values.stream().map(TargetFailureExcelGenerator::split).toList();
                int segments = parts.stream().mapToInt(List::size).max().orElse(1);
                for (int segment = 0; segment < segments; segment++) {
                    if (counts[1] >= 1048574) throw new IOException("失败明细超过单工作表行数限制，完整证据保留在 JSON 中");
                    List<String> cells = new ArrayList<>();
                    for (int column = 0; column < parts.size(); column++) {
                        List<String> chunks = parts.get(column);
                        boolean repeated = column == 0 || column == 1 || column == 2 || column == 10;
                        cells.add(repeated ? values.get(column) : segment < chunks.size() ? chunks.get(segment) : "");
                    }
                    cells.add(Integer.toString(segment + 1));
                    write(sheet.createRow(counts[1]++), cells, body);
                }
            });
            sheet.setAutoFilter(new CellRangeAddress(5, Math.max(5, counts[1] - 1), 0, 11));
            write(sheet.createRow(counts[1]++), List.of("目标执行失败数", Integer.toString(counts[0]),
                    "运行状态", text(metadata.fields().getOrDefault("runStatus", "查询报告已生成")),
                    "停止原因", text(metadata.fields().get("stopReason"))), body);
            write(sheet.createRow(counts[1]), List.of("本轮统计", JSON.toJSONString(metadata.fields().get("summary"))), body);
            try (OutputStream stream = Files.newOutputStream(temporary)) { workbook.write(stream); }
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return output;
        } finally {
            workbook.dispose();
            Files.deleteIfExists(temporary);
        }
    }

    /** 所有报告字段以文本保存，防止 SQL/参数被 Excel 解释为公式或损失数值精度。 */
    private static void write(Row row, List<String> values, CellStyle style) {
        row.setHeightInPoints(row.getRowNum() < 6 ? 30 : 90);
        for (int i = 0; i < values.size(); i++) {
            var cell = row.createCell(i);
            cell.setCellValue(values.get(i));
            cell.setCellStyle(style);
        }
    }

    private static String text(Object value) { return value == null ? "" : value.toString(); }

    private static String join(List<Map<?, ?>> errors, String key) {
        return String.join("\n", errors.stream().map(error -> text(error.get(key))).toList());
    }

    /** 不截断长 SQL 或参数，也不拆开 UTF-16 代理对。 */
    private static List<String> split(String value) {
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < value.length();) {
            int end = Math.min(value.length(), start + CELL_LIMIT);
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
            parts.add(value.substring(start, end));
            start = end;
        }
        return parts.isEmpty() ? List.of("") : parts;
    }
}
