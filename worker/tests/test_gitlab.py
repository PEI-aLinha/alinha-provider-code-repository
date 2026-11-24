import pytest

from alinha_coderepository.checks import Check, Outcome
from alinha_coderepository.hosts.gitlab import GitLab
from alinha_job_consumer.collectors import CollectorError, TransientError
from fake_gitlab import FakeGitLab, protected_project, security

TOKEN = {"token": "glpat-secret"}
LIST = "per_page=100&archived=false&order_by=path&sort=asc&simple=true"


def outcomes(result):
    return {check.check: check for check in result.checks}


def test_every_check_passes_on_a_well_protected_project():
    fake = FakeGitLab(protected_project(), graphql=(200, security()))

    result = GitLab(TOKEN, fake).inspect("acme/api")

    checks = outcomes(result)
    assert result.default_branch == "main"
    assert {c: r.outcome for c, r in checks.items()} == {check: Outcome.PASS for check in Check}
    assert checks[Check.REQUIRED_REVIEWS].details == {"requiredApprovals": 1}
    assert fake.calls[0][1]["PRIVATE-TOKEN"] == "glpat-secret"


@pytest.mark.parametrize("levels,force", [((30,), False), ((40,), True)])
def test_protection_that_still_lets_developers_push_or_force_push_fails(levels, force):
    fake = FakeGitLab(protected_project(push_levels=levels, force_push=force), graphql=(200, security()))

    assert outcomes(GitLab(TOKEN, fake).inspect("acme/api"))[Check.BRANCH_PROTECTION].outcome == Outcome.FAIL


def test_unprotected_default_branch_fails_and_hidden_protection_is_unknown():
    routes = protected_project()
    del routes["/api/v4/projects/acme%2Fapi/protected_branches/main"]
    assert outcomes(GitLab(TOKEN, FakeGitLab(routes)).inspect("acme/api"))[Check.BRANCH_PROTECTION].outcome == Outcome.FAIL

    routes["/api/v4/projects/acme%2Fapi/protected_branches/main"] = (403, {"message": "403 Forbidden"})
    assert outcomes(GitLab(TOKEN, FakeGitLab(routes)).inspect("acme/api"))[Check.BRANCH_PROTECTION].outcome == Outcome.UNKNOWN


def test_approvals_count_only_rules_for_the_default_branch_and_not_security_approvers():
    routes = protected_project()
    routes["/api/v4/projects/acme%2Fapi/approval_rules?per_page=100"] = (200, [
        {"rule_type": "report_approver", "approvals_required": 2, "protected_branches": []},
        {"rule_type": "regular", "approvals_required": 3, "protected_branches": [{"name": "release"}]},
        {"rule_type": "regular", "approvals_required": 0, "protected_branches": [{"name": "main"}]},
    ])

    reviews = outcomes(GitLab(TOKEN, FakeGitLab(routes)).inspect("acme/api"))[Check.REQUIRED_REVIEWS]

    assert (reviews.outcome, reviews.details) == (Outcome.FAIL, {"requiredApprovals": 0})


def test_free_plan_makes_reviews_and_scanners_unknown_not_fail():
    routes = protected_project()
    routes["/api/v4/projects/acme%2Fapi/approval_rules?per_page=100"] = (403, {"message": "403 Forbidden"})
    fake = FakeGitLab(routes, graphql=(200, {"data": {"project": {"securityScanners": None}}}))

    checks = outcomes(GitLab(TOKEN, fake).inspect("acme/api"))

    for check in (Check.REQUIRED_REVIEWS, Check.DEPENDENCY_ALERTS, Check.SECRET_SCANNING):
        assert checks[check].outcome == Outcome.UNKNOWN
        assert checks[check].details["reason"] == "not_available"


def test_graphql_errors_are_unknown():
    fake = FakeGitLab(protected_project(), graphql=(200, {"errors": [{"message": "Field doesn't exist"}]}))

    assert outcomes(GitLab(TOKEN, fake).inspect("acme/api"))[Check.SECRET_SCANNING].outcome == Outcome.UNKNOWN


def test_disabled_scanners_and_open_findings_fail():
    fake = FakeGitLab(protected_project(), graphql=(200, security(dependency=False, secret_findings=2)))

    checks = outcomes(GitLab(TOKEN, fake).inspect("acme/api"))

    assert (checks[Check.DEPENDENCY_ALERTS].outcome, checks[Check.DEPENDENCY_ALERTS].details) == (
        Outcome.FAIL, {"enabled": False})
    assert (checks[Check.SECRET_SCANNING].outcome, checks[Check.SECRET_SCANNING].details) == (
        Outcome.FAIL, {"enabled": True, "openFindings": 2})


def test_lists_a_groups_projects_with_subgroups_and_skips_other_namespaces():
    fake = FakeGitLab({
        f"/api/v4/groups/acme/projects?include_subgroups=true&{LIST}&page=1": (200, [
            {"path_with_namespace": "acme/api"},
            {"path_with_namespace": "acme/platform/web"},
            {"path_with_namespace": "other/thing"},
        ]),
    })

    assert GitLab(TOKEN, fake).repositories("acme", 10) == ["acme/api", "acme/platform/web"]


def test_falls_back_to_a_users_projects_and_pages():
    page1 = [{"path_with_namespace": f"jane/p{i:03}"} for i in range(100)]
    fake = FakeGitLab({
        f"/api/v4/users/jane/projects?{LIST}&page=1": (200, page1),
        f"/api/v4/users/jane/projects?{LIST}&page=2": (200, [{"path_with_namespace": "jane/last"}]),
    })

    found = GitLab(TOKEN, fake).repositories("jane", 200)

    assert len(found) == 101 and found[-1] == "jane/last"


def test_no_listing_is_no_repositories():
    with pytest.raises(CollectorError) as error:
        GitLab(TOKEN, FakeGitLab({f"/api/v4/users/x/projects?{LIST}&page=1": (403, {})})).repositories("x", 5)
    assert error.value.code == "no_repositories"


@pytest.mark.parametrize("account", ["", "../admin", "a/../b", "-bad", "a b"])
def test_refuses_invalid_accounts(account):
    with pytest.raises(CollectorError) as error:
        GitLab(TOKEN, FakeGitLab()).repositories(account, 5)
    assert error.value.code == "invalid_scope"


def test_invalid_or_missing_project_is_unknown():
    gitlab = GitLab(TOKEN, FakeGitLab())
    assert outcomes(gitlab.inspect("../x"))[Check.BRANCH_PROTECTION].details == {"reason": "invalid_name"}
    assert outcomes(gitlab.inspect("acme/gone"))[Check.BRANCH_PROTECTION].details == {"reason": "not_found"}
    routes = {"/api/v4/projects/acme%2Fempty": (200, {"default_branch": None})}
    assert outcomes(GitLab(TOKEN, FakeGitLab(routes)).inspect("acme/empty"))[Check.BRANCH_PROTECTION].details == {
        "reason": "no_default_branch"}


def test_refused_token_is_final_and_outages_are_retryable():
    with pytest.raises(CollectorError) as error:
        GitLab(TOKEN, FakeGitLab({"/api/v4/projects/acme%2Fapi": (401, {})})).inspect("acme/api")
    assert error.value.code == "invalid_credentials"
    with pytest.raises(TransientError):
        GitLab(TOKEN, FakeGitLab({"/api/v4/projects/acme%2Fapi": (502, {})})).inspect("acme/api")
    with pytest.raises(CollectorError):
        GitLab({}, FakeGitLab())


def test_self_managed_server_is_called_at_its_own_address():
    fake = FakeGitLab(protected_project(), base="https://git.acme.pt", graphql=(200, security()))

    GitLab(TOKEN, fake, "https://git.acme.pt/").inspect("acme/api")

    assert all(url.startswith("https://git.acme.pt/api/") for url, _ in fake.calls)
