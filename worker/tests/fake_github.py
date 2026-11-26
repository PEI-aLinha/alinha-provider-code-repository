"""A scripted GitHub: maps request paths to (status, json body). Unlisted paths answer 404."""
from __future__ import annotations

import json
from typing import Any, Mapping

from alinha_coderepository.transport import Response

API = "https://api.github.com"


class FakeGitHub:
    def __init__(self, routes: dict[str, tuple[int, Any]] | None = None, headers: dict[str, str] | None = None):
        self.routes = dict(routes or {})
        self.headers = headers or {}
        self.calls: list[tuple[str, Mapping[str, str]]] = []

    def __call__(self, url: str, headers: Mapping[str, str]) -> Response:
        self.calls.append((url, headers))
        assert url.startswith(API), url
        status, body = self.routes.get(url[len(API):], (404, {"message": "Not Found"}))
        raw = b"" if body is None else json.dumps(body).encode()
        return Response(status, self.headers, raw)


def protected_repo(repo: str = "acme/api", branch: str = "main", approvals: int = 1) -> dict[str, tuple[int, Any]]:
    """A repository where every check passes."""
    base = f"/repos/{repo}"
    return {
        base: (200, {
            "full_name": repo,
            "default_branch": branch,
            "security_and_analysis": {
                "secret_scanning": {"status": "enabled"},
                "secret_scanning_push_protection": {"status": "enabled"},
            },
        }),
        f"{base}/rules/branches/{branch}": (200, []),
        f"{base}/branches/{branch}/protection": (200, {
            "required_pull_request_reviews": {"required_approving_review_count": approvals},
        }),
        f"{base}/vulnerability-alerts": (204, None),
        f"{base}/dependabot/alerts?state=open&severity=critical,high&per_page=100": (200, []),
        f"{base}/secret-scanning/alerts?state=open&per_page=100": (200, []),
    }
