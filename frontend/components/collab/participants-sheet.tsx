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

import { useState } from 'react';
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
  transferOwnership,
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

export function ParticipantsSheet({ threadId }: { threadId: string }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [status, setStatus] = useState<LoadStatus>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [participants, setParticipants] = useState<ThreadParticipant[]>([]);
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(
    null,
  );
  const [busy, setBusy] = useState(false);
  const [inviteSubject, setInviteSubject] = useState('');

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

  async function handleInvite() {
    const subject = inviteSubject.trim();
    if (!subject || busy) return;
    setBusy(true);
    try {
      await inviteParticipant(threadId, subject);
      setInviteSubject('');
      await load();
    } catch (err) {
      toast.error(resolveChatError(err as Error).message);
    } finally {
      setBusy(false);
    }
  }

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
                          {ROLE_LABEL[participant.role]}
                        </span>
                      </span>

                      <span className="flex shrink-0 gap-1">
                        {participant.self ? (
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
                    <Label htmlFor="invite-subject">초대할 사용자 식별자</Label>
                    <div className="flex gap-2">
                      <Input
                        id="invite-subject"
                        placeholder="가입한 사용자의 식별자를 입력하세요"
                        value={inviteSubject}
                        disabled={busy}
                        onChange={(event) =>
                          setInviteSubject(event.target.value)
                        }
                        onKeyDown={(event) => {
                          if (event.key === 'Enter') {
                            event.preventDefault();
                            void handleInvite();
                          }
                        }}
                      />
                      <Button
                        disabled={busy || inviteSubject.trim() === ''}
                        onClick={() => void handleInvite()}
                      >
                        초대
                      </Button>
                    </div>
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
