package com.smartparking.dtusim;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eclipse.paho.client.mqttv3.MqttException;

/**
 * DTU 模拟器入口：按参数启动多台设备，然后从标准输入读取控制台命令。
 *
 * <pre>
 * ./gradlew :dtu-sim:run --args="--count 3"
 * </pre>
 */
public final class DtuSimulator implements AutoCloseable {

    static final String USAGE =
            """
            用法：dtu-sim [选项]
              --broker <url>          MQTT 地址，默认 tcp://127.0.0.1:1883，也可用环境变量 BROKER
              --count <n>             设备数量，默认 1；IMEI 由 --imei-prefix 起顺序生成并带 Luhn 校验位
              --imei-prefix <14位>     默认 86000000000000
              --imeis <a,b,...>       直接指定 IMEI，优先于 --count
              --travel-ms <ms>        动作耗时，默认 1200
              --no-echo               抬杆 / 落杆不回显
              --active-report         状态变化时主动上报
              --clean-session         使用干净会话（默认持久会话）
              --keep-alive <s>        MQTT 心跳，默认 60
              --secret-key <key>      一机一密平台密钥，默认与本地开发环境一致，也可用环境变量 DEVICE_SECRET_KEY
              --anonymous             不带用户名密码连接（broker 未开启认证时）""";

    /** 与 server 的 application-local.yml 一致，只用于本机开发 */
    static final String LOCAL_SECRET_KEY = "device_secret_key_dev_only_0123456789";

    private final ExecutorScheduler scheduler;
    private final List<DtuDevice> devices;

    public DtuSimulator(SimulatorConfig config) throws MqttException {
        this.scheduler =
                new ExecutorScheduler(Math.max(2, Math.min(16, config.imeis().size())));
        var list = new ArrayList<DtuDevice>();
        for (String imei : config.imeis()) {
            list.add(new DtuDevice(imei, config, scheduler));
        }
        this.devices = List.copyOf(list);
    }

    public List<DtuDevice> devices() {
        return devices;
    }

    public void start() {
        devices.forEach(DtuDevice::start);
    }

    @Override
    public void close() {
        for (DtuDevice device : devices) {
            try {
                device.close();
            } catch (MqttException e) {
                // 退出阶段的断开失败不影响其余设备
            }
        }
        scheduler.close();
    }

    public static void main(String[] args) throws Exception {
        SimulatorConfig config;
        try {
            config = parse(args, System.getenv("BROKER"), System.getenv("DEVICE_SECRET_KEY"));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        try (var simulator = new DtuSimulator(config)) {
            System.out.printf(
                    "启动 %d 台设备，broker %s，动作耗时 %d ms%n", config.imeis().size(), config.broker(), config.travelMs());
            simulator.start();
            var console = new Console(simulator.devices(), System.out::println);
            System.out.println("输入 help 查看命令");
            var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null && console.execute(line)) {
                // 逐行执行，直到 quit 或输入结束
            }
        }
    }

    static SimulatorConfig parse(String[] args, String brokerEnv) {
        return parse(args, brokerEnv, null);
    }

    static SimulatorConfig parse(String[] args, String brokerEnv, String secretKeyEnv) {
        String broker = brokerEnv == null || brokerEnv.isBlank() ? "tcp://127.0.0.1:1883" : brokerEnv;
        int count = 1;
        String prefix = "86000000000000";
        List<String> imeis = null;
        long travelMs = 1200;
        boolean echo = true;
        boolean activeReport = false;
        boolean cleanSession = false;
        int keepAlive = 60;
        String secretKey = secretKeyEnv == null || secretKeyEnv.isBlank() ? LOCAL_SECRET_KEY : secretKeyEnv;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--broker" -> broker = value(args, ++i);
                case "--count" -> count = Integer.parseInt(value(args, ++i));
                case "--imei-prefix" -> prefix = value(args, ++i);
                case "--imeis" -> imeis = Arrays.asList(value(args, ++i).split(","));
                case "--travel-ms" -> travelMs = Long.parseLong(value(args, ++i));
                case "--no-echo" -> echo = false;
                case "--active-report" -> activeReport = true;
                case "--clean-session" -> cleanSession = true;
                case "--keep-alive" -> keepAlive = Integer.parseInt(value(args, ++i));
                case "--secret-key" -> secretKey = value(args, ++i);
                case "--anonymous" -> secretKey = null;
                case "--help", "-h" -> throw new IllegalArgumentException("");
                default -> throw new IllegalArgumentException("未知参数：" + args[i]);
            }
        }
        if (imeis == null) {
            if (count < 1 || count > 1000) {
                throw new IllegalArgumentException("--count 应在 1–1000 之间：" + count);
            }
            imeis = Imeis.sequence(prefix, count);
        }
        for (String imei : imeis) {
            if (!imei.matches("\\d{15}")) {
                throw new IllegalArgumentException("IMEI 必须是 15 位数字：" + imei);
            }
        }
        return new SimulatorConfig(
                broker, imeis, travelMs, echo, activeReport, cleanSession, keepAlive, 5000, secretKey);
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("参数 " + args[i - 1] + " 缺少取值");
        }
        return args[i];
    }
}
