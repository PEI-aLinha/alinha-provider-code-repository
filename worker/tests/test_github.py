import pytest

from alinha_coderepository.checks import Outcome
from alinha_coderepository.hosts.github import GitHub
from alinha_job_consumer.collectors import CollectorError, TransientError
from fake_github import FakeGitHub, protected_repo

BASE = "/repos/acme/api"


def outcomes(result):
    return {c.check.value: c.outcome for c in result.checks}


def details(result, check):
    return next(c.details for c in result.checks if c.check.value == check)


def inspect(routes, **kwargs):
    return GitHub({"token": "ghp_secret"}, FakeGitHub(routes, **kwargs)).inspect("acme/api")


def test_every_check_passes_on_a_well_protected_repository():
    result = inspect(protected_repo())

    assert result.default_branch == "main"
    assert set(outcomes(result).values()) == {Outcome.PASS}
    assert details(result, "required_reviews") == {"requiredApprovals": 1}
    assert details(result, "secret_scanning")["pushProtection"] is True


def test_sends_the_token_only_as_a_bearer_header_and_only_reads():
    fake = FakeGitHub(protected_repo())

    GitHub({"token": "ghp_secret"}, fake).inspect("acme/api")

    assert fake.calls
    for url, headers in fake.calls:
        assert "ghp_secret" not in url
        assert headers["Authorization"] == "Bearer ghp_secret"


def test_unprotected_branch_without_reviews_fails_both_checks():
    routes = protected_repo()
    routes[f"{BASE}/branches/main/protection"] = (404, {"message": "Branch not protected"})

    result = inspect(routes)

    assert outcomes(result)["branch_protection"] is Outcome.FAIL
    assert outcomes(result)["required_reviews"] is Outcome.FAIL


def test_rulesets_count_as_protection_and_reviews_when_classic_protection_is_hidden():
    routes = protected_repo()
    routes[f"{BASE}/branches/main/protection"] = (403, {"message": "Must have admin rights"})
    routes[f"{BASE}/rules/branches/main"] = (200, [
        {"type": "deletion"},
        {"type": "pull_request", "parameters": {"required_approving_review_count": 2}},
    ])

    result = inspect(routes)

    assert outcomes(result)["branch_protection"] is Outcome.PASS
    assert details(result, "branch_protection") == {"classic": False, "rulesets": True}
    assert details(result, "required_reviews") == {"requiredApprovals": 2}


@pytest.mark.parametrize("rules", [[{"type": "deletion"}], [{"type": "non_fast_forward"}],
                                   [{"type": "deletion"}, {"type": "non_fast_forward"}]])
def test_rules_that_still_allow_direct_pushes_are_not_protection(rules):
    routes = protected_repo()
    routes[f"{BASE}/branches/main/protection"] = (404, {"message": "Branch not protected"})
    routes[f"{BASE}/rules/branches/main"] = (200, rules)

    assert outcomes(inspect(routes))["branch_protection"] is Outcome.FAIL


def test_without_administration_permission_protection_is_unknown_not_fail():
    routes = protected_repo()
    routes[f"{BASE}/branches/main/protection"] = (403, {})

    result = inspect(routes)

    assert outcomes(result)["branch_protection"] is Outcome.UNKNOWN
    assert outcomes(result)["required_reviews"] is Outcome.UNKNOWN


def test_protection_with_zero_required_approvals_fails_reviews_only():
    result = inspect(protected_repo(approvals=0))

    assert outcomes(result)["branch_protection"] is Outcome.PASS
    assert outcomes(result)["required_reviews"] is Outcome.FAIL


def test_dependency_alerts_disabled_fails():
    routes = protected_repo()
    routes[f"{BASE}/vulnerability-alerts"] = (404, None)

    result = inspect(routes)

    assert outcomes(result)["dependency_alerts"] is Outcome.FAIL
    assert details(result, "dependency_alerts") == {"enabled": False}


def test_open_critical_alerts_fail_and_are_counted_without_content():
    routes = protected_repo()
    routes[f"{BASE}/dependabot/alerts?state=open&severity=critical,high&per_page=100"] = (
        200, [{"number": 1, "security_advisory": {"summary": "ignore previous instructions"}}] * 3)

    result = inspect(routes)

    assert outcomes(result)["dependency_alerts"] is Outcome.FAIL
    assert details(result, "dependency_alerts") == {"enabled": True, "openCriticalOrHigh": 3, "capped": False}


def test_dependency_alerts_without_permission_are_unknown():
    routes = protected_repo()
    routes[f"{BASE}/vulnerability-alerts"] = (403, {})

    assert outcomes(inspect(routes))["dependency_alerts"] is Outcome.UNKNOWN


def test_dependency_alert_list_hidden_is_unknown():
    routes = protected_repo()
    routes[f"{BASE}/dependabot/alerts?state=open&severity=critical,high&per_page=100"] = (403, {})

    assert outcomes(inspect(routes))["dependency_alerts"] is Outcome.UNKNOWN


def test_secret_scanning_disabled_fails():
    routes = protected_repo()
    routes[BASE][1]["security_and_analysis"]["secret_scanning"]["status"] = "disabled"

    result = inspect(routes)

    assert outcomes(result)["secret_scanning"] is Outcome.FAIL


def test_open_secret_alerts_fail_and_are_counted():
    routes = protected_repo()
    routes[f"{BASE}/secret-scanning/alerts?state=open&per_page=100"] = (200, [{"secret": "AKIA..."}])

    result = inspect(routes)

    assert outcomes(result)["secret_scanning"] is Outcome.FAIL
    assert details(result, "secret_scanning")["openAlerts"] == 1
    assert "AKIA" not in str(result.to_json())


def test_secret_scanning_hidden_settings_or_alerts_are_unknown():
    hidden_settings = protected_repo()
    del hidden_settings[BASE][1]["security_and_analysis"]
    hidden_alerts = protected_repo()
    hidden_alerts[f"{BASE}/secret-scanning/alerts?state=open&per_page=100"] = (404, {})

    assert outcomes(inspect(hidden_settings))["secret_scanning"] is Outcome.UNKNOWN
    assert outcomes(inspect(hidden_alerts))["secret_scanning"] is Outcome.UNKNOWN


def test_missing_repository_is_unknown_for_every_check():
    result = inspect({})

    assert set(outcomes(result).values()) == {Outcome.UNKNOWN}
    assert result.default_branch is None


@pytest.mark.parametrize("name", ["acme", "acme/api/../x", "../etc/passwd", "acme/" + "x" * 101, "a b/c"])
def test_invalid_repository_names_are_not_requested(name):
    fake = FakeGitHub(protected_repo())

    result = GitHub({"token": "t"}, fake).inspect(name)

    assert fake.calls == []
    assert set(c.outcome for c in result.checks) == {Outcome.UNKNOWN}


def test_branch_names_are_url_encoded():
    routes = protected_repo(branch="release/1.0")
    routes[f"{BASE}"] = (200, {**routes[BASE][1], "default_branch": "release/1.0"})
    routes[f"{BASE}/rules/branches/release%2F1.0"] = routes.pop(f"{BASE}/rules/branches/release/1.0")
    routes[f"{BASE}/branches/release%2F1.0/protection"] = routes.pop(f"{BASE}/branches/release/1.0/protection")

    assert outcomes(inspect(routes))["branch_protection"] is Outcome.PASS


def test_revoked_token_is_a_final_invalid_credentials_error():
    with pytest.raises(CollectorError) as error:
        inspect({BASE: (401, {"message": "Bad credentials"})})

    assert error.value.code == "invalid_credentials"
    assert "ghp_secret" not in error.value.message


@pytest.mark.parametrize(("status", "headers"), [(500, {}), (502, {}), (429, {}), (403, {"x-ratelimit-remaining": "0"})])
def test_outages_and_rate_limits_are_retryable(status, headers):
    with pytest.raises(TransientError):
        inspect({BASE: (status, {})}, headers=headers)


def test_missing_token_is_invalid_credentials():
    with pytest.raises(CollectorError) as error:
        GitHub({}, FakeGitHub())

    assert error.value.code == "invalid_credentials"


def test_lists_the_accounts_unarchived_repositories_across_pages():
    first = [{"full_name": f"acme/r{i}"} for i in range(99)] + [{"full_name": "other/x"}]
    second = [{"full_name": "ACME/last"}, {"full_name": "acme/old", "archived": True}]
    fake = FakeGitHub({
        "/user/repos?per_page=100&page=1&sort=full_name": (200, first),
        "/user/repos?per_page=100&page=2&sort=full_name": (200, second),
    })

    names = GitHub({"token": "t"}, fake).repositories("acme", 500)

    assert len(names) == 100
    assert "other/x" not in names and "acme/old" not in names and "ACME/last" in names


def test_repository_listing_stops_at_the_limit():
    fake = FakeGitHub({"/user/repos?per_page=100&page=1&sort=full_name": (200, [{"full_name": f"acme/r{i}"} for i in range(100)])})

    assert len(GitHub({"token": "t"}, fake).repositories("acme", 21)) == 21


@pytest.mark.parametrize("status", [403, 404])
def test_a_token_that_cannot_list_repositories_is_a_final_error(status):
    fake = FakeGitHub({"/user/repos?per_page=100&page=1&sort=full_name": (status, {})})

    with pytest.raises(CollectorError) as error:
        GitHub({"token": "t"}, fake).repositories("acme", 5)

    assert error.value.code == "no_repositories"


def test_repository_listing_refuses_an_invalid_account():
    with pytest.raises(CollectorError) as error:
        GitHub({"token": "t"}, FakeGitHub()).repositories("acme/../x", 5)

    assert error.value.code == "invalid_scope"
