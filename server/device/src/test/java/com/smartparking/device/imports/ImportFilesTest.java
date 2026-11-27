package com.smartparking.device.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.device.imports.ImportFileReader.RawRow;
import com.smartparking.device.imports.ImportSheets.FailedRow;
import com.smartparking.device.imports.ImportSheets.PrintItem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.junit.jupiter.api.Test;

/** 导入文件读取（CSV / xlsx）与下载文件（模板、失败行、印刷 ZIP） */
class ImportFilesTest {

    private static final String HEADER = "SN,IMEI,蓝牙 MAC,型号,批次\n";

    private static void rejects(String fileName, byte[] content, String messagePart) {
        assertThatThrownBy(() -> ImportFileReader.read(fileName, content))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.IMPORT_FILE_INVALID);
                    assertThat(e.getMessage()).contains(messagePart);
                });
    }

    @Test
    void readsCsvWithBomQuotesBlankRowsAndRowNumbers() {
        String csv = "﻿" + HEADER
                + "SP0000000001,490154203237518,AA:BB:CC:DD:EE:01,SPG-100,B1\r\n"
                + ",,,,\n"
                + "\"SP0000000002\",\"356938035643809\",,\"型号,带逗号\",B1\n"
                + "SP0000000003,123,,M,B";
        List<RawRow> rows = ImportFileReader.read("a.CSV", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(rows).extracting(RawRow::row).containsExactly(2, 4, 5);
        assertThat(rows.get(0).bleMac()).isEqualTo("AA:BB:CC:DD:EE:01");
        assertThat(rows.get(1).model()).isEqualTo("型号,带逗号");
        assertThat(rows.get(1).bleMac()).isEmpty();
        assertThat(rows.get(2).imei()).isEqualTo("123");
    }

    @Test
    void readsGbkCsvSavedByChineseExcel() {
        String csv = HEADER + "SP0000000001,490154203237518,,道闸一代,第一批\n";
        List<RawRow> rows = ImportFileReader.read("a.csv", csv.getBytes(Charset.forName("GBK")));
        assertThat(rows.get(0).model()).isEqualTo("道闸一代");
        assertThat(rows.get(0).batchNo()).isEqualTo("第一批");
    }

    @Test
    void rejectsBadFiles() {
        rejects("a.txt", HEADER.getBytes(StandardCharsets.UTF_8), ".xlsx 或 .csv");
        rejects("a.csv", new byte[0], "文件为空");
        rejects("a.csv", HEADER.getBytes(StandardCharsets.UTF_8), "没有数据行");
        rejects("a.csv", "IMEI,SN,蓝牙 MAC,型号,批次\nx,y,,m,b".getBytes(StandardCharsets.UTF_8), "表头");
        rejects("a.csv", (HEADER + "\"unclosed,1,,m,b").getBytes(StandardCharsets.UTF_8), "引号");
        rejects("a.xlsx", "not a zip".getBytes(StandardCharsets.UTF_8), "Excel");

        StringBuilder big = new StringBuilder(HEADER);
        for (int i = 0; i <= ImportFileReader.MAX_ROWS; i++) {
            big.append("SP").append(i).append(",1,,m,b\n");
        }
        rejects("a.csv", big.toString().getBytes(StandardCharsets.UTF_8), "最多 5000 行");
    }

    @Test
    void headerToleratesSpacingAndExtraColumns() {
        String csv = "sn,imei,蓝牙MAC,型号,批次,失败原因\nSP0000000001,490154203237518,,M,B,第 2 行：xx\n";
        assertThat(ImportFileReader.read("a.csv", csv.getBytes(StandardCharsets.UTF_8)))
                .hasSize(1);
    }

    @Test
    void readsXlsxWithNumericImei() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Workbook wb = new Workbook(out, "test", "1.0");
        Worksheet ws = wb.newWorksheet("s");
        String[] header = {"SN", "IMEI", "蓝牙 MAC", "型号", "批次"};
        for (int c = 0; c < header.length; c++) {
            ws.value(0, c, header[c]);
        }
        ws.value(1, 0, "SP0000000001");
        ws.value(1, 1, 490154203237518L); // 录入时被 Excel 存成数字
        ws.value(1, 3, "SPG-100");
        ws.value(1, 4, "B1");
        ws.value(3, 0, "SP0000000002");
        ws.value(3, 1, "356938035643809");
        ws.value(3, 3, "SPG-100");
        ws.value(3, 4, "B1");
        wb.finish();

        List<RawRow> rows = ImportFileReader.read("a.xlsx", out.toByteArray());
        assertThat(rows).extracting(RawRow::row).containsExactly(2, 4);
        assertThat(rows.get(0).imei()).isEqualTo("490154203237518");
        assertThat(rows.get(0).bleMac()).isEmpty();
    }

    @Test
    void templateHasHeaderOnly() {
        rejects("t.xlsx", ImportSheets.template(), "没有数据行");
    }

    @Test
    void failedRowsFileCanBeReimported() {
        byte[] file = ImportSheets.failedRows(List.of(
                new FailedRow(3, "SP0000000001", "490154203237517", "", "M", "B", "IMEI 校验位不正确"),
                new FailedRow(9, "SP0000000002", "356938035643809", null, "M", "B", "SN 已存在")));
        List<RawRow> rows = ImportFileReader.read("failed.xlsx", file);
        assertThat(rows).extracting(RawRow::sn).containsExactly("SP0000000001", "SP0000000002");
        assertThat(rows.get(0).imei()).isEqualTo("490154203237517");
    }

    @Test
    void printZipHasManifestAndScannableQrCodes() throws Exception {
        List<PrintItem> items = List.of(
                new PrintItem("SP0000000001", "K7Q2M9XA", "SPG-100", "B1"),
                new PrintItem("SP0000000002", "ABCDEFGH", "SPG-100", "B1"));
        byte[] zip = ImportSheets.printZip(items, sn -> "https://smartparking.invalid/bind?sn=" + sn);

        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                entries.put(e.getName(), in.readAllBytes());
            }
        }
        assertThat(entries).containsOnlyKeys("manifest.xlsx", "qr/SP0000000001.png", "qr/SP0000000002.png");

        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(entries.get("manifest.xlsx")))) {
            List<List<String>> rows = wb.getFirstSheet().read().stream()
                    .map(r -> r.stream().map(c -> c == null ? "" : c.getText()).toList())
                    .toList();
            assertThat(rows.get(0)).containsExactly("序号", "SN", "激活码", "型号", "批次", "二维码内容", "二维码文件");
            assertThat(rows.get(1))
                    .containsExactly(
                            "1",
                            "SP0000000001",
                            "K7Q2M9XA",
                            "SPG-100",
                            "B1",
                            "https://smartparking.invalid/bind?sn=SP0000000001",
                            "qr/SP0000000001.png");
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(entries.get("qr/SP0000000002.png")));
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        String decoded = new QRCodeReader()
                .decode(new BinaryBitmap(
                        new HybridBinarizer(new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels))))
                .getText();
        assertThat(decoded).isEqualTo("https://smartparking.invalid/bind?sn=SP0000000002");
    }
}
