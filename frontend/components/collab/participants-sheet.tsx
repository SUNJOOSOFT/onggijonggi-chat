'use client';

/********************************************************
 파일명 : participants-sheet.tsx (components/collab)
 설 명 : 협업방 참여자 관리 UI(이슈 #23). 방 헤더의 "참여자" 버튼으로 열리는 Sheet 하나에
 조회·초대·제거·소유권 위임을 전부 담는다. #20의 참여자 관리 API(PR #136)를 그대로 소비한다.

 Sheet를 열 때마다, 그리고 초대·제거·위임·나가기가 성공할 때마다 매번 목록을 다시 불러온다.
 #129(참여자 변경 실시간 통지)가 아직 없어 Sheet가 닫혀 있는 동안 남이 만든 변경을 알 방법이
 재조회뿐이기 때문이다 — #129가 끝나면 이 폴링은 걷어내고 그 통지를 받아 갱신하도록 바꿔야 한다.

 나가기·제거·위임은 전부 같은 AlertDialog(app-sidebar.tsx의 세션 삭제 확인과 같은 컴포넌트)로
 확인을 거친다. 위임도 되돌리려면 상대가 다시 위임해줘야 하는 무거운 동작이라 같은 절차를 둔다.

 자기 자신의 행(self)에는 role과 무관하게 "나가기" 버튼만 뜬다. OWNER가 위임 없이 나가려 하면
 서버가 409(PARTICIPANT_STATE_CONFLICT)를 주는데, errors.ts의 공용 문구("참여자 정보가 방금
 바뀌었어요")는 경합을 전제로 한 것이라 여기서는 틀린 조언이 된다(확정적 규칙 위반이라 재시도해도
 계속 실패한다) — 이 화면에서만 "위임해야 나갈 수 있어요" 문구로 바꿔 보여준다.

 GET이 404로 실패하면(다른 OWNER가 이미 나를 제거했지만 #135 미착수라 WS 연결은 아직 살아있는
 경우) 자진탈퇴 성공과 똑같이 취급해 /collab으로 보낸다 — "더 이상 이 방의 참가자가 아니다"라는
 같은 확정적 사실이기 때문이다.
 *********************************************************/

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Users } from 'lucide-react';
import { toast } from 'sonner';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '@/components/ui/sheet';
import { Skeleton } from '@/components/ui/skeleton';
import {
  fetchThreadParticipants,
  inviteParticipant,
  removeParticipant,
  revokeInvitation,
  searchInviteCandidates,
  transferOwnership,
  type InviteCandidate,
  type ThreadParticipant,
} from '@/lib/api/collab';
import { resolveChatError } from '@/lib/api/errors';

const ROLE_LABEL: Record<ThreadParticipant['role'], string> = {
  OWNER: '소유자',
  MEMBER: '멤버',
};

type PendingAction = {
  kind: 'remove' | 'transfer';
  target: ThreadParticipant;
};

type LoadStatus = 'idle' | 'loading' | 'loaded' | 'error';

export function ParticipantsSheet({
  threadId,
  refreshSignal,
}: {
  threadId: string;
  /**
   * WS로 참여자 변경을 통보받을 때마다 올라가는 눈금(이슈 #129·#172). 값 자체에는 의미가
   * 없고 "바뀌었다"만 나른다 — 시트가 열려 있을 때만 다시 불러온다. 닫혀 있으면 어차피
   * 열 때 load()가 도므로 미리 부를 이유가 없다.
   */
  refreshSignal: number;
}) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [status, setStatus] = useState<LoadStatus>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [participants, setParticipants] = useState<ThreadParticipant[]>([]);
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(
    null,
  );
  const [busy, setBusy] = useState(false);
  const [inviteQuery, setInviteQuery] = useState('');
  const [candidates, setCandidates] = useState<InviteCandidate[]>([]);
  const [searching, setSearching] = useState(false);

  const callerRole = participants.find((p) => p.self)?.role;

  async function load() {
    setStatus('loading');
    setErrorMessage(null);
    try {
      const list = await fetchThreadParticipants(threadId);
      setParticipants(list);
      setStatus('loaded');
    } catch (err) {
      const { code, message } = resolveChatError(err as Error);
      if (code === 'NOT_FOUND') {
        // 더 이상 이 방의 참가자가 아니다 — 남이 먼저 제거했든 내가 방금 나갔든 처리는 같다.
        setOpen(false);
        toast.error('더 이상 이 방의 참가자가 아니에요.');
        router.push('/collab');
        return;
      }
      setStatus('error');
      setErrorMessage(message);
    }
  }

  /**
   * 고른 사람을 초대한다. subject는 검색 결과가 들고 있던 값을 그대로 되돌려 주는 것이라
   * 사람이 UUID를 볼 일이 없다(이슈 #172).
   */
  async function handleInvite(candidate: InviteCandidate) {
    if (busy) return;
    setBusy(true);
    try {
      await inviteParticipant(threadId, candidate.subject);
      setInviteQuery('');
      setCandidates([]);
      await load();
    } catch (err) {
      toast.error(resolveChatError(err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  /** 대기 초대를 거둔다. 성공하면 명단을 다시 불러 그 줄이 사라진다. */
  async function handleRevoke(target: ThreadParticipant) {
    if (busy) return;
    setBusy(true);
    try {
      await revokeInvitation(threadId, target.subject);
      await load();
    } catch (err) {
      toast.error(resolveChatError(err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  // biome-ignore lint/correctness/useExhaustiveDependencies: refreshSignal이 이 effect의
  // 트리거다 — load는 매 렌더 새로 만들어져 deps에 넣으면 무한 재조회가 된다.
  useEffect(() => {
    if (refreshSignal === 0 || !open) return;
    void load();
  }, [refreshSignal, open]);

  /**
   * 검색어가 멎으면 후보를 불러온다. 타이핑마다 부르면 Keycloak Admin API를 글자 수만큼
   * 두드리게 된다 — 250ms는 사람이 한 단어를 마치는 간격이다.
   *
   * 두 글자 미만은 서버가 빈 목록으로 답하므로 아예 부르지 않는다. 응답이 늦게 도착한 이전
   * 검색이 최신 결과를 덮지 않도록 cancelled 플래그로 막는다.
   */
  useEffect(() => {
    const query = inviteQuery.trim();
    if (callerRole !== 'OWNER' || query.length < 2) {
      setCandidates([]);
      setSearching(false);
      return;
    }
    let cancelled = false;
    setSearching(true);
    const timer = setTimeout(() => {
      searchInviteCandidates(threadId, query)
        .then((found) => {
          if (!cancelled) setCandidates(found);
        })
        .catch((err) => {
          if (!cancelled) {
            setCandidates([]);
            toast.error(resolveChatError(err as Error).message);
          }
        })
        .finally(() => {
          if (!cancelled) setSearching(false);
        });
    }, 250);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [inviteQuery, threadId, callerRole]);

  async function handleConfirm() {
    const action = pendingAction;
    if (!action) return;
    setPendingAction(null);
    setBusy(true);
    try {
      if (action.kind === 'remove') {
        await removeParticipant(threadId, action.target.subject);
        if (action.target.self) {
          // 나 자신을 제거해 성공했다 — 더 이상 참가자가 아니므로 방 목록으로 보낸다.
          setOpen(false);
          router.push('/collab');
          return;
        }
      } else {
        await transferOwnership(threadId, action.target.subject);
      }
      await load();
    } catch (err) {
      const { code, message } = resolveChatError(err as Error);
      // OWNER 본인이 위임 없이 나가려 한 409만 예외 — 경합을 전제한 공용 문구가 틀린 조언이 된다.
      if (
        action.kind === 'remove' &&
        action.target.self &&
        code === 'PARTICIPANT_STATE_CONFLICT'
      ) {
        toast.error('다른 사람에게 먼저 위임해야 나갈 수 있어요.');
      } else {
        toast.error(message);
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Sheet
        open={open}
        onOpenChange={(next) => {
          setOpen(next);
          if (next) void load();
        }}
      >
        <SheetTrigger asChild>
          <Button variant="ghost" size="sm" className="gap-1.5">
            <Users className="h-4 w-4" />
            참여자 관리
          </Button>
        </SheetTrigger>
        <SheetContent>
          <SheetHeader>
            <SheetTitle>
              참여자{status === 'loaded' ? ` ${participants.length}명` : ''}
            </SheetTitle>
          </SheetHeader>

          <div className="mt-4 flex flex-col gap-4">
            {status === 'loading' && (
              <div className="flex flex-col gap-2">
                <Skeleton className="h-12 w-full" />
                <Skeleton className="h-12 w-full" />
              </div>
            )}

            {status === 'error' && (
              <div className="flex flex-col items-start gap-2 text-sm">
                <p className="text-muted-foreground">{errorMessage}</p>
                <Button variant="outline" size="sm" onClick={() => void load()}>
                  다시 시도
                </Button>
              </div>
            )}

            {status === 'loaded' && (
              <>
                <ul className="flex flex-col gap-1">
                  {participants.map((participant) => (
                    <li
                      key={participant.subject}
                      className="flex items-center justify-between gap-2 rounded-md px-2 py-1.5 text-sm"
                    >
                      <span className="flex min-w-0 items-center gap-1.5">
                        <span className="truncate">
                          {participant.displayName}
                        </span>
                        {participant.self && (
                          <span className="shrink-0 text-xs text-muted-foreground">
                            (나)
                          </span>
                        )}
                        <span className="shrink-0 rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                          {participant.pending
                            ? '초대 대기'
                            : ROLE_LABEL[participant.role]}
                        </span>
                      </span>

                      <span className="flex shrink-0 gap-1">
                        {participant.pending ? (
                          callerRole === 'OWNER' && (
                            <Button
                              variant="ghost"
                              size="sm"
                              disabled={busy}
                              onClick={() => void handleRevoke(participant)}
                            >
                              초대 취소
                            </Button>
                          )
                        ) : participant.self ? (
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={busy}
                            onClick={() =>
                              setPendingAction({
                                kind: 'remove',
                                target: participant,
                              })
                            }
                          >
                            나가기
                          </Button>
                        ) : (
                          callerRole === 'OWNER' && (
                            <>
                              <Button
                                variant="ghost"
                                size="sm"
                                disabled={busy}
                                onClick={() =>
                                  setPendingAction({
                                    kind: 'remove',
                                    target: participant,
                                  })
                                }
                              >
                                제거
                              </Button>
                              <Button
                                variant="ghost"
                                size="sm"
                                disabled={busy}
                                onClick={() =>
                                  setPendingAction({
                                    kind: 'transfer',
                                    target: participant,
                                  })
                                }
                              >
                                위임
                              </Button>
                            </>
                          )
                        )}
                      </span>
                    </li>
                  ))}
                </ul>

                {callerRole === 'OWNER' && (
                  <div className="flex flex-col gap-2 border-t pt-4">
                    <Label htmlFor="invite-search">사람 초대</Label>
                    <Input
                      id="invite-search"
                      placeholder="이름이나 이메일로 검색하세요"
                      value={inviteQuery}
                      disabled={busy}
                      onChange={(event) => setInviteQuery(event.target.value)}
                    />

                    {/* 두 글자 미만은 검색하지 않는다 — 안내가 없으면 고장으로 읽힌다. */}
                    {inviteQuery.trim().length > 0 &&
                      inviteQuery.trim().length < 2 && (
                        <p className="px-1 text-xs text-muted-foreground">
                          두 글자 이상 입력하세요.
                        </p>
                      )}

                    {searching && (
                      <p className="px-1 text-xs text-muted-foreground">
                        찾는 중…
                      </p>
                    )}

                    {!searching &&
                      inviteQuery.trim().length >= 2 &&
                      candidates.length === 0 && (
                        <p className="px-1 text-xs text-muted-foreground">
                          찾는 사람이 없어요. 이미 방에 있거나 초대한 사람은
                          결과에 나오지 않아요.
                        </p>
                      )}

                    {candidates.length > 0 && (
                      <ul className="flex flex-col gap-1">
                        {candidates.map((candidate) => (
                          <li
                            key={candidate.subject}
                            className="flex items-center justify-between gap-2 rounded-md px-2 py-1.5 text-sm"
                          >
                            <span className="truncate">
                              {candidate.displayName}
                            </span>
                            <Button
                              size="sm"
                              disabled={busy}
                              onClick={() => void handleInvite(candidate)}
                            >
                              초대
                            </Button>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                )}
              </>
            )}
          </div>
        </SheetContent>
      </Sheet>

      <AlertDialog
        open={pendingAction !== null}
        onOpenChange={(next) => {
          if (!next) setPendingAction(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {pendingAction?.kind === 'transfer'
                ? '소유권을 넘길까요?'
                : pendingAction?.target.self
                  ? '이 방에서 나갈까요?'
                  : '참여자를 제거할까요?'}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {pendingAction?.kind === 'transfer'
                ? `${pendingAction.target.displayName}님에게 소유권을 넘깁니다. 되돌리려면 상대가 다시 위임해줘야 합니다.`
                : pendingAction?.target.self
                  ? '더 이상 이 방의 메시지를 볼 수 없게 됩니다.'
                  : `${pendingAction?.target.displayName}님을 이 방에서 제거합니다.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={() => void handleConfirm()}>
              확인
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
