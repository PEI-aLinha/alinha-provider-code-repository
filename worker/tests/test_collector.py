import uuid

import pytest

from alinha_coderepository.collector import MAX_REPOSITORIES, RESULT_SCHEMA, collect
from alinha_job_consumer.collectors import CollectorError
from fake_github import FakeGitHub, protected_repo


def job(**scope_overrides):
    scope = {"host": "github", "account": "acme", "repositories": ["acme/api"]}
    scope.update(scope_overrides)
    return {
        "jobId": str(uuid.uuid4()),
        "type": "evidence.collect",
        "tenantId": str(uuid.uuid4()),
        "provider": "code-repository",
        "connectionRef": "providers/code-repository",
        "requestedAt": "2026-11-24T10:00:00Z",
        "scope": scope,
    }


def secret(_job):
    return {"token": "ghp_secret"}


def test_collects_every_check_for_the_requested_repositories():
    result = collect(job(), read_secret=secret, get=FakeGitHub(protected_repo()))

    assert result["schema"] == RESULT_SCHEMA
    assert result["host"] == "github" and result["account"] == "acme" and result["truncated"] is False
    [repository] = result["repositories"]
    assert repository["repository"] == "acme/api"
    assert {c["check"] for c in repository["checks"]} == {
        "branch_protection", "required_reviews", "dependency_alerts", "secret_scanning"}
    assert "ghp_secret" not in str(result)


def test_reads_the_credential_for_this_job():
    seen = []

    collect(job(), read_secret=lambda j: seen.append(j) or {"token": "t"}, get=FakeGitHub(protected_repo()))

    assert seen[0]["connectionRef"] == "providers/code-repository"


def test_without_repositories_inspects_what_the_token_sees_up_to_the_cap():
    listing = [{"full_name": f"acme/r{i}"} for i in range(MAX_REPOSITORIES + 5)]
    fake = FakeGitHub({"/user/repos?per_page=100&page=1&sort=full_name": (200, listing)})

    result = collect(job(repositories=[]), read_secret=secret, get=fake)

    assert len(result["repositories"]) == MAX_REPOSITORIES
    assert result["truncated"] is True


def test_an_account_with_no_visible_repository_is_a_final_error():
    fake = FakeGitHub({"/user/repos?per_page=100&page=1&sort=full_name": (200, [{"full_name": "other/x"}])})

    with pytest.raises(CollectorError) as error:
        collect(job(repositories=[]), read_secret=secret, get=fake)

    assert error.value.code == "no_repositories"


def test_duplicate_repositories_are_inspected_once():
    result = collect(job(repositories=["acme/api", "acme/api"]), read_secret=secret, get=FakeGitHub(protected_repo()))

    assert len(result["repositories"]) == 1


@pytest.mark.parametrize(
    "scope",
    [None, {"account": "acme"}, {"host": "github"}, {"host": "github", "account": ""},
     {"host": "github", "account": "acme", "repositories": "acme/api"},
     {"host": "github", "account": "acme", "repositories": [1]}],
)
def test_invalid_scope_is_a_final_error(scope):
    bad = job()
    bad["scope"] = scope

    with pytest.raises(CollectorError) as error:
        collect(bad, read_secret=secret, get=FakeGitHub())

    assert error.value.code == "invalid_scope"


def test_unsupported_host_is_a_final_error():
    with pytest.raises(CollectorError) as error:
        collect(job(host="bitbucket"), read_secret=secret, get=FakeGitHub())

    assert error.value.code == "unsupported_host"


def test_rejects_a_job_for_another_provider():
    other = job()
    other["provider"] = "external-surface"

    with pytest.raises(CollectorError):
        collect(other, read_secret=secret, get=FakeGitHub())


def test_collects_a_gitlab_group():
    from fake_gitlab import FakeGitLab, protected_project, security

    fake = FakeGitLab(protected_project("acme/platform/web"), graphql=(200, security()))

    result = collect(job(host="gitlab", repositories=["acme/platform/web"]), read_secret=secret, get=fake)

    assert result["host"] == "gitlab"
    assert result["repositories"][0]["repository"] == "acme/platform/web"


def test_github_does_not_take_a_server_address():
    with pytest.raises(CollectorError) as error:
        collect(job(baseUrl="https://ghe.acme.pt"), read_secret=secret, get=FakeGitHub())
    assert error.value.code == "unsupported_host"


def test_self_managed_server_on_a_private_address_is_refused():
    with pytest.raises(CollectorError) as error:
        collect(job(host="gitlab", baseUrl="https://localhost"), read_secret=secret)
    assert error.value.code == "invalid_scope"


@pytest.mark.parametrize("base_url", ["", 42])
def test_invalid_base_url_is_refused(base_url):
    with pytest.raises(CollectorError) as error:
        collect(job(host="gitlab", baseUrl=base_url), read_secret=secret, get=FakeGitHub())
    assert error.value.code == "invalid_scope"


def test_github_app_connection_gets_an_installation_token():
    tokens = []
    collect(job(), read_secret=lambda _j: {"auth": "app", "installationId": "9"},
            get=FakeGitHub(protected_repo()), app_token=lambda i: tokens.append(i) or "ghs_x")
    assert tokens == ["9"]
