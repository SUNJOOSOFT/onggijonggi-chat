#!/usr/bin/env node
// 팀·직급 배정 CSV(email,team,rank)를 bff에 넣는다. 기본은 미리보기이고 --apply를 줘야 저장한다.
// 사용: node scripts/import-members.mjs <파일.csv> [--apply]
//   예) node scripts/import-members.mjs infra/config/demo-members.csv
//       node scripts/import-members.mjs infra/config/demo-members.csv --apply
//
// bff의 casbin 프로필이 켜져 있어야 한다(infra/.env에 SPRING_PROFILE=prod,casbin). 꺼져 있으면 임포트 주소가 없다(404).
// 한 줄이라도 틀리면 아무것도 저장하지 않고 틀린 줄을 모두 보여준다. CSV에 없는 사람의 배정은 지우지 않는다.
// 이력에는 이 스크립트로 로그인한 사람이 행위자로 남는다. 기본은 infra/.env의 APP_USER이고
// IMPORT_USER·IMPORT_PASSWORD로 바꿀 수 있다. 주소는 BFF_URL(기본 http://localhost:8090)과
// KEYCLOAK_URL(기본 http://localhost:8081)로 바꿀 수 있다.
//
// 의존성 없음(node 내장만) — 저장소 스크립트의 무설치 단독 실행 전제를 따른다.

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const ENV_PATH = join(ROOT, 'infra', '.env');
const OUTCOME_LABELS = { ASSIGNED: '새로 배정', CHANGED: '변경', UNCHANGED: '그대로', UNASSIGNED: '해제' };

function readEnv(path) {
	if (!existsSync(path)) throw new Error(`${path}가 없다. infra/.env.example을 복사해 먼저 만든다.`);
	const values = {};
	for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
		const match = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
		if (match) values[match[1]] = match[2];
	}
	return values;
}

async function login(keycloak, env) {
	const response = await fetch(`${keycloak}/realms/${env.KEYCLOAK_REALM || 'app-realm'}/protocol/openid-connect/token`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
		body: new URLSearchParams({
			grant_type: 'password',
			client_id: env.KEYCLOAK_CLIENT_ID || 'ogjg-client',
			client_secret: env.KEYCLOAK_CLIENT_SECRET,
			username: process.env.IMPORT_USER || env.APP_USER || 'appuser',
			password: process.env.IMPORT_PASSWORD || env.APP_USER_PASSWORD,
			scope: 'openid',
		}),
	});
	if (!response.ok) throw new Error(`로그인하지 못했다 → ${response.status} ${await response.text()}`);
	return (await response.json()).access_token;
}

async function main() {
	const args = process.argv.slice(2);
	const apply = args.includes('--apply');
	const file = args.find((arg) => !arg.startsWith('--'));
	if (!file) throw new Error('사용: node scripts/import-members.mjs <파일.csv> [--apply]');
	const csv = readFileSync(file, 'utf8');
	const env = readEnv(ENV_PATH);
	const bff = (process.env.BFF_URL || 'http://localhost:8090').replace(/\/$/, '');
	const token = await login((process.env.KEYCLOAK_URL || 'http://localhost:8081').replace(/\/$/, ''), env);

	const response = await fetch(`${bff}/api/authz/members/import?apply=${apply}`, {
		method: 'POST',
		headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'text/csv; charset=utf-8' },
		body: csv,
	});
	if (response.status === 404) throw new Error('임포트 주소가 없다 — bff의 casbin 프로필이 켜져 있는지 확인한다(SPRING_PROFILE=prod,casbin)');
	if (!response.ok) throw new Error(`임포트 실패 → ${response.status} ${await response.text()}`);
	const report = await response.json();

	for (const row of report.rows) {
		console.log(`${String(row.line).padStart(4)}줄  ${row.email.padEnd(28)} ${row.team.padEnd(8)} ${row.rank.padEnd(3)} ${OUTCOME_LABELS[row.outcome] || row.outcome}`);
	}
	if (report.problems.length > 0) {
		console.log(`\n틀린 줄 ${report.problems.length}개 — 아무것도 저장하지 않았다.`);
		for (const problem of report.problems) console.log(`${String(problem.line).padStart(4)}줄  ${problem.message}`);
		// process.exit()는 쓰지 않는다 — Windows의 Node에서 fetch 연결이 닫히는 중에 부르면 libuv 단언으로 죽는다.
		process.exitCode = 1;
		return;
	}
	const summary = Object.entries(report.counts).map(([outcome, count]) => `${OUTCOME_LABELS[outcome] || outcome} ${count}`).join(', ');
	console.log(`\n${summary || '넣을 줄이 없다'}`);
	const changed = report.rows.some((row) => row.outcome !== 'UNCHANGED');
	if (!report.applied) console.log('미리보기다. 저장하려면 --apply를 붙인다.');
	else console.log(changed ? '저장했다.' : '바뀐 것이 없어 이력도 남기지 않았다.');
}

main().catch((error) => {
	console.error(error.message);
	process.exitCode = 1;
});
