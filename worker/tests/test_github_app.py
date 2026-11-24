import json

import pytest

from alinha_coderepository.hosts.github import GitHub
from alinha_coderepository.hosts.github_app import READ_ONLY, installation_token
from alinha_coderepository.transport import Response
from alinha_job_consumer.collectors import CollectorError, TransientError
from fake_github import FakeGitHub

PLATFORM = {"github_app_id": "12345", "github_app_private_key": "-----BEGIN KEY-----"}


def answering(status, body=None):
    calls = []

    def post(url, headers, payload):
        calls.append((url, headers, payload))
        return Response(status, {}, json.dumps(body).encode() if body is not None else b"")

    post.calls = calls
    return post


def sign(claims, key):
    assert key == PLATFORM["github_app_private_key"]
    return f"jwt-{claims['iss']}-{claims['exp'] - claims['iat']}"


def test_mints_a_read_only_installation_token():
    post = answering(201, {"token": "ghs_installation"})

    token = installation_token("777", PLATFORM, post, sign, now=lambda: 1_000_000)

    url, headers, payload = post.calls[0]
    assert token == "ghs_installation"
    assert url == "https://api.github.com/app/installations/777/access_tokens"
    assert headers["Authorization"] == "Bearer jwt-12345-540"
    assert payload == {"permissions": READ_ONLY}
    assert set(READ_ONLY.values()) == {"read"}


@pytest.mark.parametrize("installation", ["", "0", "12a", "../1", None])
def test_refuses_an_invalid_installation(installation):
    with pytest.raises(CollectorError) as error:
        installation_token(installation, PLATFORM, answering(201, {"token": "t"}), sign)
    assert error.value.code == "invalid_credentials"


@pytest.mark.parametrize("platform", [{}, {"github_app_id": "x", "github_app_private_key": "k"},
                                      {"github_app_id": "1"}])
def test_unconfigured_app_is_a_final_error(platform):
    with pytest.raises(CollectorError) as error:
        installation_token("777", platform, answering(201, {"token": "t"}), sign)
    assert error.value.code == "provider_not_configured"


@pytest.mark.parametrize("status,code", [(404, "installation_not_found"), (403, "installation_not_found"),
                                         (401, "provider_not_configured"), (422, "provider_not_configured"),
                                         (201, "installation_not_found")])
def test_refusals_are_final(status, code):
    with pytest.raises(CollectorError) as error:
        installation_token("777", PLATFORM, answering(status, {}), sign)
    assert error.value.code == code


@pytest.mark.parametrize("status", [429, 500, 503])
def test_outages_are_retryable(status):
    with pytest.raises(TransientError):
        installation_token("777", PLATFORM, answering(status), sign)


def test_an_invalid_private_key_is_a_final_error():
    pytest.importorskip("jwt")
    with pytest.raises(CollectorError) as error:
        installation_token("777", {"github_app_id": "1", "github_app_private_key": "not a key"}, answering(201))
    assert error.value.code == "provider_not_configured"


def test_app_connection_lists_the_installation_repositories():
    fake = FakeGitHub({
        "/installation/repositories?per_page=100&page=1": (200, {"repositories": [
            {"full_name": "acme/api"}, {"full_name": "acme/old", "archived": True}, {"full_name": "other/x"},
        ]}),
    })
    seen = []

    github = GitHub({"auth": "app", "installationId": "777"}, fake, lambda i: seen.append(i) or "ghs_t")

    assert github.repositories("acme", 10) == ["acme/api"]
    assert seen == ["777"]
    assert fake.calls[0][1]["Authorization"] == "Bearer ghs_t"


def test_app_connection_without_app_support_is_a_final_error():
    with pytest.raises(CollectorError) as error:
        GitHub({"auth": "app", "installationId": "777"}, FakeGitHub())
    assert error.value.code == "provider_not_configured"
