package com.smartparking.dtusim;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.iot.codec.GateStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsoleTest {

    private final List<String> out = new ArrayList<>();
    private final ManualScheduler clock = new ManualScheduler();
    private List<DtuDevice> devices;
    private Console console;

    @BeforeEach
    void setUp() throws Exception {
        // 不连接 broker，只验证命令解析与作用对象
        var config = SimulatorConfig.defaults("tcp://127.0.0.1:1", Imeis.sequence("86000000000000", 3));
        devices = new ArrayList<>();
        for (String imei : config.imeis()) {
            devices.add(new DtuDevice(imei, config, clock));
        }
        console = new Console(devices, out::add);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (DtuDevice d : devices) {
            d.close();
        }
    }

    @Test
    void selectsByIndexImeiOrAll() {
        assertThat(console.select("2")).containsExactly(devices.get(1));
        assertThat(console.select(devices.get(2).imei())).containsExactly(devices.get(2));
        assertThat(console.select("all")).hasSize(3);
    }

    @Test
    void faultAppliesToSelectedDevice() {
        console.execute("fault 1 stall");
        assertThat(devices.get(0).board().describe()).contains("待触发 STALL");
        assertThat(devices.get(1).board().describe()).doesNotContain("待触发");
    }

    @Test
    void settingsApplyToAll() {
        console.execute("travel all 300");
        console.execute("echo all off");
        assertThat(devices).allSatisfy(d -> assertThat(d.board().describe()).contains("耗时 300ms", "无回显"));
    }

    @Test
    void triggerUsesSourceReasonCodes() {
        console.execute("trigger 3 open remote");
        clock.advance(1200);
        assertThat(devices.get(2).board().status()).isEqualTo(GateStatus.OPENED);
        assertThat(Console.reason("sensor", GateStatus.CLOSED)).isEqualTo(0x04);
        assertThat(Console.reason("switch", GateStatus.OPENED)).isEqualTo(0x06);
    }

    @Test
    void reportsErrorsWithoutThrowing() {
        assertThat(console.execute("fault 9 motor")).isTrue();
        assertThat(console.execute("fault 1 meltdown")).isTrue();
        assertThat(console.execute("travel 1")).isTrue();
        assertThat(console.execute("bogus")).isTrue();
        assertThat(out).hasSize(4).allMatch(line -> line.startsWith("错误") || line.startsWith("未知命令"));
    }

    @Test
    void listAndQuit() {
        console.execute("list");
        assertThat(out).hasSize(3).allMatch(line -> line.contains("断开"));
        assertThat(console.execute("quit")).isFalse();
    }
}
