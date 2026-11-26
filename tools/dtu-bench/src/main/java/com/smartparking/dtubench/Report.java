package com.smartparking.dtubench;

import com.smartparking.dtubench.PayloadAnalyzer.Kind;
import com.smartparking.dtubench.Scenario.Outcome;
import com.smartparking.dtubench.Scenario.Sample;
import com.smartparking.dtubench.Scenario.Step;
import com.smartparking.iot.codec.Hex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 联调结果：Markdown 汇总（可直接贴进 docs/iot/bench.md 的记录表）与两份 CSV 明细。 */
public final class Report {

    private Report() {}

    public static String markdown(String imei, Instant startedAt, List<Sample> samples, List<Received> received) {
        var sb = new StringBuilder();
        sb.append("## 联调记录 ")
                .append(imei)
                .append(" @ ")
                .append(local(startedAt))
                .append("\n\n");
        sb.append("| 步骤 | 次数 | 成功 | 失败 | 超时 | 回包 / 回显 ms（p50 / p95 / max） | 到位 ms（p50 / p95 / max） |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (Step step : Step.values()) {
            List<Sample> rows = samples.stream().filter(s -> s.step() == step).toList();
            if (rows.isEmpty()) {
                continue;
            }
            var ack = new Latencies();
            var done = new Latencies();
            rows.forEach(s -> {
                if (s.ackMs() != null) {
                    ack.add(s.ackMs());
                }
                if (s.doneMs() != null) {
                    done.add(s.doneMs());
                }
            });
            sb.append(String.format(
                    "| %s | %d | %d | %d | %d | %s | %s |%n",
                    step.label,
                    rows.size(),
                    count(rows, Outcome.OK),
                    count(rows, Outcome.FAILED),
                    count(rows, Outcome.TIMEOUT),
                    ack.summary(),
                    step == Step.OPEN || step == Step.CLOSE ? done.summary() : "—"));
        }
        sb.append("\n").append(payloads(received));
        List<Sample> bad =
                samples.stream().filter(s -> s.outcome() != Outcome.OK).toList();
        if (!bad.isEmpty()) {
            sb.append("\n### 失败 / 超时明细\n\n");
            bad.forEach(s -> sb.append(
                    String.format("- 第 %d 轮 %s：%s %s%n", s.round(), s.step().label, s.outcome(), s.detail())));
        }
        return sb.toString();
    }

    /** 上行载荷归类统计；除“恰好一帧”外的都列出样例 */
    public static String payloads(List<Received> received) {
        Map<Kind, Long> counts = new EnumMap<>(Kind.class);
        received.forEach(r -> counts.merge(r.analysis().kind(), 1L, Long::sum));
        var sb = new StringBuilder("### 上行载荷\n\n| 类型 | 条数 |\n| --- | --- |\n");
        for (Kind kind : Kind.values()) {
            sb.append("| ")
                    .append(label(kind))
                    .append(" | ")
                    .append(counts.getOrDefault(kind, 0L))
                    .append(" |\n");
        }
        String examples = received.stream()
                .filter(r -> r.analysis().kind() != Kind.FRAME)
                .limit(20)
                .map(r -> "- " + label(r.analysis().kind()) + "：`" + Hex.format(r.payload()) + "` "
                        + r.analysis().detail())
                .collect(Collectors.joining("\n"));
        if (!examples.isEmpty()) {
            sb.append("\n异常载荷样例（最多 20 条）：\n\n").append(examples).append("\n");
        }
        return sb.toString();
    }

    /** 东八区时刻，便于与现场记录对照 */
    static String local(Instant at) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(at);
    }

    static String label(Kind kind) {
        return switch (kind) {
            case FRAME -> "恰好一帧";
            case CONCATENATED -> "粘包（多帧）";
            case FRAGMENT -> "拆包前半";
            case REASSEMBLED -> "拆包后半（已拼接）";
            case NON_RS485 -> "非 RS485（疑似心跳 / 注册包）";
            case INVALID -> "无法识别";
        };
    }

    private static long count(List<Sample> rows, Outcome outcome) {
        return rows.stream().filter(s -> s.outcome() == outcome).count();
    }

    /** 写出 report.md、samples.csv、uplinks.csv */
    public static void write(Path dir, String markdown, List<Sample> samples, List<Received> received, long startNanos)
            throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("report.md"), markdown, StandardCharsets.UTF_8);
        var s = new StringBuilder("round,step,outcome,ack_ms,done_ms,detail\n");
        samples.forEach(x -> s.append(x.round())
                .append(',')
                .append(x.step())
                .append(',')
                .append(x.outcome())
                .append(',')
                .append(x.ackMs() == null ? "" : x.ackMs())
                .append(',')
                .append(x.doneMs() == null ? "" : x.doneMs())
                .append(',')
                .append(csv(x.detail()))
                .append('\n'));
        Files.writeString(dir.resolve("samples.csv"), s, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("uplinks.csv"), uplinkCsv(received, startNanos), StandardCharsets.UTF_8);
    }

    public static String uplinkCsv(List<Received> received, long startNanos) {
        var u = new StringBuilder("received_at,since_start_ms,bytes,hex,kind,frames,detail\n");
        received.forEach(r -> u.append(r.at())
                .append(',')
                .append((r.nanos() - startNanos) / 1_000_000)
                .append(',')
                .append(r.payload().length)
                .append(',')
                .append(Hex.format(r.payload()))
                .append(',')
                .append(r.analysis().kind())
                .append(',')
                .append(r.analysis().frames().size())
                .append(',')
                .append(csv(r.analysis().detail()))
                .append('\n'));
        return u.toString();
    }

    private static String csv(String value) {
        return value.contains(",") || value.contains("\"") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
