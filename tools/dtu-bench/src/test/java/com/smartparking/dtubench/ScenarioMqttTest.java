package com.smartparking.dtubench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.smartparking.dtubench.PayloadAnalyzer.Kind;
import com.smartparking.dtubench.Scenario.Outcome;
import com.smartparking.dtubench.Scenario.Step;
import com.smartparking.dtusim.DtuDevice;
import com.smartparking.dtusim.ExecutorScheduler;
import com.smartparking.dtusim.Fault;
import com.smartparking.dtusim.SimulatorConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 用 DTU 模拟器冒充真机，经真实 EMQX 跑完整剧本，验证工具本身的计时、判定与报告。 */
@Testcontainers
class ScenarioMqttTest {

    private static final long TRAVEL_MS = 400;
    private static final String IMEI = "860000000000009";

    @Container
    static final GenericContainer<?> EMQX = new GenericContainer<>("emqx/emqx:5.8.6")
            .withExposedPorts(1883)
            .waitingFor(Wait.forLogMessage(".*is running now.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    private ExecutorScheduler scheduler;
    private DtuDevice device;
    private CloudLink link;
    private final List<String> log = new ArrayList<>();

    static String broker() {
        return "tcp://" + EMQX.getHost() + ":" + EMQX.getMappedPort(1883);
    }

    @BeforeEach
    void setUp() throws Exception {
        scheduler = new ExecutorScheduler(2);
        device = new DtuDevice(
                IMEI,
                new SimulatorConfig(broker(), List.of(IMEI), TRAVEL_MS, true, false, true, 10, 500, null),
                scheduler);
        device.connect();
        link = new CloudLink(broker(), null, null, IMEI, Duration.ofSeconds(15));
        await().atMost(Duration.ofSeconds(5)).until(device::isConnected);
    }

    @AfterEach
    void tearDown() throws Exception {
        link.close();
        device.close();
        scheduler.close();
    }

    private static Scenario.Options fast(int rounds) {
        return new Scenario.Options(
                rounds, Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(50));
    }

    @Test
    void fullRoundsSucceedWithPlausibleLatencies(@TempDir Path dir) throws Exception {
        var scenario = new Scenario(link, fast(2), log::add);
        var samples = scenario.run();

        assertThat(samples).hasSize(8).allMatch(s -> s.outcome() == Outcome.OK);
        assertThat(samples).filteredOn(s -> s.step() == Step.VERSION).allMatch(s -> s.detail()
                .equals("版本 16"));
        assertThat(samples)
                .filteredOn(s -> s.step() == Step.OPEN || s.step() == Step.CLOSE)
                .allSatisfy(s -> {
                    assertThat(s.ackMs()).as("回显").isNotNull().isLessThan(TRAVEL_MS);
                    assertThat(s.doneMs()).as("到位不早于动作耗时").isGreaterThanOrEqualTo(TRAVEL_MS);
                });
        assertThat(scenario.received()).isNotEmpty().allMatch(r -> r.analysis().kind() == Kind.FRAME);

        String md = Report.markdown(IMEI, java.time.Instant.now(), samples, scenario.received());
        assertThat(md).contains("| 抬杆 | 2 | 2 | 0 | 0 |").contains("| 恰好一帧 |");
        Report.write(dir, md, samples, scenario.received(), System.nanoTime());
        assertThat(Files.readAllLines(dir.resolve("samples.csv"))).hasSize(9);
        assertThat(Files.readString(dir.resolve("uplinks.csv"))).contains("FRAME");
    }

    @Test
    void motorFaultIsReportedAsFailure() throws Exception {
        device.board().inject(Fault.MOTOR_FAULT);
        var samples = new Scenario(link, fast(1), log::add).run();

        assertThat(samples)
                .filteredOn(s -> s.step() == Step.OPEN)
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.outcome()).isEqualTo(Outcome.FAILED);
                    assertThat(s.detail()).contains("MOTOR_FAULT");
                });
    }

    @Test
    void stalledMotionTimesOut() throws Exception {
        device.board().inject(Fault.STALL);
        var samples = new Scenario(link, fast(1), log::add).run();

        assertThat(samples)
                .filteredOn(s -> s.step() == Step.OPEN)
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.outcome()).isEqualTo(Outcome.TIMEOUT);
                    assertThat(s.ackMs()).as("卡住前仍有回显").isNotNull();
                });
    }

    @Test
    void silentBoardTimesOutEveryStep() throws Exception {
        device.board().setSilent(true);
        var samples = new Scenario(link, fast(1), log::add).run();

        assertThat(samples).hasSize(4).allMatch(s -> s.outcome() == Outcome.TIMEOUT);
    }
}
