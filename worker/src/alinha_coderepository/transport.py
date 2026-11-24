"""Minimal read-only HTTP client (stdlib only: the plugin CI installs no runtime dependencies)."""
from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable, Mapping

from alinha_job_consumer.collectors import TransientError

TIMEOUT_S = 10.0
# Larger answers are refused: collected data is untrusted and the job result is capped at 1 MiB.
MAX_BODY_BYTES = 2 * 1024 * 1024


@dataclass(frozen=True)
class Response:
    status: int
    headers: Mapping[str, str]
    body: bytes

    def json(self) -> Any:
        try:
            return json.loads(self.body) if self.body else None
        except ValueError:
            return None


# (url, headers) -> Response. Only GET exists: providers are read-only.
Get = Callable[[str, Mapping[str, str]], Response]


class _NoRedirects(urllib.request.HTTPRedirectHandler):
    """urllib would follow a redirect with the Authorization header still set, possibly to another host."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001 - urllib's signature
        return None


_OPENER = urllib.request.build_opener(_NoRedirects)


def urllib_get(url: str, headers: Mapping[str, str]) -> Response:
    if not url.startswith("https://"):
        raise ValueError("providers are only called over https")
    request = urllib.request.Request(url, headers=dict(headers), method="GET")
    try:
        with _OPENER.open(request, timeout=TIMEOUT_S) as answer:  # https only, checked above; redirects refused
            return Response(answer.status, _lower(answer.headers.items()), _read(answer))
    except urllib.error.HTTPError as error:
        return Response(error.code, _lower(error.headers.items() if error.headers else []), b"")
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        # The exception text may contain the URL; the consumer only logs it.
        raise TransientError("provider unreachable") from error


def _read(answer: Any) -> bytes:
    body = answer.read(MAX_BODY_BYTES + 1)
    if len(body) > MAX_BODY_BYTES:
        raise TransientError("provider answer too large")
    return body


def _lower(items: Any) -> dict[str, str]:
    return {key.lower(): value for key, value in items}
