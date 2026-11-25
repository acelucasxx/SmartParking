package com.smartparking.dtusim;

import com.smartparking.iot.codec.GateStatus;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** 运行时控制台：按行解析命令，作用于一台或全部设备。 */
public final class Console {

    static final String HELP =
            """
            命令（<设备> 可填 IMEI、序号（从 1 开始）或 all）：
              list                              列出设备与状态
              fault <设备> motor|obstructed|infrared|stall   下一次动作注入异常
              repair <设备>                      清除电机故障与未触发的异常
              travel <设备> <毫秒>                设置动作耗时（到位延迟）
              echo <设备> on|off                 抬杆 / 落杆是否回显
              silent <设备> on|off               闸板是否完全不响应
              report <设备> on|off               状态变化时是否主动上报
              car <设备> on|off                  车位有车 / 无车
              trigger <设备> open|close remote|sensor|switch|auto   模拟非平台触发的动作
              drop <设备> <秒>                    模拟断线，到时自动重连
              help                              显示本帮助
              quit                              退出""";

    private final List<DtuDevice> devices;
    private final Consumer<String> out;

    public Console(List<DtuDevice> devices, Consumer<String> out) {
        this.devices = List.copyOf(devices);
        this.out = out;
    }

    /**
     * 执行一行命令。
     *
     * @return false 表示应退出
     */
    public boolean execute(String line) {
        String[] args = line.trim().split("\\s+");
        if (args.length == 0 || args[0].isEmpty()) {
            return true;
        }
        String cmd = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (cmd) {
                case "quit", "exit" -> {
                    return false;
                }
                case "help" -> out.accept(HELP);
                case "list" -> list();
                case "fault" -> each(args, 3, d -> d.board().inject(fault(args[2])));
                case "repair" -> each(args, 2, d -> d.board().repair());
                case "travel" -> {
                    long ms = Long.parseLong(arg(args, 2));
                    each(args, 3, d -> d.board().setTravelMs(ms));
                }
                case "echo" -> each(args, 3, d -> d.board().setEcho(onOff(args[2])));
                case "silent" -> each(args, 3, d -> d.board().setSilent(onOff(args[2])));
                case "report" -> each(args, 3, d -> d.board().setActiveReport(onOff(args[2])));
                case "car" -> each(args, 3, d -> d.board().setOccupied(onOff(args[2])));
                case "trigger" -> {
                    GateStatus target = target(arg(args, 2));
                    int reason = reason(arg(args, 3), target);
                    each(args, 4, d -> d.board().trigger(target, reason));
                }
                case "drop" -> {
                    long seconds = Long.parseLong(arg(args, 2));
                    each(args, 3, d -> {
                        try {
                            d.dropFor(seconds);
                        } catch (Exception e) {
                            throw new IllegalStateException(e.getMessage(), e);
                        }
                    });
                }
                default -> out.accept("未知命令：" + cmd + "，输入 help 查看用法");
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            out.accept("错误：" + e.getMessage());
        }
        return true;
    }

    private void list() {
        for (int i = 0; i < devices.size(); i++) {
            DtuDevice d = devices.get(i);
            out.accept(String.format(
                    "%3d  %s  %s  %s",
                    i + 1, d.imei(), d.isConnected() ? "在线" : "断开", d.board().describe()));
        }
    }

    private void each(String[] args, int expected, Consumer<DtuDevice> action) {
        if (args.length < expected) {
            throw new IllegalArgumentException("参数不足，输入 help 查看用法");
        }
        List<DtuDevice> targets = select(args[1]);
        targets.forEach(action);
        out.accept("已作用于 " + targets.size() + " 台设备");
    }

    List<DtuDevice> select(String which) {
        if ("all".equalsIgnoreCase(which)) {
            return devices;
        }
        if (which.length() <= 4 && which.chars().allMatch(Character::isDigit)) {
            int index = Integer.parseInt(which);
            if (index < 1 || index > devices.size()) {
                throw new IllegalArgumentException("序号超出范围：" + which);
            }
            return List.of(devices.get(index - 1));
        }
        return devices.stream()
                .filter(d -> d.imei().equals(which))
                .findFirst()
                .map(List::of)
                .orElseThrow(() -> new IllegalArgumentException("没有这台设备：" + which));
    }

    private static String arg(String[] args, int i) {
        if (args.length <= i) {
            throw new IllegalArgumentException("参数不足，输入 help 查看用法");
        }
        return args[i];
    }

    private static Fault fault(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "motor" -> Fault.MOTOR_FAULT;
            case "obstructed" -> Fault.OBSTRUCTED;
            case "infrared" -> Fault.INFRARED;
            case "stall" -> Fault.STALL;
            default -> throw new IllegalArgumentException("未知异常：" + name);
        };
    }

    private static boolean onOff(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new IllegalArgumentException("只能是 on 或 off：" + value);
        };
    }

    private static GateStatus target(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "open" -> GateStatus.OPENED;
            case "close" -> GateStatus.CLOSED;
            default -> throw new IllegalArgumentException("只能是 open 或 close：" + value);
        };
    }

    /** 协议用奇偶两个码区分同一来源的开 / 关（PRD 8.3） */
    static int reason(String source, GateStatus target) {
        boolean open = target == GateStatus.OPENED;
        return switch (source.toLowerCase(Locale.ROOT)) {
            case "remote" -> open ? 0x01 : 0x02;
            case "sensor" -> open ? 0x03 : 0x04;
            case "switch" -> open ? 0x06 : 0x07;
            case "auto" -> 0x10;
            default -> throw new IllegalArgumentException("未知触发来源：" + source);
        };
    }
}
