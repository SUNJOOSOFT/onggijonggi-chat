'use client';

/********************************************************
 파일명 : chat-header.tsx
 설 명 : 채팅 화면 상단 헤더. 사이드바 토글·(좁은 화면/접힘 상태에서만 보이는) 새 대화 버튼·모델
 선택기·라이트/다크 토글을 한 줄에 배치한다.
 *********************************************************/

import { useRouter } from 'next/navigation';
import { memo } from 'react';
import { useWindowSize } from 'usehooks-ts';

import { ModelSelector } from '@/components/model-selector';
import { SidebarToggle } from '@/components/sidebar-toggle';
import { ThemeToggle } from '@/components/theme-toggle';
import { ThreadDocumentsButton } from '@/components/thread-documents';
import { Button } from '@/components/ui/button';
import { PlusIcon } from './icons';
import { useSidebar } from './ui/sidebar';
import { Tooltip, TooltipContent, TooltipTrigger } from './ui/tooltip';

/** 사이드바가 닫혀 있거나 좁은 화면(<768px)일 때만 "New Chat" 버튼을 노출한다
 * (열려 있으면 그 안의 새 대화 버튼과 중복이라 숨김). */
function PureChatHeader({
  availableModels,
  selectedModelId,
  onModelChange,
  showDocuments = false,
}: {
  availableModels: string[];
  selectedModelId: string;
  onModelChange: (modelId: string) => void;
  /** 좁은 화면의 방 문서 버튼. ThreadDocumentsProvider 안에서만 켠다(넓은 화면은 오른쪽 패널). */
  showDocuments?: boolean;
}) {
  const router = useRouter();
  const { open } = useSidebar();

  const { width: windowWidth } = useWindowSize();

  return (
    <header className="flex sticky top-0 bg-background py-1.5 items-center px-2 md:px-2 gap-2">
      <SidebarToggle />

      {(!open || windowWidth < 768) && (
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              variant="outline"
              className="order-2 md:order-1 md:px-2 px-2 md:h-fit ml-auto md:ml-0"
              onClick={() => {
                router.push('/');
                router.refresh();
              }}
            >
              <PlusIcon />
              <span className="sr-only sm:not-sr-only md:sr-only">
                New Chat
              </span>
            </Button>
          </TooltipTrigger>
          <TooltipContent>New Chat</TooltipContent>
        </Tooltip>
      )}

      <ModelSelector
        availableModels={availableModels}
        selectedModelId={selectedModelId}
        onModelChange={onModelChange}
        className="order-1 md:order-2"
      />

      <ThemeToggle />

      {showDocuments && <ThreadDocumentsButton className="order-3 md:hidden" />}
    </header>
  );
}

export const ChatHeader = memo(PureChatHeader);
