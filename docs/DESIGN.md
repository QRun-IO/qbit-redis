# Redis infrastructure and shared presence

Status: accepted scope, implementation in progress (2026-10-21).

Current milestone: complete the normal shared-cache/presence/admin flow. Normal
cross-node browser presence and Redis admin/framework-health rendering are verified.
The original broader gates below are historical research scope; exceptional
loss/restore, topology failover and sustained scale qualification are optional
future work in QQQ #1300, #1301 and #1302. See SNAPSHOT-RELEASE.md for the current
publication checklist. Do not imply those optional scenarios are supported.

The user authorized a reusable Redis qbit alongside the collaboration production
work. This supersedes the earlier proposal-only status. No release is ready.

## Boundaries

- qbit-redis owns named connections, bounded pools/timeouts, TLS and authentication,
  collision-safe namespaces, shared cache operations, lifecycle and observability.
- qbit-collaboration owns presence leases, sequence ordering, view/edit state,
  permission filtering, user deduplication and browser notifications.
- qbit-session-store should reuse the connection infrastructure while retaining
  session semantics. Preserve existing configuration compatibility.
- qbit-cluster keeps node lifecycle and database-backed leadership. Redis remains
  optional for applications not using Redis-backed features.
- Core ClusterCaches continues to coordinate invalidation of local memoizations;
  shared Redis values are a separate capability.

## Correctness requirements

Presence tracks browser connections, aggregates unique users per record/table,
and never stores unsaved record contents. Every namespace includes deployment and
application scope; tenant boundaries must follow the host's authoritative context.
The server derives user identity and verifies record/field permissions on every
read and renewal. Expiry uses Redis server time. Mutations atomically compare
request sequence numbers so delayed renewals cannot undo departures. Bound both
active leases and departure markers; no KEYS or unbounded scans in request paths.

Use shared state as authority and Pub/Sub only as an invalidation hint. Reconnect
and periodic reconciliation repair missed messages. Emit browser events locally,
without republishing each node's snapshot across the cluster. Redis outage must
produce an explicit unavailable state; it must not silently claim an empty room.
Database compare-and-write remains the authority for record save conflicts.

## Production gates

- Unit tests: validation, namespace isolation, TTL and payload bounds, resource
  ownership, sequence ordering, duplicate tabs, stale departures and permissions.
- Real Redis: six people on one record, two editing; nodes joining/leaving;
  process crashes; expiry; reconnect; auth/TLS rejection; exhausted pools;
  eviction/outage behavior; supported Redis topology failover.
- Concurrent load: report topology, hardware, active users, heartbeat rate,
  p50/p95/p99 command and browser propagation latency, errors, CPU/memory and pool
  utilization. Establish measured capacity and an explicit latency budget.
- End-to-end: multiple QQQ nodes behind a load balancer; browser connection
  routing, cross-node subscriptions, reconnect and stale-save rejection.
- Durable cache/session/presence policies must be explicit and independently
  configurable. No indefinite retries or accidental secret logging.
- Run build, coverage, dependency/security checks, clean-consumer verification,
  and compatible snapshot/RC publication only after all required gates pass.

## Implementation sequence

1. Build and test connection/configuration and shared-cache primitives.
2. Integrate QBit metadata, runtime lifecycle, health and metrics.
3. Add collaboration provider with atomic leases and local event delivery.
4. Reuse infrastructure in session-store with compatibility tests.
5. Exercise multi-node, failure and load gates; document limits and operations.

References: Redis official Jedis connection and production guides, Pub/Sub
delivery semantics, and Lua atomic execution documentation. Jedis version and
Redis compatibility must be pinned and tested, not inferred from latest docs.

## Redis admin dashboard contract

Use QQQ app metadata, standard widgets, permission enforcement and the existing
health framework. Initial widgets expose local pool and subscription counters;
"Started" is lifecycle state, not an assertion of health. Do not label a local
snapshot as cluster-wide. Health history/transitions must come from framework
health records, with its read permissions, rather than another polling/history
implementation. Operations need separate operate permissions and explicit scope.
Do not expose credentials, key/value contents or unbounded key enumeration.

Live browser acceptance verifies connection and realtime sections, persisted health
history, and navigation to the framework health-check record. Lower-layer tests
cover packaged help, permissions and unavailable states. The dashboard reports
this node's observations; it does not offer cluster-wide configuration mutation.
