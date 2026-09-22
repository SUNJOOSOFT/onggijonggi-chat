'use client';

/********************************************************
 파일명 : permissions-admin.tsx (components/permissions)
 설 명 : 권한 관리 화면(/admin/permissions). 사람마다 팀·직급을 고르면 바로 저장하고, 아래 "누가 무엇을 보나" 표를
 서버의 실제 판정으로 다시 그린다. CSV(email,team,rank)는 미리보기 뒤 저장한다. 판정은 흉내 내지 않는다 —
 표의 O/-는 bff가 Casbin에 물어본 결과 그대로다.
 bff의 casbin 프로필에서만 API가 있다. 꺼져 있으면 404라 안내만 보여준다. v0.3은 실제 사용자에게 나가지 않아
 로그인한 누구나 바꿀 수 있다 — 실제 배포 전에 ADMIN만 쓰게 막는다.
 *********************************************************/

import { type ChangeEvent, useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';

import { SidebarToggle } from '@/components/sidebar-toggle';
import { Button } from '@/components/ui/button';
import {
  type ImportReport,
  type PermissionsOverview,
  fetchPermissionsOverview,
  importMembersCsv,
  saveAssignment,
} from '@/lib/api/permissions';

const SELECT_CLASS =
  'h-8 rounded-md border border-input bg-background px-2 text-sm disabled:cursor-not-allowed disabled:opacity-50';

const OUTCOME_LABELS: Record<string, string> = {
  ASSIGNED: '새로 배정',
  CHANGED: '변경',
  UNCHANGED: '그대로',
  UNASSIGNED: '해제',
};

export function PermissionsAdmin() {
  const [overview, setOverview] = useState<PermissionsOverview | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [savingSubject, setSavingSubject] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      setOverview(await fetchPermissionsOverview());
      setFailed(null);
    } catch (error) {
      setFailed(error instanceof Error ? error.message : '');
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  async function change(
    subject: string,
    teamId: string | null,
    rank: string | null,
  ) {
    setSavingSubject(subject);
    try {
      await saveAssignment(subject, teamId, rank);
      await reload();
    } catch (error) {
      toast.error(
        `저장하지 못했습니다. ${error instanceof Error ? error.message : ''}`,
      );
    } finally {
      setSavingSubject(null);
    }
  }

  return (
    <div className="flex h-dvh flex-col">
      <header className="flex sticky top-0 items-center gap-2 border-b bg-background px-2 py-1.5">
        <SidebarToggle />
        <h1 className="text-sm font-semibold">권한 관리</h1>
      </header>

      <div className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-8 overflow-y-auto p-6">
        <p className="rounded-lg bg-muted px-4 py-3 text-sm">
          팀·직급을 바꾸면 바로 저장되고, 아래 표가 실제 권한 판정 결과로 다시
          그려집니다. 개발·데모용이라 지금은 로그인한 누구나 바꿀 수 있습니다.
        </p>

        {failed !== null && (
          <p className="rounded-lg bg-muted px-4 py-3 text-sm" role="alert">
            불러오지 못했습니다. 권한 기능(casbin)이 켜져 있는지 확인해 주세요.{' '}
            {failed}
          </p>
        )}
        {failed === null && overview === null && (
          <p className="text-sm text-muted-foreground">불러오는 중…</p>
        )}

        {overview && (
          <>
            <AssignmentTable
              overview={overview}
              savingSubject={savingSubject}
              onChange={change}
            />
            <VisibilityTable overview={overview} />
            <CsvImport onApplied={reload} />
          </>
        )}
      </div>
    </div>
  );
}

function AssignmentTable({
  overview,
  savingSubject,
  onChange,
}: {
  overview: PermissionsOverview;
  savingSubject: string | null;
  onChange: (
    subject: string,
    teamId: string | null,
    rank: string | null,
  ) => void;
}) {
  return (
    <section className="flex flex-col gap-3">
      <h2 className="text-base font-semibold">배정</h2>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-muted-foreground">
            <th className="py-2 font-medium">사람</th>
            <th className="py-2 font-medium">팀</th>
            <th className="py-2 font-medium">직급</th>
          </tr>
        </thead>
        <tbody>
          {overview.people.map((person) => {
            const saving = savingSubject === person.subject;
            return (
              <tr key={person.subject} className="border-b">
                <td className="py-2">
                  <span className="font-medium">{person.name}</span>{' '}
                  <span className="text-muted-foreground">
                    {person.username}
                  </span>
                  {!person.enabled && (
                    <span className="ml-2 text-xs text-muted-foreground">
                      (비활성)
                    </span>
                  )}
                </td>
                <td className="py-2">
                  <select
                    aria-label={`${person.name} 팀`}
                    className={SELECT_CLASS}
                    disabled={saving}
                    value={person.teamId ?? ''}
                    onChange={(event: ChangeEvent<HTMLSelectElement>) => {
                      const teamId = event.target.value || null;
                      // 팀을 처음 고르면 직급이 없으니 가장 낮은 직급으로 시작한다.
                      onChange(
                        person.subject,
                        teamId,
                        teamId ? (person.rank ?? 'S') : null,
                      );
                    }}
                  >
                    <option value="">미배정</option>
                    {overview.teams.map((team) => (
                      <option key={team.id} value={team.id}>
                        {team.name}
                      </option>
                    ))}
                  </select>
                </td>
                <td className="py-2">
                  <select
                    aria-label={`${person.name} 직급`}
                    className={SELECT_CLASS}
                    disabled={saving || person.teamId === null}
                    value={person.rank ?? ''}
                    onChange={(event: ChangeEvent<HTMLSelectElement>) =>
                      onChange(
                        person.subject,
                        person.teamId,
                        event.target.value,
                      )
                    }
                  >
                    {person.teamId === null && <option value="">-</option>}
                    {overview.ranks.map((rank) => (
                      <option key={rank.code} value={rank.code}>
                        {rank.label}
                      </option>
                    ))}
                  </select>
                  {saving && (
                    <span className="ml-2 text-xs text-muted-foreground">
                      저장 중…
                    </span>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </section>
  );
}

function VisibilityTable({ overview }: { overview: PermissionsOverview }) {
  return (
    <section className="flex flex-col gap-3">
      <h2 className="text-base font-semibold">누가 무엇을 보나</h2>
      <p className="text-xs text-muted-foreground">
        모든 사람은 1:1 채팅 공간(common)을 봅니다. 표에서는 뺐습니다.
      </p>
      <div className="overflow-x-auto">
        <table className="text-sm">
          <thead>
            <tr className="border-b text-muted-foreground">
              <th className="py-2 pr-4 text-left font-medium">사람</th>
              {overview.workspaces.map((workspace) => (
                <th
                  key={workspace.id}
                  className="px-2 py-2 text-center font-medium whitespace-nowrap"
                >
                  {workspace.depth > 1 ? `└ ${workspace.name}` : workspace.name}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {overview.people.map((person) => (
              <tr key={person.subject} className="border-b">
                <td className="py-2 pr-4 whitespace-nowrap">
                  {person.name}{' '}
                  <span className="text-muted-foreground">
                    {person.username}
                  </span>
                </td>
                {overview.workspaces.map((workspace) => {
                  const visible = person.visible.includes(workspace.id);
                  return (
                    <td
                      key={workspace.id}
                      className={`px-2 py-2 text-center ${visible ? 'font-semibold' : 'text-muted-foreground'}`}
                      aria-label={`${person.name} ${workspace.name} ${visible ? '볼 수 있음' : '볼 수 없음'}`}
                    >
                      {visible ? 'O' : '-'}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function CsvImport({ onApplied }: { onApplied: () => Promise<void> }) {
  const [csv, setCsv] = useState<string | null>(null);
  const [fileName, setFileName] = useState('');
  const [report, setReport] = useState<ImportReport | null>(null);
  const [busy, setBusy] = useState(false);

  async function run(text: string, apply: boolean) {
    setBusy(true);
    try {
      const result = await importMembersCsv(text, apply);
      setReport(result);
      if (result.applied) {
        await onApplied();
        toast.success('저장했습니다.');
      }
    } catch (error) {
      toast.error(
        `CSV를 넣지 못했습니다. ${error instanceof Error ? error.message : ''}`,
      );
    } finally {
      setBusy(false);
    }
  }

  async function pick(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    const text = await file.text();
    setCsv(text);
    setFileName(file.name);
    await run(text, false);
  }

  const canApply =
    csv !== null &&
    report !== null &&
    !report.applied &&
    report.problems.length === 0;

  return (
    <section className="flex flex-col gap-3">
      <h2 className="text-base font-semibold">CSV로 넣기</h2>
      <p className="text-xs text-muted-foreground">
        첫 줄은 email,team,rank입니다. 파일을 고르면 먼저 미리보기만 하고,
        저장을 눌러야 들어갑니다. 한 줄이라도 틀리면 아무것도 저장하지 않습니다.
        CSV에 없는 사람의 배정은 그대로 둡니다.
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <label className="inline-flex h-9 cursor-pointer items-center rounded-md border px-3 text-sm hover:bg-muted">
          파일 고르기
          <input
            type="file"
            accept=".csv,text/csv"
            className="hidden"
            onChange={pick}
            disabled={busy}
          />
        </label>
        {fileName && (
          <span className="text-sm text-muted-foreground">{fileName}</span>
        )}
        <Button
          disabled={!canApply || busy}
          onClick={() => csv && run(csv, true)}
          size="sm"
        >
          저장
        </Button>
      </div>

      {report && (
        <div className="flex flex-col gap-2 text-sm">
          {report.problems.length > 0 && (
            <div
              className="rounded-lg border border-destructive px-4 py-3"
              role="alert"
            >
              <p className="font-medium text-destructive">
                틀린 줄 {report.problems.length}개 — 아무것도 저장하지
                않았습니다.
              </p>
              <ul className="mt-1 list-disc pl-5">
                {report.problems.map((problem) => (
                  <li key={`${problem.line}-${problem.message}`}>
                    {problem.line}줄: {problem.message}
                  </li>
                ))}
              </ul>
            </div>
          )}
          <p>
            {Object.entries(report.counts)
              .map(
                ([outcome, count]) =>
                  `${OUTCOME_LABELS[outcome] ?? outcome} ${count}`,
              )
              .join(', ') || '넣을 줄이 없습니다'}
            {' · '}
            {report.applied ? '저장했습니다' : '미리보기입니다'}
          </p>
          <table className="text-sm">
            <tbody>
              {report.rows.map((row) => (
                <tr key={row.line} className="border-b">
                  <td className="py-1 pr-4 text-muted-foreground">
                    {row.line}줄
                  </td>
                  <td className="py-1 pr-4">{row.email}</td>
                  <td className="py-1 pr-4">{row.team}</td>
                  <td className="py-1 pr-4">{row.rank}</td>
                  <td className="py-1">
                    {OUTCOME_LABELS[row.outcome] ?? row.outcome}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
