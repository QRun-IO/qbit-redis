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

## Stable endpoint switch evidence

`RedisEndpointSwitchTest` adds a separate controlled infrastructure-switch case.
Two existing QQQ pools and their subscriptions initially connect through one
loopback TCP relay to the primary. The test verifies command sockets alongside
acknowledged subscription sockets, waits for a marker on the replica, stops the
old primary, and promotes the replica. The relay then changes its target and
closes every accepted old connection. The QQQ instances, pools and buses are
not recreated or reconfigured.

Bounded read probes tolerate only connection errors while the old sockets are
discarded; this is test polling, not automatic retry of application writes.
After recovery, a single new write is read by the other pool and independently
from the replica. Both subscription reconnect counters must advance, and a
fresh cross-node event must arrive. Two-slot pool bounds and release of borrowed
connections on bus shutdown are checked. The fixture closes its loopback relay,
Redis containers and network. The focused run passed with 907 ms measured from
promotion through fresh event delivery; this single observation is not an SLA.
The subsequent full `./mvnw.cmd verify` run passed all 26 tests with zero
failures, errors or skips, plus packaging and JaCoCo gates. Its endpoint-switch
observation was 6,433 ms; the separate unchanged-endpoint promotion measured
851 ms. Preserve this variability rather than treating the faster focused run
as a deployment recovery target.

An initial test stopped before promotion because it assumed the initial command
sockets would remain separate after subscriptions borrowed them. The corrected
fixture explicitly issues commands after subscription acknowledgement to ensure
both connection types are established before injecting the switch.

This tests controlled routing with forced old-socket termination and a stopped
old primary. It does not qualify DNS changes while old sockets remain open,
Sentinel/Cluster discovery, live-primary fencing, partition healing, TLS rotation,
or a production HA endpoint provider. Session durability remains a separate
session-store concern. No production routing or retry behavior changed.

## Remaining deployment qualification

- Select and qualify the actual HA endpoint provider and its routing behavior,
  including existing pooled connections when the primary changes.
- Exercise partitions, old-primary fencing and healing. Stopping a process in
  this test does not prove fencing of a live, isolated old primary.
- Verify the session-store's actual create, refresh, revocation and expiry
  semantics through failover. A replicated marker is not a session test.
- Qualify TLS/authentication, endpoint changes, subscriber continuity and pool
  pressure together in the deployment topology.
- Adopt the opt-in write-health probe below and define how the host uses its
  result for readiness. Existing published health remains connectivity-only.
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

## Opt-in write-health candidate

`RedisConnectionConfig.withWriteHealthKeyPrefix` enables a write check in the
existing `redis.connections` framework health check. Each configured connection
must accept a unique `SET NX PX 1000` under its dedicated host-owned prefix.
Random suffixes and NX avoid overwriting existing keys; the one-second expiry
also removes data after a lost reply. No deletion command or application key is
used. The default remains connectivity-only for read-only/Pub/Sub consumers.

The real Redis fixture verifies that PING still succeeds while a replica rejects
the probe, promotion restores UP, denying SET through ACL makes it DOWN, and
restoring SET permission returns UP. It also checks probe expiry and pool release.
The six focused health/runtime/realtime tests pass. Full verification also passed: 28 tests, zero failures/errors/skips, packaging and JaCoCo gates (1:46). Initial test compilation used unsupported admin methods on the
pooled client; the fixture now uses its owned container's redis-cli instead.

This change is local and unpublished. Kof22 has not adopted it. The host must
reserve and authorize the prefix, select its readiness policy, and qualify the
deployed route. A writable isolated old primary can still pass: this probe does
not establish fencing, replication durability or permission for arbitrary domain
Lua/scripts. Connectivity and write acceptance are distinct from those guarantees.
