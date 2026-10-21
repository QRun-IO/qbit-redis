# Production readiness

Current checklist, 2026-10-21. Historical evidence is tracked on QQQ #1295.
No production readiness claim or release yet.

## Current user-directed milestone

The user explicitly narrowed this milestone to the main-line happy path on2026-10-21. The historical audit list below is retained for traceability, not as an instruction to resume every optional scenario before this snapshot. Current publication steps and evidence are in [SNAPSHOT-RELEASE.md](SNAPSHOT-RELEASE.md).

- [x] Live browser presence across two independent QQQ JVMs sharing Redis.
- [x] Live Redis dashboard metrics, framework-persisted UP health history and health-record navigation.
- [x] Collaboration normal-flow backend179, UI34 and Chromium16 checks.
- [x] Packaged standalone Maven consumer and optional Redis-absent issued-lease consumer.
- [x] Redis POM license/developer/SCM metadata, correct NOTICE and standard CircleCI setup configuration prepared locally.
- [x] Establish Redis remote repository and CircleCI project (empty; no source uploaded).
- [ ] Capture reviewed source revisions, set develop default branch, and verify CI context access/actual pipeline execution.
- [ ] Publish coordinated core/Redis/collaboration/frontend snapshots and verify remote consumer resolution.

Optional loss/restore, failover topology and sustained scale work: QQQ #1300, #1301 and #1302 respectively. No claim that those scenarios are supported by the current candidate.

## Implemented with targeted verification

- [x] Named bounded Redis pools, cache primitives, namespaces and runtime lifecycle.
- [x] Framework health checks, pool metrics and subscription readiness.
- [x] Authentication/TLS rejection and bounded pool exhaustion tests.
- [x] Redis presence store/service selection, tenant isolation and explicit unavailable UI.
- [x] Cross-node Pub/Sub hints, bounded dispatch, reconnect/resync and silent-stall detection.
- [x] Framework Redis app and local connection/realtime widgets with permission checks.
- [x] Framework-persisted health history and link to existing check settings/actions.
- [x] Packaged app/widget help and health read/write permission-contract tests.
- [x] Metadata-name collision rejection and atomic rejected realtime namespace updates.
- [x] Source dependency report across 44 canonical qbits, with browser validation.

## Historical broader audit backlog (not the current milestone definition)

- [ ] Browser acceptance of Redis app, widgets, help, health navigation and Run check now.
- [ ] Complete connection-management requirements and multi-node presentation semantics.
- [ ] Replace or constrain native-client API exposure and prove resource ownership.
- [x] Candidate session-store Jedis alignment and optional shared Redis provider integration tests.
- [x] Shared session-store runtime owns cleanup/restart of registrations with real Redis lifecycle coverage.
- [x] Actual launcher shutdown/failure/retry acceptance for shared Redis session storage.
- [x] Real shared Redis OAuth HTTP login/logout, bounded outage failure and retry acceptance.
- [x] Same-node delayed authentication cache fill after logout rejects the next HTTP request.
- [ ] Token memoization fill/invalidation race and delayed/lost cross-node logout invalidation acceptance.
- [ ] Qualify #1297 memoization invalidation repair: focused, HTTP and full core unit rerun pass; earlier canary failure remains unexplained with diagnostics added.
- [ ] Qualify #1298 application-scoped OAuth token cache; targeted cluster and 14 HTTP acceptance tests pass.
- [ ] Complete #1299 JDBC ownership: registry/health/launcher/native C3P0 callback isolation tested; deployed-host migration and combined acceptance remain.
- [ ] Deployment migration and complete multi-node HTTP authentication compatibility.
- [ ] Clean-consumer tests with optional Redis/email artifacts absent.
- [ ] Full multi-node collaboration/browser acceptance behind a load balancer.
- [ ] Redis eviction/restart recovery and delayed request/tombstone semantics.
- [ ] Supported topology failover, network partition and lifecycle stress tests.
- [ ] mTLS/credential rotation acceptance for supported deployment configuration.
- [ ] Measured load, latency percentiles and explicit supported capacity.
- [ ] Remaining collaboration issues (including durable attachment cleanup and notification leadership).
- [ ] Complete security review, dependency convergence and compatible full-stack quality gates.
- [ ] QQQ issue resolution, release notes, clean-consumer verification and snapshot/RC when ready.

Latest full Redis verify: 23 tests, zero failures/errors/skips, 94.50% instruction
coverage and guardrails pass (%TEMP%/redis-script-boundary-verify.log).
Collaboration uses script operations instead of the native client. Public credential
and direct-cache construction types still expose Jedis; complete boundary and
session-store compatibility remain open. Report browser checks: all eight pass.
