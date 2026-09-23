-- 04·DATA
-- 변경 이유: Casbin ABAC 판정에 넘길 사람의 팀·직급(org_unit_mbr)과 직급 서열 규칙(rank_grn)을 둔다.
--           Keycloak은 USER 역할만 주므로 팀·직급의 정본은 이 DB다. Casbin 서버는 규칙만 들고 사람은 모른다.
-- 기존 데이터 전제: 새 표 둘만 만든다. 기존 표는 건드리지 않는다.
-- 이관·중단 조건: 없다. 두 표 모두 비어 있는 채로 시작한다.

-- 사람 한 명의 팀·직급 배정이다. 사람은 Keycloak subject(sub)로 가리키고 FK를 걸지 않는다 — 아직 로그인하지 않아
-- app_user 행이 없는 사람도 배정해야 해서다(thr_inv.subj와 같은 이유). 겸직이 없어 subj당 한 행이다. 겸직이 생기면
-- uq_org_unit_mbr_subj를 (subj, org_unit_id)로 바꾼다.
create table org_unit_mbr (
    id          uuid         not null,
    tnn_id      uuid         not null,
    org_unit_id uuid         not null,
    subj        varchar(255) not null,
    rank        varchar(8)   not null,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    primary key (id),
    constraint fk_org_unit_mbr_tnn foreign key (tnn_id) references tnn (id) on delete restrict,
    constraint fk_org_unit_mbr_org_unit foreign key (tnn_id, org_unit_id) references org_unit (tnn_id, id) on delete restrict,
    constraint org_unit_mbr_rank_value check (rank in ('TL', 'B', 'C', 'K', 'D', 'S')),
    constraint uq_org_unit_mbr_subj unique (subj)
);

create index ix_org_unit_mbr_tnn_unit on org_unit_mbr (tnn_id, org_unit_id);

-- 배정은 ACTIVE org-unit에만 만들거나 옮긴다. 직급만 바꾸는 UPDATE는 org-unit 상태와 무관하게 허용한다.
create or replace function org_unit_mbr_guard()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    if tg_op = 'INSERT' or new.org_unit_id <> old.org_unit_id or new.tnn_id <> old.tnn_id then
        -- 대상 행을 FOR SHARE로 잡아 검사 뒤 커밋 전에 org-unit이 비활성화되는 경쟁을 막는다(wrk_grn_guard와 같은 방식).
        perform 1 from org_unit where id = new.org_unit_id and tnn_id = new.tnn_id and status = 'ACTIVE' for share;
        if not found then
            raise exception 'organization unit member requires an active organization unit';
        end if;
    end if;
    new.updated_at := now();
    return new;
end;
$$;

create trigger trg_org_unit_mbr_guard
before insert or update on org_unit_mbr
for each row execute function org_unit_mbr_guard();

-- 직급 서열 규칙이다. rank 이상(서열 숫자가 같거나 작은) 직급이면 wrk_node를 볼 수 있다. 서열(TL 1 ~ S 6)은
-- 코드의 Rank enum이 정본이다. wrk_grn(팀 규칙)과 짝이고 같은 대상 규칙을 따른다.
create table rank_grn (
    id          uuid        not null,
    tnn_id      uuid        not null,
    wrk_node_id uuid        not null,
    rank        varchar(8)  not null,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    primary key (id),
    constraint fk_rank_grn_tnn foreign key (tnn_id) references tnn (id) on delete restrict,
    constraint fk_rank_grn_node foreign key (tnn_id, wrk_node_id) references wrk_node (tnn_id, id) on delete restrict,
    constraint rank_grn_rank_value check (rank in ('TL', 'B', 'C', 'K', 'D', 'S')),
    constraint uq_rank_grn_policy unique (tnn_id, rank, wrk_node_id)
);

create index ix_rank_grn_tnn_node on rank_grn (tnn_id, wrk_node_id);

-- 규칙은 ACTIVE이고 ROOT가 아닌 노드에만 만들거나 바꾼다. 바꿀 수 있는 것은 rank뿐이다.
create or replace function rank_grn_guard()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    if tg_op = 'UPDATE' and (new.tnn_id <> old.tnn_id or new.wrk_node_id <> old.wrk_node_id) then
        raise exception 'only the rank of a rank grant can change';
    end if;
    perform 1 from wrk_node where id = new.wrk_node_id and tnn_id = new.tnn_id and status = 'ACTIVE' and kind <> 'ROOT' for share;
    if not found then
        raise exception 'rank grant requires an active non-ROOT node';
    end if;
    new.updated_at := now();
    return new;
end;
$$;

create trigger trg_rank_grn_guard
before insert or update on rank_grn
for each row execute function rank_grn_guard();

-- #264의 guard와 같이 ENABLE ALWAYS로 고정한다(session_replication_role = replica로 건너뛰지 못하게).
alter table org_unit_mbr enable always trigger trg_org_unit_mbr_guard;
alter table rank_grn enable always trigger trg_rank_grn_guard;
