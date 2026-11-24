"""Collector for the Code Repository provider (read-only).

Receives a validated `evidence.collect` job (contracts/jobs/evidence-collect.v1.schema.json in the main repo) with
`scope = {"host": "github", "account": "<owner>", "repositories": ["<owner>/<name>", ...]}`, reads the tenant's
credential from OpenBao and returns one outcome per (repository, check). Counts and flags only: no code, no alert
content, no credential. The Kotlin side validates this result again, maps it to controls and stores the evidence.
"""
from __future__ import annotations

from typing import Any, Callable, Mapping

from alinha_job_consumer.collectors import CollectorError
from alinha_job_consumer.credentials import provider_secret

from .hosts import RepositoryHost, host_for
from .transport import Get, urllib_get

PROVIDER_ID = "code-repository"
RESULT_SCHEMA = "code-repository.result.v1"
# A collector run has WORKER_COLLECTOR_TIMEOUT_S (50 s by default) and each repository costs about six calls.
MAX_REPOSITORIES = 20

ReadSecret = Callable[[dict[str, Any]], Mapping[str, str]]


def collect(
    job: dict[str, Any], read_secret: ReadSecret = provider_secret, get: Get = urllib_get
) -> dict[str, Any]:
    if job.get("provider") != PROVIDER_ID:
        raise CollectorError("unknown_provider", "this collector only handles code-repository jobs")
    scope = job.get("scope")
    if not isinstance(scope, dict):
        raise CollectorError("invalid_scope", "the job has no scope")
    host_name, account, requested = _scope(scope)

    host: RepositoryHost | None = host_for(host_name, read_secret(job), get)
    if host is None:
        raise CollectorError("unsupported_host", "this repository platform is not supported yet")

    repositories = requested or host.repositories(account, MAX_REPOSITORIES + 1)
    if not repositories:
        # A typo in the account, or a token scoped to no repository: never report an empty, healthy connection.
        raise CollectorError("no_repositories", "the token sees no repository of this account")
    truncated = len(repositories) > MAX_REPOSITORIES
    inspected = [host.inspect(name).to_json() for name in repositories[:MAX_REPOSITORIES]]
    return {
        "schema": RESULT_SCHEMA,
        "host": host_name,
        "account": account,
        "repositories": inspected,
        "truncated": truncated,
    }


def _scope(scope: dict[str, Any]) -> tuple[str, str, list[str]]:
    host_name = scope.get("host")
    account = scope.get("account")
    repositories = scope.get("repositories", [])
    if not isinstance(host_name, str) or not isinstance(account, str) or not account:
        raise CollectorError("invalid_scope", "the scope needs a host and an account")
    if not isinstance(repositories, list) or not all(isinstance(r, str) for r in repositories):
        raise CollectorError("invalid_scope", "repositories must be a list of owner/name")
    return host_name, account, list(dict.fromkeys(repositories))
