package com.smartparking.dtubench;

import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.codec.UplinkFrame;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 联调剧本：每轮依次执行查询、查版本、抬杆、落杆（IOT-11），记录每一步的时延与结果。
 *
 * <ul>
 *   <li><b>查询 / 查版本</b>：下发到收到对应帧的时间。
 *   <li><b>抬杆 / 落杆</b>：下发到收到回显的时间（“回包”），以及下发到查询到到位状态的时间（“到位”）。到位按云端同样的方式判定：
 *       每 {@code pollInterval} 发一次查询，收到到位帧为成功，电机故障 / 遇阻 / 红外为失败，{@code timeout} 未到位为超时（PRD 8.4）。
 *       到位时延的分辨率受查询间隔限制。
 * </ul>
 *
 * <p>协议没有请求编号，收到的帧只能按“下发之后到达的第一帧同类帧”对应；联调时应关闭业务服务的定时查询，避免串扰（见 docs/iot/bench.md）。
 */
public final class Scenario {

    public enum Step {
        QUERY("查询"),
        VERSION("查版本"),
        OPEN("抬杆"),
        CLOSE("落杆");

        public final String label;

        Step(String label) {
            this.label = label;
        }
    }

    public enum Outcome {
        OK,
        FAILED,
        TIMEOUT
    }

    /**
     * @param ackMs 查询 / 查版本为回包时延；抬杆 / 落杆为回显时延，没有回显为空
     * @param doneMs 抬杆 / 落杆到位时延；查询 / 查版本为空
     */
    public record Sample(int round, Step step, Outcome outcome, Long ackMs, Long doneMs, String detail) {}

    public record Options(int rounds, Duration timeout, Duration replyTimeout, Duration pollInterval, Duration pause) {

        public static Options defaults(int rounds) {
            return new Options(
                    rounds,
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(3),
                    Duration.ofMillis(300),
                    Duration.ofSeconds(1));
        }
    }

    private final Link link;
    private final Options options;
    private final Consumer<String> out;
    private final List<Sample> samples = new ArrayList<>();
    private final List<Received> received = new ArrayList<>();

    public Scenario(Link link, Options options, Consumer<String> out) {
        this.link = link;
        this.options = options;
        this.out = out;
    }

    public List<Sample> samples() {
        return List.copyOf(samples);
    }

    /** 剧本执行期间收到的全部上行（含与剧本无关的，如心跳） */
    public List<Received> received() {
        return List.copyOf(received);
    }

    public List<Sample> run() throws Exception {
        for (int round = 1; round <= options.rounds(); round++) {
            prepare();
            record(query(round));
            record(version(round));
            record(move(round, Step.OPEN));
            record(move(round, Step.CLOSE));
            if (round < options.rounds()) {
                Thread.sleep(options.pause().toMillis());
            }
        }
        return samples();
    }

    private void record(Sample sample) {
        samples.add(sample);
        out.accept(String.format(
                "第 %d 轮 %s：%s%s%s%s",
                sample.round(),
                sample.step().label,
                sample.outcome(),
                sample.ackMs() == null ? "" : "，回包 " + sample.ackMs() + " ms",
                sample.doneMs() == null ? "" : "，到位 " + sample.doneMs() + " ms",
                sample.detail().isEmpty() ? "" : "（" + sample.detail() + "）"));
    }

    /** 每轮开始前把闸杆收回关闸到位，不计入统计 */
    private void prepare() throws Exception {
        drain();
        link.send(Rs485Codec.encodeQuery());
        Optional<UplinkFrame.Status> status =
                awaitStatus(System.nanoTime() + options.replyTimeout().toNanos());
        if (status.isPresent() && status.get().status() != GateStatus.CLOSED) {
            out.accept("当前 " + status.get().status() + "，先落杆复位");
            move(0, Step.CLOSE);
        }
    }

    private Sample query(int round) throws Exception {
        drain();
        long t0 = System.nanoTime();
        link.send(Rs485Codec.encodeQuery());
        Optional<UplinkFrame.Status> status =
                awaitStatus(t0 + options.replyTimeout().toNanos());
        return status.map(s -> new Sample(round, Step.QUERY, Outcome.OK, since(t0, lastAt), null, s.rawFrame()))
                .orElseGet(() -> new Sample(round, Step.QUERY, Outcome.TIMEOUT, null, null, "无回包"));
    }

    private Sample version(int round) throws Exception {
        drain();
        long t0 = System.nanoTime();
        link.send(Rs485Codec.encodeVersionQuery());
        long deadline = t0 + options.replyTimeout().toNanos();
        while (true) {
            Optional<UplinkFrame> frame = next(deadline);
            if (frame.isEmpty()) {
                return new Sample(round, Step.VERSION, Outcome.TIMEOUT, null, null, "无回包");
            }
            if (frame.get() instanceof UplinkFrame.Version v) {
                return new Sample(
                        round,
                        Step.VERSION,
                        Outcome.OK,
                        since(t0, lastAt),
                        null,
                        String.format("版本 %02X", v.version()));
            }
        }
    }

    private Sample move(int round, Step step) throws Exception {
        drain();
        GateStatus target = step == Step.OPEN ? GateStatus.OPENED : GateStatus.CLOSED;
        long t0 = System.nanoTime();
        link.send(step == Step.OPEN ? Rs485Codec.encodeOpen() : Rs485Codec.encodeClose());
        long deadline = t0 + options.timeout().toNanos();
        Long ackMs = null;
        long nextPoll = t0 + options.pollInterval().toNanos();
        while (System.nanoTime() < deadline) {
            if (System.nanoTime() >= nextPoll) {
                link.send(Rs485Codec.encodeQuery());
                nextPoll = System.nanoTime() + options.pollInterval().toNanos();
            }
            Optional<UplinkFrame> frame = next(Math.min(nextPoll, deadline));
            if (frame.isEmpty()) {
                continue;
            }
            if (frame.get() instanceof UplinkFrame.Echo && ackMs == null) {
                ackMs = since(t0, lastAt);
            } else if (frame.get() instanceof UplinkFrame.Status s) {
                if (s.status() == target) {
                    return new Sample(round, step, Outcome.OK, ackMs, since(t0, lastAt), "");
                }
                if (s.fault().isPresent()) {
                    return new Sample(
                            round, step, Outcome.FAILED, ackMs, null, s.fault().get() + " " + s.rawFrame());
                }
            }
        }
        return new Sample(
                round, step, Outcome.TIMEOUT, ackMs, null, options.timeout().toSeconds() + " 秒未到位");
    }

    /* ---------------- 收帧 ---------------- */

    private long lastAt;

    private Optional<UplinkFrame.Status> awaitStatus(long deadline) throws InterruptedException {
        while (true) {
            Optional<UplinkFrame> frame = next(deadline);
            if (frame.isEmpty()) {
                return Optional.empty();
            }
            if (frame.get() instanceof UplinkFrame.Status s) {
                return Optional.of(s);
            }
        }
    }

    private final List<UplinkFrame> buffered = new ArrayList<>();

    /** 下一帧（一条载荷可能解出多帧）；{@link #lastAt} 为其所在载荷的到达时刻 */
    private Optional<UplinkFrame> next(long deadline) throws InterruptedException {
        while (buffered.isEmpty()) {
            long wait = deadline - System.nanoTime();
            if (wait <= 0) {
                return Optional.empty();
            }
            Received r = link.uplinks().poll(wait, TimeUnit.NANOSECONDS);
            if (r == null) {
                return Optional.empty();
            }
            received.add(r);
            lastAt = r.nanos();
            buffered.addAll(r.analysis().frames());
        }
        return Optional.of(buffered.remove(0));
    }

    /** 丢弃上一步残留的帧（如迟到的查询回包），保留到 {@link #received} */
    private void drain() {
        buffered.clear();
        Received r;
        while ((r = link.uplinks().poll()) != null) {
            received.add(r);
        }
    }

    private static long since(long t0, long at) {
        return TimeUnit.NANOSECONDS.toMillis(at - t0);
    }
}
