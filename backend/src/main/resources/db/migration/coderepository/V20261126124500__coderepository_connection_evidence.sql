-- One connection per tenant to a repository host (GitHub or GitLab). The credential itself is in OpenBao
-- (TenantSecretStore, secret/tenants/<tenant>/providers/code-repository), never here.
create table coderepository.connection (
    id                uuid primary key,
    tenant_id         uuid not null,
    host              text not null check (host in ('github', 'gitlab')),
    -- A self-managed server (https://host[:port]); null for the cloud service.
    base_url          text check (base_url ~ '^https://[a-z0-9.-]+(:[0-9]{1,5})?$'),
    -- token: a read-only access token the client pasted; app: the aLinha GitHub App, installed by the client.
    auth              text not null default 'token' check (auth in ('token', 'app')),
    installation_id   bigint check (installation_id > 0),
    account           text not null check (length(account) between 1 and 255),
    repositories      text[] not null default '{}',
    status            text not null check (status in ('PENDING', 'ACTIVE', 'FAILED')),
    last_job_id       uuid,
    collecting_since  timestamptz,
    -- The current collection was started by the daily schedule, not by a person.
    collecting_scheduled boolean not null default false,
    collected_job_id  uuid,
    last_error        text check (last_error ~ '^[a-z][a-z0-9_]{0,99}$'),
    last_collected_at timestamptz,
    truncated         boolean not null default false,
    connected_at      timestamptz not null,
    version           bigint not null default 0,
    constraint connection_one_per_tenant unique (tenant_id),
    constraint connection_app_has_installation check ((auth = 'app') = (installation_id is not null)),
    constraint connection_app_is_github check (auth = 'token' or (host = 'github' and base_url is null))
);

-- What one collection found: one row per (repository, check). Insert-only: a new collection adds new rows.
create table coderepository.evidence (
    id              uuid primary key,
    tenant_id       uuid not null,
    -- Kept when the connection is removed: what was collected stays part of the tenant's history.
    connection_id   uuid references coderepository.connection (id) on delete set null,
    job_id          uuid not null,
    repository      text not null,
    check_name      text not null check (check_name in
                        ('branch_protection', 'required_reviews', 'dependency_alerts', 'secret_scanning')),
    outcome         text not null check (outcome in ('PASS', 'FAIL', 'UNKNOWN')),
    details         jsonb not null default '{}',
    control_ids     text[] not null,
    strength        text not null,
    mapping_version integer not null,
    collected_at    timestamptz not null,
    constraint evidence_once_per_job unique (job_id, repository, check_name)
);

create index evidence_tenant_collected on coderepository.evidence (tenant_id, collected_at desc);
create index evidence_job on coderepository.evidence (job_id);

-- When each connected tenant's next daily collection is due. Read by the scheduler across tenants, so it holds only
-- the tenant id and a time: no tenant data. Not tenant-filtered on purpose (CollectionScheduleEntity).
create table coderepository.collection_schedule (
    tenant_id   uuid primary key,
    next_run_at timestamptz not null,
    version     bigint not null default 0
);

create index collection_schedule_due on coderepository.collection_schedule (next_run_at);

-- A GitHub App installation the tenant started: the hash of the state sent to GitHub, valid for a short time.
create table coderepository.github_app_install (
    id         uuid primary key,
    tenant_id  uuid not null,
    state_hash text not null check (length(state_hash) = 64),
    expires_at timestamptz not null,
    version    bigint not null default 0,
    constraint github_app_install_one_per_tenant unique (tenant_id)
);

-- Who the responsible person asked to connect the repository (an email invite).
create table coderepository.assignment (
    id          uuid primary key,
    tenant_id   uuid not null,
    email       text not null check (length(email) between 3 and 254),
    assigned_at timestamptz not null,
    version     bigint not null default 0,
    constraint assignment_one_per_tenant unique (tenant_id)
);
