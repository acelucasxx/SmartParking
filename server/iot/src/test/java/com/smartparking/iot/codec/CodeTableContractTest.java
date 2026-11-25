package com.smartparking.iot.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** 码表枚举名直接用于接口输出，必须与接口契约 docs/api/common.yaml 一致。 */
class CodeTableContractTest {

    private static final Path API_DIR = Path.of(System.getProperty("smartparking.apiDocsDir", "../../docs/api"));

    @Test
    void gateStatusMatchesContract() throws IOException {
        assertThat(names(GateStatus.values())).containsExactlyInAnyOrderElementsOf(contractEnum("GateStatus"));
    }

    @Test
    void triggerSourceMatchesContract() throws IOException {
        assertThat(names(TriggerSource.values())).containsExactlyInAnyOrderElementsOf(contractEnum("TriggerSource"));
    }

    @Test
    void gateFaultIsSubsetOfCommandResultCode() throws IOException {
        assertThat(contractEnum("CommandResultCode")).containsAll(names(GateFault.values()));
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> contractEnum(String schema) throws IOException {
        try (var in = Files.newInputStream(API_DIR.resolve("common.yaml"))) {
            Map<String, Object> doc = new Yaml().load(in);
            var components = (Map<String, Object>) doc.get("components");
            var schemas = (Map<String, Object>) components.get("schemas");
            var def = (Map<String, Object>) schemas.get(schema);
            return (List<String>) def.get("enum");
        }
    }
}
