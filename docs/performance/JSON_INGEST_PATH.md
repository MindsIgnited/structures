# The JSON ingest path and Jackson 3 (3.6.0)

**Status:** done in 3.6.0. Structures is on Jackson 3 end to end; the Jackson 2 bridge introduced
earlier in the 3.6.0 upgrade is gone. The `TokenBuffer` API stays. A further step - dropping
`TokenBuffer` from `JsonEntitiesService` in favour of `RawJson` - is recorded at the end as a
possible 4.0.0, not planned.

## Why

Structures moved to continuum 3.1.0, which serializes RPC with Jackson 3, while Structures itself
was built on Jackson 2 because the Elasticsearch client it used (8.18.1) had no Jackson 3 binding.
The first cut of the 3.6.0 upgrade bridged the two mappers by handing the Jackson 2 shaped payload
types back to the Jackson 2 mapper.

That bridge was cheap in one direction and expensive in the other, measured against the configured
mappers with `ThreadMXBean.getThreadAllocatedBytes`, warmed, 100 iterations:

| payload | direction | direct Jackson 2 | through bridge | cost |
|---------|-----------|------------------|----------------|------|
| 16 KB   | read      | 80 µs / 65 KB    | 272 µs / 241 KB | 3.4x time, 3.7x alloc |
| 163 KB  | read      | 349 µs / 640 KB  | 1.46 ms / 2.8 MB | 4.2x time, 4.4x alloc |
| 830 KB  | read      | 1.87 ms / 3.2 MB | 6.95 ms / 14.4 MB | 3.7x time, 4.5x alloc |
| 830 KB  | write     | 1.49 ms / 3.2 MB | 1.78 ms / 6.5 MB | 1.2x time, 2.0x alloc |

Reading cost three passes and held two intermediates - Jackson 3 parsed to a tree, the tree was
written to a string, Jackson 2 parsed that. Writing was one extra string. The expensive direction
was the one entity ingest uses, because the bridged types are parameters to `save`, `update`,
`bulkSave` and `bulkUpdate`. At tens of thousands of bulk updates a day moving hundreds of GB, 4.5x
allocation on the write path was not a tuning matter.

## What changed

The premise that kept Jackson 2 - no Jackson 3 Elasticsearch client - stopped being true within the
8.19 line: `elasticsearch-java` 8.19.21 ships `Jackson3JsonpMapper`, `Jackson3JsonpParser` and
`Jackson3JsonpGenerator`, so the client can be moved without moving the 8.19 server. That is the
approach kinotic took (on the 9.5 line with a 9.5 server); Structures does the same on 8.19.

With that, there is one mapper in the application: the Jackson 3 `JsonMapper` Spring Boot builds,
which continuum, the Elasticsearch client and Structures all share. Structures contributes a
`JacksonModule` (`StructuresJacksonConfig`) with its IDL subtypes and the serializers for `RawJson`,
`FieldValue` and `FastestType`, all native Jackson 3. Nothing is bridged; the bridge classes are
deleted. Ingest is back to a single parse:

```
bytes -> J3 parse -> TokenBuffer -> asParser -> ByteArrayBuilder -> RawJson
```

which is the pre-upgrade shape, on the new mapper. `TokenBuffer` is Jackson 3's
(`tools.jackson.databind.util.TokenBuffer`); the `JsonEntitiesService` signatures are otherwise as
they were. The wire format is unchanged - `TokenBuffer` and `RawJson` both serialize as raw JSON -
so the JS clients, the CLI and the e2e suite see no difference, and Jackson 3 writes dates as text
by default, which is what Boot 3 had configured on Jackson 2 (pinned by `IdlJacksonInteropTest`).

Jackson 2 is no longer declared by any Structures module. It remains on the classpath as a
transitive of libraries that are Jackson 2 themselves, and is theirs: continuum's Ignite/Calcite
chain and Vert.x's JSON layer, `jjwt-jackson` (0.13 still binds Jackson 2), swagger-core, and
Spring AI's OpenAI client. The one place Structures code touches it is
`OpenApiVertxRouterFactory`, which serializes the OpenAPI model with swagger-core's own mapper.
Excluding Jackson 2 outright is not an option while those are on the classpath; kinotic carries the
same exclusions commented out for the same reason.

## Measured

`JsonPathBenchmark` (structures-test, opt in with `STRUCTURES_BENCHMARK=true`) measures the same
paths the bridge figures were taken on: median time / mean allocation per operation, 100 iterations
after 20 warm-up, `ThreadMXBean.getThreadAllocatedBytes`, on an Apple Silicon Mac, Java 21. The
*Jackson 2 direct* column is the pre-upgrade path re-measured on the same machine, not quoted;
*ingest* is the whole upsert pre-processor path a save takes, parameter type in, `RawJson` out.

```
STRUCTURES_BENCHMARK=true ./gradlew :structures-test:test --tests '*JsonPathBenchmark*'
```

| payload | path | Jackson 2 direct | Jackson 3 | ingest (Jackson 3) |
|---------|------|------------------|-----------|--------------------|
| 16 KB | read TokenBuffer | 77 µs / 51 KB | 72 µs / 51 KB | 101 µs / 79 KB |
| 16 KB | read RawJson | 62 µs / 47 KB | 65 µs / 44 KB | 177 µs / 71 KB |
| 16 KB | write TokenBuffer | 37 µs / 30 KB | 37 µs / 30 KB | - |
| 16 KB | write RawJson | 33 µs / 46 KB | 36 µs / 46 KB | - |
| 168 KB | read TokenBuffer | 254 µs / 513 KB | 243 µs / 513 KB | 586 µs / 543 KB |
| 168 KB | read RawJson | 476 µs / 493 KB | 371 µs / 461 KB | 565 µs / 505 KB |
| 168 KB | write TokenBuffer | 320 µs / 307 KB | 322 µs / 308 KB | - |
| 168 KB | write RawJson | 69 µs / 476 KB | 67 µs / 477 KB | - |
| 871 KB | read TokenBuffer | 1.21 ms / 2.6 MB | 1.24 ms / 2.6 MB | 1.84 ms / 2.8 MB |
| 871 KB | read RawJson | 1.74 ms / 2.3 MB | 1.93 ms / 2.2 MB | 2.40 ms / 2.5 MB |
| 871 KB | write TokenBuffer | 1.70 ms / 1.6 MB | 1.67 ms / 1.6 MB | - |
| 871 KB | write RawJson | 353 µs / 2.5 MB | 343 µs / 2.5 MB | - |

Jackson 3 is at parity with the Jackson 2 direct baseline on every path and size, within noise. Set
against the bridge - 14.4 MB and 6.95 ms to read the 830 KB payload - the 4.5x allocation on the
write path is gone, not reduced. The ingest column is the number that matters for bulk saves: an
871 KB batch costs 2.8 MB and 1.84 ms end to end from a `TokenBuffer`, which is the buffer itself
plus the streaming copy to `RawJson`; the difference between the *read* and *ingest* cells is the
whole pre-processor. The benchmark also checks that every path still produces the input JSON, so a
change that made a path fast by making it wrong fails rather than looks like a win.

## Follow-ups worth their own change

- **Version stamping still builds a tree per entity.** `updateVersionForEntity` does
  `readTree -> put -> write` for both the `RawJson` and the `TokenBuffer` case, a full tree per
  saved entity on the optimistic locking path. Injecting the field with a streaming copy would
  remove it. Probably worth as much as the bridge removal on the write path.
- **`RawJsonSerializer` converts `byte[]` to `String`** before `writeRawValue`, a 2x allocation on
  the response path. Jackson 3's `writeRawValue` takes `String`/`char[]` only; writing bytes
  directly would need the generator's underlying stream.
- **`TokenBuffer` → `RawJson` on `JsonEntitiesService` (4.0.0, if ever).** `TokenBuffer` is a full
  in-memory copy of every payload that the upsert path turns into `RawJson` and, for `save` and
  `update`, converts back to satisfy the signature. Taking `RawJson` on the interface would remove
  that buffer from both request and response, at the cost of a breaking change for Java callers of
  `structures-api`. Decided against for 3.6.0: the bridge was the cost that mattered, and the API
  stays stable.
