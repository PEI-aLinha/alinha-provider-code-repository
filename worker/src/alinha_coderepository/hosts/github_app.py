"""Short-lived installation tokens for the aLinha GitHub App.

The App's id and private key are the operators' (OpenBao `secret/providers/code-repository`, keys `github_app_id`
and `github_app_private_key`); the tenant's secret only names its installation, written by the app after it checked
that the person who installed it can see that installation. The token asks for read permissions only, so it stays
read-only even if the App registration is ever given more.
"""
from __future__ import annotations

import json
import re
import time
import urllib.error
import urllib.request
from typing import Any, Callable, Mapping

from alinha_job_consumer.collectors import CollectorError, TransientError

from ..transport import TIMEOUT_S, Response

API = "https://api.github.com"
READ_ONLY = {
    "metadata": "read",
    "administration": "read",
    "vulnerability_alerts": "read",
    "secret_scanning_alerts": "read",
}
_INSTALLATION = re.compile(r"[1-9][0-9]{0,19}")
_APP_ID = re.compile(r"[1-9][0-9]{0,19}")

# (url, headers, json body) -> Response. Only used to mint the token: everything else is a GET.
Post = Callable[[str, Mapping[str, str], dict[str, Any]], Response]
Sign = Callable[[dict[str, Any], str], str]


def installation_token(installation_id: str, platform: Mapping[str, str], post: Post | None = None,
                       sign: Sign | None = None, now: Callable[[], float] = time.time) -> str:
    if not _INSTALLATION.fullmatch(installation_id or ""):
        raise CollectorError("invalid_credentials", "the stored GitHub App installation is not valid")
    app_id = platform.get("github_app_id", "")
    key = platform.get("github_app_private_key", "")
    if not (_APP_ID.fullmatch(app_id) and key):
        raise CollectorError("provider_not_configured", "the GitHub App is not configured on this platform")
    issued = int(now()) - 60  # GitHub allows 60 s of clock drift
    jwt = (sign or _sign)({"iat": issued, "exp": issued + 540, "iss": app_id}, key)
    answer = (post or _post)(
        f"{API}/app/installations/{installation_id}/access_tokens",
        {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {jwt}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "alinha-code-repository",
        },
        {"permissions": READ_ONLY},
    )
    if answer.status in (404, 403):
        raise CollectorError("installation_not_found", "the GitHub App is no longer installed on this account")
    if answer.status in (401, 422):  # 422: the App registration lacks one of the requested read permissions
        raise CollectorError("provider_not_configured", "GitHub refused the App credentials or permissions")
    if answer.status == 429 or answer.status >= 500:
        raise TransientError(f"GitHub answered {answer.status}")
    body = answer.json() if answer.status == 201 else None
    token = body.get("token") if isinstance(body, dict) else None
    if not isinstance(token, str) or not token:
        raise CollectorError("installation_not_found", "GitHub did not issue an installation token")
    return token


def _sign(claims: dict[str, Any], key: str) -> str:
    import jwt  # PyJWT[crypto]; imported here so token connections and tests do not need it

    try:
        return jwt.encode(claims, key, algorithm="RS256")
    except (ValueError, TypeError, jwt.PyJWTError):
        raise CollectorError("provider_not_configured", "the GitHub App private key is not valid") from None


def _post(url: str, headers: Mapping[str, str], body: dict[str, Any]) -> Response:
    if not url.startswith(API + "/"):
        raise ValueError("tokens are only requested from api.github.com")
    request = urllib.request.Request(
        url, data=json.dumps(body).encode(), headers={**headers, "Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_S) as answer:  # noqa: S310 - fixed https host
            return Response(answer.status, {}, answer.read(64 * 1024))
    except urllib.error.HTTPError as error:
        return Response(error.code, {}, b"")
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise TransientError("GitHub unreachable") from error
