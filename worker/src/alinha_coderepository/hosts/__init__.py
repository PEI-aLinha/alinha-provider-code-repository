"""Repository hosting platforms. Each one implements `RepositoryHost`; the collector picks one by `scope.host`."""
from __future__ import annotations

from typing import Mapping

from ..transport import Get
from .base import RepositoryHost
from .github import AppToken, GitHub
from .gitlab import CLOUD as GITLAB_CLOUD
from .gitlab import GitLab


def host_for(name: str, credential: Mapping[str, str], get: Get, base_url: str | None = None,
             app_token: AppToken | None = None) -> RepositoryHost | None:
    if name == GitHub.NAME and base_url is None:
        return GitHub(credential, get, app_token)
    if name == GitLab.NAME:
        return GitLab(credential, get, base_url or GITLAB_CLOUD)
    return None


__all__ = ["AppToken", "RepositoryHost", "host_for"]
