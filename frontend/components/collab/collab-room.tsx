'use client';

/********************************************************
 파일명 : collab-room.tsx (components/collab)
 설 명 : 협업방 화면(이슈 #19) — 참여자·메시지 목록·입력창, 그리고 방에 들어가지 못했을 때의 처리.

 메시지 목록을 1:1 채팅의 messages.tsx로 그리지 않은 이유는 그쪽이 AI SDK Message(role만 있고
 보낸 사람이 없다)를 전제하기 때문이다. 협업방은 여러 사람의 메시지를 이름과 함께 보여줘야 한다.
 스크롤 붙임(use-scroll-to-bottom)과 마크다운 렌더는 그대로 재사용한다.

 참여자 목록 사이드바와 입퇴장 라인은 프레임이 실어 보낸 표시 이름을 그대로 쓴다(#26·#130).
 subject는 화면에 그리지 않고 title로만 남긴다 — 같은 이름이 둘일 때 구분할 수단은 있어야 한다.

 이 사이드바(#26)는 "지금 붙어 있는 사람"이고, 방에 들어올 자격이 있는 참여자 "명단" 관리는
 REST 쪽 참여자 관리 API(#20)를 그대로 쓰는 별개 화면이다(#23) — 헤더의 ParticipantsSheet
 참고. 아래 aside 주석도 같은 구분을 반복한다.

 메시지 시각과 AI 라벨(@FIN 같은 에이전트 구분)은 기획 시안에 있으나 그리지 않는다 — 프레임
 계약(#8)에 그 필드가 없어 서버가 보내주지 않는다. 계약이 넓어지면 여기에 붙일 자리다.

 시스템 알림(#29)은 오류 배너와 자리는 같지만 성격이 다르다 — 오류는 방금 한 일이 실패했다는
 신호라 닫으면 끝이고, 알림은 배치가 사후에 알려주는 것이라 닫기 전까지 남는다. 색을 나눠 둔
 것도 그래서다. info 알림은 여기 오지 않는다(토스트, use-collab-room.ts).

 방 접근 거부는 두 갈래로 도착한다. 서버가 error 프레임(FORBIDDEN)으로 알려주면 사유가 분명해
 그대로 보여주고, 핸드셰이크에서 거부하면 브라우저가 이유를 넘겨주지 않아(#4) 서버 장애와
 구분되지 않는다 — 뒤쪽은 단정하지 않는 문구로 안내한다. 어느 방식이 될지는 #22에 미결이다.
 *********************************************************/

import { useEffect, useState } from 'react';

import { CitationsPanel } from '@/components/citations-panel';
import { Markdown } from '@/components/markdown';
import { SidebarToggle } from '@/components/sidebar-toggle';
import { useScrollToBottom } from '@/components/use-scroll-to-bottom';
import { fetchCollabThreads } from '@/lib/api/collab';
import type {
  CollabMessage,
  CollabPresenceNotice,
  SystemNotice,
} from '@/lib/collab/room-state';
import { isForbidden, isPresenceNotice } from '@/lib/collab/room-state';
import {
  type RoomConnection,
  useCollabRoom,
} from '@/lib/collab/use-collab-room';
import { CollabInput } from './collab-input';
import { ParticipantsSheet } from './participants-sheet';

const CONNECTION_LABEL: Record<RoomConnection, string> = {
  connecting: '연결 중…',
  open: '연결됨',
  reconnecting: '다시 연결하는 중…',
  stalled: '연결하지 못했습니다',
};

/** 입퇴장 시스템 라인(#111). 말풍선도 작성자 머리글도 없이 흐름 가운데에 옅게 남긴다 —
 * 사람이 한 말이 아니기 때문이다. */
function PresenceRow({ notice }: { notice: CollabPresenceNotice }) {
  return (
    <p
      title={notice.participant.subject}
      className="px-3 py-1 text-center text-xs text-muted-foreground"
    >
      {notice.participant.displayName}님이{' '}
      {notice.event === 'join' ? '입장했습니다' : '퇴장했습니다'}
    </p>
  );
}

/** 사람 메시지는 보낸 사람 이름을, AI 답변은 "AI"를 머리에 달고 배경으로 구분한다. */
function MessageRow({ message }: { message: CollabMessage }) {
  const isAi = message.from === null;
  return (
    <div
      className={`flex flex-col gap-1 ${
        isAi ? 'rounded-lg bg-muted/60 px-3 py-2' : 'px-3 py-2'
      }`}
    >
      <span
        className="text-xs font-medium text-muted-foreground"
        title={message.from?.subject}
      >
        {isAi ? 'AI' : message.from?.displayName}
      </span>
      {isAi ? (
        <>
          {message.content !== '' && <Markdown>{message.content}</Markdown>}
          {(message.citations.length > 0 ||
            message.restrictedResultsOmitted) && (
            <CitationsPanel
              state={{
                status: 'success',
                citations: message.citations,
                restrictedResultsOmitted: message.restrictedResultsOmitted,
              }}
            />
          )}
        </>
      ) : (
        <p className="whitespace-pre-wrap text-sm">{message.content}</p>
      )}
    </div>
  );
}

/** 방 위에 얹히는 시스템 알림(#29). 닫기 전까지 남는다 — 사후에 오는 알림이라 지나가면
 * 놓치기 때문이다. traceId는 문의할 때 쓰라고 title로만 남긴다. */
function NoticeBanner({
  notice,
  onDismiss,
}: {
  notice: SystemNotice;
  onDismiss: () => void;
}) {
  return (
    <div
      role="alert"
      title={notice.traceId}
      className="flex items-center gap-3 border-b border-destructive/40 bg-destructive/10 px-4 py-2 text-xs text-destructive"
    >
      <span className="flex-1">{notice.message}</span>
      <button
        type="button"
        className="shrink-0 underline underline-offset-2"
        onClick={onDismiss}
      >
        닫기
      </button>
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
    dismissError,
    dismissNotice,
    participantsRevision,
  } = useCollabRoom(threadId);
  const [containerRef, endRef] = useScrollToBottom<HTMLDivElement>();
  const title = useThreadTitle(threadId);

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
    <div className="flex h-dvh flex-col">
      {/* 패딩·정렬을 chat-header.tsx와 같은 값으로 둔다 — 1:1 채팅과 오갈 때 사이드바 토글이
          같은 자리에 있어야 한다. */}
      <header className="flex sticky top-0 items-center gap-2 border-b bg-background px-2 py-1.5">
        <SidebarToggle />
        <h1 className="text-sm font-semibold">{title}</h1>
        <ParticipantsSheet
          threadId={threadId}
          refreshSignal={participantsRevision}
        />
        <span className="ml-auto pr-2 text-xs text-muted-foreground">
          {CONNECTION_LABEL[connection]}
        </span>
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
              isPresenceNotice(entry) ? (
                <PresenceRow key={entry.id} notice={entry} />
              ) : (
                <MessageRow key={entry.id} message={entry} />
              ),
            )}
            <div ref={endRef} className="min-h-6 shrink-0" />
          </div>

          <div className="border-t p-4">
            <CollabInput canSend={connection === 'open'} onSend={send} />
          </div>
        </main>

        {/* 접속자 목록(#26) — "지금 방에 붙어 있는 사람"이다. 방에 들어올 자격이 있는
            참여자 명단(#23)과는 다른 목록이라 같은 자리에 겹쳐 그리지 않는다.
            이름은 프레임이 실어 온 값이고, subject는 동명이인을 가릴 수 있게 title로 남긴다. */}
        <aside className="hidden w-56 shrink-0 flex-col gap-2 border-l p-4 sm:flex">
          <h2 className="text-xs font-medium text-muted-foreground">
            접속중 {state.participants.length}
          </h2>
          <ul className="flex flex-col gap-1">
            {state.participants.map((participant) => (
              <li
                key={participant.subject}
                title={participant.subject}
                className="text-sm"
              >
                {participant.displayName}
              </li>
            ))}
          </ul>
        </aside>
      </div>
    </div>
  );
}
