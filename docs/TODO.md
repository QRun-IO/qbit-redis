# Main-line snapshot complete

The owner narrowed this milestone to the main-line happy path. Published versions, evidence and operating requirements are in [SNAPSHOT-RELEASE.md](SNAPSHOT-RELEASE.md).

- [x] Named bounded Redis connections/cache primitives, framework health and admin metrics.
- [x] Shared presence across two JVMs, viewer/editor focus, table projection, departures and access filtering.
- [x] Owned shared session-store integration, application isolation and launcher cleanup.
- [x] Cache invalidation, OAuth cache and JDBC provider isolation repairs.
- [x] Atomic versioned saves and frontend stale-save review/retry behavior.
- [x] Full affected verification, downstream CI and four backend snapshot publishers.
- [x] Remote JAR/POM receipts and fresh-cache standalone consumer with aligned dependency graph.
- [x] Published-artifact browser presence/admin/framework health acceptance using the tested frontend export.
- [x] Release/migration documentation and QQQ Project12 issue reconciliation.

## Future work retained in QQQ Project12

- #1315: host inventory and coordinated deployment. Required before each host adopts the candidate; no production rollout has occurred.
- #1289: complete real-DB attachment rollback/crash/retry qualification.
- #1196: selectable virtual tag/EAV projection.
- #1291/#1302: My Work budgets, measured capacity and sustained failure/load qualification.
- #1300/#1301: data loss/restore and additional Redis failover topologies.
- #1316: additional standalone/ingress/CDN/reverse-proxy SSE configurations.
- #1317: additional admin management/aggregation, client-library boundary and credential rotation/mTLS qualification.
- #1304/#1305/#1307/#1309/#1310/#1312/#1314: tooling, CI, layout and test-dependency maintenance.

All issues are in https://github.com/orgs/QRun-IO/projects/12 and carry the relevant QBit labels. No future scenario above is declared tested merely because the normal flow passes. Worktrees and optional research files remain preserved.
