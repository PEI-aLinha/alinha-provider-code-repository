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
./gradlew :code-repository:test :app:test              # plugin tests + module verification
pnpm --filter @alinha/plugin-code-repository typecheck
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
