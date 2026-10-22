# Coordinated candidate snapshot

Status, 2026-10-22: source is uploaded, canonical branch CI is verified and enforced, and the required Core snapshot is published. Publication of Redis `0.1.0-SNAPSHOT` through the canonical `develop` pipeline remains to be verified. This is not a production deployment checklist or an RC certification.

## Local evidence

- Collaboration default verify:179tests, no failures/errors/skips, coverage and guardrails passed.
- UI focused34 plus Chromium normal-flow suites16 passed; TypeScript passed.
- Live browser with mocks disabled: two independent QQQ JVMs share presence; editing focus, drafts, table projection and departure verified.
- Redis admin shows live pool/realtime metrics and real framework-persisted health results, with navigation to the standard health check.
- Standalone Maven consumer built and ran packaged collaboration/Redis candidates without source paths or a reactor; Redis-absent consumer uses the new issued-lease protocol successfully.

## Publication order and remaining checks

1. Capture reviewed source revisions for the coordinated core, Redis, collaboration, session-store and frontend candidates. Preserve unrelated worktrees and edits.
2. Publish the corresponding QQQ4.1.0-SNAPSHOT parent/BOM and required core modules first. The consumer graph currently includes backend-core, backend-module-rdbms, middleware-javalin and openapi.
3. The public [Redis repository](https://github.com/QRun-IO/qbit-redis) has canonical `main` and `develop` branches; `develop` is the default. [PR #1](https://github.com/QRun-IO/qbit-redis/pull/1) installed the shared orb declarations at `33b21f9c0a1ea316f7f720bbde2465ac11d81af0`. CircleCI pipelines 10 and 11 passed on that revision. Both branches enforce the `setup` and `verify` checks from CircleCI app 18001, including for administrators.
4. Publish `0.1.0-SNAPSHOT` through the shared QBit profile declared in `.qqq-ci.yml`. The canonical caller tracks `kingsrook/qqq-orb@0.8`; the orb owns verification, version calculation, signing and deployment. Normal `develop` changes select snapshot publication. CI-only declaration changes intentionally verify without publishing, so pipelines 10 and 11 do not prove artifact publication. Follow the actual publisher to success and resolve its timestamped POM/JAR from the remote repository before marking this step complete. Do not add a repository-specific publishing workflow.
5. Run and publish collaboration0.1.0-SNAPSHOT and the coordinated session-store/frontend candidates as applicable. Frontend and collaboration lease endpoints require coordinated rollout; do not pair the new frontend with an older backend.
6. Resolve exact published artifacts in a fresh consumer cache, repeat normal host startup/presence/admin checks, and record remote coordinates, source SHAs and artifact checksums. Local cache success is insufficient for this step.

## Verified dependency baseline

Core commit `47eb326c4a36e481a8cf36b93c2e022760d08c76` completed publisher job 9606. Its published `4.1.0-SNAPSHOT` artifacts were checked remotely: 19 resolved POMs and the backend-core/Javalin JAR APIs needed by Redis (`getIfStarted`, message audience accessors, audience-provider configuration and hub audience filtering). Redis pipeline 8 then passed against the published dependency before PR #1 merged.

This baseline establishes the Core dependency and Redis CI path. It does not certify the coordinated collaboration/session-store/frontend deployment or a stable Redis release. Retain the separate consumer and host checks in steps 5 and 6.

## Operational scope

Initial path: multiple QQQ web nodes, one configured Redis endpoint, host-provided authentication/tenant scope, HTTP lease writes, SSE browser delivery and Redis Pub/Sub invalidation. Presence is advisory; database version checks protect saves.

Optional future work remains QQQ #1300 (loss/restore), #1301 (failover topology) and #1302 (sustained scale/failure injection). No transparent disaster-recovery guarantee is part of this snapshot. If the normal presence/admin flow fails in staging, stop rollout and retain the prior coordinated frontend/backend set; do not downgrade only one side of the lease protocol.
