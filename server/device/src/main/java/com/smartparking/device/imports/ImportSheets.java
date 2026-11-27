package com.smartparking.device.imports;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/** 导入相关的下载文件：模板、失败行、印刷文件。单元格一律写成文本，IMEI 不会被 Excel 转成科学计数法 */
public final class ImportSheets {

    static final String FAILED_REASON = "失败原因";
    static final int QR_PIXELS = 360;

    private ImportSheets() {}

    /** 导入失败的一行：原始值与原因 */
    public record FailedRow(
            int row, String sn, String imei, String bleMac, String model, String batchNo, String reason) {}

    /** 印刷文件中的一台设备 */
    public record PrintItem(String sn, String code, String model, String batchNo) {}

    public static byte[] template() {
        return workbook("导入模板", ws -> {
            header(ws, ImportFileReader.HEADERS);
            // 整列预设为文本格式，录入 IMEI 时不会变成数字
            ws.range(1, 0, ImportFileReader.MAX_ROWS, ImportFileReader.HEADERS.size() - 1)
                    .style()
                    .format("@")
                    .set();
        });
    }

    /** 原始五列 + 失败原因；修正后可直接重新导入（读取时忽略多出的列） */
    public static byte[] failedRows(List<FailedRow> rows) {
        return workbook("失败行", ws -> {
            List<String> header = new java.util.ArrayList<>(ImportFileReader.HEADERS);
            header.add(FAILED_REASON);
            header(ws, header);
            int r = 1;
            for (FailedRow f : rows) {
                row(ws, r++, f.sn(), f.imei(), f.bleMac(), f.model(), f.batchNo(), "第 " + f.row() + " 行：" + f.reason());
            }
        });
    }

    /**
     * 印刷文件（ZIP）：{@code manifest.xlsx} 清单（序号、SN、激活码、型号、批次、二维码内容、二维码文件）与 {@code qr/<SN>.png}。
     * 文件名用 ASCII，避免解压工具对中文文件名的编码问题。
     */
    public static byte[] printZip(List<PrintItem> items, Function<String, String> qrContent) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.xlsx"));
            zip.write(workbook("印刷清单", ws -> {
                header(ws, List.of("序号", "SN", "激活码", "型号", "批次", "二维码内容", "二维码文件"));
                int r = 1;
                for (PrintItem item : items) {
                    row(
                            ws,
                            r,
                            Integer.toString(r),
                            item.sn(),
                            item.code(),
                            item.model(),
                            item.batchNo(),
                            qrContent.apply(item.sn()),
                            qrFile(item.sn()));
                    r++;
                }
            }));
            zip.closeEntry();
            for (PrintItem item : items) {
                zip.putNextEntry(new ZipEntry(qrFile(item.sn())));
                zip.write(qrPng(qrContent.apply(item.sn())));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static String qrFile(String sn) {
        return "qr/" + sn + ".png";
    }

    static byte[] qrPng(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter()
                    .encode(
                            content,
                            BarcodeFormat.QR_CODE,
                            QR_PIXELS,
                            QR_PIXELS,
                            Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            BufferedImage image =
                    new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
            for (int x = 0; x < matrix.getWidth(); x++) {
                for (int y = 0; y < matrix.getHeight(); y++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            return png.toByteArray();
        } catch (WriterException e) {
            throw new IllegalStateException("二维码生成失败", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private interface Sheet {
        void fill(Worksheet ws) throws IOException;
    }

    private static byte[] workbook(String sheetName, Sheet sheet) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            write(out, sheetName, sheet);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void write(OutputStream out, String sheetName, Sheet sheet) throws IOException {
        Workbook wb = new Workbook(out, "SmartParking", "1.0");
        Worksheet ws = wb.newWorksheet(sheetName);
        sheet.fill(ws);
        wb.finish();
    }

    private static void header(Worksheet ws, List<String> names) {
        for (int c = 0; c < names.size(); c++) {
            ws.value(0, c, names.get(c));
            ws.style(0, c).bold().set();
            ws.width(c, 20);
        }
    }

    private static void row(Worksheet ws, int r, String... values) {
        for (int c = 0; c < values.length; c++) {
            ws.value(r, c, values[c] == null ? "" : values[c]);
        }
    }
}
