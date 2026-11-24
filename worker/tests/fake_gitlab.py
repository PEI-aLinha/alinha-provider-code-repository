"""A scripted GitLab: maps request paths (after the server address) to (status, json body). Unlisted: 404."""
from __future__ import annotations

import json
from typing import Any, Mapping
from urllib.parse import parse_qs, urlsplit

from alinha_coderepository.transport import Response


class FakeGitLab:
    def __init__(self, routes: dict[str, tuple[int, Any]] | None = None, base: str = "https://gitlab.com",
                 graphql: tuple[int, Any] | None = None):
        self.routes = dict(routes or {})
        self.base = base
        self.graphql = graphql
        self.calls: list[tuple[str, Mapping[str, str]]] = []

    def __call__(self, url: str, headers: Mapping[str, str]) -> Response:
        self.calls.append((url, headers))
        assert url.startswith(self.base), url
        path = url[len(self.base):]
        if path.startswith("/api/graphql?"):
            query = parse_qs(urlsplit(path).query)
            assert "query" in query and "variables" in query
            status, body = self.graphql or (404, None)
        else:
            status, body = self.routes.get(path, (404, {"message": "404 Not Found"}))
        return Response(status, {}, b"" if body is None else json.dumps(body).encode())


def security(dependency=True, secrets=True, high=0, secret_findings=0) -> dict[str, Any]:
    enabled = [name for name, on in (("DEPENDENCY_SCANNING", dependency), ("SECRET_DETECTION", secrets)) if on]
    return {"data": {"project": {
        "securityScanners": {"enabled": enabled},
        "dependencies": {"critical": 0, "high": high},
        "secrets": {"critical": secret_findings, "high": 0, "medium": 0, "low": 0, "unknown": 0, "info": 0},
    }}}


def protected_project(path: str = "acme/api", branch: str = "main", approvals: int = 1,
                      push_levels: tuple[int, ...] = (40,), force_push: bool = False) -> dict[str, tuple[int, Any]]:
    encoded = path.replace("/", "%2F")
    base = f"/api/v4/projects/{encoded}"
    return {
        base: (200, {"path_with_namespace": path, "default_branch": branch}),
        f"{base}/protected_branches?per_page=100": (200, [{
            "name": branch,
            "push_access_levels": [{"access_level": level} for level in push_levels],
            "allow_force_push": force_push,
        }]),
        f"{base}/approval_rules?per_page=100": (200, [
            {"rule_type": "regular", "approvals_required": approvals, "protected_branches": []},
        ]),
    }
