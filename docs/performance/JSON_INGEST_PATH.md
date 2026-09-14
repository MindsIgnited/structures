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

## Follow-ups worth their own change

- **Version stamping still builds a tree per entity.** `updateVersionForEntity` does
  `readTree -> put -> write` for both the `RawJson` and the `TokenBuffer` case, a full tree per
  saved entity on the optimistic locking path. Injecting the field with a streaming copy would
  remove it. Probably worth as much as the bridge removal on the write path.
- **`RawJsonSerializer` converts `byte[]` to `String`** before `writeRawValue`, a 2x allocation on
  the response path. Jackson 3's `writeRawValue` takes `String`/`char[]` only; writing bytes
  directly would need the generator's underlying stream.
- **Measure.** The figures above were taken against the bridge. Port that throwaway benchmark into
  an opt-in task, gated the way the k8s tests are, and confirm the single-parse path allocates at or
  below the "direct Jackson 2" column. Anything above it means a copy is still hiding somewhere.
- **`TokenBuffer` → `RawJson` on `JsonEntitiesService` (4.0.0, if ever).** `TokenBuffer` is a full
  in-memory copy of every payload that the upsert path turns into `RawJson` and, for `save` and
  `update`, converts back to satisfy the signature. Taking `RawJson` on the interface would remove
  that buffer from both request and response, at the cost of a breaking change for Java callers of
  `structures-api`. Decided against for 3.6.0: the bridge was the cost that mattered, and the API
  stays stable.
