/********************************************************
 파일명 : chat-sessions.ts (lib/store)
 설 명 : 다중 채팅 세션 상태 스토어. 세션 목록과 현재 세션을 zustand로 관리하고 localStorage로
 지속한다. 대화 내용은 여기 두지 않는다 — 이력의 정본은 서버이고(이슈 #218), 로컬 사본은 서버
 이력과 id가 어긋나 같은 메시지를 두 벌로 보이게 하던 원인이었다. 스트리밍 여부도 방 상태
 (lib/chat/room-state)에서 나오므로 여긴 두지 않는다.
 failedMessageIds는 재로그인 리다이렉트 후에도 "전송 실패 + 재전송" 표시가 남아야 해서 이 스토어에 둔다.
 하이드레이션: 서버 평가 시점엔 localStorage가 없어 skipHydration으로 자동 복원을 끄고,
 클라이언트 마운트 후 useChatSessionsHydrated가 명시적으로 복원한다.
 *********************************************************/

import { useEffect, useState } from 'react';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

/** setSessions 입력 — 서버(GET /api/chat/sessions) 응답 항목 모양(lib/api/server-history.ts와 동일). */
export interface ServerChatSession {
  id: string;
  title: string;
  createdAt: string;
}

export interface ChatSession {
  id: string;
  title: string;
  modelId: string;
  createdAt: number;
  failedMessageIds: string[];
  /** true면 renameSession으로 사용자가 직접 정한 제목 — 자동 파생·서버 동기화가 덮어쓰지 않는다. */
  titleCustomized: boolean;
}

const DEFAULT_TITLE = '새 대화';
/** 세션 탭 제목 최대 길이. 자동 파생(deriveTitle)과 사용자 수동 변경(renameSession) 둘 다 이 값으로 자른다. */
export const TITLE_MAX_LENGTH = 40;

/** failedMessageIds 폴백용 고정 참조 — 매번 새 배열(`?? []`)을 셀렉터에서 반환하면
 * zustand가 "바뀐 값"으로 보고 매 렌더 재구독을 트리거해 무한 리렌더로 이어진다. */
export const EMPTY_FAILED_MESSAGE_IDS: string[] = [];

/** 첫 발화로 세션 탭 제목을 만든다. 비었으면 기본 제목, TITLE_MAX_LENGTH 초과 시 말줄임표로 자른다. */
export function deriveTitle(content: string): string {
  const trimmed = content.trim();
  if (!trimmed) return DEFAULT_TITLE;

  return trimmed.length > TITLE_MAX_LENGTH
    ? `${trimmed.slice(0, TITLE_MAX_LENGTH)}…`
    : trimmed;
}

/** 세션 삭제 후 currentSessionId를 정한다. 현재 세션이 지워진 경우에만 남은 세션 중 마지막으로
 * 전환한다(없으면 null → 새 대화 화면). */
export function pickNextSessionId(
  sessions: ChatSession[],
  deletedId: string,
  currentSessionId: string | null,
): string | null {
  if (currentSessionId !== deletedId) return currentSessionId;
  const remaining = sessions.filter((session) => session.id !== deletedId);
  return remaining.at(-1)?.id ?? null;
}

interface ChatSessionsState {
  sessions: ChatSession[];
  currentSessionId: string | null;
  createSession: (params: { id: string; modelId: string }) => void;
  switchSession: (id: string) => void;
  deleteSession: (id: string) => void;
  applyFirstMessageTitle: (id: string, content: string) => void;
  clearCurrentSession: () => void;
  setSessions: (serverSessions: ServerChatSession[]) => void;
  markMessageFailed: (sessionId: string, messageId: string) => void;
  clearMessageFailed: (sessionId: string, messageId: string) => void;
  renameSession: (id: string, title: string) => void;
}

/**
 * v0 → v1: 세션마다 들고 있던 messages 배열을 버린다.
 *
 * 그냥 두면 코드를 고쳐도 기존 사용자 화면의 중복이 그대로 남는다. 그 사본은 임시 id(useChat이
 * 만들던 nanoid)로 저장돼 있어 서버 이력의 msgId와 짝이 맞지 않고, 합치는 쪽은 id로만 같은
 * 메시지를 알아보기 때문이다. 버려도 잃는 것은 없다 — 이력의 정본은 서버다(이슈 #218).
 *
 * persist 옵션 안에 두지 않고 밖으로 뺀 이유는 시험하기 위해서다. vitest 환경이 'node'라
 * localStorage가 없어 persist가 아예 안 붙고, 그러면 스토어를 통해서는 이 함수에 닿을 수 없다.
 */
export function migrateChatSessions(persisted: unknown, version: number) {
  if (version >= 1) return persisted;
  const state = persisted as { sessions?: unknown[] } | null;
  if (state?.sessions === undefined) return persisted;
  return {
    ...state,
    sessions: state.sessions.map((session) => {
      const { messages: _dropped, ...rest } = session as Record<
        string,
        unknown
      >;
      return rest;
    }),
  };
}

export const useChatSessionsStore = create<ChatSessionsState>()(
  persist(
    (set) => ({
      sessions: [],
      currentSessionId: null,

      createSession: ({ id, modelId }) =>
        set((state) => ({
          sessions: [
            ...state.sessions,
            {
              id,
              title: DEFAULT_TITLE,
              modelId,
              createdAt: Date.now(),
              failedMessageIds: [],
              titleCustomized: false,
            },
          ],
          currentSessionId: id,
        })),

      switchSession: (id) =>
        set((state) =>
          state.sessions.some((session) => session.id === id)
            ? { currentSessionId: id }
            : state,
        ),

      // draft 세션("/" 새 진입)으로 이동 시 사이드바 활성 하이라이트만 해제한다.
      clearCurrentSession: () => set({ currentSessionId: null }),

      deleteSession: (id) =>
        set((state) => ({
          sessions: state.sessions.filter((session) => session.id !== id),
          currentSessionId: pickNextSessionId(
            state.sessions,
            id,
            state.currentSessionId,
          ),
        })),

      // 제목은 첫 발화를 보낼 때 한 번만 확정한다(계속 바뀌면 탭을 못 찾는 UX가 됨).
      // titleCustomized면 사용자가 직접 정한 제목이라 손대지 않고, 이미 확정된 제목도 그대로 둔다.
      //
      // 서버도 첫 발화로 제목을 정하지만(ThreadWebSocketHandler.titleFor) 사이드바는 마운트당
      // 한 번만 서버 목록을 읽는다 — 로컬에서도 정해 두지 않으면 새 대화가 새로고침 전까지
      // "새 대화"로 남는다.
      applyFirstMessageTitle: (id, content) =>
        set((state) => ({
          sessions: state.sessions.map((session) =>
            session.id === id &&
            !session.titleCustomized &&
            session.title === DEFAULT_TITLE
              ? { ...session, title: deriveTitle(content) }
              : session,
          ),
        })),

      // 서버가 진실의 원천이라 서버 목록에 없는 로컬 세션은 제거한다. title은 titleCustomized면
      // 로컬 값을 지킨다 — 그러지 않으면 PATCH 실패 시 새로고침마다 이름이 되돌아간다.
      setSessions: (serverSessions) =>
        set((state) => {
          const localById = new Map(
            state.sessions.map((session) => [session.id, session]),
          );
          return {
            sessions: serverSessions.map((server) => {
              const local = localById.get(server.id);
              return {
                id: server.id,
                title: local?.titleCustomized ? local.title : server.title,
                // 서버 응답에는 모델이 없다. 로컬 기록이 없으면 비워둔다 — 이 값은 세션을 만든
                // 시점의 기록일 뿐이고, 실제 전송에 쓰이는 모델은 chat.tsx가 쥔 modelId 상태다.
                modelId: local?.modelId ?? '',
                createdAt: new Date(server.createdAt).getTime(),
                failedMessageIds: local?.failedMessageIds ?? [],
                titleCustomized: local?.titleCustomized ?? false,
              };
            }),
          };
        }),

      // 재로그인이 강제된 요청의 마지막 user 메시지를 "전송 실패"로 표시한다(중복 추가 방지).
      markMessageFailed: (sessionId, messageId) =>
        set((state) => ({
          sessions: state.sessions.map((session) => {
            if (session.id !== sessionId) return session;
            // 이 필드 도입 전 저장된 로컬 세션엔 없을 수 있다.
            const failedMessageIds = session.failedMessageIds ?? [];
            return failedMessageIds.includes(messageId)
              ? session
              : {
                  ...session,
                  failedMessageIds: [...failedMessageIds, messageId],
                };
          }),
        })),

      // 재전송 시도 시 낙관적으로 실패 표시를 지운다. 다시 재로그인 강제로 실패하면 onError가
      // 재표시한다 — 스트림절단·일반에러로 실패하는 경우엔 토스트의 "다시 시도"가 재시도를 맡는다.
      clearMessageFailed: (sessionId, messageId) =>
        set((state) => ({
          sessions: state.sessions.map((session) =>
            session.id === sessionId
              ? {
                  ...session,
                  failedMessageIds: (session.failedMessageIds ?? []).filter(
                    (id) => id !== messageId,
                  ),
                }
              : session,
          ),
        })),

      // 빈 문자열(공백만 입력)은 무시하고 기존 제목을 유지한다 — 빈 탭 이름은 클릭 대상을 찾을 수 없게 만든다.
      renameSession: (id, title) =>
        set((state) => {
          const trimmed = title.trim().slice(0, TITLE_MAX_LENGTH);
          if (!trimmed) return state;
          return {
            sessions: state.sessions.map((session) =>
              session.id === id
                ? { ...session, title: trimmed, titleCustomized: true }
                : session,
            ),
          };
        }),
    }),
    {
      name: 'chat-sessions',
      storage: createJSONStorage(() => localStorage),
      skipHydration: true,
      version: 1,
      migrate: migrateChatSessions,
    },
  ),
);

/**
 * localStorage 복원 완료 여부. true 전에 세션 생성/전환을 판정하면 빈 스토어를 보고 매번
 * 새 세션을 만들어버리므로, 호출부(Chat)는 이 값으로 채팅 UI 마운트를 게이트해야 한다.
 * SSR엔 localStorage가 없어 `persist`가 아예 안 붙을 수 있다 — 초기값 조회는 옵셔널 체이닝으로 방어한다.
 */
export function useChatSessionsHydrated(): boolean {
  const [hydrated, setHydrated] = useState(
    () => useChatSessionsStore.persist?.hasHydrated() ?? false,
  );

  useEffect(() => {
    if (hydrated) return;
    const unsubscribe = useChatSessionsStore.persist.onFinishHydration(() =>
      setHydrated(true),
    );
    useChatSessionsStore.persist.rehydrate();
    return unsubscribe;
  }, [hydrated]);

  return hydrated;
}
