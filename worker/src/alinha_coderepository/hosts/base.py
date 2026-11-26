from __future__ import annotations

from typing import Protocol

from ..checks import RepositoryResult


class RepositoryHost(Protocol):
    """A hosting platform read with a read-only credential. Adding GitLab or Azure DevOps means one more of these."""

    def repositories(self, account: str, limit: int) -> list[str]:
        """`owner/name` of the repositories of `account` the credential can see, at most `limit`."""
        ...

    def inspect(self, repository: str) -> RepositoryResult:
        """Every check for one `owner/name` repository."""
        ...
