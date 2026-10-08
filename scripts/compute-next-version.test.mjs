/********************************************************
 파일명 : compute-next-version.test.mjs
 설 명 : compute-next-version.mjs가 커밋 타입으로 minor·major를 올리지 않고 release-as로만
 올리는지 검증한다. `node --test`로 실행한다.
 *********************************************************/

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), 'compute-next-version.mjs');

function createRepo(commits, tag = 'v0.2.15') {
	const root = mkdtempSync(join(tmpdir(), 'next-version-'));
	const git = (...args) =>
		execFileSync(
			'git',
			['-c', 'user.name=test', '-c', 'user.email=test@example.com', '-c', 'commit.gpgsign=false', ...args],
			{ cwd: root, stdio: 'pipe' },
		);
	git('init', '-q');
	git('commit', '--allow-empty', '-q', '-m', 'chore: init');
	git('tag', tag);
	for (const message of commits) git('commit', '--allow-empty', '-q', '-m', message);
	return root;
}

function compute(root) {
	return JSON.parse(execFileSync(process.execPath, [SCRIPT], { cwd: root, encoding: 'utf8', stdio: 'pipe' }));
}

function withRepo(commits, tag, body) {
	const root = createRepo(commits, tag);
	try {
		body(root);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
}

test('feat 커밋이 있어도 patch만 올린다', () => {
	withRepo(['feat: 새 기능'], 'v0.2.15', (root) => {
		assert.deepEqual(compute(root), { lastTag: 'v0.2.15', bump: 'patch', nextVersion: 'v0.2.16' });
	});
});

test('breaking change도 자동으로 major를 올리지 않는다', () => {
	withRepo(['feat!: 호환 깨짐'], 'v0.2.15', (root) => {
		assert.equal(compute(root).nextVersion, 'v0.2.16');
	});
});

test('release-as가 마지막 정식 태그보다 크면 그 버전을 쓴다', () => {
	withRepo(['feat: 새 기능'], 'v0.3.0', (root) => {
		writeFileSync(join(root, 'release-as'), 'v0.4.0\n');
		assert.deepEqual(compute(root), { lastTag: 'v0.3.0', bump: 'release-as', nextVersion: 'v0.4.0' });
	});
});

test('release-as가 마지막 정식 태그 이하이면 무시한다', () => {
	withRepo(['fix: 수정'], 'v0.4.0', (root) => {
		writeFileSync(join(root, 'release-as'), 'v0.4.0\n');
		assert.deepEqual(compute(root), { lastTag: 'v0.4.0', bump: 'patch', nextVersion: 'v0.4.1' });
	});
});

test('release-as 형식이 틀리면 실패한다', () => {
	withRepo(['fix: 수정'], 'v0.3.0', (root) => {
		writeFileSync(join(root, 'release-as'), '0.4\n');
		assert.throws(() => compute(root));
	});
});

test('정식 태그가 없으면 v0.0.0 기준으로 patch를 올린다', () => {
	const root = mkdtempSync(join(tmpdir(), 'next-version-'));
	try {
		const git = (...args) =>
			execFileSync(
				'git',
				['-c', 'user.name=test', '-c', 'user.email=test@example.com', '-c', 'commit.gpgsign=false', ...args],
				{ cwd: root, stdio: 'pipe' },
			);
		git('init', '-q');
		git('commit', '--allow-empty', '-q', '-m', 'feat: 처음');
		assert.deepEqual(compute(root), { lastTag: 'v0.0.0', bump: 'patch', nextVersion: 'v0.0.1' });
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
