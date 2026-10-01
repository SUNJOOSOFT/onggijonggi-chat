'use client';

/********************************************************
 파일명 : permissions-admin.tsx (components/permissions)
 설 명 : 권한 관리 화면(/admin/permissions). 사람마다 팀·직급을 고르면 바로 저장하고, 아래 "누가 무엇을 보나" 표를
 서버의 실제 판정으로 다시 그린다. CSV(email,team,rank)는 미리보기 뒤 저장한다. 판정은 흉내 내지 않는다 —
 표의 O/-는 bff가 Casbin에 물어본 결과 그대로다.
 bff의 casbin 프로필에서만 API가 있다. 꺼져 있으면 404, PLATFORM_ADMIN이 아니면 403을 반환한다.
 *********************************************************/

import {
  type ChangeEvent,
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react';
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

export function PermissionsAdmin({ embedded = false }: { embedded?: boolean }) {
  const [overview, setOverview] = useState<PermissionsOverview | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const mutationBusy = useRef(false);
  const sequence = useRef(0);

  const reload = useCallback(async () => {
    const request = ++sequence.current;
    try {
      const result = await fetchPermissionsOverview();
      if (request !== sequence.current) return;
      setOverview(result);
      setFailed(null);
    } catch (error) {
      if (request === sequence.current) {
        setOverview(null);
        setFailed(error instanceof Error ? error.message : '');
        throw error;
      }
    }
  }, []);

  useEffect(() => {
    void reload().catch(() => undefined);
    return () => {
      ++sequence.current;
    };
  }, [reload]);

  function beginChange() {
    if (mutationBusy.current) return false;
    mutationBusy.current = true;
    setSaving(true);
    return true;
  }
  function endChange() {
    mutationBusy.current = false;
    setSaving(false);
  }

  async function change(
    subject: string,
    teamId: string | null,
    rank: string | null,
  ) {
    if (!beginChange()) return;
    try {
      await saveAssignment(subject, teamId, rank);
      toast.success('저장했습니다.');
      try {
        await reload();
      } catch {
        toast.warning('변경 완료, 최신 정보 조회 실패');
      }
    } catch (error) {
      toast.error(
        `저장하지 못했습니다. ${error instanceof Error ? error.message : ''}`,
      );
    } finally {
      endChange();
    }
  }

  return (
    <div className={embedded ? 'flex flex-col' : 'flex h-dvh flex-col'}>
      {!embedded && (
        <header className="flex sticky top-0 items-center gap-2 border-b bg-background px-2 py-1.5">
          <SidebarToggle />
          <h1 className="text-sm font-semibold">권한 관리</h1>
        </header>
      )}

      <div
        className={
          embedded
            ? 'flex flex-col gap-8'
            : 'mx-auto flex w-full max-w-6xl flex-1 flex-col gap-8 overflow-y-auto p-6'
        }
      >
        <p className="rounded-lg bg-muted px-4 py-3 text-sm">
          팀·직급을 바꾸면 바로 저장되고, 아래 표가 실제 권한 판정 결과로 다시
          그려집니다. 변경과 CSV 적용은 PLATFORM_ADMIN만 할 수 있습니다.
        </p>

        {failed !== null && (
          <p className="rounded-lg bg-muted px-4 py-3 text-sm" role="alert">
            불러오지 못했습니다. 권한 기능이 켜져 있고 PLATFORM_ADMIN 권한이
            있는지 확인해 주세요. {failed}
          </p>
        )}
        {failed === null && overview === null && (
          <p className="text-sm text-muted-foreground">불러오는 중…</p>
        )}

        {failed !== null && (
          <Button onClick={() => void reload().catch(() => undefined)}>
            목록 다시 조회
          </Button>
        )}

        {overview && (
          <>
            <AssignmentTable
              overview={overview}
              saving={saving}
              onChange={change}
            />
            <VisibilityTable overview={overview} />
            <CsvImport
              onApplied={reload}
              changing={saving}
              beginChange={beginChange}
              endChange={endChange}
            />
          </>
        )}
      </div>
    </div>
  );
}

function AssignmentTable({
  overview,
  saving,
  onChange,
}: {
  overview: PermissionsOverview;
  saving: boolean;
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
        1:1 채팅 공간(common)은 소속·정책과 방 소유권으로 따로 판정하므로 표에서
        제외했습니다.
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

function CsvImport({
  onApplied,
  changing,
  beginChange,
  endChange,
}: {
  onApplied: () => Promise<void>;
  changing: boolean;
  beginChange: () => boolean;
  endChange: () => void;
}) {
  const [csv, setCsv] = useState<string | null>(null);
  const [fileName, setFileName] = useState('');
  const [report, setReport] = useState<ImportReport | null>(null);
  const [busy, setBusy] = useState(false);
  const [previewing, setPreviewing] = useState(false);
  const selection = useRef(0);

  async function applyCsv(text: string) {
    if (!beginChange()) return;
    setBusy(true);
    try {
      const result = await importMembersCsv(text, true);
      setReport(result);
      if (result.applied) {
        toast.success('저장했습니다.');
        try {
          await onApplied();
        } catch {
          toast.warning('변경 완료, 최신 정보 조회 실패');
        }
      }
    } catch (error) {
      toast.error(
        `CSV를 넣지 못했습니다. ${error instanceof Error ? error.message : ''}`,
      );
    } finally {
      setBusy(false);
      endChange();
    }
  }

  async function pick(event: ChangeEvent<HTMLInputElement>) {
    if (changing) return;
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    const selected = ++selection.current;
    setCsv(null);
    setReport(null);
    setFileName(file.name);
    setPreviewing(true);
    try {
      const text = await file.text();
      if (selected !== selection.current) return;
      setCsv(text);
      const result = await importMembersCsv(text, false);
      if (selected === selection.current) setReport(result);
    } catch (error) {
      if (selected === selection.current) {
        toast.error(
          `CSV를 읽지 못했습니다. ${error instanceof Error ? error.message : ''}`,
        );
      }
    } finally {
      if (selected === selection.current) setPreviewing(false);
    }
  }

  const canApply =
    csv !== null &&
    report !== null &&
    !report.applied &&
    report.problems.length === 0 &&
    !previewing;

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
            disabled={busy || changing}
          />
        </label>
        {fileName && (
          <span className="text-sm text-muted-foreground">{fileName}</span>
        )}
        <Button
          disabled={!canApply || busy || changing}
          onClick={() => csv && applyCsv(csv)}
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
