# API Changes

Changes to the APIs Structures exposes, by release: the Java and TypeScript services, the REST API and the OpenAPI spec
it is described by. Each release's notes include its section.

In the paths below, `{app}` is the application id and `{structure}` the structure name, both lowercase, under the
configured `openApiPath` (`/api/` by default) or `openApiAdminPath` (`/admin/api/`). In operationIds, `<S>` is the
structure name with its first letter capitalized.

## 3.9.0

Writes that refresh before returning, so they can be searched straight away whatever the refresh settings say. See
[Choosing a refresh mode](./structures-server-config.md#choosing-a-refresh-mode) for when to use them.

### Added

**Services.** `@kinotic/structures-api` 3.7.0 and the Java `structures-api` 3.9.0:

| Service | Methods |
|---------|---------|
| `JsonEntitiesService` (Java), `IEntitiesService` / `IEntityService` (TypeScript) | `saveSync`, `updateSync`, `deleteByIdSync` |
| `AdminJsonEntitiesService` (Java), `IAdminEntitiesService` / `IAdminEntityService` (TypeScript) | `deleteByIdSync` (by tenant and entity id) |

They take the same values as `save`, `update` and `deleteById`, plus a `waitFor` flag, and return the same values.
They are allowed or denied by the same policies. By default they force a refresh; with `waitFor` they wait for the next
scheduled refresh instead, so they take up to the index's `refresh_interval` longer, and fail with a timeout, with the
write applied, if that interval is longer than the Elasticsearch socket timeout (see
[waitFor](./structures-server-config.md)). In TypeScript `waitFor` is an optional last argument; in Java it is a
`boolean` before the `Participant`. Code that implements these interfaces itself, rather than using the classes Structures provides, has to add
the new methods.

**REST operations**, for every structure, all in the OpenAPI spec:

| Method and path | operationId | Same as |
|-----------------|-------------|---------|
| `POST /api/{app}/{structure}/save-sync` | `saveSync<S>` | `POST /api/{app}/{structure}` (`save<S>`) |
| `POST /api/{app}/{structure}/update-sync` | `updateSync<S>` | `POST /api/{app}/{structure}/update` (`update<S>`) |
| `DELETE /api/{app}/{structure}/{id}/sync` | `deleteSync<S>` | `DELETE /api/{app}/{structure}/{id}` (`delete<S>`) |
| `DELETE /admin/api/{app}/{structure}/{tenantId}/{id}/sync` | `deleteSync<S>Admin` | `DELETE /admin/api/{app}/{structure}/{tenantId}/{id}` (`delete<S>Admin`) |

Each takes an optional `waitFor` query parameter (`true` or `false`, default `false`), described in the spec; any other
value is a 400. The admin operation is in the spec only for structures with multi-tenant selection, as its plain twin
is. GraphQL has no new operations.

### Changed

These change the OpenAPI spec, and two REST routes, to describe what the server really does. **Clients generated from
the spec will change when regenerated**, as described for each; clients that call the REST API directly see only the
406 change.

**Responses with no body.** These operations answer 200 with an empty body and no `Content-Type`, but the spec
described the response as `application/json` without a schema. It now describes them without content, which is how
OpenAPI says there is no body. The server's responses are unchanged.

| Method and path | operationId |
|-----------------|-------------|
| `DELETE /api/{app}/{structure}/{id}` | `delete<S>` |
| `POST /api/{app}/{structure}/delete/by-query` | `delete<S>ByQuery` |
| `POST /api/{app}/{structure}/bulk` | `bulkSave<S>` |
| `POST /api/{app}/{structure}/bulk-update` | `bulkUpdate<S>` |
| `GET /api/{app}/{structure}/util/sync` | `sync<S>` |
| `DELETE /admin/api/{app}/{structure}/{tenantId}/{id}` | `delete<S>Admin` |
| `POST /admin/api/{app}/{structure}/delete/by-query` | `delete<S>ByQueryAdmin` |

The two admin operations are in the spec only for structures with multi-tenant selection. The new `deleteSync<S>` and
`deleteSync<S>Admin` are documented the same way.

*Breaking for regenerated clients:* these methods now return nothing (`void`) instead of an untyped value, so code
that used their result no longer compiles. Generators that parsed the old documented JSON, such as
`typescript-fetch`, could fail on every successful call to them; those clients now work.

**Error responses.** Errors (400, 401, 403 and 500 on every operation, and 409 where documented) answer
`{"error": "<message>"}` as `application/json`, but the spec described them without content. They now reference a new
`ErrorResponse` schema with a string `error` property. A 404 for an entity that isn't found has an empty body and is
still documented without content. The server's responses are unchanged.

*For regenerated clients:* they gain an `ErrorResponse` model, and can read the error message instead of an
undocumented body.

**`bulk` and `bulk-update` accept any `Accept` header.** `POST /api/{app}/{structure}/bulk` and
`POST /api/{app}/{structure}/bulk-update` answered 406 to a request whose `Accept` header didn't include
`application/json`, although they answer with no body. They now answer it like any other request, as the deletes do.

### Compatibility

- **Older servers:** clients that call the new methods or operations need a 3.9.0 server; an older one answers
  method not found, or 404 for the REST operations. Older clients work with a 3.9.0 server unchanged.
- **Rolling upgrades:** while servers of both versions run, a call to a new method or operation can reach an older
  server, so start using them once every server runs 3.9.0.
- **Configuration and data:** no new settings, no changed defaults, no index or data changes. Going back to 3.8.0
  is safe; only callers of the new methods and operations stop working.
- **Generated clients:** regenerating from the 3.9.0 spec changes the methods listed under Changed, as described
  there. Clients generated from an older spec work against a 3.9.0 server as they did before.

### Known issue

A new operationId can repeat one that another structure already has: `saveSync` for a structure `Job` and `save` for a
structure `SyncJob` are both `saveSyncJob`. The REST paths always differ, since each includes the structure name, so
every operation works; only clients generated from the spec are affected, as they already were by repeats such as
`Job`'s countByQuery and `JobByQuery`'s count (`countJobByQuery`).
