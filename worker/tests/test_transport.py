import pytest

from alinha_coderepository.transport import Response, urllib_get
from alinha_job_consumer.collectors import TransientError


def test_refuses_plain_http():
    with pytest.raises(ValueError):
        urllib_get("http://api.github.com/user", {})


def test_unreachable_host_is_retryable():
    with pytest.raises(TransientError):
        urllib_get("https://127.0.0.1:9/user", {})


def test_redirects_are_not_followed():
    from alinha_coderepository.transport import _NoRedirects

    assert _NoRedirects().redirect_request(None, None, 302, "Found", {}, "https://evil.example/") is None


def test_json_of_a_bad_body_is_none():
    assert Response(200, {}, b"not json").json() is None
    assert Response(204, {}, b"").json() is None


# -- self-managed servers -------------------------------------------------------------------------------------

import socket  # noqa: E402

from alinha_coderepository.transport import pinned_get, public_address, self_managed_base  # noqa: E402
from alinha_job_consumer.collectors import CollectorError  # noqa: E402


def resolving(*addresses):
    return lambda host, port, type=None: [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (a, port)) for a in addresses]


@pytest.mark.parametrize("url,expected", [
    ("https://git.acme.pt", ("https://git.acme.pt", "git.acme.pt", 443)),
    ("https://Git.Acme.pt:8443/", ("https://git.acme.pt:8443", "git.acme.pt", 8443)),
])
def test_accepts_only_bare_https_server_addresses(url, expected):
    assert self_managed_base(url) == expected


@pytest.mark.parametrize("url", [
    "http://git.acme.pt", "https://user:pw@git.acme.pt", "https://git.acme.pt/api", "https://git.acme.pt?x=1",
    "https://10.0.0.1", "https://localhost", "https://git.acme.pt:99999", "ftp://git.acme.pt", "https://",
])
def test_refuses_anything_else(url):
    with pytest.raises(CollectorError) as error:
        self_managed_base(url)
    assert error.value.code == "invalid_scope"


@pytest.mark.parametrize("addresses", [("10.1.2.3",), ("127.0.0.1",), ("169.254.169.254",), ("192.168.1.1",),
                                       ("8.8.8.8", "10.0.0.1"), ("::1",), ("fd00::1",), ()])
def test_refuses_hosts_that_resolve_to_a_private_address(addresses):
    with pytest.raises(CollectorError) as error:
        public_address("git.acme.pt", 443, resolving(*addresses))
    assert error.value.code == "host_not_allowed"


def test_public_host_resolves_once_to_its_first_address():
    assert public_address("git.acme.pt", 443, resolving("8.8.8.8", "1.1.1.1")) == "8.8.8.8"


def test_unresolvable_host_is_retryable():
    def fail(*_args, **_kwargs):
        raise socket.gaierror("no such host")

    with pytest.raises(TransientError):
        public_address("git.acme.pt", 443, fail)


def test_pinned_get_only_calls_its_own_server():
    get = pinned_get("https://git.acme.pt", resolving("8.8.8.8"))

    with pytest.raises(ValueError):
        get("https://evil.example/api/v4/projects", {})
    with pytest.raises(ValueError):
        get("https://git.acme.pt.evil.example/api", {})


def test_pinned_get_unreachable_server_is_retryable():
    get = pinned_get("https://git.acme.pt:9", resolving("1.1.1.1"))
    original = socket.create_connection

    def refuse(*_args, **_kwargs):
        raise ConnectionRefusedError()

    socket.create_connection = refuse
    try:
        with pytest.raises(TransientError):
            get("https://git.acme.pt:9/api/v4/version", {})
    finally:
        socket.create_connection = original
