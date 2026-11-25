package com.smartparking.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/**
 * 错误码以接口契约 docs/api/common.yaml 为准，这里校验三处一致：契约、本枚举、docs/api/README.md 的错误码表；
 * 并校验各接口 x-error-codes 引用的错误码都已定义，且声明了对应的 4XX / 5XX 响应。
 */
class ErrorCodeContractTest {

    private static final Path API_DIR = Path.of(System.getProperty("smartparking.apiDocsDir", "../../docs/api"));
    private static final Pattern README_ROW =
            Pattern.compile("^\\| (\\d+) \\| `([A-Z_]+)` \\| (\\d{3}) \\| (.+?) \\|$", Pattern.MULTILINE);

    /** 一行错误码：编码、名称、HTTP 状态、默认提示。 */
    private record Row(int code, String name, int status, String message) {}

    @Test
    void enumMatchesContract() throws IOException {
        var fromEnum = Arrays.stream(ErrorCode.values())
                .map(e -> new Row(e.code(), e.name(), e.status().value(), e.defaultMessage()))
                .toList();
        assertThat(fromEnum).containsExactlyElementsOf(contractRows());
    }

    @Test
    void readmeTableMatchesContract() throws IOException {
        var readme = Files.readString(API_DIR.resolve("README.md"));
        var rows = new ArrayList<Row>();
        var m = README_ROW.matcher(readme);
        while (m.find()) {
            rows.add(new Row(Integer.parseInt(m.group(1)), m.group(2), Integer.parseInt(m.group(3)), m.group(4)));
        }
        assertThat(rows).containsExactlyElementsOf(contractRows());
    }

    @Test
    void codesAreUnique() {
        var codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList();
        assertThat(codes).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @ValueSource(strings = {"openapi-app.yaml", "openapi-admin.yaml"})
    void operationsReferenceDefinedCodes(String file) throws IOException {
        var statusByCode = Arrays.stream(ErrorCode.values())
                .collect(Collectors.toMap(ErrorCode::code, e -> e.status().value()));
        Map<String, Object> doc = load(file);
        Map<String, Map<String, Object>> paths = cast(doc.get("paths"));
        var problems = new ArrayList<String>();

        paths.forEach((path, item) -> item.forEach((method, value) -> {
            if (!(value instanceof Map<?, ?>)) {
                return; // path 级 parameters
            }
            Map<String, Object> op = cast(value);
            Map<String, Object> responses = cast(op.get("responses"));
            List<Integer> codes = cast(op.getOrDefault("x-error-codes", List.of()));
            for (int code : codes) {
                var status = statusByCode.get(code);
                if (status == null) {
                    problems.add(method + " " + path + ": 未定义的错误码 " + code);
                } else if (!responses.containsKey(status / 100 + "XX")
                        && !responses.containsKey(String.valueOf(status))) {
                    problems.add(method + " " + path + ": 错误码 " + code + " 对应 HTTP " + status + "，但未声明该响应");
                }
            }
        }));
        assertThat(problems).isEmpty();
    }

    private static List<Row> contractRows() throws IOException {
        Map<String, Object> common = load("common.yaml");
        Map<String, Object> components = cast(common.get("components"));
        Map<String, Object> schemas = cast(components.get("schemas"));
        Map<String, Object> schema = cast(schemas.get("ErrorCode"));
        List<Integer> codes = cast(schema.get("enum"));
        List<String> names = cast(schema.get("x-enum-varnames"));
        List<Integer> statuses = cast(schema.get("x-enum-http-status"));
        List<String> messages = cast(schema.get("x-enum-descriptions"));
        assertThat(names).hasSameSizeAs(codes);
        assertThat(statuses).hasSameSizeAs(codes);
        assertThat(messages).hasSameSizeAs(codes);

        var rows = new ArrayList<Row>();
        for (int i = 0; i < codes.size(); i++) {
            rows.add(new Row(codes.get(i), names.get(i), statuses.get(i), messages.get(i)));
        }
        return rows;
    }

    private static Map<String, Object> load(String file) throws IOException {
        try (var in = Files.newInputStream(API_DIR.resolve(file))) {
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
