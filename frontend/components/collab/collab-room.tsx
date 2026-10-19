'use client';

/********************************************************
 파일명 : collab-room.tsx (components/collab)
 설 명 : 협업방 화면(이슈 #19) — 참여자·메시지 목록·입력창, 그리고 방에 들어가지 못했을 때의 처리.

 메시지 목록을 1:1 채팅의 messages.tsx로 그리지 않은 이유는 그쪽이 AI SDK Message(role만 있고
 보낸 사람이 없다)를 전제하기 때문이다. 협업방은 여러 사람의 메시지를 이름과 함께 보여줘야 한다.
 스크롤 붙임(use-scroll-to-bottom)과 마크다운 렌더는 그대로 재사용한다.

 참여자 목록 사이드바와 입퇴장·승인 라인은 프레임이 실어 보낸 표시 이름을 그대로 쓴다(#26·#130·#407).
 subject는 화면에 그리지 않고 title로만 남긴다 — 같은 이름이 둘일 때 구분할 수단은 있어야 한다.

 이 사이드바(#26)는 "지금 붙어 있는 사람"이고, 방에 들어올 자격이 있는 참여자 "명단" 관리는
 REST 쪽 참여자 관리 API(#20)를 그대로 쓰는 별개 화면이다(#23) — 헤더의 ParticipantsSheet
 참고. 아래 aside 주석도 같은 구분을 반복한다.

 메시지 시각과 AI 라벨(@FIN 같은 에이전트 구분)은 기획 시안에 있으나 그리지 않는다 — 프레임
 계약(#8)에 그 필드가 없어 서버가 보내주지 않는다. 계약이 넓어지면 여기에 붙일 자리다.

 승인 요청(#407)은 메시지에 붙는 "승인 요청" 버튼으로 시작하고, 처리할 요청은 헤더의 "승인 대기" 버튼과 토스트,
 그리고 대화 흐름의 승인 카드(#416)의 버튼으로 모달을 연다. 목록 조회와 토스트 판단은 use-thread-approvals.ts가
 맡고 이 파일은 모달을 여는 상태만 든다. 원글마다 지금의 승인 상태는 대화 흐름의 승인 줄에서 가린다.

 시스템 알림(#29)은 오류 배너와 자리는 같지만 성격이 다르다 — 오류는 방금 한 일이 실패했다는
 신호라 닫으면 끝이고, 알림은 배치가 사후에 알려주는 것이라 닫기 전까지 남는다. 색을 나눠 둔
 것도 그래서다. info 알림은 여기 오지 않는다(토스트, use-collab-room.ts).

 방 접근 거부는 구독에 대한 error 프레임(FORBIDDEN)으로 도착해 사유가 분명하다 — 핸드셰이크에는
 방이 없어(이슈 #161) 방 단위로 거부될 수 없다. 핸드셰이크 자체가 실패하면 브라우저가 이유를
 넘겨주지 않아(#4) 서버 장애와 구분되지 않으므로, 그쪽은 단정하지 않는 문구로 안내한다.
 *********************************************************/

import { useEffect, useMemo, useState } from 'react';

import { MessageAttachments } from '@/components/attachments';
import { CitationsPanel } from '@/components/citations-panel';
import { Markdown } from '@/components/markdown';
import { ActivityLine } from '@/components/message';
import { NoticeBanner } from '@/components/notice-banner';
import { SidebarToggle } from '@/components/sidebar-toggle';
import { useScrollToBottom } from '@/components/use-scroll-to-bottom';
import { Button } from '@/components/ui/button';
import { fetchCollabThreads } from '@/lib/api/collab';
import type { ThreadApproval } from '@/lib/api/thread-approvals';
import { activityLabel } from '@/lib/chat/activity';
import type { RoomMembershipNotice, RoomMessage } from '@/lib/chat/room-state';
import {
  isApprovalNotice,
  isForbidden,
  isMembershipNotice,
  isNotice,
} from '@/lib/chat/room-state';
import { useLingeringActivity } from '@/lib/chat/use-lingering-activity';
import { typingLabel } from '@/lib/chat/typing';
import {
  type OriginApproval,
  latestApprovalByOrigin,
} from '@/lib/collab/approval-status';
import { useThreadApprovals } from '@/lib/collab/use-thread-approvals';
import {
  type RoomConnection,
  useCollabRoom,
} from '@/lib/collab/use-collab-room';
import {
  ApprovalCard,
  OriginStatusButton,
  entryAnchorId,
} from './approval-card';
import { ApprovalDialog } from './approval-dialog';
import { ApprovalRequestDialog } from './approval-request-dialog';
import { CollabInput } from './collab-input';
import { ParticipantsSheet } from './participants-sheet';
import { PresenceAvatars } from './presence-avatars';
import {
  ThreadDocumentsButton,
  ThreadDocumentsPanel,
  ThreadDocumentsProvider,
} from '@/components/thread-documents';

const CONNECTION_LABEL: Record<RoomConnection, string> = {
  connecting: '연결 중…',
  open: '연결됨',
  reconnecting: '다시 연결하는 중…',
  stalled: '연결하지 못했습니다',
};

/** 참가자가 되거나 빠진 입퇴장 줄. 말풍선도 작성자 머리글도 없이 흐름 가운데에 옅게 남긴다 —
 * 사람이 한 말이 아니기 때문이다. */
function MembershipRow({ notice }: { notice: RoomMembershipNotice }) {
  return (
    <p
      id={entryAnchorId(notice.id)}
      title={notice.participant.subject}
      className="px-3 py-1 text-center text-xs text-muted-foreground"
    >
      {notice.participant.displayName}님이{' '}
      {notice.event === 'join' ? '입장했습니다' : '퇴장했습니다'}
    </p>
  );
}

/** `@SOL`·`@FIN`·`@LAW`로 답했으면 그 이름을, `@AI`로 답했으면 "AI"를 보여준다(이슈 #357). */
function agentLabel(agent: RoomMessage['agent']): string {
  return agent === null ? 'AI' : agent.toUpperCase();
}

/** 사람 메시지는 보낸 사람 이름을, AI 답변은 "AI" 또는 역할별 에이전트 이름을 머리에 달고
 * 배경으로 구분한다(이슈 #357). */
function MessageRow({
  message,
  approval,
  onRequestApproval,
  activity = null,
}: {
  message: RoomMessage;
  /** 이 메시지에 대한 지금의 승인 상태. 승인 흐름에 없으면 null이다(이슈 #416). */
  approval: OriginApproval | null;
  onRequestApproval: (messageId: string) => void;
  /** 이 답변의 턴이 지금 실행 중인 도구의 문구(이슈 #397). */
  activity?: string | null;
}) {
  const isAi = message.from === null;
  // 쓰는 중인 답변은 서버가 승인 대상에서 제외하고, 본문 없는 메시지는 보여 줄 내용이 없어 요청하지 않는다.
  // 대기 중이거나 이미 승인된 글에는 요청을 또 보내지 않고, 반려된 글만 다시 요청할 수 있다.
  const canRequestApproval =
    !message.streaming &&
    message.content !== '' &&
    (approval === null || approval.status === 'rejected');
  return (
    <div
      id={entryAnchorId(message.id)}
      className={`group flex flex-col gap-1 transition-shadow ${
        isAi ? 'rounded-lg bg-muted/60 px-3 py-2' : 'px-3 py-2'
      }`}
    >
      <span
        className="text-xs font-medium text-muted-foreground"
        title={message.from?.subject}
      >
        {isAi ? agentLabel(message.agent) : message.from?.displayName}
      </span>
      {isAi ? (
        <>
          {message.content !== '' && <Markdown>{message.content}</Markdown>}
          {(message.citations.length > 0 ||
            message.restrictedResultsOmitted ||
            message.groundingNotice !== null) && (
            <CitationsPanel
              state={{
                status: 'success',
                citations: message.citations,
                restrictedResultsOmitted: message.restrictedResultsOmitted,
                groundingNotice: message.groundingNotice,
              }}
            />
          )}
          {activity && <ActivityLine label={activity} className="" />}
        </>
      ) : (
        <>
          <MessageAttachments attachments={message.attachments} />
          {message.content !== '' && (
            <p className="whitespace-pre-wrap text-sm">{message.content}</p>
          )}
        </>
      )}
      {approval !== null && (
        <OriginStatusButton
          status={approval.status}
          approverName={approval.notice.target.displayName}
          noticeId={approval.notice.id}
        />
      )}
      {canRequestApproval && (
        <Button
          variant="ghost"
          className="h-auto w-fit self-start px-1 py-0 text-xs text-muted-foreground opacity-0 transition-opacity focus-visible:opacity-100 group-hover:opacity-100"
          onClick={() => onRequestApproval(message.id)}
        >
          {approval === null ? '승인 요청' : '다시 요청'}
        </Button>
      )}
    </div>
  );
}

/** 방을 그릴 수 없을 때 화면 전체를 대신한다. */
function RoomBlocked({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="flex h-dvh flex-col items-center justify-center gap-2 p-8 text-center">
      <h1 className="text-lg font-semibold">{title}</h1>
      <p className="max-w-md text-sm text-muted-foreground">{detail}</p>
      <a className="mt-2 text-sm underline" href="/collab">
        협업 채널 목록으로
      </a>
    </div>
  );
}

/** 방 제목만 얻으려고 목록을 다시 부른다 — 단건 조회 경로가 아직 없어서다(#14·#16). */
function useThreadTitle(threadId: string): string {
  const [title, setTitle] = useState('협업 채널');

  useEffect(() => {
    let alive = true;
    fetchCollabThreads()
      .then((threads) => {
        const found = threads.find((thread) => thread.id === threadId);
        if (alive && found) setTitle(found.title);
      })
      // 제목은 부가 정보다 — 못 얻었다고 방을 못 열 이유는 없다.
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [threadId]);

  return title;
}

export function CollabRoom({ threadId }: { threadId: string }) {
  const {
    state,
    connection,
    send,
    cancel,
    cancellableTurnId,
    dismissError,
    dismissNotice,
    participantsRevision,
    approvalsRevision,
    typing,
    setTyping,
  } = useCollabRoom(threadId);
  const [requestingMessageId, setRequestingMessageId] = useState<string | null>(
    null,
  );
  // rejecting은 승인 카드의 "반려"로 열었을 때 반려 사유 입력부터 보이게 한다.
  const [reviewing, setReviewing] = useState<{
    approval: ThreadApproval;
    rejecting: boolean;
  } | null>(null);
  const { decidable, nameOf, reload } = useThreadApprovals(threadId, {
    revision: approvalsRevision,
    open: connection === 'open',
    participantsRevision,
    onOpenRequest: (approval) => setReviewing({ approval, rejecting: false }),
  });
  const [containerRef, endRef] = useScrollToBottom<HTMLDivElement>();
  // 도구가 연달아 돌 때 그 사이 빈틈에 표시가 깜박이지 않게 지울 때만 잠깐 남긴다(이슈 #397).
  const activity = useLingeringActivity(state.activity);
  const title = useThreadTitle(threadId);
  // 방 문서 패널의 첨부에 보낸 사람 이름을 붙인다. 첨부는 메시지에 실려 오므로 작성자에서 찾는다.
  const senderNames = useMemo(() => {
    const names = new Map<string, string>();
    for (const entry of state.messages) {
      if (!isNotice(entry) && entry.from) {
        names.set(entry.from.subject, entry.from.displayName);
      }
    }
    return names;
  }, [state.messages]);

  const reviewingContent = useMemo(() => {
    if (reviewing === null) return null;
    const found = state.messages.find(
      (entry) => !isNotice(entry) && entry.id === reviewing.approval.messageId,
    );
    return found && !isNotice(found) ? found.content : null;
  }, [reviewing, state.messages]);

  // 원글마다 지금의 승인 상태와, 승인 카드가 앞부분을 보여 줄 원글을 미리 찾아 둔다(이슈 #416).
  const approvalByOrigin = useMemo(
    () => latestApprovalByOrigin(state.messages),
    [state.messages],
  );
  const originById = useMemo(() => {
    const origins = new Map<
      string,
      { id: string; label: string; content: string }
    >();
    for (const entry of state.messages) {
      if (isNotice(entry) || entry.content === '') continue;
      origins.set(entry.id, {
        id: entry.id,
        label:
          entry.from === null
            ? agentLabel(entry.agent)
            : entry.from.displayName,
        content: entry.content,
      });
    }
    return origins;
  }, [state.messages]);

  if (isForbidden(state.error)) {
    return (
      <RoomBlocked
        title="이 방에 들어갈 수 없습니다"
        detail={state.error?.message ?? '접근 권한이 없습니다.'}
      />
    );
  }

  if (connection === 'stalled') {
    return (
      <RoomBlocked
        title="방에 연결하지 못했습니다"
        detail="접근 권한이 없거나 서버에 닿지 못하고 있습니다. 브라우저는 이 둘을 구분할 수 없어 계속 다시 시도하는 중입니다."
      />
    );
  }

  return (
    <ThreadDocumentsProvider
      threadId={threadId}
      attachmentCount={state.messages.reduce(
        (count, entry) =>
          isNotice(entry) ? count : count + entry.attachments.length,
        0,
      )}
      senderNames={senderNames}
    >
      <div className="flex h-dvh flex-col">
        {/* 패딩·정렬을 chat-header.tsx와 같은 값으로 둔다 — 1:1 채팅과 오갈 때 사이드바 토글이
          같은 자리에 있어야 한다. */}
        <header className="flex sticky top-0 items-center gap-2 border-b bg-background px-2 py-1.5">
          <SidebarToggle />
          <h1 className="min-w-0 truncate text-sm font-semibold" title={title}>
            {title}
          </h1>
          <ParticipantsSheet
            threadId={threadId}
            refreshSignal={participantsRevision}
          />
          <div className="ml-auto flex items-center gap-2">
            {decidable.length > 0 && (
              <Button
                variant="outline"
                className="h-7 px-2 text-xs"
                onClick={() =>
                  setReviewing({ approval: decidable[0], rejecting: false })
                }
              >
                승인 대기 {decidable.length}건
              </Button>
            )}
            <PresenceAvatars participants={state.participants} />
            <span className="whitespace-nowrap pr-2 text-xs text-muted-foreground">
              {CONNECTION_LABEL[connection]}
            </span>
          </div>
          <ThreadDocumentsButton className="md:hidden" />
        </header>

        <div className="flex min-h-0 flex-1">
          <main className="flex min-h-0 flex-1 flex-col">
            {/* 접근 거부가 아닌 오류는 방을 닫을 이유가 아니라 위에 얹어 알린다. */}
            {state.error && !isForbidden(state.error) && (
              <div
                role="alert"
                className="flex items-center gap-3 border-b bg-muted px-4 py-2 text-xs"
              >
                <span className="flex-1">{state.error.message}</span>
                <button
                  type="button"
                  className="shrink-0 underline underline-offset-2"
                  onClick={dismissError}
                >
                  닫기
                </button>
              </div>
            )}

            {/* 같은 code는 한 건뿐이라(room-state.ts) key가 겹치지 않는다. */}
            {state.notices.map((notice) => (
              <NoticeBanner
                key={notice.code}
                notice={notice}
                onDismiss={() => dismissNotice(notice.code)}
              />
            ))}

            <div
              ref={containerRef}
              className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto p-4"
            >
              {state.messages.length === 0 && (
                <p className="text-sm text-muted-foreground">
                  아직 메시지가 없습니다.
                </p>
              )}
              {state.messages.map((entry) =>
                isMembershipNotice(entry) ? (
                  <MembershipRow key={entry.id} notice={entry} />
                ) : isApprovalNotice(entry) ? (
                  <ApprovalCard
                    key={entry.id}
                    notice={entry}
                    origin={
                      entry.originMsgId === null
                        ? null
                        : (originById.get(entry.originMsgId) ?? null)
                    }
                    decidable={
                      entry.event === 'requested'
                        ? (decidable.find(
                            (approval) => approval.id === entry.approvalId,
                          ) ?? null)
                        : null
                    }
                    onReview={(approval, rejecting) =>
                      setReviewing({ approval, rejecting })
                    }
                  />
                ) : (
                  <MessageRow
                    key={entry.id}
                    message={entry}
                    approval={approvalByOrigin.get(entry.id) ?? null}
                    onRequestApproval={setRequestingMessageId}
                    activity={
                      activity?.msgId === entry.id
                        ? activityLabel(activity.kind, activity.target)
                        : null
                    }
                  />
                ),
              )}
              {/* 답변 말풍선이 생기기 전에 도구가 돌면 AI 자리를 만들어 문구를 보인다(이슈 #397). */}
              {activity &&
                !state.messages.some(
                  (entry) => entry.id === activity.msgId,
                ) && (
                  <div className="flex flex-col gap-1 rounded-lg bg-muted/60 px-3 py-2">
                    <span className="text-xs font-medium text-muted-foreground">
                      {agentLabel(activity.agent)}
                    </span>
                    <ActivityLine
                      className=""
                      label={activityLabel(activity.kind, activity.target)}
                    />
                  </div>
                )}
              <div ref={endRef} className="min-h-6 shrink-0" />
            </div>

            <div className="border-t p-4">
              <CollabInput
                canSend={connection === 'open'}
                onSend={(content, attachmentIds) =>
                  send(content, undefined, undefined, attachmentIds) !== null
                }
                typingLabel={typingLabel(typing)}
                onTyping={setTyping}
                onCancel={
                  cancellableTurnId === null
                    ? undefined
                    : () => cancel(cancellableTurnId)
                }
              />
            </div>
          </main>

          <ThreadDocumentsPanel />
        </div>
      </div>

      <ApprovalRequestDialog
        threadId={threadId}
        messageId={requestingMessageId}
        onRequested={reload}
        onClose={() => setRequestingMessageId(null)}
      />
      <ApprovalDialog
        threadId={threadId}
        approval={reviewing?.approval ?? null}
        initialRejecting={reviewing?.rejecting ?? false}
        messageContent={reviewingContent}
        requesterName={
          reviewing ? nameOf(reviewing.approval.requesterSubject) : ''
        }
        onDecided={reload}
        onClose={() => setReviewing(null)}
      />
    </ThreadDocumentsProvider>
  );
}
