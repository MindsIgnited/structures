# Plan: remove the JSON ingest copies (4.0.0)

**Status:** planned, not started. Targeted at 4.0.0, after the 3.6.0 dependency upgrade lands.

## Why

Structures moved to continuum 3.1.0, which serializes RPC with Jackson 3, while Structures itself is
built on Jackson 2 and cannot leave it: the Elasticsearch client has no Jackson 3 binding in any
published version, 8.18.1 or the current 9.5.3. The 3.6.0 upgrade bridged the two mappers by handing
the Jackson 2 shaped payload types back to the Jackson 2 mapper.

That bridge is cheap in one direction and expensive in the other, measured against the configured
mappers with `ThreadMXBean.getThreadAllocatedBytes`, warmed, 100 iterations:

| payload | direction | direct Jackson 2 | through bridge | cost |
|---------|-----------|------------------|----------------|------|
| 16 KB   | read      | 80 µs / 65 KB    | 272 µs / 241 KB | 3.4x time, 3.7x alloc |
| 163 KB  | read      | 349 µs / 640 KB  | 1.46 ms / 2.8 MB | 4.2x time, 4.4x alloc |
| 830 KB  | read      | 1.87 ms / 3.2 MB | 6.95 ms / 14.4 MB | 3.7x time, 4.5x alloc |
| 830 KB  | write     | 1.49 ms / 3.2 MB | 1.78 ms / 6.5 MB | 1.2x time, 2.0x alloc |

Reading costs three passes and holds two intermediates - Jackson 3 parses to a tree, the tree is
written to a string, Jackson 2 parses that. Writing is one extra string.

The expensive direction is the one entity ingest uses, because the bridged types are parameters to
`save`, `update`, `bulkSave` and `bulkUpdate`. At tens of thousands of bulk updates a day moving
hundreds of GB, 4.5x allocation on the write path is not a tuning matter.

## What is actually wrong

The bridge is the immediate cause but not the whole cost. `TokenBuffer` is a full in-memory copy of
every payload that is built and then discarded.

Ingest today:

```
bytes -> J3 parse -> JsonNode tree -> toString -> J2 parse -> TokenBuffer -> asParser -> ByteArrayBuilder -> RawJson
```

Ingest before the upgrade, on continuum 2.6 and Jackson 2 only:

```
bytes -> J2 parse -> TokenBuffer -> asParser -> ByteArrayBuilder -> RawJson
```

So even the old baseline built a `TokenBuffer` and threw it away. The codebase already knows this.
From `DefaultEntityService.postProcessSaveOrUpdate`:

```java
// All token buffers received will be converted to RawJson in the upsert preprocessor
// This is done since it uses less memory for bulk operations
// So we convert those cases back to a TokenBuffer before returning
```

`TokenBuffer` survives only because it is the declared type on the published interface. The internals
convert to `RawJson` for the work, then convert back to satisfy the signature - and converting back
costs a full tree plus a full buffer per entity, in `updateVersionForEntity` and
`postProcessSaveOrUpdate`.

`AbstractJsonUpsertPreProcessor` only ever needs a `JsonParser`. It streams into a `ByteArrayBuilder`
and emits `RawJson`. It does not care where the parser came from.

## Design

Take `RawJson` on the published entity methods and give it native Jackson 3 serialization. Target
ingest path:

```
bytes -> J3 copyCurrentStructure -> RawJson(byte[]) -> non-blocking parser -> ByteArrayBuilder -> RawJson
```

One streaming pass. This should beat the pre-upgrade baseline, not merely restore it, because the
`TokenBuffer` disappears from both the request and the response path.

Three things make this smaller than it looks:

1. `RawJson.from(JsonParser, ObjectMapper)` already implements the optimal shape in Jackson 2 -
   `ByteArrayBuilder` plus `copyCurrentStructure`, one pass, no tree, no string. Jackson 3's
   `JsonGenerator` has the same `copyCurrentStructure`, so the Jackson 3 deserializer is close to a
   transliteration.
2. `DelegatingUpsertPreProcessor` already dispatches on `RawJson`, and `RawJsonUpsertPreProcessor`
   already feeds a non-blocking byte array parser straight from the bytes. The fast path exists and
   is exercised.
3. The wire format does not change. `TokenBuffer` and `RawJson` both serialize as raw JSON, so the
   JS clients, the CLI and the e2e suite are unaffected.

## Work items

Serialization
- [ ] Native Jackson 3 `RawJson` deserializer: `tools.jackson.core.util.ByteArrayBuilder` plus
      `copyCurrentStructure`, mirroring `RawJson.from`. No tree, no intermediate string.
- [ ] Native Jackson 3 `RawJson` serializer. Note `writeRaw` in Jackson 3 takes `String`/`char[]`
      only, so the existing `byte[] -> String` step needs a decision - see open questions.
- [ ] Register both on the Jackson 3 module in `StructuresJacksonConfig`, replacing the bridged
      registrations.
- [ ] Delete `Jackson2BridgeSerializer` and `Jackson2BridgeDeserializer` once nothing needs them.
      `FastestType` is serialize-only and may still want a bridged serializer.

API
- [ ] `JsonEntitiesService`: `bulkSave`, `bulkUpdate`, `save`, `update` take and return `RawJson`
      instead of `TokenBuffer` (`structures-api`, published).
- [ ] `DefaultJsonEntitiesService`: matching signature changes.

Internals
- [ ] `DelegatingUpsertPreProcessor`: drop the `TokenBuffer` branch from `process` and `processArray`.
- [ ] `DefaultEntityService.postProcessSaveOrUpdate`: the `RawJson -> tree -> TokenBuffer` conversion
      back becomes unnecessary; remove it.
- [ ] `DefaultEntityService.updateVersionForEntity`: drop the `TokenBuffer` case and the
      `convertRawJsonToTokenBuffer` flag.
- [ ] `TokenBufferUpsertPreProcessor`: delete, unless something outside the entity path still needs it.
- [ ] `DataInitializer`: builds a `TokenBuffer` for sample data; switch to `RawJson`.

Release
- [ ] `structuresVersion` to 4.0.0.
- [ ] Note the breaking change for Java consumers of `structures-api` in the release notes.

## Verification

- [ ] Port the throwaway benchmark used for the numbers above into an opt-in task, gated the way the
      k8s tests are, so the figures are reproducible rather than anecdotal. Compare three paths:
      Jackson 2 direct, the 3.6.0 bridge, and the new `RawJson` path.
- [ ] Confirm the new path allocates at or below the Jackson 2 direct column. Anything above that
      means a copy is still hiding somewhere.
- [ ] Full e2e suite against the KinD cluster through the ingress. The wire format is unchanged, so a
      failure there means the change leaked into the protocol and is a defect, not an expected break.
- [ ] A large bulk save, on the order of the 830 KB payload or bigger, watched for allocation rate
      rather than wall clock.

## Out of scope, worth a follow-up

- **Version stamping still builds a tree per entity.** `updateVersionForEntity` does
  `readTree -> put -> writeValueAsBytes` for the `RawJson` case, which is a full tree per saved
  entity on the optimistic locking path. Injecting the field with a streaming copy would remove it.
  Independent of this change and probably worth as much.
- **`RawJsonSerializer` converts `byte[]` to `String`** before `writeRawValue`, which is a 2x
  allocation on the response path.
- **Fully leaving Jackson 2.** Not possible while the Elasticsearch client, swagger-core and
  jjwt-jackson are Jackson 2 only. This plan moves the seam to the Elasticsearch edge rather than
  removing it.

## Open questions

- Should `save` and `update` still return the entity at all? The return costs a version stamp and a
  re-serialization per entity; a caller that ignores it pays for it anyway.
- Is there a byte-oriented raw write in Jackson 3 that avoids the `byte[] -> String` step when
  serializing `RawJson`? If not, whether to write bytes directly to the underlying stream where the
  generator exposes one.
- Does anything outside this repo call `JsonEntitiesService` from Java? The wire is unchanged, so
  only Java consumers break, and the blast radius depends entirely on that answer.
