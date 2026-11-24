-- One connection per tenant to a repository host (GitHub first). The credential itself is in OpenBao
-- (TenantSecretStore, secret/tenants/<tenant>/providers/code-repository), never here.
create table coderepository.connection (
    id                uuid primary key,
    tenant_id         uuid not null,
    host              text not null check (host in ('github')),
    account           text not null check (length(account) between 1 and 100),
    repositories      text[] not null default '{}',
    status            text not null check (status in ('PENDING', 'ACTIVE', 'FAILED')),
    last_job_id       uuid,
    collecting_since  timestamptz,
    collected_job_id  uuid,
    last_error        text check (last_error ~ '^[a-z][a-z0-9_]{0,99}$'),
    last_collected_at timestamptz,
    truncated         boolean not null default false,
    connected_at      timestamptz not null,
    version           bigint not null default 0,
    constraint connection_one_per_tenant unique (tenant_id)
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
