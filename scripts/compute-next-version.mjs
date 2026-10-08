#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const STABLE_TAG = /^v\d+\.\d+\.\d+$/;

function git(args) {
  return execFileSync('git', args, { encoding: 'utf8' }).trim();
}

function compareSemver(a, b) {
  const pa = a.slice(1).split('.').map(Number);
  const pb = b.slice(1).split('.').map(Number);
  for (let i = 0; i < 3; i += 1) {
    if (pa[i] !== pb[i]) return pa[i] - pb[i];
  }
  return 0;
}

// minor·major는 커밋 타입으로 올리지 않는다. 한 차수를 마치고 정식 배포할 때 저장소 루트의
// release-as 파일에 올릴 버전(예: v0.4.0)을 적는다. 마지막 정식 태그보다 클 때만 쓰이므로,
// 릴리스된 뒤에는 지우지 않아도 저절로 무시된다.
function readReleaseAs() {
  const path = join(git(['rev-parse', '--show-toplevel']), 'release-as');
  if (!existsSync(path)) return null;
  const value = readFileSync(path, 'utf8').trim();
  if (!STABLE_TAG.test(value)) {
    throw new Error(`release-as 형식이 vX.Y.Z가 아니다: "${value}"`);
  }
  return value;
}

const stableTags = git(['tag', '--merged', 'HEAD', '--list', 'v*'])
  .split('\n')
  .filter((tag) => STABLE_TAG.test(tag))
  .sort(compareSemver);

const lastTag = stableTags.at(-1) ?? 'v0.0.0';
const [major, minor, patch] = lastTag.slice(1).split('.').map(Number);

let bump = 'patch';
let nextVersion = `v${major}.${minor}.${patch + 1}`;

const releaseAs = readReleaseAs();
if (releaseAs && compareSemver(releaseAs, lastTag) > 0) {
  bump = 'release-as';
  nextVersion = releaseAs;
}

process.stdout.write(JSON.stringify({ lastTag, bump, nextVersion }));
