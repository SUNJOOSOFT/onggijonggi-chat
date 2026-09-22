-- 04-DATA: v0.3 Tenant·조직 단위·Workspace 트리·권한 부여·권한 변경 감사 스키마(배포 1).
-- 기존 Thread 계열의 Tenant 컬럼은 다음 migration이 nullable로 확장하고, NOT NULL·복합 FK는 절체 PR이 강제한다.
-- 규칙(0001 6.0·6.2·6.4·6.14)을 DB가 직접 지키게 하는 것이 목적이라 trigger와 CHECK를 함께 둔다.

-- 만든 뒤 바꿀 수 없는 컬럼을 UPDATE로 바꾸려는 시도를 거부하는 공용 trigger 함수다(trigger 인자로 컬럼 이름을 받는다).
-- tnn_key·org_unit_key·node_key는 bootstrap 설정과 Keycloak claim이 가리키는 안정 식별자라 이름이 바뀌어도 그대로여야 하고,
-- org_unit.tnn_id는 그 key가 속한 Tenant 자체다. 다르게 쓰고 싶으면 새 행을 만든다. 어떤 계정이 UPDATE해도 거부한다.
create or replace function immutable_column_guard()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    -- 없는 컬럼 이름을 주면 두 값이 모두 null이라 비교가 늘 거짓이 된다. 조용히 통과하지 않도록 먼저 확인한다.
    if not jsonb_exists(to_jsonb(new), tg_argv[0]) then
        raise exception 'immutable_column_guard: unknown column %', tg_argv[0];
    end if;
    if to_jsonb(new) ->> tg_argv[0] is distinct from to_jsonb(old) ->> tg_argv[0] then
        raise exception '% is immutable', tg_argv[0];
    end if;
    return new;
end;
$$;

create table tnn (
    id          uuid         not null,
    tnn_key     varchar(64)  not null,
    name        varchar(255) not null,
    status      varchar(16)  not null default 'ACTIVE',
    inactive_at timestamptz,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    primary key (id),
    constraint tnn_key_format check (tnn_key ~ '^[a-z][a-z0-9-]{0,62}$'),
    constraint tnn_status_value check (status in ('ACTIVE', 'INACTIVE')),
    constraint tnn_status_time check ((status = 'ACTIVE') = (inactive_at is null)),
    constraint uq_tnn_key unique (tnn_key)
);

create trigger trg_tnn_key_immutable
before update of tnn_key on tnn
for each row execute function immutable_column_guard('tnn_key');

-- Tenant는 배포 설정의 status로만 켜고 끄며 물리 삭제하지 않는다. 하위 행이 없는 Tenant는 FK가 막지 못하지만,
-- bootstrap이 ROOT·COMMON·감사 행을 함께 만들어 실제로는 생기지 않는다. 그 우연에 기대지 않고 trigger가 직접 거부한다.
create or replace function tnn_no_delete()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    raise exception 'tenants are never physically deleted';
end;
$$;

create trigger trg_tnn_no_delete
before delete on tnn
for each row execute function tnn_no_delete();

create table org_unit (
    id           uuid         not null,
    tnn_id       uuid         not null,
    org_unit_key varchar(64)  not null,
    name         varchar(255) not null,
    status       varchar(16)  not null default 'ACTIVE',
    inactive_at  timestamptz,
    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now(),
    primary key (id),
    constraint fk_org_unit_tnn foreign key (tnn_id) references tnn (id) on delete restrict,
    constraint org_unit_key_format check (org_unit_key ~ '^[a-z][a-z0-9-]{0,62}$'),
    constraint org_unit_status_value check (status in ('ACTIVE', 'INACTIVE')),
    constraint org_unit_status_time check ((status = 'ACTIVE') = (inactive_at is null)),
    constraint uq_org_unit_tnn_id unique (tnn_id, id),
    constraint uq_org_unit_tnn_key unique (tnn_id, org_unit_key)
);

create trigger trg_org_unit_key_immutable
before update of org_unit_key on org_unit
for each row execute function immutable_column_guard('org_unit_key');

-- org-unit은 다른 Tenant로 옮길 수 없다. 부여가 붙은 행은 복합 FK도 막지만 부여가 없는 행은 막는 것이 없어 trigger가 직접 거부한다.
create trigger trg_org_unit_tnn_immutable
before update of tnn_id on org_unit
for each row execute function immutable_column_guard('tnn_id');

-- org-unit은 비활성화·보존 대상이라 물리 삭제하지 않는다(부여·감사·token의 key가 가리키는 근거). 부여가 없는 행도 마찬가지다.
create or replace function org_unit_no_delete()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    raise exception 'organization units are never physically deleted';
end;
$$;

create trigger trg_org_unit_no_delete
before delete on org_unit
for each row execute function org_unit_no_delete();

create table wrk_node (
    id          uuid         not null,
    tnn_id      uuid         not null,
    prn_id      uuid,
    node_key    varchar(64)  not null,
    kind        varchar(16)  not null,
    name        varchar(255) not null,
    path        uuid[]       not null,
    status      varchar(16)  not null default 'ACTIVE',
    inactive_at timestamptz,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    primary key (id),
    constraint fk_wrk_node_tnn foreign key (tnn_id) references tnn (id) on delete restrict,
    constraint uq_wrk_node_tnn_id unique (tnn_id, id),
    constraint fk_wrk_node_parent foreign key (tnn_id, prn_id) references wrk_node (tnn_id, id) on delete restrict,
    constraint wrk_node_key_format check (node_key ~ '^[a-z][a-z0-9-]{0,62}$'),
    constraint wrk_node_key_reserved check ((kind = 'ROOT') = (node_key = 'root') and (kind = 'COMMON') = (node_key = 'common')),
    constraint wrk_node_name_trimmed check (name ~ '^\S(.*\S)?$' and name !~ '[[:cntrl:]]'),
    constraint wrk_node_kind_value check (kind in ('ROOT', 'COMMON', 'ORG', 'WORK')),
    constraint wrk_node_status_value check (status in ('ACTIVE', 'INACTIVE')),
    constraint wrk_node_status_time check ((status = 'ACTIVE') = (inactive_at is null)),
    constraint uq_wrk_node_tnn_key unique (tnn_id, node_key)
);

create trigger trg_wrk_node_key_immutable
before update of node_key on wrk_node
for each row execute function immutable_column_guard('node_key');

-- 같은 부모 아래 활성 형제의 이름은 종류와 무관하게 유일하다(대소문자 무시, 루트의 null 부모도 같은 규칙).
create unique index ux_wrk_node_active_sibling_name
    on wrk_node (tnn_id, prn_id, lower(name)) nulls not distinct
    where status = 'ACTIVE';
create unique index ux_wrk_node_active_root on wrk_node (tnn_id)
    where kind = 'ROOT' and status = 'ACTIVE';
create unique index ux_wrk_node_active_common_parent on wrk_node (tnn_id, prn_id)
    where kind = 'COMMON' and status = 'ACTIVE';
create index ix_wrk_node_tnn_parent_status on wrk_node (tnn_id, prn_id, status);

create table wrk_grn (
    id              uuid        not null,
    tnn_id          uuid        not null,
    org_unit_id     uuid        not null,
    wrk_node_id     uuid        not null,
    role            varchar(16) not null,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    primary key (id),
    constraint fk_wrk_grn_tnn foreign key (tnn_id) references tnn (id) on delete restrict,
    constraint fk_wrk_grn_org_unit foreign key (tnn_id, org_unit_id) references org_unit (tnn_id, id) on delete restrict,
    constraint fk_wrk_grn_node foreign key (tnn_id, wrk_node_id) references wrk_node (tnn_id, id) on delete restrict,
    constraint wrk_grn_role_value check (role in ('VIEWER', 'CONTRIBUTOR', 'ADMIN')),
    constraint uq_wrk_grn_policy unique (tnn_id, org_unit_id, role, wrk_node_id)
);

-- wrk_node의 삭제·reparent 검사가 부여를 찾을 때 쓴다(FK 컬럼은 자동으로 인덱스가 생기지 않는다).
create index ix_wrk_grn_tnn_node on wrk_grn (tnn_id, wrk_node_id);

-- 판정 순서(첫 위반에서 거부): kind 불변 → 부모가 같은 Tenant에 있고 ACTIVE → 순환 → 깊이 → ROOT·COMMON 형태 → path 계산.
-- 부모 ACTIVE는 생성·reparent·재활성화 때만 요구한다. 그래야 부모가 이미 꺼진 뒤에도 자식의 이름 변경·비활성화가 된다.
-- "ACTIVE 노드의 조상은 모두 ACTIVE"는 비활성화가 자식부터(bottom-up) 일어나도록 강제해 지킨다.
create or replace function wrk_node_guard()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
declare
    parent_node wrk_node%rowtype;
begin
    if tg_op = 'UPDATE' then
        if new.kind <> old.kind then
            raise exception 'workspace kind is immutable';
        end if;
        if new.tnn_id <> old.tnn_id then
            raise exception 'workspace tenant is immutable';
        end if;
    end if;

    if new.kind = 'ROOT' then
        if new.prn_id is not null or new.path <> array[new.id] then
            raise exception 'ROOT must have no parent and a singleton path';
        end if;
    else
        if new.prn_id is null then
            raise exception 'workspace node must have a parent';
        end if;
        select * into parent_node from wrk_node where id = new.prn_id and tnn_id = new.tnn_id for share;
        if not found then
            raise exception 'workspace parent must exist in the same tenant';
        end if;
        if parent_node.status <> 'ACTIVE'
           and (tg_op = 'INSERT'
                or new.prn_id is distinct from old.prn_id
                or (old.status = 'INACTIVE' and new.status = 'ACTIVE')) then
            raise exception 'workspace parent must be active';
        end if;
        if new.id = any(parent_node.path) then
            raise exception 'workspace parent creates a cycle';
        end if;
        if cardinality(new.path) > 11 then
            raise exception 'workspace depth exceeds 11';
        end if;
        if new.kind = 'COMMON' and parent_node.kind <> 'ROOT' then
            raise exception 'COMMON must be a direct ROOT child';
        end if;
        if new.kind in ('ORG', 'WORK') and parent_node.kind = 'COMMON' then
            raise exception 'COMMON is a leaf workspace';
        end if;
        if new.path <> parent_node.path || new.id then
            raise exception 'workspace path must follow the parent path';
        end if;
    end if;

    if tg_op = 'UPDATE' then
        -- 자식·부여 생성은 이 행을 FOR SHARE로 잡는다. 검사 전에 자기 행을 먼저 잠가야 커밋 전 자식을 기다렸다가 본다.
        if new.prn_id is distinct from old.prn_id
           or (old.status = 'ACTIVE' and new.status = 'INACTIVE') then
            perform 1 from wrk_node where id = old.id for update;
        end if;
        if new.prn_id is distinct from old.prn_id then
            if exists (select 1 from wrk_node child where child.tnn_id = old.tnn_id and child.prn_id = old.id)
               or exists (select 1 from wrk_grn grant_row where grant_row.tnn_id = old.tnn_id and grant_row.wrk_node_id = old.id)
               or exists (select 1 from thr where thr.wrk_node_id = old.id) then
                raise exception 'only an unreferenced workspace leaf can be reparented';
            end if;
        end if;
        if old.kind in ('ROOT', 'COMMON') and new.status <> old.status then
            raise exception 'ROOT and COMMON follow tenant lifecycle only';
        end if;
        if old.status = 'ACTIVE' and new.status = 'INACTIVE'
           and exists (select 1 from wrk_node child where child.tnn_id = old.tnn_id and child.prn_id = old.id and child.status = 'ACTIVE') then
            raise exception 'deactivate child workspaces first';
        end if;
    end if;
    return new;
end;
$$;

create trigger trg_wrk_node_guard
before insert or update on wrk_node
for each row execute function wrk_node_guard();

-- Workspace 노드는 물리 삭제하지 않는다. ORG·WORK는 비활성화만, ROOT·COMMON은 Tenant 생명주기만 따른다.
create or replace function wrk_node_no_delete()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    raise exception 'workspace nodes are never physically deleted';
end;
$$;

create trigger trg_wrk_node_no_delete
before delete on wrk_node
for each row execute function wrk_node_no_delete();

-- 부여는 org-unit·workspace가 ACTIVE이고 ROOT가 아닐 때만 만들거나 바꾼다. 바꿀 수 있는 것은 role뿐이다(POLICY_REPLACED).
create or replace function wrk_grn_guard()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    if tg_op = 'UPDATE' and (new.tnn_id <> old.tnn_id or new.org_unit_id <> old.org_unit_id or new.wrk_node_id <> old.wrk_node_id) then
        raise exception 'only the role of a workspace grant can change';
    end if;
    -- 대상 행을 FOR SHARE로 잡아 검사 뒤 커밋 전에 org-unit·노드가 비활성화되는 경쟁을 막는다.
    perform 1 from org_unit where id = new.org_unit_id and tnn_id = new.tnn_id and status = 'ACTIVE' for share;
    if not found then
        raise exception 'workspace grant requires an active organization unit';
    end if;
    perform 1 from wrk_node where id = new.wrk_node_id and tnn_id = new.tnn_id and status = 'ACTIVE' and kind <> 'ROOT' for share;
    if not found then
        raise exception 'workspace grant requires an active non-ROOT node';
    end if;
    new.updated_at := now();
    return new;
end;
$$;

create trigger trg_wrk_grn_guard
before insert or update on wrk_grn
for each row execute function wrk_grn_guard();

-- ACTIVE org-unit의 COMMON VIEWER 부여는 삭제하거나 역할을 바꿀 수 없다. DIRECT가 COMMON VIEW를 요구해서, 이 부여가 사라지면
-- 그 org-unit 사람들이 개인 채팅을 못 쓴다. ADMIN API도 같은 규칙으로 거절해야 하지만 DB가 마지막 방어선이다.
-- org-unit의 상태와 무관하게 막는다. 비활성 중에는 허용하면 "끄고 → 지우고 → 켠다"로 ACTIVE org-unit에 필수 부여가
-- 없는 상태를 만들 수 있고, 비활성화 뒤에도 부여를 보존하는 이유 자체가 재활성화 때 끄기 전 상태로 돌아가는 것이다.
-- org-unit은 물리 삭제하지 않으므로 이 부여는 사실상 영구 보존된다.
create or replace function wrk_grn_required_guard()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    if old.role = 'VIEWER'
       and (tg_op = 'DELETE' or new.role <> old.role)
       and exists (select 1 from wrk_node node where node.id = old.wrk_node_id and node.tnn_id = old.tnn_id and node.kind = 'COMMON') then
        raise exception 'the COMMON VIEWER grant of an organization unit is required';
    end if;
    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end;
$$;

create trigger trg_wrk_grn_required_guard
before delete or update of role on wrk_grn
for each row execute function wrk_grn_required_guard();

create table authz_adt (
    id            uuid         not null,
    tnn_id        uuid         not null,
    act_kind      varchar(16)  not null,
    act_user_id   uuid,
    act_role_json jsonb        not null,
    evt_kind      varchar(32)  not null,
    trg_kind      varchar(32)  not null,
    trg_ref       jsonb        not null,
    wrk_node_id   uuid,
    bfr_json      jsonb,
    aft_json      jsonb,
    req_id        varchar(255),
    trc_id        varchar(64),
    dpl_id        varchar(128),
    cnf_fgpt      varchar(128),
    created_at    timestamptz  not null default now(),
    primary key (id),
    constraint fk_authz_adt_tnn foreign key (tnn_id) references tnn (id) on delete restrict,
    constraint fk_authz_adt_actor foreign key (act_user_id) references app_user (id) on delete restrict,
    constraint authz_adt_actor_value check ((act_kind = 'USER' and act_user_id is not null) or (act_kind = 'SYSTEM' and act_user_id is null)),
    constraint authz_adt_evt_value check (evt_kind in (
        'TENANT_CREATED', 'TENANT_RENAMED', 'TENANT_DEACTIVATED', 'TENANT_REACTIVATED',
        'ORG_UNIT_CREATED', 'ORG_UNIT_RENAMED', 'ORG_UNIT_DEACTIVATED', 'ORG_UNIT_REACTIVATED',
        'NODE_CREATED', 'NODE_RENAMED', 'NODE_REPARENTED', 'NODE_DEACTIVATED', 'NODE_REACTIVATED',
        'POLICY_ADDED', 'POLICY_REMOVED', 'POLICY_REPLACED', 'THREAD_MOVED', 'OWNER_TRANSFERRED',
        'TENANT_DRIFT_DETECTED')),
    constraint authz_adt_target_value check (trg_kind in ('TENANT', 'ORG_UNIT', 'WORKSPACE', 'POLICY', 'THREAD'))
);

create index ix_authz_adt_tnn_created on authz_adt (tnn_id, created_at desc, id desc);
create index ix_authz_adt_tnn_node_created on authz_adt (tnn_id, wrk_node_id, created_at desc, id desc);

-- 감사 행은 계정과 무관하게 UPDATE·DELETE·TRUNCATE를 모두 거부하고 v0.3은 삭제 경로를 두지 않는다(영구 보존).
-- trigger는 기본 상태에서 session_replication_role = replica로 우회할 수 있어 ENABLE ALWAYS로 고정한다.
create or replace function authz_adt_append_only()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    raise exception 'authorization audit rows are append-only';
end;
$$;

create trigger trg_authz_adt_append_only
before update or delete on authz_adt
for each row execute function authz_adt_append_only();

create trigger trg_authz_adt_no_truncate
before truncate on authz_adt
for each statement execute function authz_adt_append_only();

-- 행 단위 삭제를 거부하는 표는 TRUNCATE도 거부한다. TRUNCATE는 행 trigger를 부르지 않아 따로 걸어야 한다.
create trigger trg_tnn_no_truncate
before truncate on tnn
for each statement execute function tnn_no_delete();

create trigger trg_org_unit_no_truncate
before truncate on org_unit
for each statement execute function org_unit_no_delete();

create trigger trg_wrk_node_no_truncate
before truncate on wrk_node
for each statement execute function wrk_node_no_delete();

-- 위 guard는 전부 ENABLE ALWAYS로 고정한다. 기본 상태의 trigger는 session_replication_role = replica면 건너뛰는데,
-- 이 저장소의 앱 계정이 그 설정을 바꿀 수 있어서 고정하지 않으면 "어떤 계정으로도 거부한다"가 성립하지 않는다.
alter table tnn enable always trigger trg_tnn_key_immutable;
alter table tnn enable always trigger trg_tnn_no_delete;
alter table tnn enable always trigger trg_tnn_no_truncate;
alter table org_unit enable always trigger trg_org_unit_key_immutable;
alter table org_unit enable always trigger trg_org_unit_tnn_immutable;
alter table org_unit enable always trigger trg_org_unit_no_delete;
alter table org_unit enable always trigger trg_org_unit_no_truncate;
alter table wrk_node enable always trigger trg_wrk_node_key_immutable;
alter table wrk_node enable always trigger trg_wrk_node_guard;
alter table wrk_node enable always trigger trg_wrk_node_no_delete;
alter table wrk_node enable always trigger trg_wrk_node_no_truncate;
alter table wrk_grn enable always trigger trg_wrk_grn_guard;
alter table wrk_grn enable always trigger trg_wrk_grn_required_guard;
alter table authz_adt enable always trigger trg_authz_adt_append_only;
alter table authz_adt enable always trigger trg_authz_adt_no_truncate;
