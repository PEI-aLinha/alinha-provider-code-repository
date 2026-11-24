"""GitLab (gitlab.com or a self-managed instance) with a personal, group or project access token, read-only.

Token scope: `read_api`, role Reporter or higher (Maintainer to see protected branch settings).
Approval rules need GitLab Premium and the security scanners need Ultimate: on a lower plan those checks are UNKNOWN,
never FAIL. Security data comes from GraphQL (the REST vulnerability APIs are deprecated), sent as a GET: providers
are read-only.
"""
from __future__ import annotations

import json
import re
from typing import Any, Mapping
from urllib.parse import quote, urlencode

from alinha_job_consumer.collectors import CollectorError, TransientError

from ..checks import Check, CheckResult, Outcome, RepositoryResult, unknown_for_all
from ..transport import Get, Response

CLOUD = "https://gitlab.com"
# A group (with subgroups) or a user namespace; the project path adds one more segment.
_SEGMENT = r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}"
_ACCOUNT = re.compile(rf"{_SEGMENT}(?:/{_SEGMENT}){{0,19}}")
_PAGE = 100
# GitLab access levels: 0 no one, 30 developer, 40 maintainer, 60 admin.
_DEVELOPER = 30
_SCANNERS_QUERY = """query($path: ID!) { project(fullPath: $path) {
  securityScanners { enabled }
  dependencies: vulnerabilitySeveritiesCount(reportType: [DEPENDENCY_SCANNING], state: [DETECTED, CONFIRMED]) { critical high }
  secrets: vulnerabilitySeveritiesCount(reportType: [SECRET_DETECTION], state: [DETECTED, CONFIRMED]) { critical high medium low unknown info }
} }"""


class GitLab:
    NAME = "gitlab"

    def __init__(self, credential: Mapping[str, str], get: Get, base_url: str = CLOUD) -> None:
        token = credential.get("token", "")
        if not token:
            raise CollectorError("invalid_credentials", "no token is stored for this connection")
        self._get = get
        self._base = base_url.rstrip("/")
        self._headers = {"PRIVATE-TOKEN": token, "Accept": "application/json", "User-Agent": "alinha-code-repository"}

    # -- RepositoryHost -------------------------------------------------------------------------------------------

    def repositories(self, account: str, limit: int) -> list[str]:
        if not _ACCOUNT.fullmatch(account) or ".." in account:
            raise CollectorError("invalid_scope", "the account is not a GitLab group or user path")
        encoded = quote(account, safe="")
        query = f"per_page={_PAGE}&archived=false&order_by=path&sort=asc&simple=true"
        first = self._call(f"/groups/{encoded}/projects?include_subgroups=true&{query}&page=1")
        base = f"/groups/{encoded}/projects?include_subgroups=true&{query}"
        if first.status == 404:  # not a group: a user's own namespace
            base = f"/users/{encoded}/projects?{query}"
            first = self._call(f"{base}&page=1")
        found: list[str] = []
        answer, page = first, 1
        while True:
            items = answer.json() if answer.status == 200 else None
            if not isinstance(items, list):
                raise CollectorError("no_repositories", "the token cannot list the account's projects")
            for item in items:
                path = item.get("path_with_namespace") if isinstance(item, dict) else None
                if isinstance(path, str) and path.lower().startswith(account.lower() + "/"):
                    found.append(path)
            if len(found) >= limit or len(items) < _PAGE:
                return found[:limit]
            page += 1
            answer = self._call(f"{base}&page={page}")

    def inspect(self, repository: str) -> RepositoryResult:
        if not _ACCOUNT.fullmatch(repository) or "/" not in repository or ".." in repository:
            return RepositoryResult(repository[:201], None, unknown_for_all("invalid_name"))
        path = f"/projects/{quote(repository, safe='')}"
        answer = self._call(path)
        meta = answer.json() if answer.status == 200 else None
        if not isinstance(meta, dict):
            return RepositoryResult(repository, None, unknown_for_all("not_found"))
        branch = meta.get("default_branch")
        if not isinstance(branch, str) or not branch:
            return RepositoryResult(repository, None, unknown_for_all("no_default_branch"))
        security = self._security(repository)
        return RepositoryResult(
            repository,
            branch,
            (
                self._protection(path, branch),
                self._reviews(path, branch),
                _scanner(Check.DEPENDENCY_ALERTS, security, "DEPENDENCY_SCANNING", "dependencies", ("critical", "high")),
                _scanner(Check.SECRET_SCANNING, security, "SECRET_DETECTION", "secrets",
                         ("critical", "high", "medium", "low", "unknown", "info")),
            ),
        )

    # -- checks ---------------------------------------------------------------------------------------------------

    def _protection(self, path: str, branch: str) -> CheckResult:
        answer = self._call(f"{path}/protected_branches/{quote(branch, safe='')}")
        if answer.status == 404:
            return CheckResult(Check.BRANCH_PROTECTION, Outcome.FAIL, {"protected": False})
        body = answer.json() if answer.status == 200 else None
        if not isinstance(body, dict):
            return CheckResult(Check.BRANCH_PROTECTION, Outcome.UNKNOWN, {"reason": "no_permission"})
        levels = [_int(level.get("access_level")) for level in body.get("push_access_levels") or []
                  if isinstance(level, dict)]
        # Protected, but developers may still push straight to it: not a protection that forces a review.
        developers_push = any(0 < level <= _DEVELOPER for level in levels)
        force_push = body.get("allow_force_push") is True
        outcome = Outcome.FAIL if developers_push or force_push else Outcome.PASS
        return CheckResult(Check.BRANCH_PROTECTION, outcome,
                           {"protected": True, "developersCanPush": developers_push, "forcePush": force_push})

    def _reviews(self, path: str, branch: str) -> CheckResult:
        answer = self._call(f"{path}/approval_rules?per_page={_PAGE}")
        rules = answer.json() if answer.status == 200 else None
        if not isinstance(rules, list):  # 403/404: Free plan, or no permission
            return CheckResult(Check.REQUIRED_REVIEWS, Outcome.UNKNOWN, {"reason": "not_available"})
        required = max(
            (_int(rule.get("approvals_required")) for rule in rules
             if isinstance(rule, dict) and _applies_to(rule, branch)),
            default=0,
        )
        outcome = Outcome.PASS if required >= 1 else Outcome.FAIL
        return CheckResult(Check.REQUIRED_REVIEWS, outcome, {"requiredApprovals": required})

    def _security(self, repository: str) -> dict[str, Any] | None:
        query = urlencode({"query": _SCANNERS_QUERY, "variables": json.dumps({"path": repository})})
        answer = self._call(f"/graphql?{query}", api="/api")
        body = answer.json() if answer.status == 200 else None
        if not isinstance(body, dict) or body.get("errors"):
            return None
        project = (body.get("data") or {}).get("project")
        return project if isinstance(project, dict) else None

    # -- HTTP -----------------------------------------------------------------------------------------------------

    def _call(self, path: str, api: str = "/api/v4") -> Response:
        answer = self._get(f"{self._base}{api}{path}", self._headers)
        if answer.status == 401:
            raise CollectorError("invalid_credentials", "GitLab refused the token: it may be expired or revoked")
        if answer.status == 429 or answer.status >= 500:
            raise TransientError(f"GitLab answered {answer.status}")
        return answer


def _applies_to(rule: dict[str, Any], branch: str) -> bool:
    if rule.get("rule_type") == "report_approver":  # security approvals, not code review
        return False
    branches = rule.get("protected_branches")
    if not branches or rule.get("applies_to_all_protected_branches"):
        return True
    return any(isinstance(b, dict) and b.get("name") == branch for b in branches)


def _scanner(check: Check, project: dict[str, Any] | None, scanner: str, counts_key: str,
             severities: tuple[str, ...]) -> CheckResult:
    scanners = project.get("securityScanners") if project else None
    enabled = scanners.get("enabled") if isinstance(scanners, dict) else None
    if not isinstance(enabled, list):  # Ultimate only, or no permission
        return CheckResult(check, Outcome.UNKNOWN, {"reason": "not_available"})
    if scanner not in enabled:
        return CheckResult(check, Outcome.FAIL, {"enabled": False})
    counts = project.get(counts_key) if project else None
    if not isinstance(counts, dict):
        return CheckResult(check, Outcome.UNKNOWN, {"enabled": True, "reason": "no_permission"})
    open_count = sum(_int(counts.get(severity), cap=100_000) for severity in severities)
    return CheckResult(check, Outcome.PASS if open_count == 0 else Outcome.FAIL,
                       {"enabled": True, "openFindings": open_count})


def _int(value: Any, cap: int = 100) -> int:
    return value if isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= cap else 0
