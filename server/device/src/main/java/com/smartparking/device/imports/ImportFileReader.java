package com.smartparking.device.imports;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;

/**
 * 读取批量导入文件（契约 createImport）：.xlsx 或 .csv，第一行为表头，列固定为 SN、IMEI、蓝牙 MAC、型号、批次，之后可以有其他列
 * （如失败行文件里的“失败原因”）。整行为空的跳过。格式问题一律 40014，不进入异步处理。
 *
 * <ul>
 *   <li>Excel 里 IMEI 常被存成数字，按原始值还原，不会变成科学计数法。
 *   <li>CSV 先按 UTF-8（可带 BOM）解码，不是合法 UTF-8 时按 GB18030 解码：中文版 Excel “另存为 CSV” 默认是 GBK。
 * </ul>
 */
public final class ImportFileReader {

    public static final List<String> HEADERS = List.of("SN", "IMEI", "蓝牙 MAC", "型号", "批次");
    public static final int MAX_ROWS = 5000;

    private ImportFileReader() {}

    /** 一个数据行；{@code row} 为文件中的行号（含表头，从 1 开始），各值为原文（只去首尾空白） */
    public record RawRow(int row, String sn, String imei, String bleMac, String model, String batchNo) {}

    public static List<RawRow> read(String fileName, byte[] content) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        List<List<String>> table;
        List<Integer> rowNumbers = new ArrayList<>();
        if (name.endsWith(".xlsx")) {
            table = xlsx(content, rowNumbers);
        } else if (name.endsWith(".csv")) {
            table = csv(decode(content), rowNumbers);
        } else {
            throw invalid("仅支持 .xlsx 或 .csv 文件");
        }
        if (table.isEmpty()) {
            throw invalid("文件为空");
        }
        checkHeader(table.get(0));
        List<RawRow> rows = new ArrayList<>();
        for (int i = 1; i < table.size(); i++) {
            List<String> cells = table.get(i);
            if (cells.stream().allMatch(String::isBlank)) {
                continue;
            }
            rows.add(new RawRow(
                    rowNumbers.get(i), cell(cells, 0), cell(cells, 1), cell(cells, 2), cell(cells, 3), cell(cells, 4)));
            if (rows.size() > MAX_ROWS) {
                throw invalid("单个文件最多 " + MAX_ROWS + " 行");
            }
        }
        if (rows.isEmpty()) {
            throw invalid("文件中没有数据行");
        }
        return rows;
    }

    private static void checkHeader(List<String> header) {
        for (int i = 0; i < HEADERS.size(); i++) {
            String expected = HEADERS.get(i).replace(" ", "");
            String actual = cell(header, i).replace(" ", "").toUpperCase(Locale.ROOT);
            if (!expected.equalsIgnoreCase(actual)) {
                throw invalid("表头应为：" + String.join("、", HEADERS) + "，请使用导入模板");
            }
        }
    }

    private static List<List<String>> xlsx(byte[] content, List<Integer> rowNumbers) {
        List<List<String>> table = new ArrayList<>();
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(content));
                Stream<Row> stream = wb.getFirstSheet().openStream()) {
            stream.forEach(row -> {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < Math.max(row.getCellCount(), HEADERS.size()); c++) {
                    cells.add(text(c < row.getCellCount() ? row.getCell(c) : null));
                }
                table.add(cells);
                rowNumbers.add(row.getRowNum());
            });
        } catch (IOException | RuntimeException e) {
            throw invalid("无法读取 Excel 文件，请确认是 .xlsx 格式");
        }
        return table;
    }

    private static String text(Cell cell) {
        if (cell == null || cell.getType() == CellType.EMPTY) {
            return "";
        }
        if (cell.getType() == CellType.NUMBER) {
            // 原始值如 "861234567890128" 或 "8.61234567890128E14"，按十进制还原
            try {
                return new BigDecimal(cell.getRawValue()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return cell.getRawValue().strip();
            }
        }
        String text = cell.getText();
        return text == null ? "" : text.strip();
    }

    static String decode(byte[] content) {
        int offset = content.length >= 3
                        && (content[0] & 0xFF) == 0xEF
                        && (content[1] & 0xFF) == 0xBB
                        && (content[2] & 0xFF) == 0xBF
                ? 3
                : 0;
        ByteBuffer bytes = ByteBuffer.wrap(content, offset, content.length - offset);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes)
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(content, Charset.forName("GB18030"));
        }
    }

    /** RFC 4180：逗号分隔，双引号包裹的字段内可含逗号、换行，"" 表示一个引号 */
    static List<List<String>> csv(String text, List<Integer> rowNumbers) {
        List<List<String>> table = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int rowStart = 1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    if (ch == '\n') {
                        line++;
                    }
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(field.toString().strip());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString().strip());
                field.setLength(0);
                table.add(row);
                rowNumbers.add(rowStart);
                row = new ArrayList<>();
                line++;
                rowStart = line;
            } else {
                field.append(ch);
            }
        }
        if (quoted) {
            throw invalid("CSV 中有未闭合的引号");
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString().strip());
            table.add(row);
            rowNumbers.add(rowStart);
        }
        return table;
    }

    private static String cell(List<String> cells, int index) {
        return index < cells.size() ? cells.get(index) : "";
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.IMPORT_FILE_INVALID, message);
    }
}
