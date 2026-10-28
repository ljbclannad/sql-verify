package com.kairui.tools.sqlverify.report;

import com.alibaba.fastjson2.JSON;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TargetFailureExcelGeneratorTest {
    @TempDir Path directory;

    private Path generate(Map<String, Object> report) throws Exception {
        Path input = directory.resolve("verification.json");
        Files.writeString(input, JSON.toJSONString(report));
        return new TargetFailureExcelGenerator().generate(input);
    }

    @Test void selectsOnlyTargetFailuresAndPreservesLiteralSqlAndParameters() throws Exception {
        String sql = "=中文<&>\nSELECT ?";
        var parameters = List.of(Map.of("jdbcType", "VARCHAR", "value", "900719925474099312345"));
        Path output = generate(Map.of("queries", List.of(
                Map.of("id", "aimb:failed", "sequence", 2, "sql", sql, "parameters", parameters,
                        "target", Map.of("status", "FAILED", "error", Map.of("stage", "EXECUTE", "message", "错误", "sqlState", "HY000", "vendorCode", 4243))),
                Map.of("id", "sourceOnly", "source", Map.of("status", "FAILED"), "target", Map.of("status", "SUCCESS")),
                Map.of("id", "skipped", "target", Map.of("status", "NOT_EXECUTED")))));
        try (var workbook = new XSSFWorkbook(output.toFile())) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("aimb", sheet.getRow(6).getCell(1).getStringCellValue());
            assertEquals(sql, sheet.getRow(6).getCell(7).getStringCellValue());
            assertEquals(CellType.STRING, sheet.getRow(6).getCell(7).getCellType());
            assertEquals(JSON.toJSONString(parameters), sheet.getRow(6).getCell(8).getStringCellValue());
            assertEquals("1", sheet.getRow(7).getCell(1).getStringCellValue());
            assertEquals(8, sheet.getLastRowNum());
        }
    }

    @Test void dmlCleanupFailureIsExcludedAndStoppedStateIsVisible() throws Exception {
        Path output = generate(Map.of("mode", "DML_ROLLBACK", "runStatus", "STOPPED", "stopReason", "恢复未确认", "cases", List.of(
                Map.of("id", "cleanup", "target", Map.of("execution", "SUCCESS", "cleanup", "UNKNOWN")),
                Map.of("id", "write", "target", Map.of("execution", "FAILED", "errors", List.of(
                        Map.of("stage", "EXECUTE", "message", "写入失败"), Map.of("stage", "CLOSE", "message", "关闭失败")))))));
        try (var workbook = new XSSFWorkbook(output.toFile())) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("写入失败", sheet.getRow(6).getCell(4).getStringCellValue());
            assertEquals("STOPPED", sheet.getRow(7).getCell(3).getStringCellValue());
            assertEquals("恢复未确认", sheet.getRow(7).getCell(5).getStringCellValue());
        }
    }

    @Test void emptyReportStillExportsAndLongValuesAreLossless() throws Exception {
        Path empty = generate(Map.of("queries", List.of()));
        try (var workbook = new XSSFWorkbook(empty.toFile())) {
            assertEquals("0", workbook.getSheetAt(0).getRow(6).getCell(1).getStringCellValue());
        }
        String sql = "a".repeat(31999) + "😀" + "b".repeat(2000);
        Path longFile = generate(Map.of("queries", List.of(Map.of("id", "long", "sql", sql, "target", Map.of("status", "FAILED")))));
        try (var workbook = new XSSFWorkbook(longFile.toFile())) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(sql, sheet.getRow(6).getCell(7).getStringCellValue() + sheet.getRow(7).getCell(7).getStringCellValue());
            assertEquals("1", sheet.getRow(8).getCell(1).getStringCellValue());
        }
        byte[] previous = Files.readAllBytes(longFile);
        Files.writeString(directory.resolve("verification.json"), "{broken");
        assertThrows(Exception.class, () -> new TargetFailureExcelGenerator().generate(directory.resolve("verification.json")));
        assertArrayEquals(previous, Files.readAllBytes(longFile));
    }
}
