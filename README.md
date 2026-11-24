# Code Repository

**provider** plugin of [aLinha](https://github.com/PEI-aLinha/alinha). It lives in the main repo as a
submodule at `plugins/provider/code-repository` and is built together with the core into a single deployable.

```
backend/    Kotlin — Spring Modulith module pt.alinha.coderepository
  api/        «api» contract (the only synchronous access for other plugins)
  events/     published events
  internal/   everything else — private
  resources/db/migration/coderepository/   Flyway migrations (schema coderepository)
frontend/   React — @alinha/plugin-code-repository (routes + menu, read by the shell)
worker/     Python — collector (providers only)
plugin.yaml manifest
```

## What it does (alinha-provider-code-repository#1)

Reads, with a **read-only** token, the security settings of the organization's repositories and stores them as
evidence for NIS2/QNRCS controls (strength `TECHNICALLY_VERIFIED`). It never reads the code.

1. **Connect** (`PUT /api/code-repository/connection`, permission `code-repository:connect`: responsible, analyst,
   consultant). The token goes to OpenBao through `TenantSecretStore` (`secret/tenants/<tenant>/providers/code-repository`),
   never to the database. An `evidence.collect` job is queued with `connectionRef = providers/code-repository`.
2. **Collect** (`worker/`, runs in the main repo's job consumer). Reads the token with
   `alinha_job_consumer.credentials.provider_secret`, then for each repository (at most 20) checks:

   | Check | GitHub source | Controls (mapping v1) |
   |---|---|---|
   | `branch_protection` | classic branch protection or rulesets on the default branch | PR.SP-4-S, ID.GA-9-E |
   | `required_reviews` | required approving reviews ≥ 1 | PR.SP-4-S, PR.SP-4-E |
   | `dependency_alerts` | Dependabot alerts enabled, no open critical/high | ID.AR-1-B, ID.AR-7-B, ID.AR-7-E |
   | `secret_scanning` | secret scanning enabled, no open alerts | PR.SP-4-S, PR.SP-4-E |

   Each outcome is `PASS`, `FAIL` or `UNKNOWN` (the token cannot see it: never reported as `FAIL`). Counts and
   flags only. A revoked token is a final `invalid_credentials`; rate limits and 5xx are retried.
3. **Store** (`CollectionResultListener`). The untrusted result is validated (`CollectionResultParser`), mapped to
   controls (`ControlMapping`, versioned; moves into the catalogue with alinha#29), stored in
   `coderepository.evidence`, audited, and one `EvidenceCollected` (plugin-api) is published per `PASS`/`FAIL` row.

GitHub token: a fine-grained personal access token with **Read-only** on Metadata, Administration, Dependabot alerts
and Secret scanning alerts. GitLab and Azure DevOps fit behind `worker/.../hosts/base.py` (`RepositoryHost`).

## Development

This repo **does not build on its own**. Work inside a checkout of the main repo:

```bash
git clone --recurse-submodules git@github.com:PEI-aLinha/alinha.git
cd alinha/plugins/provider/code-repository
git switch -c feat/<topic>
# ... code ...
git push -u origin feat/<topic>      # PR in this repo
```

Run the tests (from the main repo root):

```bash
./gradlew :code-repository:test :app:test              # plugin tests + module verification (needs Docker)
pnpm --filter @alinha/plugin-code-repository typecheck
pnpm --filter @alinha/plugin-code-repository test
(cd plugins/provider/code-repository/worker && PYTHONPATH=src pytest)   # uses ../../../../workers/job_consumer/src
```

## Rules

- Code, comments and commits in English; user-visible UI text in Portuguese.
- Backend: only use `pt.alinha.pluginapi`, the core's named interfaces (`core.tenant`, `core.access`,
  `core.audit`, `core.jobs`, `core.compliance`, `core.aigateway`) and other plugins' `api`/`events`.
- Frontend: only import `@alinha/plugin-sdk`, `@alinha/ui`, `@alinha/api-client`.
- Migrations: `V<yyyyMMddHHmmss>__<description>.sql` (the timestamp avoids collisions between plugins).
- Keep `plugin.yaml` and `*Plugin.kt` in sync (published/consumed events, permissions).

## Definition of Done

- [ ] Endpoints have `@RequiresPermission`
- [ ] Writes go to the Audit Trail in the same transaction
- [ ] Queries are filtered by `TenantContext`
- [ ] Tests pass and CI is green (including the main repo's `ModularityTest`)
