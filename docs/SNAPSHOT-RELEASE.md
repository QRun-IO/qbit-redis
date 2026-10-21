# Coordinated candidate snapshot

Status: locally verified candidate; remote publication is pending. This is not a production deployment checklist or an RC certification.

## Local evidence

- Collaboration default verify:179tests, no failures/errors/skips, coverage and guardrails passed.
- UI focused34 plus Chromium normal-flow suites16 passed; TypeScript passed.
- Live browser with mocks disabled: two independent QQQ JVMs share presence; editing focus, drafts, table projection and departure verified.
- Redis admin shows live pool/realtime metrics and real framework-persisted health results, with navigation to the standard health check.
- Standalone Maven consumer built and ran packaged collaboration/Redis candidates without source paths or a reactor; Redis-absent consumer uses the new issued-lease protocol successfully.

## Publication order and remaining checks

1. Capture reviewed source revisions for the coordinated core, Redis, collaboration, session-store and frontend candidates. Preserve unrelated worktrees and edits.
2. Publish the corresponding QQQ4.1.0-SNAPSHOT parent/BOM and required core modules first. The consumer graph currently includes backend-core, backend-module-rdbms, middleware-javalin and openapi.
3. GitHub repository https://github.com/QRun-IO/qbit-redis now exists PUBLIC, matching the existing collaboration/session-store repositories; it is still empty. Local origin is connected. CircleCI project gh/QRun-IO/qbit-redis was created (ID7c6fe3ea-5281-437a-b188-818d7d83a604). Initial source upload, develop default branch and required context access/pipeline execution remain to be verified. The empty project initially reports main; change it after develop exists.
4. Run Redis CI and publish0.1.0-SNAPSHOT through the standard QBit orb. The local CI config matches the current GitHub collaboration configuration's orb0.7.18 entry point. Authenticated registry lookup confirms this is a private orb and0.7.18 exists. Config validation PASSED with the verified QRun-IO organization ID: `circleci config validate .circleci/config.yml --org bb329fea-03cd-4fb5-b530-15e85703598d`. Earlier missing-orb messages were caused by absent organization context (this new repo has no remote for inference), not a missing release. Use the existing session credential loader and map CIRCLECI_TOKEN to CIRCLE_TOKEN only within the invoking process. Actual Redis project pipeline execution remains unverified. The Redis POM exposes the revision property required by the orb's version script.
5. Run and publish collaboration0.1.0-SNAPSHOT and the coordinated session-store/frontend candidates as applicable. Frontend and collaboration lease endpoints require coordinated rollout; do not pair the new frontend with an older backend.
6. Resolve exact published artifacts in a fresh consumer cache, repeat normal host startup/presence/admin checks, and record remote coordinates, source SHAs and artifact checksums. Local cache success is insufficient for this step.

The earlier request authorizes preparing a snapshot when ready. Repository and CI project creation are complete; no source commit/push or artifact publication has occurred. Keep this distinction explicit. Source-only Gitleaks scan passed (no leaks; generated logs and local evidence were not included in that scan).

## Operational scope

Initial path: multiple QQQ web nodes, one configured Redis endpoint, host-provided authentication/tenant scope, HTTP lease writes, SSE browser delivery and Redis Pub/Sub invalidation. Presence is advisory; database version checks protect saves.

Optional future work remains QQQ #1300 (loss/restore), #1301 (failover topology) and #1302 (sustained scale/failure injection). No transparent disaster-recovery guarantee is part of this snapshot. If the normal presence/admin flow fails in staging, stop rollout and retain the prior coordinated frontend/backend set; do not downgrade only one side of the lease protocol.
