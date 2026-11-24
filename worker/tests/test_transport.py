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
