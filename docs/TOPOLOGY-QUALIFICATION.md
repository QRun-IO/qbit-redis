# Redis topology qualification — QQQ #1301

The implemented client accepts one explicit host and port per connection. It
does not discover Sentinel primaries or route Redis Cluster slots. Promotion
and endpoint ownership remain infrastructure responsibilities. This follow-up
keeps topology qualification in qbit-redis; collaboration consumes its shared
connection and realtime primitives.

## Controlled promotion evidence

`RedisPromotionTest` runs a real Redis 7.4 primary and replica on an isolated
container network. Two independently configured QQQ instances each use the
replica endpoint with a two-slot pool: one subscription and one command slot.
This deliberately tests role transition at an unchanged endpoint, not routing
from an old primary endpoint to a new one.

The test observes a marker replicated from the primary, rejects writes while
the endpoint is read-only, stops the old primary and verifies writes there
fail. It promotes the replica, verifies both node pools can read/write there,
disconnects both subscription sockets, and requires acknowledged subscriptions
and delivery of a fresh cross-node event. Pool bounds and release of active
connections after shutdown are checked. Recovery from the promotion command
through reconnect and event delivery has a 25-second fixture deadline; the
measured duration is logged. This is not a production recovery-time guarantee.

The initial focused test passed on October 26, 2026. In the first full run,
promotion through fresh event delivery measured 683 ms, but an existing
heartbeat test timed out waiting eight seconds for its first pulse. The
isolated heartbeat test then passed without changing its deadline or runtime;
the cause is not established. The diagnostic full rerun measured 1,216 ms for
promotion/reconnect/delivery and passed all 25 tests, packaging and coverage
gates. The original timeout remains unexplained. These timing observations are not a
latency distribution or an SLA. Heartbeat failures now include sanitized
transport and pool counters for diagnosis.
No runtime behavior or supported deployment
contract changes merely because this fixture passes.

## Remaining acceptance

- Select and qualify the actual HA endpoint provider and its routing behavior,
  including existing pooled connections when the primary changes.
- Exercise partitions, old-primary fencing and healing. Stopping a process in
  this test does not prove fencing of a live, isolated old primary.
- Verify the session-store's actual create, refresh, revocation and expiry
  semantics through failover. A replicated marker is not a session test.
- Qualify TLS/authentication, endpoint changes, subscriber continuity and pool
  pressure together in the deployment topology.
- Define host write-readiness when an endpoint is a replica. The current health
  check observes connectivity and subscription acknowledgement, not writability.
- Record production-representative recovery timing and data-loss limits.

Redis replication is asynchronous and permits data-loss windows. The read-only
replica default prevents ordinary accidental replica writes; it is not a
split-brain fencing mechanism. See the official [Redis replication documentation](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/).
Presence availability and durable session correctness require separate evidence.

Run the focused fixture with:

```text
./mvnw -Dtest=RedisPromotionTest test
```

The test owns and removes its containers and network. It does not connect to
an existing deployment, change host configuration, or promote a live service.
