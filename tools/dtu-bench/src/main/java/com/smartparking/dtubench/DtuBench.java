package com.smartparking.dtubench;

import com.smartparking.iot.Imei;
import com.smartparking.iot.auth.DeviceCredentials;
import com.smartparking.iot.codec.Hex;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 真机联调工具（IOT-11），站在云端一侧。三个子命令：
 *
 * <pre>
 * password --imei X              打印该 DTU 的 MQTT 密码，用于给 DTU 配参数
 * run      --imei X --rounds 10  跑查询 / 查版本 / 抬杆 / 落杆剧本，输出时延与上行归类
 * listen   --imei X --minutes 10 只收不发，记录全部上行（观察心跳 / 注册包、遥控器动作后是否主动上报）
 * </pre>
 *
 * 详见 docs/iot/bench.md。
 */
public final class DtuBench {

    static final String USAGE =
            """
            用法：dtu-bench <password|run|listen> --imei <15位> [选项]
              --broker <url>        MQTT 地址，默认 tcp://127.0.0.1:1883，也可用环境变量 BROKER
              --username <name>     云端账号，默认 smartparking-server
              --password <pwd>      云端账号密码，默认读环境变量 MQTT_SERVER_PASSWORD，再默认本地开发值
              --anonymous           不带用户名密码连接（broker 未开启认证时）
              --secret-key <key>    password 子命令用：一机一密平台密钥，默认读 DEVICE_SECRET_KEY，再默认本地开发值
              --rounds <n>          run：轮数，默认 10
              --timeout-s <s>       run：抬杆 / 落杆超时，默认 15
              --poll-ms <ms>        run：动作中查询间隔，默认 300
              --pause-ms <ms>       run：轮间隔，默认 1000
              --minutes <n>         listen：监听时长，默认 10
              --out <dir>           结果目录，默认 bench-<imei>-<时间>""";

    static final String LOCAL_SERVER_PASSWORD = "mqtt_server_dev_only";
    static final String LOCAL_SECRET_KEY = "device_secret_key_dev_only_0123456789";

    /** 解析后的命令行 */
    record Args(
            String command,
            String imei,
            String broker,
            String username,
            String password,
            String secretKey,
            int rounds,
            Duration timeout,
            Duration poll,
            Duration pause,
            int minutes,
            Path out) {}

    public static void main(String[] argv) throws Exception {
        Args args;
        try {
            args = parse(argv, System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        switch (args.command()) {
            case "password" -> System.out.println(new DeviceCredentials(args.secretKey()).passwordFor(args.imei()));
            case "run" -> run(args);
            case "listen" -> listen(args);
            default -> throw new IllegalStateException(args.command());
        }
    }

    static Args parse(String[] argv, Map<String, String> env) {
        if (argv.length == 0 || argv[0].startsWith("-")) {
            throw new IllegalArgumentException("缺少子命令");
        }
        String command = argv[0];
        if (!List.of("password", "run", "listen").contains(command)) {
            throw new IllegalArgumentException("未知子命令：" + command);
        }
        Map<String, String> opts = new HashMap<>();
        List<String> flags = new ArrayList<>();
        for (int i = 1; i < argv.length; i++) {
            String a = argv[i];
            if (a.equals("--anonymous")) {
                flags.add(a);
            } else if (a.startsWith("--")) {
                if (i + 1 >= argv.length) {
                    throw new IllegalArgumentException("参数 " + a + " 缺少取值");
                }
                opts.put(a.substring(2), argv[++i]);
            } else {
                throw new IllegalArgumentException("未知参数：" + a);
            }
        }
        for (String key : opts.keySet()) {
            if (!List.of(
                            "imei",
                            "broker",
                            "username",
                            "password",
                            "secret-key",
                            "rounds",
                            "timeout-s",
                            "poll-ms",
                            "pause-ms",
                            "minutes",
                            "out")
                    .contains(key)) {
                throw new IllegalArgumentException("未知参数：--" + key);
            }
        }
        String imei = opts.get("imei");
        if (!Imei.isWellFormed(imei)) {
            throw new IllegalArgumentException("--imei 必须是 15 位数字：" + imei);
        }
        boolean anonymous = flags.contains("--anonymous");
        String password =
                opts.getOrDefault("password", env.getOrDefault("MQTT_SERVER_PASSWORD", LOCAL_SERVER_PASSWORD));
        int rounds = positive(opts.getOrDefault("rounds", "10"), "--rounds");
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.of("Asia/Shanghai"))
                .format(Instant.now());
        return new Args(
                command,
                imei,
                opts.getOrDefault("broker", env.getOrDefault("BROKER", "tcp://127.0.0.1:1883")),
                anonymous ? null : opts.getOrDefault("username", "smartparking-server"),
                anonymous ? null : password,
                opts.getOrDefault("secret-key", env.getOrDefault("DEVICE_SECRET_KEY", LOCAL_SECRET_KEY)),
                rounds,
                Duration.ofSeconds(positive(opts.getOrDefault("timeout-s", "15"), "--timeout-s")),
                Duration.ofMillis(positive(opts.getOrDefault("poll-ms", "300"), "--poll-ms")),
                Duration.ofMillis(Integer.parseInt(opts.getOrDefault("pause-ms", "1000"))),
                positive(opts.getOrDefault("minutes", "10"), "--minutes"),
                Path.of(opts.getOrDefault("out", "bench-" + imei + "-" + stamp)));
    }

    private static int positive(String value, String name) {
        int n;
        try {
            n = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 应为正整数：" + value);
        }
        if (n <= 0) {
            throw new IllegalArgumentException(name + " 应为正整数：" + value);
        }
        return n;
    }

    private static CloudLink connect(Args args) throws Exception {
        System.out.printf(
                "连接 %s（%s），设备 %s%n", args.broker(), args.username() == null ? "匿名" : args.username(), args.imei());
        // 与业务服务一致的 15 秒过期：设备不在线时不排队
        return new CloudLink(args.broker(), args.username(), args.password(), args.imei(), Duration.ofSeconds(15));
    }

    private static void run(Args args) throws Exception {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        try (CloudLink link = connect(args)) {
            var options = new Scenario.Options(
                    args.rounds(), args.timeout(), Duration.ofSeconds(3), args.poll(), args.pause());
            var scenario = new Scenario(link, options, System.out::println);
            scenario.run();
            // 收尾：再等 1 秒，把迟到的帧也记下
            Thread.sleep(1000);
            List<Received> all = new ArrayList<>(scenario.received());
            link.uplinks().drainTo(all);
            String md = Report.markdown(args.imei(), startedAt, scenario.samples(), all);
            Report.write(args.out(), md, scenario.samples(), all, startNanos);
            System.out.println();
            System.out.println(md);
            System.out.println("明细已写入 " + args.out().toAbsolutePath());
        }
    }

    private static void listen(Args args) throws Exception {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        List<Received> all = new ArrayList<>();
        try (CloudLink link = connect(args)) {
            System.out.printf("只收不发，监听 %d 分钟；Ctrl+C 提前结束时不会写出结果%n", args.minutes());
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(args.minutes());
            while (System.nanoTime() < deadline) {
                Received r = link.uplinks().poll(1, TimeUnit.SECONDS);
                if (r != null) {
                    all.add(r);
                    System.out.printf(
                            "%s ↑ %s  %s %s%n",
                            r.at(),
                            Hex.format(r.payload()),
                            Report.label(r.analysis().kind()),
                            r.analysis().frames().isEmpty()
                                    ? r.analysis().detail()
                                    : r.analysis().frames());
                }
            }
        }
        String md = "## 监听记录 " + args.imei() + " @ " + Report.local(startedAt) + "（" + args.minutes() + " 分钟）\n\n"
                + Report.payloads(all);
        Report.write(args.out(), md, List.of(), all, startNanos);
        System.out.println(md);
        System.out.println("明细已写入 " + args.out().toAbsolutePath());
    }
}
