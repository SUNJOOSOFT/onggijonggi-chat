'use client';

/********************************************************
 파일명 : app-sidebar.tsx
 설 명 : 다중 세션 탭 사이드바. 세션 목록을 최신순으로 보여주고 생성/전환/삭제를 처리한다.
 로그인 버튼(authSlot)은 서버 컴포넌트라 상위에서 prop으로 주입받는다.
 *********************************************************/

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';

import {
  CrossSmallIcon,
  PencilEditIcon,
  PlusIcon,
  TrashIcon,
} from '@/components/icons';
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
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuAction,
  SidebarMenuButton,
  SidebarMenuItem,
  useSidebar,
} from '@/components/ui/sidebar';
import { fetchPermissionsEnabled } from '@/lib/api/permissions';
import {
  deleteSessionOnServer,
  fetchChatSessions,
  renameSessionOnServer,
} from '@/lib/api/chat';
import {
  TITLE_MAX_LENGTH,
  useChatSessionsHydrated,
  useChatSessionsStore,
} from '@/lib/store/chat-sessions';
import { Tooltip, TooltipContent, TooltipTrigger } from './ui/tooltip';

/** 세션 목록을 생성 역순(최신 위로)으로 정렬해 탭으로 보여준다. 보고 있던 세션을 지운
 * 경우에만 다른 세션(또는 새 대화)으로 이동한다. 로컬 하이드레이션이 끝나면 서버 세션
 * 목록(serverSessions)으로 메타데이터를 1회 갱신한다. */
export function AppSidebar({
  authSlot,
}: {
  authSlot?: React.ReactNode;
}) {
  const router = useRouter();
  const { setOpenMobile } = useSidebar();
  const hydrated = useChatSessionsHydrated();

  const sessions = useChatSessionsStore((state) => state.sessions);
  const currentSessionId = useChatSessionsStore(
    (state) => state.currentSessionId,
  );
  const sortedSessions = [...sessions].sort(
    (a, b) => b.createdAt - a.createdAt,
  );

  const [searchQuery, setSearchQuery] = useState('');
  const trimmedQuery = searchQuery.trim().toLowerCase();
  const filteredSessions = trimmedQuery
    ? sortedSessions.filter((session) =>
        session.title.toLowerCase().includes(trimmedQuery),
      )
    : sortedSessions;

  // ref로 "이미 동기화함"을 표시해 이후 로컬 변경을 서버 목록이 되돌리지 않게 한다.
  const hasSyncedRef = useRef(false);
  useEffect(() => {
    if (!hydrated || hasSyncedRef.current) return;
    hasSyncedRef.current = true;
    fetchChatSessions()
      .then((sessions) => useChatSessionsStore.getState().setSessions(sessions))
      .catch(() => undefined);
  }, [hydrated]);

  // 권한 관리 메뉴는 bff의 casbin 프로필이 켜져 있을 때만 보인다(꺼져 있으면 API가 404).
  const [permissionsEnabled, setPermissionsEnabled] = useState(false);
  useEffect(() => {
    let alive = true;
    fetchPermissionsEnabled().then((enabled) => {
      if (alive) setPermissionsEnabled(enabled);
    });
    return () => {
      alive = false;
    };
  }, []);

  // 삭제는 되돌릴 수 없어 AlertDialog로 확인한다. pendingDeleteId는 확인창이 띄워진 세션을 가리킨다.
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null);
  const pendingDeleteSession = sessions.find(
    (session) => session.id === pendingDeleteId,
  );

  const confirmDeleteSession = () => {
    const id = pendingDeleteId;
    setPendingDeleteId(null);
    if (!id) return;

    const wasCurrent = useChatSessionsStore.getState().currentSessionId === id;
    useChatSessionsStore.getState().deleteSession(id);
    if (wasCurrent) {
      const nextId = useChatSessionsStore.getState().currentSessionId;
      router.push(nextId ? `/chat/${nextId}` : '/');
    }
    // 낙관적으로 로컬은 이미 지웠다 — 서버 삭제가 실패해도 되돌리지 않고 알림만 띄운다.
    deleteSessionOnServer(id).catch(() => {
      toast.error('대화를 삭제하지 못했습니다. 다시 시도해 주세요.');
    });
  };

  const [editingSessionId, setEditingSessionId] = useState<string | null>(null);
  const [draftTitle, setDraftTitle] = useState('');
  const renameInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (editingSessionId) {
      renameInputRef.current?.focus();
      renameInputRef.current?.select();
    }
  }, [editingSessionId]);

  const startRename = (session: { id: string; title: string }) => {
    setEditingSessionId(session.id);
    setDraftTitle(session.title);
  };

  // 빈 제목은 renameSession이 조용히 무시하므로(store 쪽 방어) 여기선 편집 모드만 닫으면
  // 된다. 서버 호출도 같은 기준(trim 후 비어있지 않을 때만)으로 건너뛴다.
  const commitRename = () => {
    if (editingSessionId) {
      useChatSessionsStore
        .getState()
        .renameSession(editingSessionId, draftTitle);

      const trimmedTitle = draftTitle.trim();
      if (trimmedTitle) {
        renameSessionOnServer(editingSessionId, trimmedTitle).catch(() => {
          toast.error('이름을 저장하지 못했습니다. 다시 시도해 주세요.');
        });
      }
    }
    setEditingSessionId(null);
  };

  const cancelRename = () => {
    setEditingSessionId(null);
  };

  return (
    <>
      <Sidebar className="group-data-[side=left]:border-r-0">
        <SidebarHeader>
          {/* 헤더 로고+버튼 행이라 SidebarMenu(<ul>)로 감싸지 않는다(list 구조 위반 방지). */}
          <div className="flex flex-row justify-between items-center">
            <Link
              href="/"
              onClick={() => {
                setOpenMobile(false);
              }}
              aria-label="onggijonggi-chat 홈"
              className="flex flex-row gap-3 items-center px-2 py-1 hover:bg-muted rounded-md cursor-pointer"
            >
              <img
                src="/brand/logos/onggijonggi-logo-horizontal.svg"
                alt=""
                className="block dark:hidden h-7 w-auto"
              />
              <img
                src="/brand/logos/onggijonggi-logo-horizontal-dark.svg"
                alt=""
                className="hidden dark:block h-7 w-auto"
              />
            </Link>
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  type="button"
                  className="p-2 h-fit"
                  aria-label="새 대화"
                  onClick={() => {
                    setOpenMobile(false);
                    router.push('/');
                    router.refresh();
                  }}
                >
                  <PlusIcon />
                </Button>
              </TooltipTrigger>
              <TooltipContent align="end">New Chat</TooltipContent>
            </Tooltip>
          </div>
        </SidebarHeader>
        <SidebarContent>
          {/* 협업 채널(이슈 #19)은 세션 목록과 성격이 달라 검색·목록 위에 따로 둔다. */}
          <SidebarGroup>
            <SidebarMenu>
              <SidebarMenuItem>
                <SidebarMenuButton asChild>
                  <Link
                    href="/collab"
                    onClick={() => {
                      setOpenMobile(false);
                    }}
                  >
                    협업 채널
                  </Link>
                </SidebarMenuButton>
              </SidebarMenuItem>
              {permissionsEnabled && (
                <SidebarMenuItem>
                  <SidebarMenuButton asChild>
                    <Link
                      href="/admin/permissions"
                      onClick={() => {
                        setOpenMobile(false);
                      }}
                    >
                      권한 관리
                    </Link>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              )}
            </SidebarMenu>
          </SidebarGroup>
          <SidebarGroup>
            <div className="relative px-2 pb-2">
              <Input
                value={searchQuery}
                onChange={(event) => setSearchQuery(event.target.value)}
                placeholder="대화 검색"
                aria-label="대화 검색"
                className="h-8 pr-7"
              />
              {searchQuery && (
                <button
                  type="button"
                  aria-label="검색어 지우기"
                  onClick={() => setSearchQuery('')}
                  className="absolute right-3.5 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                >
                  <CrossSmallIcon size={14} />
                </button>
              )}
            </div>
            <SidebarMenu>
              {filteredSessions.map((session) =>
                editingSessionId === session.id ? (
                  <SidebarMenuItem key={session.id}>
                    <Input
                      ref={renameInputRef}
                      value={draftTitle}
                      onChange={(event) => setDraftTitle(event.target.value)}
                      onBlur={commitRename}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter') {
                          event.preventDefault();
                          commitRename();
                        } else if (event.key === 'Escape') {
                          event.preventDefault();
                          cancelRename();
                        }
                      }}
                      aria-label="대화 이름 수정"
                      maxLength={TITLE_MAX_LENGTH}
                      className="h-8"
                    />
                  </SidebarMenuItem>
                ) : (
                  <SidebarMenuItem key={session.id}>
                    <SidebarMenuButton
                      asChild
                      isActive={session.id === currentSessionId}
                      aria-current={
                        session.id === currentSessionId ? 'page' : undefined
                      }
                      onClick={() => setOpenMobile(false)}
                    >
                      <Link href={`/chat/${session.id}`}>
                        <span>{session.title}</span>
                      </Link>
                    </SidebarMenuButton>
                    <SidebarMenuAction
                      showOnHover
                      className="right-7"
                      aria-label={`${session.title} 이름 수정`}
                      onClick={() => startRename(session)}
                    >
                      <PencilEditIcon size={14} />
                    </SidebarMenuAction>
                    <SidebarMenuAction
                      showOnHover
                      aria-label={`${session.title} 대화 삭제`}
                      onClick={() => setPendingDeleteId(session.id)}
                    >
                      <TrashIcon size={14} />
                    </SidebarMenuAction>
                  </SidebarMenuItem>
                ),
              )}
            </SidebarMenu>
            {filteredSessions.length === 0 && trimmedQuery && (
              <div className="px-2 py-1.5 text-xs text-muted-foreground">
                검색 결과가 없습니다
              </div>
            )}
          </SidebarGroup>
        </SidebarContent>
        {authSlot && <SidebarFooter>{authSlot}</SidebarFooter>}
      </Sidebar>
      <AlertDialog
        open={pendingDeleteId !== null}
        onOpenChange={(open) => {
          if (!open) setPendingDeleteId(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>대화를 삭제할까요?</AlertDialogTitle>
            <AlertDialogDescription>
              {pendingDeleteSession
                ? `"${pendingDeleteSession.title}" 대화를 삭제합니다. 삭제한 대화는 복구할 수 없습니다.`
                : '삭제한 대화는 복구할 수 없습니다.'}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={confirmDeleteSession}>
              삭제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
