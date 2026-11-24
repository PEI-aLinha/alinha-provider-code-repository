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
        collect(job(host="gitlab"), read_secret=secret, get=FakeGitHub())

    assert error.value.code == "unsupported_host"


def test_rejects_a_job_for_another_provider():
    other = job()
    other["provider"] = "external-surface"

    with pytest.raises(CollectorError):
        collect(other, read_secret=secret, get=FakeGitHub())
