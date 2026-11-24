"""Repository hosting platforms. Each one implements `RepositoryHost`; the collector picks one by `scope.host`."""
from __future__ import annotations

from typing import Mapping

from ..transport import Get
from .base import RepositoryHost
from .github import GitHub


def host_for(name: str, credential: Mapping[str, str], get: Get) -> RepositoryHost | None:
    if name == GitHub.NAME:
        return GitHub(credential, get)
    return None


__all__ = ["RepositoryHost", "host_for"]
