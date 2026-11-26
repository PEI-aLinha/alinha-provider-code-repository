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

Reads, **read-only**, the security settings of the organization's repositories on GitHub or GitLab and stores them
as evidence for NIS2/QNRCS controls. It never reads the code.

1. **Connect** (permission `code-repository:connect`: responsible, analyst, consultant), in one of two ways:
   - **GitHub App** (recommended when configured, see the main repo's `deploy/DEPLOYMENT.md`, "GitHub App"):
     `POST /api/code-repository/github-app/installations` returns GitHub's install page with a single-use `state`
     bound to the tenant. GitHub returns to the shell's `/resume`, which sends the person back to this page;
     `PUT .../github-app/installation` checks the state, then exchanges the OAuth `code` and accepts the installation
     only if GitHub lists it for that person. Only the installation id is stored.
   - **Token** (`PUT /api/code-repository/connection`): GitHub fine-grained token, or GitLab (gitlab.com or a
     self-managed `https://host[:port]`) token with `read_api`. Kept in OpenBao through `TenantSecretStore`
     (`secret/tenants/<tenant>/providers/code-repository`), never in the database.

   An `evidence.collect` job is queued with `connectionRef = providers/code-repository`, and the tenant gets a daily
   schedule (`collection_schedule`).
2. **Ask someone else** (`PUT /api/code-repository/assignment`, permission `code-repository:assign`: responsible,
   consultant): saves the address and sends an email through the notifications plugin (`EmailService`, template
   `email/templates/code-repository-assigned.html` in this plugin). The page shows "Atribuído a …".
3. **Collect** (`worker/`, runs in the main repo's job consumer). Reads the tenant credential with
   `provider_secret`; for the App, mints a read-only installation token with the App key from
   `platform_secret` (`secret/providers/code-repository`). A self-managed GitLab is only called at a public address,
   pinned after one DNS lookup, without redirects. Then for each repository (at most 20):

   | Check | GitHub | GitLab | Controls (mapping v1) |
   |---|---|---|---|
   | `branch_protection` | classic protection or push rulesets on the default branch | protected default branch, developers cannot push, no force push | PR.SP-4-S, ID.GA-9-E |
   | `required_reviews` | required approving reviews ≥ 1 | approval rules ≥ 1 (Premium) | PR.SP-4-S, PR.SP-4-E |
   | `dependency_alerts` | Dependabot alerts on, no open critical/high | dependency scanning on, no open critical/high (Ultimate) | ID.AR-1-B, ID.AR-7-B, ID.AR-7-E |
   | `secret_scanning` | secret scanning on, no open alerts | secret detection on, no open findings (Ultimate) | PR.SP-4-S, PR.SP-4-E |

   Each outcome is `PASS`, `FAIL` or `UNKNOWN` (not visible to the credential, or not in the plan: never `FAIL`).
   Counts and flags only. A refused credential is a final error; rate limits and 5xx are retried.
4. **Store** (`CollectionResultListener`). The untrusted result is validated (`CollectionResultParser`), mapped to
   controls (`ControlMapping`, versioned; moves into the catalogue with alinha#29), stored in
   `coderepository.evidence`, audited, and one `EvidenceCollected` (plugin-api) is published per `PASS`/`FAIL` row.
   Strength: `CONTINUOUSLY_VERIFIED` when the daily collection found it and the previous one succeeded within 26
   hours; otherwise `TECHNICALLY_VERIFIED`.
5. **Every day** (`CollectionScheduler`, every 5 minutes it looks for due tenants). The schedule table is the one
   table here that is not tenant-filtered: it holds only a tenant id and a time, and each collection then runs inside
   `TenantContext.runAs`. Disconnecting removes it.

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
