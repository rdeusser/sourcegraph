# Telemetry

> WARNING: This is a guide intended for development reference.
>
> Additionally, export capabilities are **not yet enabled by default**.

Telemetry describes the logging of user events, such as a page view or search, from various components of the Sourcegraph and Cody applications.
There are currently two ways to log product telemetry:

- legacy mechanisms outlined in [DEPRECATED: Telemetry](deprecated.md), including writing directly to the `event_logs` database table or using `mutation { logEvent }`.
- the new telemetry framework introduced in Sourcegraph 5.2 and later (documented on this page)

All usages of old telemetry mechanisms should be migrated to the new framework.

- [Why a new framework and APIs?](#why-a-new-framework-and-apis)
- [Event lifecycle](#event-lifecycle)
- [Recording events](#recording-events)
  - [Backend services](#backend-services)
  - [Clients](#clients)
- [Exported events](#exported-events)
  - [Exported event schema](#exported-event-schema)
  - [Sensitive attributes](#sensitive-attributes)
- [Testing events](#testing-events)
- [Enabling telemetry export](#enabling-telemetry-export)

## Why a new framework and APIs?

The new telemetry framework and API aims to address the following issues:

- The existing `event_logs` parameters are arbitrarily shaped - to provide stronger guarantees against accidentally exporting sensitive data, the new APIs enforce stricter requirements - see [recording events](#recording-events) for more details.
- The shape of existing `event_logs` have grown organically over time without a clear structured schema.
  Callsites must construct full events on their own, and we cannot easily prune event objects of potentially [sensitive attributes](#sensitive-attributes) before export.

Events recorded in the new framework and APIs are still translated into the existing `event_logs` table for admin analytics on a best-effort basis - see [event lifecycle](#event-lifecycle) for more details.

## Event lifecycle

All events stay in the instance that events are recording in until they get exported - users of standalone Sourcegraph instances should no longer report any telemetry directly to the [Sourcegraph.com](https://sourcegraph.com/search) deployment, and should instead report events to their own Sourcegraph instance.

In general, the lifecycle of an event in the new system looks like this:

1. [A telemetry event is recorded](#recording-events). This can happen in clients using SDKs like [`@sourcegraph/telemetry`](https://github.com/sourcegraph/telemetry), or using [`internal/telemetry/telemetryrecorder`](https://github.com/sourcegraph/sourcegraph/blob/main/internal/telemetry/telemetryrecorder/telemetryrecorder.go) in the backend.
2. Within each telemetry SDK, additional metadata is automatically injected - in clients through [processors](https://github.com/sourcegraph/telemetry/blob/main/src/processors/index.ts) and [the GraphQL mutation](https://github.com/sourcegraph/sourcegraph/blob/main/cmd/frontend/internal/telemetry/resolvers/telemetrygateway.go), and in the backend through [the events adapter](https://github.com/sourcegraph/sourcegraph/blob/main/internal/telemetry/telemetrygateway.go).
3. The telemetry event is translated into the existing `event_logs` table (for use in [admin analytics](../../../admin/analytics.md)), and stored in a temporary queue for export - see [storing events](./architecture.md#storing-events).
4. Periodically, events are exported from the cache and exported to Sourcegraph's Telemetry Gateway service, which forwards it to our data warehouse - see [exported events](#exported-events) and [exporting events](./architecture.md#exporting-events).

See [telemetry export architecture](./architecture.md) for more details.

## Recording events

Note that recording APIs are intentionally stricter and have a smaller surface area than [the full events we end up exporting](#exported-event-schema).
This is to help prevent accidental export of sensitive data, and to make it clear what properties should be injected in a uniform manner instead of being constructed ad-hoc by callers - see [event lifecycle](#event-lifecycle) for details.

### Backend services

In the backend, events are recorded using `EventRecorder` instances created from the `internal/telemetry/telemetryrecorder` package. For example:

```go
import (
  "github.com/sourcegraph/sourcegraph/internal/telemetry"
  "github.com/sourcegraph/sourcegraph/internal/telemetry/telemetryrecorder"
)

func doMyThing(db database.DB) error {
  recorder := telemetryrecorder.New(db)

  if err := recorder.Record("myFeature", "myAction", telemetry.EventParameters{
    Version:         0,
    Metadata:        telemetry.EventMetadata{"my_metadata": 12},
    // See 'Sensitive attributes'
    PrivateMetadata: map[string]any{"my_private_metadata": 42},
  }); err != nil {
    return err
  }
}
```

If you don't care about failures to record telemetry, you can use `telemetryrecorder.NewBestEffort(log.Logger, database.DB)` to automatically have errors logged and not returned.

Note that not all attributes are exported - see [Sensitive attributes](#sensitive-attributes) for details.

### Clients

Clients should use [`@sourcegraph/telemetry`](https://github.com/sourcegraph/telemetry), providing client-specific metadata and implementation for exporting to a Sourcegraph instance's `mutation { telemetry { recordEvent(...) }}` GraphQL mutation.

> NOTE: More guidance coming soon!

## Exported events

See [telemetry export architecture](./architecture.md) for more details on how exporting events works.

A detailed schema is available in [the Telemetry Gateway protocol documentation](./protocol.md), which also has more details about what kind of data gets exported and what components are generally pruned.

### Exported event schema

The full event schema is intentionally a significant superset from the shape of the [event-recording APIs](#recording-events).
Standardized metadata (users, feature flags, etc) are automatically added at various points in an event's lifecycle - callsites should only be concerned with properties associated with the specific event.

The full event schema that ends up getting exported is defined in [`telemetrygateway.proto`](https://github.com/sourcegraph/sourcegraph/blob/main/internal/telemetrygateway/v1/telemetrygateway.proto)'s `Event` message type. The event forwarded from Telemetry Gateway currently has the following shape:

<!-- TODO: We can generate a JSON schema using https://github.com/chrusty/protoc-gen-jsonschema -->

```json
{
  "metadata": {
    "identifier": {
      // ... telemetrygatewayv1.Identifier
    }
  },
  "event": {
    // ... telemetrygatewayv1.Event
  }
}
```

A detailed schema is available in [the Telemetry Gateway protocol documentation](./protocol.md), which also has more details about what kind of data gets exported and what components are generally pruned - also see [sensitive attributes](#sensitive-attributes) above.

> NOTE: In the Sourcegraph application, the new events being exported using `internal/telemetry` are sometimes loosely referred to as "V2", as it supersedes the existing mechanisms of writing directly to the `event_logs` database table.
> The *Telemetry Gateway* schema, however, is `telemetrygateway/v1`, as it is the first iteration of the service's API.

### Sensitive attributes

There are two core attributes in events that are considered potentially sensitive, and thus not exported from individual Sourcegraph instances:

- `parameters.privateMetadata`: this fields allows the recording of arbitrarily shaped metadata, as opposed to the integer values supported in `parameters.metadata`. Due to the risk of sensitive data and PII exposure, we do not export this field by default
  - Certain events may be allowlisted to have this field exported - this is defined in [`internal/telemetry/sensitiviemetadataallowlist`](https://github.com/sourcegraph/sourcegraph/blob/main/internal/telemetry/sensitivemetadataallowlist/sensitiviemetadataallowlist.go). Adding events to this list requires review and approval from Legal.
- `marketingTracking`: this field tracks a lot of properties around URLs visited and marketing tracking that may contain sensitive data. This is only exported from the [Sourcegraph.com](https://sourcegraph.com/search) instance.


## Testing events

In summary, when adding your events in the new telemetry framework, you can verify events are being recorded by:

1. [Checking your events stored directly in `event_logs`](./architecture.md#storing-events) after recording.
2. Observing the raw payloads that the Telemetry Gateway ends up publishing in logs when [running Telemetry Gateway locally](../../how-to/telemetry_gateway.md).
   1. Note that the internal queue table only stores events until they are exported, and events are stored in raw Protobuf wire format - see [storing events](./architecture.md#storing-events).

In integration and unit tests, you can also provide a mocked [telemetry recording](#recording-events) implementation to assert that various events are recorded as expected.
For example, in the backend, you can use package `internal/telemetry/telemetrytest`, which provides a variety of testing utilities:

```go
import (
  "context"
  "testing"

  "github.com/stretchr/testify/require"

  "github.com/sourcegraph/sourcegraph/internal/telemetry"
  "github.com/sourcegraph/sourcegraph/internal/telemetry/telemetrytest"
)

func TestRecorder(t *testing.T) {
  store := telemetrytest.NewMockEventsStore()
  recorder := telemetry.NewEventRecorder(store)

  err := recorder.Record(context.Background(), "Feature", "Action", nil)
  require.NoError(t, err)

  // stored once
  require.Len(t, store.StoreEventsFunc.History(), 1)
  // called with 1 event
  require.Len(t, store.StoreEventsFunc.History()[0].Arg1, 1)
  // stored event has 1 event
  require.Equal(t, "Feature", store.StoreEventsFunc.History()[0].Arg1[0].Feature)
}
```

## Enabling telemetry export

> NOTE: Telemetry export is currently experimental, and disabled by default.

Telemetry export can be enabled by making the following configuration changes:

- Set environment variable `TELEMETRY_GATEWAY_EXPORTER_EXPORT_ADDR="https://telemetry-gateway.sourcegraph.com:443"`
- Enable feature flag `telemetry-export` on the entire instance, or on a subset of users that you want to export telemetry for

Our defaults for the above may change in the future.
