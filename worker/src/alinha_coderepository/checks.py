"""What the collector reports: one outcome per (repository, check). Counts and flags only, never content."""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class Check(str, Enum):
    BRANCH_PROTECTION = "branch_protection"
    REQUIRED_REVIEWS = "required_reviews"
    DEPENDENCY_ALERTS = "dependency_alerts"
    SECRET_SCANNING = "secret_scanning"


class Outcome(str, Enum):
    PASS = "PASS"
    FAIL = "FAIL"
    # The token cannot see it (missing permission, feature not available on the plan). Never reported as FAIL.
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True)
class CheckResult:
    check: Check
    outcome: Outcome
    details: dict[str, bool | int | str] = field(default_factory=dict)

    def to_json(self) -> dict[str, Any]:
        return {"check": self.check.value, "outcome": self.outcome.value, "details": dict(self.details)}


@dataclass(frozen=True)
class RepositoryResult:
    repository: str
    default_branch: str | None
    checks: tuple[CheckResult, ...]

    def to_json(self) -> dict[str, Any]:
        return {
            "repository": self.repository,
            "defaultBranch": self.default_branch,
            "checks": [check.to_json() for check in self.checks],
        }


def unknown_for_all(reason: str) -> tuple[CheckResult, ...]:
    return tuple(CheckResult(check, Outcome.UNKNOWN, {"reason": reason}) for check in Check)
