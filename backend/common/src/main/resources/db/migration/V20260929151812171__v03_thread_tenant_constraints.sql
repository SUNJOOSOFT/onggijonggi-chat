-- 04·DATA: Tenant backfill을 검증한 뒤 Thread 계열의 Tenant·Workspace 경계를 잠근다.
-- 앞 migration에서 미귀속·교차 Tenant 행이 있으면 이 파일까지 도달하지 못한다.
-- 부모·메시지 참조의 새 복합 FK를 먼저 검증한 후 옛 단일 FK를 제거한다.

-- 앱이 살아 있어 잠금을 오래 못 잡으면 대기열을 만들지 않고 빨리 실패하게 한다(점검창에서는 바로 잡힌다).
set local lock_timeout = '10s';

alter table thr alter column tnn_id set not null, alter column wrk_node_id set not null;
alter table thr_mbr alter column tnn_id set not null;
alter table msg alter column tnn_id set not null;
alter table thr_idm_key alter column tnn_id set not null;
alter table msg_idm_key alter column tnn_id set not null;
alter table thr_inv alter column tnn_id set not null;
alter table thr_risk_crs alter column tnn_id set not null;

alter table thr add constraint uq_thr_tnn_id unique (tnn_id, id);
alter table thr add constraint fk_thr_workspace_tnn
    foreign key (tnn_id, wrk_node_id) references wrk_node (tnn_id, id) on delete restrict;
create trigger trg_thr_tnn_immutable before update of tnn_id on thr
    for each row execute function immutable_column_guard('tnn_id');
alter table thr enable always trigger trg_thr_tnn_immutable;

alter table thr_mbr add constraint uq_thr_mbr_tnn_thr_id unique (tnn_id, thr_id, id);
alter table msg add constraint uq_msg_tnn_thr_id unique (tnn_id, thr_id, id);

alter table thr_mbr add constraint fk_thr_mbr_tnn_thr
    foreign key (tnn_id, thr_id) references thr (tnn_id, id) on delete cascade;
alter table msg add constraint fk_msg_tnn_thr
    foreign key (tnn_id, thr_id) references thr (tnn_id, id) on delete cascade;
alter table thr_idm_key add constraint fk_thr_idm_key_tnn_thr
    foreign key (tnn_id, thr_id) references thr (tnn_id, id) on delete cascade;
alter table msg_idm_key add constraint fk_msg_idm_key_tnn_thr
    foreign key (tnn_id, thr_id) references thr (tnn_id, id) on delete cascade;
alter table thr_inv add constraint fk_thr_inv_tnn_thr
    foreign key (tnn_id, thr_id) references thr (tnn_id, id) on delete cascade;
alter table thr_risk_crs add constraint fk_thr_risk_crs_tnn_thr
    foreign key (tnn_id, thr_id) references thr (tnn_id, id) on delete cascade;

alter table msg add constraint fk_msg_tnn_reply
    foreign key (tnn_id, thr_id, rpl_msg_id) references msg (tnn_id, thr_id, id);
alter table msg add constraint fk_msg_tnn_member
    foreign key (tnn_id, thr_id, thr_mbr_id) references thr_mbr (tnn_id, thr_id, id);
alter table msg_idm_key add constraint fk_msg_idm_key_tnn_human
    foreign key (tnn_id, thr_id, hmn_msg_id) references msg (tnn_id, thr_id, id);
alter table msg_idm_key add constraint fk_msg_idm_key_tnn_agent
    foreign key (tnn_id, thr_id, agn_msg_id) references msg (tnn_id, thr_id, id);

alter table thr_mbr drop constraint thr_mbr_thr_id_fkey;
alter table msg drop constraint msg_thr_id_fkey;
alter table thr_idm_key drop constraint thr_idm_key_thr_id_fkey;
alter table msg_idm_key drop constraint msg_idm_key_thr_id_fkey;
alter table thr_inv drop constraint thr_inv_thr_id_fkey;
alter table thr_risk_crs drop constraint thr_risk_crs_thr_id_fkey;
alter table msg drop constraint msg_rpl_msg_id_fkey;
alter table msg drop constraint msg_thr_mbr_id_fkey;
alter table msg_idm_key drop constraint msg_idm_key_hmn_msg_id_fkey;
alter table msg_idm_key drop constraint msg_idm_key_agn_msg_id_fkey;

drop index ux_thr_idm_key_user_key;
create unique index ux_thr_idm_key_user_key on thr_idm_key (tnn_id, user_id, idm_key);
drop index ux_msg_idm_key_user_key;
create unique index ux_msg_idm_key_user_key on msg_idm_key (tnn_id, user_id, idm_key);
