# Coordinated published snapshot

The main-line milestone is complete. These feature snapshots are published and verified together; this is not a stable/GA release or a production deployment.

| Component | Version |
| --- | --- |
| QQQ BOM/modules | `4.1.0-collab-33464d37e-SNAPSHOT` |
| qbit-redis | `0.1.0-redis-7fe5492-SNAPSHOT` |
| qbit-collaboration | `0.1.0-collab-8f348da-SNAPSHOT` |
| qbit-session-store | `0.1.0-session-b5276ab-SNAPSHOT` |
| Next frontend source | `8d372142f8af0c41a75a353edc6d150c33ccf613` |

Use the public Maven snapshot repository https://central.sonatype.com/repository/maven-snapshots/ and import the candidate QQQ BOM. All four publishers succeeded. Independent JAR/POM downloads and a standalone consumer using an initially empty Maven cache passed; all five QQQ modules align and only Jedis7.2.0 is resolved.

## Verification

Redis23, collaboration177 and session-store96 tests passed against published prerequisites, with zero failures/errors/skips and configured coverage gates passing. Core verification4209 and frontend acceptance39683690943 passed. Frontend local3664 tests and TypeScript passed. The frontend acceptance run contains the tested export; no dashboard container image was published by that run.

Final browser checks used the downloaded published JARs, exact CI frontend export, two independent JVMs and one Redis endpoint. Cross-node avatars, self exclusion, editor focus, preserved drafts, table presence and departure passed. Redis admin metrics, subscription state, persisted framework UP health history and health-record navigation passed. The fixtures use mock users and independent memory records; database concurrency and authentication have separate core/frontend tests. Test-owned processes were stopped afterward.

Exact release receipts and evidence: [QQQ #1293](https://github.com/QRun-IO/qqq/issues/1293#issuecomment-6329618640). Host integration and migration guide: [coordinated candidate](https://github.com/QRun-IO/qbit-collaboration/blob/c3182e2/docs/CANDIDATE-RELEASE.md).

## Supported milestone

Multiple QQQ web nodes share one configured Redis endpoint. Collaboration owns presence semantics; Redis owns connection/cache infrastructure; session-store retains session semantics. Browser delivery uses SSE plus HTTP lease writes, and Redis Pub/Sub carries invalidation hints. Configure node affinity for SSE subscription updates. Shared Redis does not make connection IDs portable. Presence is advisory; versioned database compare-and-write protects saves.

Before host adoption, configure authoritative identity/tenant scope, unique deployment/application namespaces, TLS/authentication, bounded pools/timeouts, explicit QInstance session registration, owner-scoped shutdown, and schema migrations. Upgrade the frontend/backend lease protocol together. Existing hosts are not automatically migrated by library publication; rollout is tracked in [#1315](https://github.com/QRun-IO/qqq/issues/1315).

## Optional future work

The owner's main-line-only scope leaves attachment failure qualification #1289, capacity/My Work failure qualification #1291, selectable tag/EAV projection #1196, data loss/restore #1300, additional Redis topologies #1301, sustained scale #1302, additional proxy deployments #1316 and admin/client coupling enhancements #1317 open. Tooling/dependency maintenance remains #1304/#1305/#1307/#1309/#1310/#1312/#1314. No measured production capacity or disaster-recovery guarantee is claimed.

## Publication lessons

Use short feature branch names: the help bundle version limit is 50 characters. The first long core candidate failed before publication; unchanged source passed with the short coordinate above. Preserve original worktree branches and failed tags. Feature-enabled QBit setup must use the canonical `/publish.*/` tag filter; schema validation alone does not check that contract. Redis/session-store use orb0.7.18; collaboration uses the managed0.8 track. Pin actual published prerequisite versions before downstream publication and verify remote resolution afterward.
