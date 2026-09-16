package org.kinotic.structures.tests.core.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.kinotic.structures.api.config.StructuresProperties;
import org.kinotic.structures.api.domain.RawJson;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.domain.idl.decorators.MultiTenancyType;
import org.kinotic.structures.internal.api.domain.DefaultEntityContext;
import org.kinotic.structures.api.domain.idl.decorators.IdDecorator;
import org.kinotic.structures.internal.api.hooks.DecoratorLogic;
import org.kinotic.structures.internal.api.hooks.UpsertFieldPreProcessor;
import org.kinotic.structures.internal.api.hooks.impl.IdUpsertFieldPreProcessor;
import org.kinotic.structures.internal.api.hooks.impl.RawJsonUpsertPreProcessor;
import org.kinotic.structures.internal.api.hooks.impl.TokenBufferUpsertPreProcessor;
import org.kinotic.structures.internal.api.services.EntityHolder;
import org.kinotic.structures.internal.sample.DummyParticipant;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.springframework.beans.factory.annotation.Autowired;

import com.sun.management.ThreadMXBean;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.util.TokenBuffer;

/**
 * Measures the JSON payload paths Structures runs on, so the cost of the serialization layer is a
 * number rather than a belief. Opt in with {@code STRUCTURES_BENCHMARK=true}; it is skipped otherwise,
 * because it takes a while and its output is a report, not a pass/fail.
 * <pre>
 *   STRUCTURES_BENCHMARK=true ./gradlew :structures-test:test --tests '*JsonPathBenchmark*'
 * </pre>
 * The report goes to stdout and to {@code build/reports/json-path-benchmark.md}.
 * <p>
 * Three columns per path and payload size:
 * <ul>
 *   <li><b>Jackson 2 direct</b> - what Structures did before the 3.6.0 upgrade: the payload types read
 *       and written by a Jackson 2 mapper. Re-measured here rather than quoted, so the comparison is on
 *       one machine. The 3.6.0 bridge (Jackson 3 tree, to string, to Jackson 2) sat on top of this at
 *       3.4-4.5x; it is gone and cannot be measured any more.</li>
 *   <li><b>Jackson 3</b> - the same payload types through the one configured mapper the application
 *       now has.</li>
 *   <li><b>ingest</b> - the whole pre-processor path an entity takes on save: parameter type in,
 *       {@link RawJson} out, on Jackson 3.</li>
 * </ul>
 * Allocation is per operation on the measuring thread ({@link ThreadMXBean#getThreadAllocatedBytes}),
 * time is the median of the measured iterations. Warmed first so the JIT is not in the numbers.
 * <p>
 * Every path is also checked to produce the same JSON as the input, so a change that made one of them
 * fast by making it wrong would fail here rather than look like an improvement.
 */
class JsonPathBenchmark extends ElasticTestBase {

    private static final int WARMUP = 20;
    private static final int ITERATIONS = 100;
    /** Target payload sizes, matching the figures the bridge was measured against */
    private static final int[] TARGET_BYTES = {16 * 1024, 163 * 1024, 830 * 1024};

    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private StructuresProperties structuresProperties;

    @Test
    void measureJsonPaths() throws Exception {
        assumeTrue("true".equals(System.getenv("STRUCTURES_BENCHMARK")),
                   "Set STRUCTURES_BENCHMARK=true to run the JSON path benchmark");

        com.fasterxml.jackson.databind.json.JsonMapper jackson2 = jackson2MapperAsItWas();
        Structure structure = new Structure();
        structure.setMultiTenancyType(MultiTenancyType.NONE);
        // The one decorator every entity has: the id, wired the way the entity service wires it
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<String, DecoratorLogic> fieldLogic = Map.of("id", new DecoratorLogic(new IdDecorator(), (UpsertFieldPreProcessor) new IdUpsertFieldPreProcessor()));
        TokenBufferUpsertPreProcessor bufferIngest = new TokenBufferUpsertPreProcessor(structuresProperties, jsonMapper, structure, fieldLogic);
        RawJsonUpsertPreProcessor rawIngest = new RawJsonUpsertPreProcessor(structuresProperties, jsonMapper, structure, fieldLogic);
        DefaultEntityContext context = new DefaultEntityContext(new DummyParticipant("benchmark", "benchmark"));

        StringBuilder report = new StringBuilder();
        report.append("| payload | path | Jackson 2 direct | Jackson 3 | ingest (Jackson 3) |\n");
        report.append("|---------|------|------------------|-----------|--------------------|\n");

        for (int target : TARGET_BYTES) {
            byte[] payload = entityArray(target);
            String size = (payload.length / 1024) + " KB";
            tools.jackson.databind.JsonNode expected = jsonMapper.readTree(payload);

            // The payload types as the application holds them, one per mapper, built outside the timing
            com.fasterxml.jackson.databind.util.TokenBuffer buffer2 = jackson2.readValue(payload, com.fasterxml.jackson.databind.util.TokenBuffer.class);
            TokenBuffer buffer3 = jsonMapper.readValue(payload, TokenBuffer.class);
            RawJson raw = new RawJson(payload);

            // read: bytes -> payload type
            Result read2Buffer = measure(() -> jackson2.readValue(payload, com.fasterxml.jackson.databind.util.TokenBuffer.class));
            Result read3Buffer = measure(() -> jsonMapper.readValue(payload, TokenBuffer.class));
            Result read2Raw = measure(() -> jackson2.readValue(payload, RawJson.class));
            Result read3Raw = measure(() -> jsonMapper.readValue(payload, RawJson.class));
            // write: payload type -> bytes
            Result write2Buffer = measure(() -> jackson2.writeValueAsBytes(buffer2));
            Result write3Buffer = measure(() -> jsonMapper.writeValueAsBytes(buffer3));
            Result write2Raw = measure(() -> jackson2.writeValueAsBytes(raw));
            Result write3Raw = measure(() -> jsonMapper.writeValueAsBytes(raw));
            // ingest: what a save does with the parameter, on the current code
            Result ingestBuffer = measure(() -> bufferIngest.processArray(buffer3, context).join());
            Result ingestRaw = measure(() -> rawIngest.processArray(raw, context).join());

            // Each path must still say the same thing as the input
            assertEquals(expected, jsonMapper.readTree(jsonMapper.writeValueAsBytes(jsonMapper.readValue(payload, TokenBuffer.class))), "Jackson 3 TokenBuffer round trip");
            assertEquals(expected, jsonMapper.readTree(jsonMapper.writeValueAsBytes(jsonMapper.readValue(payload, RawJson.class))), "Jackson 3 RawJson round trip");
            assertEquals(expected, jsonMapper.readTree(jackson2.writeValueAsBytes(jackson2.readValue(payload, com.fasterxml.jackson.databind.util.TokenBuffer.class))), "Jackson 2 TokenBuffer round trip");
            assertEquals(expected, jsonMapper.readTree(jackson2.writeValueAsBytes(jackson2.readValue(payload, RawJson.class))), "Jackson 2 RawJson round trip");
            assertEquals(expected, rejoin(bufferIngest.processArray(buffer3, context).join()), "ingest from TokenBuffer");
            assertEquals(expected, rejoin(rawIngest.processArray(raw, context).join()), "ingest from RawJson");

            report.append(row(size, "read TokenBuffer", read2Buffer, read3Buffer, ingestBuffer));
            report.append(row(size, "read RawJson", read2Raw, read3Raw, ingestRaw));
            report.append(row(size, "write TokenBuffer", write2Buffer, write3Buffer, null));
            report.append(row(size, "write RawJson", write2Raw, write3Raw, null));
        }

        String text = "# JSON path benchmark\n\n"
                + "Median time / mean allocation per operation, " + ITERATIONS + " iterations after " + WARMUP + " warm-up, "
                + "on " + System.getProperty("os.name") + " " + System.getProperty("os.arch") + ", Java " + Runtime.version() + ".\n\n"
                + "`ingest` is the upsert pre-processor path a save takes: parameter type in, RawJson out; it is listed on the read rows "
                + "because that is the type it starts from.\n\n"
                + report;
        System.out.println("\n" + text);
        Path out = Path.of("build", "reports", "json-path-benchmark.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text);
    }

    /** The mapper Structures ran on before the upgrade: Jackson 2 with RawJson handled the way it was then */
    private static com.fasterxml.jackson.databind.json.JsonMapper jackson2MapperAsItWas() {
        com.fasterxml.jackson.databind.module.SimpleModule module = new com.fasterxml.jackson.databind.module.SimpleModule("StructuresJackson2Baseline");
        module.addSerializer(RawJson.class, new com.fasterxml.jackson.databind.JsonSerializer<>() {
            @Override
            public void serialize(RawJson value, com.fasterxml.jackson.core.JsonGenerator gen, com.fasterxml.jackson.databind.SerializerProvider p) throws java.io.IOException {
                gen.writeRawValue(new String(value.data(), StandardCharsets.UTF_8));
            }
        });
        module.addDeserializer(RawJson.class, new com.fasterxml.jackson.databind.JsonDeserializer<>() {
            @Override
            public RawJson deserialize(com.fasterxml.jackson.core.JsonParser parser, com.fasterxml.jackson.databind.DeserializationContext ctxt) throws java.io.IOException {
                // What RawJson.from(JsonParser, ObjectMapper) did on Jackson 2: copy the structure straight to bytes
                try (com.fasterxml.jackson.core.util.ByteArrayBuilder builder = new com.fasterxml.jackson.core.util.ByteArrayBuilder();
                     com.fasterxml.jackson.core.JsonGenerator generator = ((com.fasterxml.jackson.databind.ObjectMapper) parser.getCodec())
                             .getFactory().createGenerator(builder, com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
                    generator.copyCurrentStructure(parser);
                    generator.flush();
                    return new RawJson(builder.toByteArray());
                }
            }
        });
        return com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .addModule(module)
                .build();
    }

    /** A JSON array of entity documents, as a bulk save would send, at about the target size */
    private byte[] entityArray(int targetBytes) {
        byte[] one = jsonMapper.writeValueAsBytes(entity(0));
        int count = Math.max(1, targetBytes / (one.length + 1));
        List<Map<String, Object>> entities = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entities.add(entity(i));
        }
        return jsonMapper.writeValueAsBytes(entities);
    }

    private static Map<String, Object> entity(int i) {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("street", (100 + i) + " Benchmark Way");
        address.put("city", "Springfield");
        address.put("state", "OR");
        address.put("zip", String.format("%05d", 97000 + (i % 1000)));
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", "person-" + i);
        entity.put("firstName", "First" + i);
        entity.put("lastName", "Last" + (i * 7 % 1000));
        entity.put("age", 20 + (i % 60));
        entity.put("email", "person" + i + "@example.com");
        entity.put("active", i % 3 != 0);
        entity.put("balance", i * 12.5);
        entity.put("address", address);
        entity.put("tags", Arrays.asList("alpha", "beta", "gamma", "tag-" + (i % 17)));
        entity.put("notes", "A short free-text field with a little variety in it, number " + i);
        return entity;
    }

    private tools.jackson.databind.JsonNode rejoin(List<EntityHolder<RawJson>> holders) {
        tools.jackson.databind.node.ArrayNode array = jsonMapper.createArrayNode();
        for (EntityHolder<RawJson> holder : holders) {
            array.add(jsonMapper.readTree(holder.entity().data()));
        }
        return array;
    }

    private record Result(long medianNanos, long meanAllocatedBytes) {}

    /** The operation under measurement; the Jackson 2 calls declare checked exceptions */
    @FunctionalInterface
    private interface Operation {
        Object run() throws Exception;
    }

    private static Result measure(Operation operation) throws Exception {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        for (int i = 0; i < WARMUP; i++) {
            operation.run();
        }
        long[] times = new long[ITERATIONS];
        long allocated = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long bytesBefore = threads.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            operation.run();
            times[i] = System.nanoTime() - start;
            allocated += threads.getThreadAllocatedBytes(thread) - bytesBefore;
        }
        Arrays.sort(times);
        return new Result(times[ITERATIONS / 2], allocated / ITERATIONS);
    }

    private static String row(String size, String path, Result jackson2, Result jackson3, Result ingest) {
        return "| " + size + " | " + path + " | " + cell(jackson2) + " | " + cell(jackson3) + " | " + (ingest == null ? "-" : cell(ingest)) + " |\n";
    }

    private static String cell(Result r) {
        return time(r.medianNanos) + " / " + bytes(r.meanAllocatedBytes);
    }

    private static String time(long nanos) {
        return nanos >= 1_000_000 ? String.format("%.2f ms", nanos / 1_000_000.0) : String.format("%d µs", nanos / 1_000);
    }

    private static String bytes(long b) {
        return b >= 1024 * 1024 ? String.format("%.1f MB", b / (1024.0 * 1024)) : String.format("%d KB", b / 1024);
    }
}
