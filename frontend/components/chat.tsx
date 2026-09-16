'use client';

/********************************************************
 파일명 : chat.tsx
 설 명 : 채팅 화면의 핵심 배선(useChat 훅 설정)과 세션 스토어 연동. Chat은 localStorage 하이드레이션이
 끝날 때까지 로딩만 보여주는 게이트고, 실제 로직(BFF URL·스트림 프로토콜·인증 fetch·요청 바디·
 에러 처리)은 ChatSession에 있다.
 *********************************************************/

import type { Message } from 'ai';
import { useChat } from 'ai/react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';

import { saveModelId } from '@/app/(chat)/actions';
import { ChatHeader } from '@/components/chat-header';
import { LoaderIcon } from '@/components/icons';
import { NoticeBanner } from '@/components/notice-banner';
import { buildChatRequestBody } from '@/lib/api/chat';
import {
  STREAM_TRUNCATED_MESSAGE,
  isStreamTruncated,
  resolveChatError,
} from '@/lib/api/errors';
import {
  type ThreadMessageItem,
  fetchThreadMessages,
} from '@/lib/api/thread-history';
import { type SystemNotice, noticeMessage } from '@/lib/collab/room-state';
import { createDirectChatFetch } from '@/lib/transport/direct-room-fetch';
import type {
  ChatAnswerFrame,
  ChatMessageFrame,
  SystemNoticeFrame,
} from '@/lib/transport/frames';
import {
  EMPTY_FAILED_MESSAGE_IDS,
  useChatSessionsHydrated,
  useChatSessionsStore,
} from '@/lib/store/chat-sessions';
import type { CitationsState } from './citations-panel';
import { Messages } from './messages';
import { MultimodalInput } from './multimodal-input';

/** localStorage 복원 전엔 빈 스토어를 보고 매번 새 세션을 만들어버리므로, 복원이 끝날 때까지
 * 로딩 스피너만 보여주는 게이트. serverMessages는 로컬에 없을 때만 폴백으로 쓰인다. */
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

/** useChat 훅을 계약에 맞게 배선하고, 세션 스토어 등록·근거 인용 병렬 조회·스트림 중단 감지·
 * 로그인 만료 시 전송 실패 표시를 담당한다. Chat이 id별로 리마운트하므로(page.tsx의 key={id})
 * 세션 전환 시 상태가 자연히 초기화된다. */
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
  // reload·messages를 onError 클로저에서 바로 참조하면 선언 전 사용(TDZ)이 되므로 ref로 가리킨다.
  const appendRef = useRef<
    ((message: { role: 'user'; content: string }) => unknown) | null
  >(null);
  const messagesRef = useRef<Message[]>([]);

  const retryLatestTurn = useCallback(() => {
    const latestUser = [...messagesRef.current]
      .reverse()
      .find((message) => message.role === 'user');
    if (latestUser) {
      appendRef.current?.({ role: 'user', content: latestUser.content });
    }
  }, []);

  const setMessagesBridgeRef = useRef<
    | ((messages: Message[] | ((messages: Message[]) => Message[])) => void)
    | null
  >(null);

  // 근거 인용 상태(이슈 #163). directChat(아래)의 onChatCitation이 채우므로 그보다 먼저
  // 선언한다. turnIdToMessageIdRef는 onTurnStarted가 알려준 turnId→clientMsgId 짝을 들고
  // 있다가, 도착한 citations 패킷의 turnId로 정확한 메시지를 찾는다 — "지금 활성 턴"만 아는
  // 단일 ref로 짝짓던 예전 방식은 턴을 취소하고 바로 재전송하면 늦게 도착한 citations가
  // 엉뚱한 메시지에 붙을 수 있었다.
  const [citationsByMessageId, setCitationsByMessageId] = useState<
    Record<string, CitationsState>
  >({});
  const requestedCitationsRef = useRef<Set<string>>(new Set());
  const turnIdToMessageIdRef = useRef(new Map<string, string>());
  const activeTurnIdsRef = useRef(new Set<string>());
  const lastSeqRef = useRef<number | undefined>(undefined);
  const [connectionEpoch, setConnectionEpoch] = useState(0);
  const [isInaccessible, setIsInaccessible] = useState(false);

  // 이번 턴이 done·cancelled·denied 중 무엇으로 끝났는지(이슈 #162, §3.2). useChat의 text
  // 스트림 body는 "끝났다"만 전해서, onAnswerTerminal(턴 종료 시점)과 onFinish(그 턴의
  // resultMessage.id 확정 시점)를 이 ref로 이어 붙여야 어느 메시지의 상태인지 알 수 있다.
  const lastTerminalStatusRef = useRef<'done' | 'cancelled' | 'denied'>('done');
  const lastTerminalServerMsgIdRef = useRef<string | null>(null);
  const [terminalStatusByMessageId, setTerminalStatusByMessageId] = useState<
    Record<string, 'cancelled' | 'denied'>
  >({});

  // 방 위에 얹히는 warning 배너(#29) — 닫기 전까지 남는다. 같은 code는 최신 1건만 남긴다
  // (room-state.ts의 upsertNotice와 같은 결). info는 배너로 남기지 않고 토스트로 지나간다.
  const [notices, setNotices] = useState<SystemNotice[]>([]);
  const dismissNotice = useCallback((code: string) => {
    setNotices((prev) => prev.filter((notice) => notice.code !== code));
  }, []);
  const handleSystemNotice = useCallback((frame: SystemNoticeFrame) => {
    if (frame.severity === 'info') {
      toast.info(noticeMessage(frame.message));
      return;
    }
    const notice: SystemNotice = {
      code: frame.code,
      message: noticeMessage(frame.message),
      traceId: frame.traceId,
    };
    setNotices((prev) => {
      const index = prev.findIndex((existing) => existing.code === notice.code);
      if (index === -1) return [...prev, notice];
      const next = [...prev];
      next[index] = notice;
      return next;
    });
  }, []);

  const mergeChatMessage = useCallback((frame: ChatMessageFrame) => {
    setMessagesBridgeRef.current?.((previous) => {
      const optimisticIndex =
        frame.clientMsgId === null
          ? -1
          : previous.findIndex((message) => message.id === frame.clientMsgId);
      const serverIndex = previous.findIndex(
        (message) => message.id === frame.msgId,
      );
      const index = optimisticIndex >= 0 ? optimisticIndex : serverIndex;
      const nextMessage: Message = {
        id: frame.msgId,
        role: 'user',
        content: frame.content,
      };
      if (index < 0) return [...previous, nextMessage];
      const next = [...previous];
      next[index] = { ...next[index], ...nextMessage };
      return next;
    });
  }, []);

  const mergeOtherTurnAnswer = useCallback((frame: ChatAnswerFrame) => {
    const isCurrentTurn =
      frame.turnId !== null && activeTurnIdsRef.current.has(frame.turnId);
    if (isCurrentTurn) {
      if (frame.status !== 'streaming')
        activeTurnIdsRef.current.delete(frame.turnId as string);
      return;
    }
    setMessagesBridgeRef.current?.((previous) => {
      const index = previous.findIndex((message) => message.id === frame.msgId);
      if (index < 0) {
        if (
          frame.delta === '' &&
          frame.status !== 'cancelled' &&
          frame.status !== 'denied'
        )
          return previous;
        return [
          ...previous,
          { id: frame.msgId, role: 'assistant', content: frame.delta },
        ];
      }
      const next = [...previous];
      next[index] = {
        ...next[index],
        content: `${next[index].content}${frame.delta}`,
      };
      return next;
    });
  }, []);

  const mergeHistory = useCallback((items: ThreadMessageItem[]) => {
    if (items.length === 0) return;
    lastSeqRef.current = Math.max(
      lastSeqRef.current ?? -1,
      ...items.map((item) => item.seq),
    );
    setMessagesBridgeRef.current?.((previous) => {
      const next = [...previous];
      for (const item of items) {
        if (item.athKind === 'SYSTEM') continue;
        const message: Message = {
          id: item.id,
          role: item.athKind === 'HUMAN' ? 'user' : 'assistant',
          content: item.content,
        };
        const index = next.findIndex((candidate) => candidate.id === item.id);
        if (index < 0) next.push(message);
        else next[index] = { ...next[index], ...message };
      }
      return next;
    });
  }, []);

  // WS 커넥션은 탭이 공유하는 허브(ws-rooms.ts)가 들고 있어 인증도 그쪽 핸드셰이크 몫이다 —
  // 옛 HTTP 경로의 authFetch 재로그인 신호는 이제 필요 없다(이슈 #162). 새 draft면 구독 없이
  // 첫 발화만 보내고(bootstrap), 기존 방이면 처음부터 room.subscribe로 시작한다(§2.1).
  const directChat = useMemo(
    () =>
      createDirectChatFetch(id, {
        startPromoted: !isNewDraft,
        onAnswerTerminal: (status) => {
          lastTerminalStatusRef.current = status;
        },
        onCurrentAnswerTerminal: (frame) => {
          lastTerminalServerMsgIdRef.current = frame.msgId;
        },
        onChatCitation: ({ citations, restrictedResultsOmitted, turnId }) => {
          const messageId = turnId
            ? turnIdToMessageIdRef.current.get(turnId)
            : undefined;
          if (!messageId) return;
          setCitationsByMessageId((prev) => ({
            ...prev,
            [messageId]: { status: 'success', citations, restrictedResultsOmitted },
          }));
        },
        onSystemNotice: handleSystemNotice,
        onChatMessage: mergeChatMessage,
        onChatAnswer: mergeOtherTurnAnswer,
        onTurnStarted: ({ clientMsgId, turnId }) => {
          activeTurnIdsRef.current.add(turnId);
          turnIdToMessageIdRef.current.set(turnId, clientMsgId);
        },
        onOpenChange: (open) => {
          if (open) setConnectionEpoch((epoch) => epoch + 1);
        },
      }),
    // handleSystemNotice는 useCallback([])으로 고정돼 있어 정체성이 안 바뀐다 — 방 재구독을
    // 일으키지 않기 위해 의도적으로 deps에 넣지 않는다.
    [
      id,
      isNewDraft,
      handleSystemNotice,
      mergeChatMessage,
      mergeOtherTurnAnswer,
    ],
  );
  useEffect(() => () => directChat.dispose(), [directChat]);

  useEffect(() => {
    if (isNewDraft) return;
    let cancelled = false;
    fetchThreadMessages(id, lastSeqRef.current)
      .then(({ status, messages }) => {
        if (cancelled) return;
        if (status === 404) {
          setIsInaccessible(true);
          return;
        }
        if (status >= 400) {
          setIsInaccessible(true);
          return;
        }
        mergeHistory(messages);
      })
      .catch(() => {
        if (!cancelled) setIsInaccessible(true);
      });
    return () => {
      cancelled = true;
    };
  }, [connectionEpoch, id, isNewDraft, mergeHistory]);

  // useChat은 initialMessages를 최초 마운트 시점에만 반영하므로 지연 초기화로 한 번만 읽는다.
  // 로컬에 메시지가 없으면(다른 기기·새 브라우저 등) serverMessages로 폴백한다.
  const [initialMessages] = useState<Message[] | undefined>(() => {
    const local = useChatSessionsStore
      .getState()
      .sessions.find((session) => session.id === id)?.messages;
    if (local && local.length > 0) return local;
    return local;
  });

  // 전송에 쓰이는 모델의 정본. prop으로 받은 selectedModelId는 쿠키에서 온 서버 렌더 값이라
  // 초기값으로만 쓴다 — 쿠키만 갱신하면 서버 컴포넌트가 다시 렌더링되지 않아 라벨과 전송값이
  // 갈라졌다(이슈 #94). page.tsx가 key={id}로 리마운트하므로 세션을 옮기면 쿠키 값으로 초기화된다.
  const [modelId, setModelId] = useState(selectedModelId);

  // 전송 시점에 읽을 최신 값. useChat이 돌려주는 handleSubmit은 memo된 입력창에 붙잡혀 낡을 수
  // 있는데, 클로저가 낡아도 ref는 최신을 가리키므로 방금 고른 모델로 나간다. 위 reloadRef와
  // 같은 패턴이다.
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
  // 여기서 무조건 생성하면 새로고침마다 빈 대화가 쌓인다(실제 생성은 handleChatSubmit).
  useEffect(() => {
    const { sessions, switchSession, clearCurrentSession } =
      useChatSessionsStore.getState();
    if (sessions.some((session) => session.id === id)) {
      switchSession(id);
    } else {
      clearCurrentSession();
    }
  }, [id]);

  // useChat에 넘기는 콜백은 useCallback으로 고정한다. 이것들이 매 렌더 새 객체면 useChat 내부의
  // triggerRequest → handleSubmit이 매 렌더 새로 만들어져, 입력창의 memo 비교자가 무력화되고
  // 스트리밍 중 100ms(experimental_throttle)마다 입력창이 다시 그려진다.
  const prepareRequestBody = useCallback(
    ({ messages }: { messages: Message[] }) => ({
      ...buildChatRequestBody({
        sessionId: id,
        modelId: modelIdRef.current,
        messages,
      }),
      clientMsgId: messages.at(-1)?.id,
    }),
    [id],
  );

  // WS 연결 자체의 인증·재로그인은 ws-connection.ts 몫이라 여기 도달하는 건 주로 서버가 돌려준
  // 오류 프레임(direct-room-fetch.ts가 JSON 봉투나 mid-stream 에러로 옮긴 것)이다.
  const handleChatError = useCallback(
    (error: Error) => {
      // duration: Infinity — 기본 지속시간 후 토스트가 사라지면 "다시 시도" 버튼도 함께
      // 사라져 재시도할 방법이 없어진다. cancel(닫기)로 재시도 없이도 넘어갈 수 있게 한다.
      if (isStreamTruncated(messagesRef.current.at(-1))) {
        toast.error(STREAM_TRUNCATED_MESSAGE, {
          duration: Number.POSITIVE_INFINITY,
          action: { label: '다시 시도', onClick: retryLatestTurn },
          cancel: { label: '닫기', onClick: () => {} },
        });
        return;
      }

      const { message, traceId } = resolveChatError(error);
      if (traceId) {
        console.error(`[chat] BFF error traceId=${traceId}`);
      }
      toast.error(message, {
        duration: Number.POSITIVE_INFINITY,
        action: { label: '다시 시도', onClick: retryLatestTurn },
        cancel: { label: '닫기', onClick: () => {} },
      });
    },
    [retryLatestTurn],
  );

  // setMessages도 reloadRef와 같은 이유로 ref에 담는다 — handleChatFinish가 useChat 호출보다
  // 앞에 있어 아직 값이 없는 시점에 정의된다.
  const setMessagesRef = useRef<
    | ((messages: Message[] | ((messages: Message[]) => Message[])) => void)
    | null
  >(null);

  // DIRECT 전용 terminal 상태(cancelled·denied)만 message.id별로 기억한다 — done은 평범한
  // 완료라 화면이 따로 표시할 게 없다(이슈 #162, §3.2).
  const handleChatFinish = useCallback((message: Message) => {
    const status = lastTerminalStatusRef.current;
    const serverMessageId = lastTerminalServerMsgIdRef.current;
    lastTerminalStatusRef.current = 'done';
    lastTerminalServerMsgIdRef.current = null;
    const visibleMessageId = serverMessageId ?? message.id;
    if (serverMessageId && serverMessageId !== message.id) {
      setMessagesRef.current?.((previous) =>
        previous.map((existing) =>
          existing.id === message.id
            ? { ...existing, id: serverMessageId }
            : existing,
        ),
      );
    }
    if (status === 'done') return;
    setTerminalStatusByMessageId((prev) => ({
      ...prev,
      [visibleMessageId]: status,
    }));
    // useChat은 텍스트 조각을 하나라도 받아야 messages에 반영한다(onUpdate가 onTextPart
    // 안에서만 불린다) — DENIED는 항상, CANCELLED도 즉시 취소되면 본문이 비어 조각이 하나도
    // 안 와서 이 메시지 자체가 messages에 없을 수 있다. 그 경우만 직접 끼워 넣는다.
    if (message.content === '') {
      setMessagesRef.current?.((prev) =>
        prev.some((existing) => existing.id === visibleMessageId)
          ? prev
          : [...prev, { ...message, id: visibleMessageId }],
      );
    }
  }, []);

  const {
    messages,
    setMessages,
    handleSubmit,
    input,
    setInput,
    append,
    isLoading,
    stop,
  } = useChat({
    id,
    initialMessages,
    // api는 실제로 안 불린다 — fetch를 완전히 대체해 WS 기반 direct-room-fetch.ts로 보낸다
    // (이슈 #162). useChat이 내부적으로 요구해 값만 채운다.
    api: `ws-direct-chat:${id}`,
    // 프레이밍 없는 raw 텍스트 스트림 소비.
    streamProtocol: 'text',
    fetch: directChat.fetch,
    experimental_prepareRequestBody: prepareRequestBody,
    experimental_throttle: 100,
    onError: handleChatError,
    onFinish: handleChatFinish,
  });

  appendRef.current = append;
  messagesRef.current = messages;
  setMessagesRef.current = setMessages;
  setMessagesBridgeRef.current = setMessages;

  useEffect(() => {
    useChatSessionsStore.getState().setSessionMessages(id, messages);
  }, [id, messages]);

  // EMPTY_FAILED_MESSAGE_IDS는 고정 참조 — 매번 새 배열을 반환하면 zustand가 값이 바뀐
  // 것으로 보고 무한 리렌더로 이어진다.
  const failedMessageIds = useChatSessionsStore(
    (state) =>
      state.sessions.find((session) => session.id === id)?.failedMessageIds ??
      EMPTY_FAILED_MESSAGE_IDS,
  );

  // reload()는 마지막 메시지가 user면 그대로 재전송한다 — 실패 메시지가 항상 마지막이므로 충분.
  const handleResendFailedMessage = useCallback(
    (messageId: string) => {
      useChatSessionsStore.getState().clearMessageFailed(id, messageId);
      const failed = messagesRef.current.find(
        (message) => message.id === messageId,
      );
      if (failed?.role === 'user') {
        append({ role: 'user', content: failed.content });
      }
    },
    [append, id],
  );

  const appendNewTurn = useCallback(
    (content: string) => {
      append({ role: 'user', content });
    },
    [append],
  );

  // 새 user 메시지가 오면 근거 인용을 'loading'으로 표시해두고, 실제 값은 WS의 chat.answer
  // citations 전용 패킷(onChatCitation, 이슈 #163)이 turnId로 짝지어 채운다 — 더 이상 REST로
  // 병렬 조회하지 않는다. ref로 이미 처리한 메시지 id를 추적해(state면 effect 의존성 순환
  // 우려) 한 번만 표시한다.
  useEffect(() => {
    const lastMessage = messages.at(-1);
    if (!lastMessage || lastMessage.role !== 'user') return;
    if (requestedCitationsRef.current.has(lastMessage.id)) return;
    requestedCitationsRef.current.add(lastMessage.id);

    setCitationsByMessageId((prev) => ({
      ...prev,
      [lastMessage.id]: { status: 'loading' },
    }));
  }, [messages]);

  // 세션은 첫 메시지를 실제로 보낼 때만 스토어에 만든다(draft 화면 새로고침으로 빈 세션이 쌓이지 않도록).
  const handleChatSubmit = useCallback<typeof handleSubmit>(
    (event, options) => {
      const { sessions, createSession } = useChatSessionsStore.getState();
      if (!sessions.some((session) => session.id === id)) {
        createSession({ id, modelId: modelIdRef.current });
      }
      handleSubmit(event, options);
    },
    [handleSubmit, id],
  );

  return (
    <div className="flex flex-col min-w-0 h-dvh bg-background">
      <ChatHeader
        availableModels={availableModels}
        selectedModelId={modelId}
        onModelChange={handleModelChange}
      />

      {/* 같은 code는 한 건뿐이라(위 handleSystemNotice) key가 겹치지 않는다. */}
      {notices.map((notice) => (
        <NoticeBanner
          key={notice.code}
          notice={notice}
          onDismiss={() => dismissNotice(notice.code)}
        />
      ))}

      <Messages
        chatId={id}
        isLoading={isLoading}
        messages={messages}
        citationsByMessageId={citationsByMessageId}
        terminalStatusByMessageId={terminalStatusByMessageId}
        failedMessageIds={failedMessageIds}
        onResendFailedMessage={handleResendFailedMessage}
        onAppendTurn={appendNewTurn}
      />

      {isInaccessible && (
        <div className="mx-auto w-full max-w-3xl px-4 pb-3 text-sm text-destructive">
          이 대화에 접근할 수 없거나 존재하지 않습니다.
        </div>
      )}

      <form className="flex mx-auto px-4 bg-background pb-4 md:pb-6 gap-2 w-full md:max-w-3xl">
        <MultimodalInput
          chatId={id}
          input={input}
          setInput={setInput}
          handleSubmit={handleChatSubmit}
          isLoading={isLoading || isInaccessible}
          stop={stop}
          messages={messages}
          append={append}
        />
      </form>
    </div>
  );
}
