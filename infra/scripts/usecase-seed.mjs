#!/usr/bin/env node
// 협업방 하나를 데이터로 떠 두었다가(export) 다른(빈) 환경에 그대로 넣는다(import).
//
//   node infra/scripts/usecase-seed.mjs import <이름>               예: import usecase1
//   node infra/scripts/usecase-seed.mjs export <threadId> <이름>
//
// <이름>은 이 파일 옆 폴더(infra/scripts/<이름>/)다. import가 끝나면 방 주소·로그인할 계정·대화 순서를 출력한다.
//
// 담는 것: 방·참여자·초대·메시지·첨부(원본 포함)·승인·출력·방 문서 상태·위험 커서(앱 DB)와
//          생성 파일·방 문서 원본(SeaweedFS). ES 색인은 담지 않는다 — 근거 검색은 되살아나지 않는다.
//
// tenant·워크스페이스 노드·사용자 ID와 Keycloak subject는 환경마다 다르다. 덤프 안의 이 UUID들을
// 새 환경 값(tenant_key, node_key, username으로 찾음)으로 글자 그대로 바꿔 넣는다. 파일 경로에 든 tenant ID도
// 같이 바뀐다. 방·메시지 ID는 그대로 두고, 같은 방이 있으면 지우고 다시 넣는다.
//
// 넣기 전에 대상 환경에 같은 workspace-setup 설정으로 bff가 한 번 떠 있고, 덤프에 나온 계정이 Keycloak에
// 있어야 한다(데모 계정이면 scripts/casbin-demo-accounts.mjs). 의존성 없음 — docker CLI만 쓴다.

import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..');
const PG = 'infra-postgres';
const WORKER = 'worker-doc';
const FILER = 'http://seaweedfs-filer:8888';
const UUID_RE = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g;

// 넣는 순서대로. where는 방 ID($1)로 고른다.
const TABLES = [
	['thr', 'id = $1'],
	['thr_mbr', 'thr_id = $1'],
	['thr_inv', 'thr_id = $1'],
	['msg', 'thr_id = $1'],
	['msg_file', 'msg_id in (select id from msg where thr_id = $1)'],
	['msg_file_org', 'file_id in (select f.id from msg_file f join msg m on m.id = f.msg_id where m.thr_id = $1)'],
	['thr_risk_crs', 'thr_id = $1'],
	['thr_apv', 'thr_id = $1'],
	['thr_out', 'thr_id = $1'],
	['thr_doc', 'thr_id = $1'],
	['thr_doc_evt', 'thr_id = $1'],
	['thr_doc_run', 'thr_id = $1'],
	['thr_doc_tag', 'thr_id = $1'],
];

const env = readEnv(join(ROOT, 'infra', '.env'));
const DB_USER = env.POSTGRES_USER || 'appuser';
const DB_NAME = env.POSTGRES_DB || 'appdb';
const REALM = env.KEYCLOAK_REALM || 'app-realm';

function readEnv(path) {
	if (!existsSync(path)) throw new Error(`${path}가 없다.`);
	const values = {};
	for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
		const match = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
		if (match) values[match[1]] = match[2];
	}
	return values;
}

// SQL은 stdin으로 넘긴다. 결과는 한 칸짜리 JSON 문자열을 기대한다.
function psql(db, sql) {
	const out = execFileSync('docker', ['exec', '-i', PG, 'psql', '-U', DB_USER, '-d', db, '-At', '-v', 'ON_ERROR_STOP=1'], {
		input: sql,
		maxBuffer: 1 << 30,
		stdio: ['pipe', 'pipe', 'inherit'],
	});
	return out.toString('utf8').trim();
}

function json(db, sql) {
	return JSON.parse(psql(db, sql) || 'null');
}

// 문자열 상수는 달러 인용으로 넣는다. 덤프 안에 같은 표시가 나올 일이 없도록 매번 새로 만든다.
function lit(text) {
	const tag = `s${randomUUID().replaceAll('-', '')}`;
	return `$${tag}$${text}$${tag}$`;
}

function uuidList(ids) {
	return ids.length ? `array[${ids.map((id) => `'${id}'`).join(',')}]::uuid[]` : `array[]::uuid[]`;
}

function filerGet(key) {
	return execFileSync('docker', ['exec', WORKER, 'python', '-c',
		'import sys,httpx; r=httpx.get(sys.argv[1], timeout=60); r.raise_for_status(); sys.stdout.buffer.write(r.content)',
		`${FILER}/${encodeURI(key)}`], { maxBuffer: 1 << 30 });
}

function filerPut(key, content) {
	execFileSync('docker', ['exec', '-i', WORKER, 'python', '-c',
		'import sys,httpx; r=httpx.post(sys.argv[1], files={"file": (sys.argv[2], sys.stdin.buffer.read(), "application/octet-stream")}, timeout=60); r.raise_for_status()',
		`${FILER}/${encodeURI(key)}`, key.split('/').pop()], { input: content });
}

function flywayVersion() {
	return psql(DB_NAME, 'select version from flyway_schema_history where success order by installed_rank desc limit 1;');
}

function sourceKey(doc) {
	const key = `thread-sources/${doc.tnn_id}/${doc.thr_id}/${doc.id}/${doc.src_key}`;
	return doc.src_att_id ? `${key}/${doc.src_att_id}` : key;
}

function exportThread(threadId, dir) {
	if (!/^[0-9a-f-]{36}$/.test(threadId)) throw new Error(`방 ID가 UUID가 아니다: ${threadId}`);
	const tables = {};
	for (const [table, where] of TABLES) {
		tables[table] = json(DB_NAME,
			`select coalesce(json_agg(t), '[]') from ${table} t where ${where.replaceAll('$1', `'${threadId}'`)};`);
	}
	if (tables.thr.length !== 1) throw new Error(`방이 없다: ${threadId}`);

	// 덤프에 나온 UUID 중 사용자·tenant·노드인 것만 골라 키를 함께 적어 둔다.
	const ids = [...new Set(JSON.stringify(tables).match(UUID_RE))];
	const users = json(DB_NAME,
		`select coalesce(json_agg(json_build_object('id', id, 'subj', keycloak_subj)), '[]') from app_user where id = any(${uuidList(ids)});`);
	const subjects = users.map((u) => u.subj);
	const names = json('keycloak',
		`select coalesce(json_object_agg(u.id, u.username), '{}') from user_entity u join realm r on r.id = u.realm_id
		 where r.name = ${lit(REALM)} and u.id = any(array[${subjects.map(lit).join(',') || "''"}]);`);
	for (const user of users) {
		user.username = names[user.subj];
		if (!user.username) throw new Error(`Keycloak에서 사용자를 찾지 못했다: app_user ${user.id}`);
	}
	const tenants = json(DB_NAME,
		`select coalesce(json_agg(json_build_object('id', id, 'key', tnn_key)), '[]') from tnn where id = any(${uuidList(ids)});`);
	const nodes = json(DB_NAME,
		`select coalesce(json_agg(json_build_object('id', n.id, 'tenantKey', t.tnn_key, 'key', n.node_key)), '[]')
		 from wrk_node n join tnn t on t.id = n.tnn_id where n.id = any(${uuidList(ids)});`);

	// 생성 파일과 방 문서 원본. 지운 문서의 원본은 이미 정리됐을 수 있어 건너뛴다.
	const keys = [
		...tables.thr_out.map((o) => o.file_key),
		...tables.thr_doc.filter((d) => d.status !== 'DELETED' && d.status !== 'UPLOADING').map(sourceKey),
	];
	mkdirSync(join(dir, 'files'), { recursive: true });
	const files = keys.map((key, index) => {
		const file = `files/${String(index).padStart(3, '0')}`;
		writeFileSync(join(dir, file), filerGet(key));
		return { key, file };
	});

	const seed = { threadId, flyway: flywayVersion(), exportedAt: new Date().toISOString(), tenants, nodes, users, files, tables };
	writeFileSync(join(dir, 'seed.json'), JSON.stringify(seed, null, 1));
	console.log(`방 ${tables.thr[0].title} → ${dir}`);
	console.log(`  메시지 ${tables.msg.length} · 출력 ${tables.thr_out.length} · 방 문서 ${tables.thr_doc.length} · 파일 ${files.length} · 사용자 ${users.map((u) => u.username).join(', ')}`);
}

function importThread(dir) {
	const seed = JSON.parse(readFileSync(join(dir, 'seed.json'), 'utf8'));
	const flyway = flywayVersion();
	if (flyway !== seed.flyway) console.warn(`주의: 스키마 버전이 다르다(덤프 ${seed.flyway}, 대상 ${flyway}). 넣다가 실패하면 덤프를 다시 뜬다.`);

	const map = new Map();
	for (const tenant of seed.tenants) {
		const id = psql(DB_NAME, `select id from tnn where tnn_key = ${lit(tenant.key)};`);
		if (!id) throw new Error(`대상에 tenant가 없다: ${tenant.key} — 같은 workspace-setup으로 bff를 먼저 띄운다.`);
		map.set(tenant.id, id);
	}
	for (const node of seed.nodes) {
		const id = psql(DB_NAME,
			`select n.id from wrk_node n join tnn t on t.id = n.tnn_id where t.tnn_key = ${lit(node.tenantKey)} and n.node_key = ${lit(node.key)};`);
		if (!id) throw new Error(`대상에 워크스페이스 노드가 없다: ${node.tenantKey}/${node.key}`);
		map.set(node.id, id);
	}
	const missing = [];
	for (const user of seed.users) {
		const subj = psql('keycloak',
			`select u.id from user_entity u join realm r on r.id = u.realm_id where r.name = ${lit(REALM)} and u.username = ${lit(user.username)};`);
		if (!subj) { missing.push(user.username); continue; }
		// 한 번도 로그인하지 않은 계정은 app_user가 없다. 로그인 때와 같은 모양으로 미리 만든다.
		let id = psql(DB_NAME, `select id from app_user where keycloak_subj = ${lit(subj)};`);
		if (!id) {
			id = randomUUID();
			psql(DB_NAME, `insert into app_user(id, keycloak_subj) values ('${id}', ${lit(subj)});`);
		}
		map.set(user.id, id);
		map.set(user.subj, subj);
	}
	if (missing.length) {
		throw new Error(`대상 Keycloak(${REALM})에 계정이 없다: ${missing.join(', ')} — 데모 계정이면 `
			+ '`node scripts/casbin-demo-accounts.mjs` 후 `docker restart infra-casbin`을 먼저 실행한다.');
	}

	const replaced = JSON.parse(JSON.stringify({ files: seed.files, tables: seed.tables }).replace(UUID_RE, (id) => map.get(id) ?? id));

	// 다시 넣을 때는 방째 지운다(하위 표는 cascade, 방 FK가 없는 문서 실행·태그만 따로). 지우면서 방 문서 정리 예약이
	// 생기므로 같은 트랜잭션에서 걷어 낸다 — 남겨 두면 정리 작업이 방금 올린 원본을 지운다.
	const docIds = replaced.tables.thr_doc.map((d) => d.id);
	const statements = [
		'begin;',
		`delete from thr_doc_run where thr_id = '${seed.threadId}';`,
		`delete from thr_doc_tag where thr_id = '${seed.threadId}';`,
		`delete from thr where id = '${seed.threadId}';`,
		...TABLES.filter(([table]) => replaced.tables[table].length).map(([table]) =>
			`insert into ${table} select * from json_populate_recordset(null::${table}, ${lit(JSON.stringify(replaced.tables[table]))});`),
		`delete from thr_doc_end where doc_id = any(${uuidList(docIds)});`,
		'commit;',
	];
	psql(DB_NAME, statements.join('\n'));

	for (const { key, file } of replaced.files) filerPut(key, readFileSync(join(dir, file)));

	const base = env.NEXTAUTH_URL || 'http://localhost:3010';
	console.log(`넣었다: ${seed.tables.thr[0].title} (메시지 ${seed.tables.msg.length} · 파일 ${replaced.files.length})`);
	console.log(`  ${base}/collab/${seed.threadId}`);
	printGuide(seed);
}

// 처음 보는 사람이 화면을 어떻게 따라가면 되는지 덤프에서 뽑아 보여 준다 — 누가 어떤 순서로 무엇을 했는지.
function printGuide(seed) {
	const names = json('keycloak',
		`select coalesce(json_object_agg(u.username, concat(u.last_name, u.first_name)), '{}') from user_entity u
		 join realm r on r.id = u.realm_id where r.name = ${lit(REALM)}
		 and u.username = any(array[${seed.users.map((u) => lit(u.username)).join(',')}]);`);
	const usernameById = new Map(seed.users.map((u) => [u.id, u.username]));
	const speaker = new Map(seed.tables.thr_mbr.map((m) => [m.id, usernameById.get(m.user_id)]));
	const label = (username) => `${names[username] || username}(${username})`;

	console.log('\n로그인 계정 (비밀번호는 아이디와 같다)');
	for (const user of seed.users) console.log(`  ${label(user.username)}`);
	console.log('\n대화 순서');
	const messages = seed.tables.msg.filter((m) => m.ath_kind === 'HUMAN').sort((a, b) => a.seq - b.seq);
	messages.forEach((m, i) => console.log(`  ${i + 1}. ${label(speaker.get(m.thr_mbr_id))}: ${m.content.replace(/\s+/g, ' ')}`));
	const approvals = seed.tables.thr_apv.length;
	const docs = seed.tables.thr_doc.filter((d) => d.status === 'READY');
	if (approvals) console.log(`  · 승인 ${approvals}건 (대화 속 승인 카드)`);
	if (docs.length) console.log(`  · 방 문서: ${docs.map((d) => d.file_name + (d.pnn ? '(고정)' : '')).join(', ')}`);
}

const [command, ...args] = process.argv.slice(2);
try {
	if (command === 'export' && args[0] && args[1]) exportThread(args[0], join(HERE, args[1]));
	else if (command === 'import' && args[0]) importThread(join(HERE, args[0]));
	else {
		console.error('사용: usecase-seed.mjs import <이름> | usecase-seed.mjs export <threadId> <이름>  (이름 = infra/scripts/<이름>/)');
		process.exit(1);
	}
} catch (error) {
	// psql 오류는 위에 그대로 찍혔다. 여기서는 실패만 알린다.
	console.error(`실패: ${error.message.split('\n')[0]}`);
	process.exit(1);
}
