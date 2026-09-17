'use client';

/********************************************************
 파일명 : chat.tsx
 설 명 : 1:1 채팅 화면의 배선. 방 상태는 협업방과 공유하는 useRoom(lib/chat)이 들고, 이 파일은
 그 상태를 화면이 쓰는 모양으로 옮기고 전송·모델 선택·세션 스토어 연동만 맡는다.

 예전에는 AI SDK의 useChat이 메시지 배열을 들고, WS 프레임을 그 배열에 손으로 밀어 넣었다.
 그 방식은 원리적으로 어긋날 수밖에 없었다 — useChat은 답변 조각이 올 때마다 "요청 직전에
 찍어 둔 스냅샷"으로 배열 전체를 다시 쓰므로, 그 사이에 프레임으로 고쳐 둔 것이 덮인다.
 서버가 에코로 알려준 진짜 msgId를 말풍선에 달지 못하고 임시 id가 그대로 굳은 것이 그 탓이고,
 이력을 다시 읽으면 같은 메시지가 id가 달라 두 벌로 보였다.

 지금은 프레임을 접는 곳이 room-state 하나뿐이라 그 경합 자체가 없다. 낙관적 말풍선을 만들지
 않고 서버 에코가 온 뒤에 그리므로, 화면에 뜨는 메시지는 처음부터 서버 msgId를 달고 있다.

 대화 내용은 localStorage에 남기지 않는다 — 이력의 정본은 서버이고(이슈 #218), 로컬 사본은
 서버 이력과 id가 어긋나 중복을 만드는 원인이었다.
 *********************************************************/

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';

import { saveModelId } from '@/app/(chat)/actions';
import { ChatHeader } from '@/components/chat-header';
import { LoaderIcon } from '@/components/icons';
import { NoticeBanner } from '@/components/notice-banner';
import {
  type ThreadMessageItem,
  fetchThreadMessages,
} from '@/lib/api/thread-history';
import type { RenderedMessage } from '@/lib/chat/rendered-message';
import { type RoomMessage, isPresenceNotice } from '@/lib/chat/room-state';
import { useRoom } from '@/lib/chat/use-room';
import {
  EMPTY_FAILED_MESSAGE_IDS,
  useChatSessionsHydrated,
  useChatSessionsStore,
} from '@/lib/store/chat-sessions';
import type { CitationsState } from './citations-panel';
import { Messages } from './messages';
import { MultimodalInput } from './multimodal-input';

/** 이력을 읽을 수 없는 방. 404·403을 "잠깐 실패"와 가르려고 타입으로 구분한다. */
class RoomInaccessibleError extends Error {}

/** localStorage 복원 전엔 빈 스토어를 보고 매번 새 세션을 만들어버리므로, 복원이 끝날 때까지
 * 로딩 스피너만 보여주는 게이트. */
export function Chat({
  id,
  availableModels,
  selectedModelId,
  isNewDraft,
}: {
  id: string;
  availableModels: string[];
  selectedModelId: string;
  /** "/"에서 막 만든 방인지(true) URL의 기존 id로 들어온 것인지(false)(이슈 #162, §2.1) —
   * DIRECT WS bootstrap이 구독 없이 첫 발화를 보낼지, 곧바로 room.subscribe로 시작할지를 가른다. */
  isNewDraft: boolean;
}) {
  const hydrated = useChatSessionsHydrated();

  if (!hydrated) {
    return (
      <div className="flex flex-col min-w-0 h-dvh bg-background items-center justify-center">
        <div className="animate-spin text-muted-foreground">
          <LoaderIcon />
        </div>
      </div>
    );
  }

  return (
    <ChatSession
      id={id}
      availableModels={availableModels}
      selectedModelId={selectedModelId}
      isNewDraft={isNewDraft}
    />
  );
}

/** 방 상태를 화면 모양으로 옮기고 전송·모델 선택을 배선한다. Chat이 id별로 리마운트하므로
 * (page.tsx의 key={id}) 세션 전환 시 상태가 자연히 초기화된다. */
function ChatSession({
  id,
  availableModels,
  selectedModelId,
  isNewDraft,
}: {
  id: string;
  availableModels: string[];
  selectedModelId: string;
  isNewDraft: boolean;
}) {
  const [isInaccessible, setIsInaccessible] = useState(false);

  /**
   * 1:1 이력 조회를 useRoom의 계약(실패는 예외)에 맞춘다.
   *
   * 아직 만들어지지 않은 draft의 404는 정상이다 — 첫 발화가 방을 만든다(이슈 #162, §2.1).
   * 그때는 빈 이력으로 넘겨 화면이 열리게 한다.
   *
   * "접근 불가"로 올리는 것은 404·403뿐이다. 그 밖의 실패는 잠깐 실패한 것이지 못 들어갈 방이
   * 아니다 — 특히 429(레이트리밋)를 접근 불가로 읽으면, 방을 빠르게 오가기만 해도 멀쩡한 방에
   * "존재하지 않습니다"가 뜬다. 나머지는 예외로 올려 훅이 로그만 남기고 빈 흐름으로 가게 둔다.
   */
  const fetchHistory = useCallback(
    async (
      threadId: string,
      afterSeq?: number,
    ): Promise<ThreadMessageItem[]> => {
      const { status, messages } = await fetchThreadMessages(
        threadId,
        afterSeq,
      );
      if (status === 404 || status === 403) {
        if (isNewDraft) return [];
        throw new RoomInaccessibleError(`history status ${status}`);
      }
      if (status >= 400) throw new Error(`history status ${status}`);
      return messages;
    },
    [isNewDraft],
  );

  const handleHistoryError = useCallback((error: unknown) => {
    if (error instanceof RoomInaccessibleError) setIsInaccessible(true);
  }, []);

  const room = useRoom(id, {
    fetchHistory,
    startPromoted: !isNewDraft,
    onHistoryError: handleHistoryError,
  });
  const { send, cancel, cancellableTurnId, dismissError, dismissNotice } = room;

  // 입퇴장 줄은 1:1에 오지 않지만 타입상 섞여 있어 한 번 걸러 둔다.
  const roomMessages = useMemo(
    () =>
      room.state.messages.filter(
        (entry): entry is RoomMessage => !isPresenceNotice(entry),
      ),
    [room.state.messages],
  );

  const renderedMessages = useMemo<RenderedMessage[]>(
    () =>
      roomMessages.map((message) => ({
        id: message.id,
        role: message.from === null ? 'assistant' : 'user',
        content: message.content,
      })),
    [roomMessages],
  );

  /**
   * 보냈지만 아직 답변 말풍선이 생기지 않은 턴. 이것이 없으면 전송 직후부터 첫 조각이 도착할
   * 때까지 "생각 중" 표시가 비어 버린다 — 그 구간에는 흐르는 메시지가 아직 하나도 없다.
   */
  const [pendingTurnIds, setPendingTurnIds] = useState<string[]>([]);

  useEffect(() => {
    if (pendingTurnIds.length === 0) return;
    const settled = new Set(
      roomMessages
        .filter((message) => message.turnId !== null && !message.streaming)
        .map((message) => message.turnId as string),
    );
    const next = pendingTurnIds.filter((turnId) => !settled.has(turnId));
    if (next.length !== pendingTurnIds.length) setPendingTurnIds(next);
  }, [pendingTurnIds, roomMessages]);

  const isLoading =
    pendingTurnIds.length > 0 ||
    roomMessages.some((message) => message.streaming);

  /** 중지·거부는 그 AI 답변 자체에 표시한다(이슈 #162, §3.2). */
  const terminalStatusByMessageId = useMemo(() => {
    const byId: Record<string, 'cancelled' | 'denied'> = {};
    for (const message of roomMessages) {
      if (message.terminalStatus !== null)
        byId[message.id] = message.terminalStatus;
    }
    return byId;
  }, [roomMessages]);

  /**
   * 근거 인용은 AI 답변이 들고 오지만 패널은 그 답변을 부른 사람 메시지 아래에 붙는다 —
   * 그래서 직전 사람 메시지의 id로 옮겨 단다(이슈 #163).
   *
   * 답변이 아직 흐르는 중이고 근거가 비었으면 "검색 중"으로 둔다. 끝났는데도 비어 있으면
   * 아무것도 걸지 않는다 — 예전에는 사람 메시지가 마지막이기만 하면 무조건 검색 중으로
   * 칠해, 답변이 실패하거나 이력에서 되살아난 메시지에 스피너가 영영 남았다.
   */
  const citationsByMessageId = useMemo(() => {
    const byId: Record<string, CitationsState> = {};
    roomMessages.forEach((message, index) => {
      if (message.from !== null) return;
      let askedBy: string | undefined;
      for (let cursor = index - 1; cursor >= 0; cursor -= 1) {
        if (roomMessages[cursor].from !== null) {
          askedBy = roomMessages[cursor].id;
          break;
        }
      }
      if (askedBy === undefined) return;
      if (message.citations.length > 0 || message.restrictedResultsOmitted) {
        byId[askedBy] = {
          status: 'success',
          citations: message.citations,
          restrictedResultsOmitted: message.restrictedResultsOmitted,
        };
      } else if (message.streaming) {
        byId[askedBy] = { status: 'loading' };
      }
    });
    return byId;
  }, [roomMessages]);

  // 전송에 쓰이는 모델의 정본. prop으로 받은 selectedModelId는 쿠키에서 온 서버 렌더 값이라
  // 초기값으로만 쓴다 — 쿠키만 갱신하면 서버 컴포넌트가 다시 렌더링되지 않아 라벨과 전송값이
  // 갈라졌다(이슈 #94). page.tsx가 key={id}로 리마운트하므로 세션을 옮기면 쿠키 값으로 초기화된다.
  const [modelId, setModelId] = useState(selectedModelId);

  // 전송 시점에 읽을 최신 값. 전송 경로의 클로저는 memo된 입력창에 붙잡혀 낡을 수 있는데,
  // 클로저가 낡아도 ref는 최신을 가리키므로 방금 고른 모델로 나간다.
  const modelIdRef = useRef(modelId);
  modelIdRef.current = modelId;

  // 정본을 갱신하고, 다음 방문에 복원할 수 있도록 쿠키에도 남긴다. ChatHeader가 memo라
  // 매 렌더 새 함수를 넘기면 memo가 무의미해지므로 useCallback으로 고정한다.
  const handleModelChange = useCallback((nextModelId: string) => {
    setModelId(nextModelId);
    // 쿠키 저장이 실패해도 이번 대화는 정상이고 다음 방문 복원만 안 된다 — 처리를 안 하면
    // unhandled rejection이 되므로 로그만 남긴다.
    saveModelId(nextModelId).catch((error: Error) => {
      console.error('[chat] saveModelId failed', error);
    });
  }, []);

  // 스토어에 없는 id(draft, "/" 새 진입)면 세션을 만들지 않고 활성 하이라이트만 해제한다 —
  // 여기서 무조건 생성하면 새로고침마다 빈 대화가 쌓인다(실제 생성은 handleSend).
  useEffect(() => {
    const { sessions, switchSession, clearCurrentSession } =
      useChatSessionsStore.getState();
    if (sessions.some((session) => session.id === id)) {
      switchSession(id);
    } else {
      clearCurrentSession();
    }
  }, [id]);

  const sendTurn = useCallback(
    (content: string) => {
      const ids = send(content, modelIdRef.current);
      if (ids === null) {
        toast.error('연결이 끊겨 있어 메시지를 보내지 못했습니다.');
        return;
      }
      setPendingTurnIds((previous) => [...previous, ids.turnId]);
    },
    [send],
  );

  const retryLatestTurn = useCallback(() => {
    const latestUser = [...renderedMessages]
      .reverse()
      .find((message) => message.role === 'user');
    if (latestUser) sendTurn(latestUser.content);
  }, [renderedMessages, sendTurn]);

  // 서버가 돌려준 오류는 방 상태에 쌓인다(room-state의 error). 토스트로 알리고 그 자리에서
  // 비운다 — 배너로 남기는 것은 system.notice(warning) 쪽이다.
  useEffect(() => {
    const error = room.state.error;
    if (error === null) return;
    console.error(`[chat] WS error traceId=${error.traceId}`);
    // duration: Infinity — 기본 지속시간 후 토스트가 사라지면 "다시 시도" 버튼도 함께
    // 사라져 재시도할 방법이 없어진다. cancel(닫기)로 재시도 없이도 넘어갈 수 있게 한다.
    toast.error(error.message, {
      duration: Number.POSITIVE_INFINITY,
      action: { label: '다시 시도', onClick: retryLatestTurn },
      cancel: { label: '닫기', onClick: () => {} },
    });
    setPendingTurnIds([]);
    dismissError();
  }, [room.state.error, retryLatestTurn, dismissError]);

  // EMPTY_FAILED_MESSAGE_IDS는 고정 참조 — 매번 새 배열을 반환하면 zustand가 값이 바뀐
  // 것으로 보고 무한 리렌더로 이어진다.
  const failedMessageIds = useChatSessionsStore(
    (state) =>
      state.sessions.find((session) => session.id === id)?.failedMessageIds ??
      EMPTY_FAILED_MESSAGE_IDS,
  );

  const handleResendFailedMessage = useCallback(
    (messageId: string) => {
      useChatSessionsStore.getState().clearMessageFailed(id, messageId);
      const failed = renderedMessages.find(
        (message) => message.id === messageId,
      );
      if (failed?.role === 'user') sendTurn(failed.content);
    },
    [id, renderedMessages, sendTurn],
  );

  // 입력창이 완성한 발화 하나를 받아 보낸다. 세션은 첫 메시지를 실제로 보낼 때만 스토어에
  // 만든다(draft 화면 새로고침으로 빈 세션이 쌓이지 않도록).
  const handleSend = useCallback(
    (content: string) => {
      const { sessions, createSession } = useChatSessionsStore.getState();
      if (!sessions.some((session) => session.id === id)) {
        createSession({ id, modelId: modelIdRef.current });
      }
      sendTurn(content);
    },
    [id, sendTurn],
  );

  const stop = useCallback(() => {
    if (cancellableTurnId !== null) cancel(cancellableTurnId);
  }, [cancel, cancellableTurnId]);

  return (
    <div className="flex flex-col min-w-0 h-dvh bg-background">
      <ChatHeader
        availableModels={availableModels}
        selectedModelId={modelId}
        onModelChange={handleModelChange}
      />

      {/* 같은 code는 한 건뿐이라(room-state의 upsertNotice) key가 겹치지 않는다. */}
      {room.state.notices.map((notice) => (
        <NoticeBanner
          key={notice.code}
          notice={notice}
          onDismiss={() => dismissNotice(notice.code)}
        />
      ))}

      <Messages
        chatId={id}
        isLoading={isLoading}
        messages={renderedMessages}
        citationsByMessageId={citationsByMessageId}
        terminalStatusByMessageId={terminalStatusByMessageId}
        failedMessageIds={failedMessageIds}
        onResendFailedMessage={handleResendFailedMessage}
        onAppendTurn={sendTurn}
      />

      {isInaccessible && (
        <div className="mx-auto w-full max-w-3xl px-4 pb-3 text-sm text-destructive">
          이 대화에 접근할 수 없거나 존재하지 않습니다.
        </div>
      )}

      <form className="flex mx-auto px-4 bg-background pb-4 md:pb-6 gap-2 w-full md:max-w-3xl">
        <MultimodalInput
          chatId={id}
          onSend={handleSend}
          isLoading={isLoading || isInaccessible}
          stop={stop}
        />
      </form>
    </div>
  );
}
