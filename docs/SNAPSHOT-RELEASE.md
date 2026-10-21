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
2. Verify core candidate b0673b0a1 on feature/collaboration-production-20261021 (supersedes failed4205; merge cleanup regression reproduced and fixed,27merge and337RDBMS tests passed), then publish its feature-specific QQQ snapshot using the existing core publish-tag workflow. Record the actual resulting Maven version; do not assume the hash abbreviation or overwrite the shared develop snapshot. Align downstream qqq.snapshot.version to that published candidate. The consumer graph includes backend-core, backend-module-rdbms, middleware-javalin and openapi, plus their parent/BOM.
3. GitHub repository https://github.com/QRun-IO/qbit-redis exists PUBLIC, matching existing QBits. Initial sourceb21356a and CI ownership fix13e9537 are on feature/redis-production-20261021; no Maven artifact is published. CircleCI project gh/QRun-IO/qbit-redis exists (ID7c6fe3ea-5281-437a-b188-818d7d83a604). Pipeline2 setup passes, but verification fails compilation against the published core SNAPSHOT, which lacks getIfStarted/getAudience. Publish the coordinated core prerequisite first, then rerun. Default branch currently follows the initial feature branch. Use the existing publish-tag feature snapshot path for this coordinated candidate; no develop merge is required.
4. Run Redis CI and publish its feature-specific snapshot through the standard QBit orb. Record the resulting exact version and pin collaboration/session-store to it before their verification/publication. The local CI config matches the current GitHub collaboration configuration's orb0.7.18 entry point. Authenticated registry lookup confirms this is a private orb and0.7.18 exists. Config validation PASSED with the verified QRun-IO organization ID: `circleci config validate .circleci/config.yml --org bb329fea-03cd-4fb5-b530-15e85703598d`. Earlier missing-orb messages were caused by absent organization context (the repo had no remote for inference at that time), not a missing release. Use the existing session credential loader and map CIRCLECI_TOKEN to CIRCLE_TOKEN only within the invoking process. Actual Redis pipeline2 reached verification and failed on the missing core APIs; a passing run remains required. The Redis POM exposes the revision property required by the orb's version script.
5. Run and publish feature-specific collaboration and session-store snapshots using publish-prefixed tags, plus the coordinated frontend candidate as applicable. Frontend and collaboration lease endpoints require coordinated rollout; do not pair the new frontend with an older backend.
6. Resolve exact published artifacts in a fresh consumer cache, repeat normal host startup/presence/admin checks, and record remote coordinates, source SHAs and artifact checksums. Local cache success is insufficient for this step.

The earlier request authorizes preparing a snapshot when ready. Repository/CI setup and feature-branch source upload are complete; artifact publication remains pending. Source and staged-diff Gitleaks scans passed. Generated logs and local progress evidence were excluded from the initial commit.

## Operational scope

Initial path: multiple QQQ web nodes, one configured Redis endpoint, host-provided authentication/tenant scope, HTTP lease writes, SSE browser delivery and Redis Pub/Sub invalidation. Presence is advisory; database version checks protect saves.

Optional future work remains QQQ #1300 (loss/restore), #1301 (failover topology), #1302 (sustained scale/failure injection), #1304 (Windows coverage-summary display portability), and #1305 (Windows filesystem qualification). No transparent disaster-recovery guarantee is part of this snapshot. If the normal presence/admin flow fails in staging, stop rollout and retain the prior coordinated frontend/backend set; do not downgrade only one side of the lease protocol.

## Candidate publication configuration

Redis uses orb0.7.18; session-store is aligned to0.7.18 because0.7.12 does not support QBit feature publication. Collaboration retains its current0.8 track. All three manifests explicitly enable feature publication, and setup tag filters accept publish-prefixed candidate tags alongside existing stable tags. Authenticated CircleCI config validation passes for all three. Exact0.7.18 manifest loading and feature-workflow rendering pass for Redis/session-store; current0.8 source confirms the same feature path. No tag or artifact is created by configuration preparation. Pin actual core/Redis coordinates only after publication proves availability.
