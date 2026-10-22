# QBit Redis

Reusable Redis connections and bounded shared caches for QQQ applications.
**Coordinated feature snapshot published; not a stable/GA release.** Track
[QQQ #1295](https://github.com/QRun-IO/qqq/issues/1295) and [completed milestone and follow-ups](docs/TODO.md).
See [published snapshot](docs/SNAPSHOT-RELEASE.md) for current normal-flow evidence,
exact compatible versions and the explicitly deferred optional scenarios.

## Host integration

Register the qbit before runtime services that use Redis:

```java
new RedisQBitProducer().withQBitConfig(new RedisQBitConfig()
   .withConnection("presence", new RedisConnectionConfig()
      .withHost("redis.internal")
      .withCredentialsProvider(new QCodeReference(HostRedisCredentials.class))))
   .produce(instance);
```

The host's `RedisCredentialsProvider` resolves credentials at runtime and returns
the Jedis `RedisCredentials` interface. Do not put passwords in QBit metadata,
connection names or host strings. Provider failures are converted to safe native
connection errors so the client closes sockets opened before authentication.

TLS is enabled by default, including hostname verification. The initial transport
uses the JVM trust configuration. Hosts with private trust roots or client keys can
configure `.withTlsProvider(new QCodeReference(HostRedisTls.class))`; that provider
returns an SSLContext at runtime, and hostname verification stays enabled.
`.withTls(false)` is an explicit option for local test Redis. Real integration tests
verify authenticated TLS, untrusted-certificate rejection, wrong-host rejection,
and no plaintext fallback. Mutual-TLS tests require a separate trusted client
identity, reject missing or untrusted client certificates, and verify framework
health after a successful authenticated connection. Certificates and keys are
generated only for the disposable fixture.

The TLS provider is resolved when the Redis runtime starts. Replacing its context
alone does not reload existing pools or subscribers. For certificate replacement,
stop dependent services and restart the Redis runtime through the application
lifecycle, then restart its dependents. Qualification replaces both server and
client certificates on the same Redis endpoint: stale material fails a fresh
handshake, health becomes DOWN, and runtime restart with updated material restores
PONG and health UP while closing the old pool. This is a restart procedure, not
zero-downtime certificate rotation. Live reload, trust-chain/revocation policies,
subscriber recovery across certificate changes and failover acceptance remain open;
only standalone endpoints are implemented.

QQQ's application launcher starts the registered runtime service. Startup validates
configuration and pings every connection before exposing any of the named pools.
If any pool fails, all pools opened by that startup are closed. Duplicate starts
are rejected. Connections are isolated by QInstance.

Hosts managing runtime services manually must call `start(instance)`, then `stop()`
during service shutdown and `afterApplicationStop()` after HTTP and dependent
services have stopped. Shared pools intentionally stay open until the final callback.

## Shared values

```java
RedisCache cache = RedisAccess.cache(instance, "presence",
   new RedisNamespace("production", "orders-app", tenantId, "lookup-cache"), 65536);
cache.put("record:123", encodedValue, Duration.ofSeconds(30));
Optional<byte[]> current = cache.get("record:123");
Optional<byte[]> renewed = cache.getAndTouch("record:123", Duration.ofSeconds(30));
boolean stillPresent = cache.touch("record:123", Duration.ofSeconds(30));
```

Resolve tenantId from authoritative host context. Namespace segments are encoded
separately so delimiters and Redis hash tags in a logical key cannot change scope.
Values have an explicit size limit, writes require a positive TTL, and a single SET
applies value and expiry atomically. Missing/expired values return empty; outages
throw instead of masquerading as cache misses. No wildcard delete or global scan
is exposed. Read-and-renew uses atomic GETEX (Redis 6.2+); it does not fall back
to separate commands or recreate a deleted value. Callers own serialization; this API does not deserialize Java objects.

Named connections allow separate capacity and eviction policies for disposable
caches, sessions and presence. Pool defaults are 16 connections, 500 ms maximum
borrow wait, and 2 s connection/socket timeouts. These are initial defaults, not
measured capacity recommendations. `connection.metrics()` reports active, idle,
waiting and maximum connections. `redis.connections` integrates with QQQ health
checks and reports aggregate pool utilization without endpoints or secrets.

Other qbits consume only `com.kingsrook.qbits.redis.api`: `RedisAccess`,
`RedisNamespace`, `RedisCache`, `RedisScript`, `RedisScriptExecutor`,
`RedisCredentialsProvider` and `RedisTlsProvider`. `RedisAccess` returns cache or
script operations, without exposing pool closure or reconfiguration. Pool lifecycle,
configuration and health implementation remain in the Redis qbit's root package.

Trusted domain Lua runs through `RedisAccess.scripts(instance, name).execute(script,
keys, arguments)`. Construct immutable `RedisScript` objects once from application
resources. Redis owns EVALSHA/NOSCRIPT recovery; other failures propagate without
retry because a script may already have applied writes. Results use Java strings,
longs, nested lists and null. Domain scripts own their input bounds and must put
all keys in one cluster slot. This is not an API for untrusted user scripts.

Presence lease ordering, aggregation, permission checks and browser delivery belong
to qbit-collaboration. Its HTTP provider supports Redis; the candidate's normal
cross-node event delivery is verified in a live two-JVM browser check. Exceptional
data-loss/restore recovery is deferred to QQQ #1300. The cache API alone
is not a presence lease implementation or a distributed lock.

## Redis realtime transport (candidate)

Hosts can opt into a Redis Pub/Sub implementation of QQQ's realtime bus:

```java
new RedisQBitConfig()
   .withConnection("realtime", new RedisConnectionConfig().withHost("redis.internal"))
   .withRealtime("realtime", "production", "orders-app");
```

Use a dedicated named pool with at least two connections: one subscription is
reserved per node, leaving command capacity for publication. TLS/authentication
use the same runtime settings as other Redis pools. The producer refuses to
replace an already configured realtime bus. Deployment/application namespaces
isolate topics; tenant audience checks remain in the core hub and collaboration.

Messages use one Redis echo path, without local duplication or node republishing.
The subscriber only parses/enqueues; a separate dispatcher delivers to the local
hub. The queue holds at most 256 hints; payloads are limited to 64 KiB. Reconnects,
invalid/oversized hints, overflow and publication failures request a local resync.
The hub sends an empty resync event to every stream, and browsers reread authorized
state. No durable Pub/Sub history is assumed. Retries use capped jittered backoff.
Transport metrics report listening, queued, dropped, publication failures and
reconnects without exposing topics or payloads. Heartbeat reception is also counted.

A dependent runtime service starts the subscriber after the pools, without waiting
for the first browser request. Health checks observe the existing transport and
report DOWN if its subscriptions are not acknowledged, even if command PING works.
They never create a bus. Shutdown stops the transport before releasing its pool.

The realtime pool uses a 15-second native blocking-read timeout. Each node publishes
a pulse every five seconds to its own private heartbeat channel on the same
subscription connection. This keeps an idle healthy connection alive and detects
silent reads without quadratic heartbeat fanout across nodes. Pulses never reach
browser queues. Silent-stall tests keep sockets open while Redis pauses command
processing, then verify DOWN, reconnect and restored readiness.

Real Redis tests cover independent pools, application isolation, preserved audience,
forced subscriber disconnect/recovery, slow-consumer overflow and outage shutdown.
Still required before release: broader network-partition and topology failover
acceptance, lifecycle stress, realistic load and multi-node browser acceptance.
A Redis command PING alone does not establish subscriber readiness.

## Development commands

Java 21, Maven wrapper and Docker are required for the full current tests:

```powershell
.\mvnw.cmd test
```

The current candidate uses local QQQ `4.1.0-SNAPSHOT` and Jedis `7.2.0`. Tests use
real disposable Redis containers. They cover sharing, expiry, isolation, outages,
registration, failed startup cleanup, credential-error redaction, lifecycle,
authentication, bounded pool exhaustion and TLS. TLS tests generate temporary
certificates using the JDK keytool and never use credentials from external services.
See [DESIGN.md](docs/DESIGN.md) for required TLS, topology, load and release gates.
