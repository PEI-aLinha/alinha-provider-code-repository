"""GitHub (github.com) with a fine-grained personal access token, read-only.

Token permissions (all "Read-only"): Metadata, Administration, Dependabot alerts, Secret scanning alerts.
A missing permission makes the matching check UNKNOWN, never FAIL.
"""
from __future__ import annotations

import re
from typing import Any, Mapping
from urllib.parse import quote

from alinha_job_consumer.collectors import CollectorError, TransientError

from ..checks import Check, CheckResult, Outcome, RepositoryResult, unknown_for_all
from ..transport import Get, Response

API = "https://api.github.com"
_REPOSITORY = re.compile(r"[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
_ACCOUNT = re.compile(r"[A-Za-z0-9-]{1,39}")
# Severities that make dependency_alerts FAIL while they are open.
_BLOCKING_SEVERITIES = "critical,high"
_PAGE = 100


class GitHub:
    NAME = "github"

    def __init__(self, credential: Mapping[str, str], get: Get) -> None:
        token = credential.get("token", "")
        if not token:
            raise CollectorError("invalid_credentials", "no token is stored for this connection")
        self._get = get
        self._headers = {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "alinha-code-repository",
        }

    # -- RepositoryHost -------------------------------------------------------------------------------------------

    def repositories(self, account: str, limit: int) -> list[str]:
        if not _ACCOUNT.fullmatch(account):
            raise CollectorError("invalid_scope", "the account is not a GitHub user or organisation name")
        found: list[str] = []
        page = 1
        while len(found) < limit:
            answer = self._call(f"/user/repos?per_page={_PAGE}&page={page}&sort=full_name")
            items = answer.json() if answer.status == 200 else None
            if not isinstance(items, list):
                raise CollectorError("no_repositories", "the token cannot list repositories")
            for item in items:
                name = item.get("full_name") if isinstance(item, dict) else None
                owner = name.split("/", 1)[0] if isinstance(name, str) else None
                if owner and owner.lower() == account.lower() and not item.get("archived"):
                    found.append(name)
            if len(items) < _PAGE:
                break
            page += 1
        return found[:limit]

    def inspect(self, repository: str) -> RepositoryResult:
        if not _REPOSITORY.fullmatch(repository) or ".." in repository:
            return RepositoryResult(repository[:201], None, unknown_for_all("invalid_name"))
        path = f"/repos/{repository}"
        answer = self._call(path)
        meta = answer.json() if answer.status == 200 else None
        if not isinstance(meta, dict):
            return RepositoryResult(repository, None, unknown_for_all("not_found"))
        branch = meta.get("default_branch")
        if not isinstance(branch, str) or not branch:
            return RepositoryResult(repository, None, unknown_for_all("no_default_branch"))
        protection = self._protection(path, branch)
        return RepositoryResult(
            repository,
            branch,
            (
                protection[0],
                protection[1],
                self._dependency_alerts(path),
                self._secret_scanning(path, meta),
            ),
        )

    # -- checks ---------------------------------------------------------------------------------------------------

    def _protection(self, path: str, branch: str) -> tuple[CheckResult, CheckResult]:
        encoded = quote(branch, safe="")
        # Rulesets need only Metadata: read, so they work even when classic protection is hidden.
        rules = self._call(f"{path}/rules/branches/{encoded}")
        rule_list = rules.json() if rules.status == 200 else None
        rule_types = {r.get("type") for r in rule_list if isinstance(r, dict)} if isinstance(rule_list, list) else set()
        ruleset_reviews = max(
            (_int(r.get("parameters", {}).get("required_approving_review_count"))
             for r in rule_list or [] if isinstance(r, dict) and r.get("type") == "pull_request"
             and isinstance(r.get("parameters"), dict)),
            default=0,
        )

        classic = self._call(f"{path}/branches/{encoded}/protection")
        classic_body = classic.json() if classic.status == 200 else None
        classic_known = classic.status in (200, 404)  # 404: "Branch not protected"; 403: no Administration: read
        classic_reviews = 0
        if isinstance(classic_body, dict):
            reviews = classic_body.get("required_pull_request_reviews")
            if isinstance(reviews, dict):
                classic_reviews = _int(reviews.get("required_approving_review_count"))

        # Only rules that stop a direct push count: `deletion` or `non_fast_forward` alone still let anyone push.
        protected_by_rules = bool(rule_types & {"pull_request", "update"})
        protected = classic.status == 200 or protected_by_rules
        if protected:
            protection = CheckResult(Check.BRANCH_PROTECTION, Outcome.PASS,
                                     {"classic": classic.status == 200, "rulesets": protected_by_rules})
        elif classic_known and rules.status == 200:
            protection = CheckResult(Check.BRANCH_PROTECTION, Outcome.FAIL, {"classic": False, "rulesets": False})
        else:
            protection = CheckResult(Check.BRANCH_PROTECTION, Outcome.UNKNOWN, {"reason": "no_permission"})

        approvals = max(classic_reviews, ruleset_reviews)
        if approvals >= 1:
            reviews_result = CheckResult(Check.REQUIRED_REVIEWS, Outcome.PASS, {"requiredApprovals": approvals})
        elif classic_known and rules.status == 200:
            reviews_result = CheckResult(Check.REQUIRED_REVIEWS, Outcome.FAIL, {"requiredApprovals": 0})
        else:
            reviews_result = CheckResult(Check.REQUIRED_REVIEWS, Outcome.UNKNOWN, {"reason": "no_permission"})
        return protection, reviews_result

    def _dependency_alerts(self, path: str) -> CheckResult:
        enabled = self._call(f"{path}/vulnerability-alerts")  # 204 enabled, 404 disabled, 403 no permission
        if enabled.status == 404:
            return CheckResult(Check.DEPENDENCY_ALERTS, Outcome.FAIL, {"enabled": False})
        if enabled.status != 204:
            return CheckResult(Check.DEPENDENCY_ALERTS, Outcome.UNKNOWN, {"reason": "no_permission"})
        alerts = self._call(f"{path}/dependabot/alerts?state=open&severity={_BLOCKING_SEVERITIES}&per_page={_PAGE}")
        items = alerts.json() if alerts.status == 200 else None
        if not isinstance(items, list):
            return CheckResult(Check.DEPENDENCY_ALERTS, Outcome.UNKNOWN, {"enabled": True, "reason": "no_permission"})
        open_blocking = len(items)
        outcome = Outcome.PASS if open_blocking == 0 else Outcome.FAIL
        return CheckResult(Check.DEPENDENCY_ALERTS, outcome,
                           {"enabled": True, "openCriticalOrHigh": open_blocking, "capped": open_blocking >= _PAGE})

    def _secret_scanning(self, path: str, meta: dict[str, Any]) -> CheckResult:
        analysis = meta.get("security_and_analysis")
        if not isinstance(analysis, dict):  # hidden without Administration: read
            return CheckResult(Check.SECRET_SCANNING, Outcome.UNKNOWN, {"reason": "no_permission"})
        scanning = _status(analysis, "secret_scanning")
        push_protection = _status(analysis, "secret_scanning_push_protection")
        if scanning != "enabled":
            return CheckResult(Check.SECRET_SCANNING, Outcome.FAIL,
                               {"enabled": False, "pushProtection": push_protection == "enabled"})
        alerts = self._call(f"{path}/secret-scanning/alerts?state=open&per_page={_PAGE}")
        items = alerts.json() if alerts.status == 200 else None
        if not isinstance(items, list):
            return CheckResult(Check.SECRET_SCANNING, Outcome.UNKNOWN,
                               {"enabled": True, "pushProtection": push_protection == "enabled",
                                "reason": "no_permission"})
        outcome = Outcome.PASS if not items else Outcome.FAIL
        return CheckResult(Check.SECRET_SCANNING, outcome,
                           {"enabled": True, "pushProtection": push_protection == "enabled",
                            "openAlerts": len(items), "capped": len(items) >= _PAGE})

    # -- HTTP -----------------------------------------------------------------------------------------------------

    def _call(self, path: str) -> Response:
        answer = self._get(f"{API}{path}", self._headers)
        if answer.status == 401:
            raise CollectorError("invalid_credentials", "GitHub refused the token: it may be expired or revoked")
        if answer.status == 429 or answer.status >= 500 or _rate_limited(answer):
            raise TransientError(f"GitHub answered {answer.status}")
        return answer


def _rate_limited(answer: Response) -> bool:
    return answer.status == 403 and answer.headers.get("x-ratelimit-remaining") == "0"


def _status(analysis: dict[str, Any], key: str) -> str | None:
    value = analysis.get(key)
    return value.get("status") if isinstance(value, dict) else None


def _int(value: Any) -> int:
    return value if isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= 100 else 0
