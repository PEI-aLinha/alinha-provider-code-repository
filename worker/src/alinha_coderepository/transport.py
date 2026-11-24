"""Minimal read-only HTTP client (stdlib only: the plugin CI installs no runtime dependencies)."""
from __future__ import annotations

import http.client
import ipaddress
import json
import re
import socket
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable, Mapping
from urllib.parse import urlsplit

from alinha_job_consumer.collectors import CollectorError, TransientError

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


# -- self-managed hosts (the tenant types the address) -----------------------------------------------------------

_HOSTNAME = re.compile(r"(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}")


def self_managed_base(url: str) -> tuple[str, str, int]:
    """`https://host[:port]` and nothing else (no user, path, query or IP literal). Returns (base, host, port)."""
    parts = urlsplit(url)
    try:
        port = parts.port or 443
    except ValueError:
        raise CollectorError("invalid_scope", "the server address has an invalid port") from None
    host = parts.hostname or ""
    if (parts.scheme != "https" or parts.username or parts.password or parts.path not in ("", "/")
            or parts.query or parts.fragment or not _HOSTNAME.fullmatch(host)):
        raise CollectorError("invalid_scope", "the server address must be https://<host>[:port]")
    return f"https://{host}" + ("" if port == 443 else f":{port}"), host.lower(), port


def public_address(host: str, port: int, resolve: Callable[..., Any] = socket.getaddrinfo) -> str:
    """Resolves [host] once and refuses it unless EVERY address is public (no SSRF into the platform's network)."""
    try:
        infos = resolve(host, port, type=socket.SOCK_STREAM)
    except OSError as error:
        raise TransientError("provider host does not resolve") from error
    addresses = [info[4][0] for info in infos]
    if not addresses or not all(_is_public(a) for a in addresses):
        raise CollectorError("host_not_allowed", "the server address is not a public internet address")
    return addresses[0]


# NAT64 prefixes count as global to `ipaddress`, but a NAT64 gateway would turn them into any IPv4 address.
_TRANSLATED = (ipaddress.ip_network("64:ff9b::/96"), ipaddress.ip_network("64:ff9b:1::/48"))


def _is_public(address: str) -> bool:
    ip = ipaddress.ip_address(address.split("%", 1)[0])
    if isinstance(ip, ipaddress.IPv6Address):
        if ip.ipv4_mapped is not None:
            ip = ip.ipv4_mapped
        elif any(ip in net for net in _TRANSLATED):
            return False
    return ip.is_global


class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    """Connects to the address checked by [public_address] (no second lookup, so no DNS rebinding) with TLS
    verified against the host name."""

    def __init__(self, host: str, port: int, address: str, timeout: float) -> None:
        super().__init__(host, port, timeout=timeout)
        self._address = address

    def connect(self) -> None:
        sock = socket.create_connection((self._address, self.port), self.timeout)
        self.sock = self._context.wrap_socket(sock, server_hostname=self.host)


def pinned_get(base_url: str, resolve: Callable[..., Any] = socket.getaddrinfo) -> Get:
    """A [Get] for one self-managed server: only URLs under [base_url], to its public address, never redirected."""
    base, host, port = self_managed_base(base_url)
    address = public_address(host, port, resolve)

    def get(url: str, headers: Mapping[str, str]) -> Response:
        if not url.startswith(base + "/"):
            raise ValueError("request outside the connected server")
        target = url[len(base):]
        connection = _PinnedHTTPSConnection(host, port, address, TIMEOUT_S)
        try:
            connection.request("GET", target, headers=dict(headers))
            answer = connection.getresponse()  # http.client never follows redirects
            body = _read(answer) if answer.status == 200 else b""
            return Response(answer.status, _lower(answer.getheaders()), body)
        except (OSError, http.client.HTTPException) as error:
            raise TransientError("provider unreachable") from error
        finally:
            connection.close()

    return get
