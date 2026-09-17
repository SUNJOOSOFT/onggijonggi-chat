/********************************************************
 파일명 : message-actions.tsx
 설 명 : assistant 메시지 하단 액션 버튼("답변 복사"·재생성). 스트리밍 중이거나 user 메시지면
 아무것도 렌더링하지 않는다.
 재생성 버튼은 마지막 assistant 메시지에만 노출한다 — reload()는 항상 최신 응답을
 재생성하므로, 이전 메시지에 붙이면 그 메시지가 다시 만들어지는 것으로 오해하게 된다.
 *********************************************************/

import type { RenderedMessage } from '@/lib/chat/rendered-message';
import { memo } from 'react';
import { toast } from 'sonner';
import { useCopyToClipboard } from 'usehooks-ts';

import { CopyIcon, UndoIcon } from './icons';
import { Button } from './ui/button';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from './ui/tooltip';

export function PureMessageActions({
  message,
  isLoading,
  isLastMessage,
  onRegenerate,
}: {
  chatId: string;
  message: RenderedMessage;
  isLoading: boolean;
  isLastMessage: boolean;
  onRegenerate: () => void;
}) {
  const [_, copyToClipboard] = useCopyToClipboard();

  if (isLoading) return null;
  if (message.role === 'user') return null;

  return (
    <TooltipProvider delayDuration={0}>
      <div className="flex flex-row gap-2">
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              className="py-1 px-2 h-fit text-muted-foreground"
              variant="outline"
              aria-label="답변 복사"
              onClick={async () => {
                await copyToClipboard(message.content);
                toast.success('Copied to clipboard!');
              }}
            >
              <CopyIcon />
            </Button>
          </TooltipTrigger>
          <TooltipContent>Copy</TooltipContent>
        </Tooltip>

        {isLastMessage && (
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                className="py-1 px-2 h-fit text-muted-foreground"
                variant="outline"
                aria-label="답변 다시 생성"
                onClick={onRegenerate}
              >
                <UndoIcon />
              </Button>
            </TooltipTrigger>
            <TooltipContent>다시 생성</TooltipContent>
          </Tooltip>
        )}
      </div>
    </TooltipProvider>
  );
}

export const MessageActions = memo(
  PureMessageActions,
  (prevProps, nextProps) => {
    if (prevProps.isLoading !== nextProps.isLoading) return false;
    if (prevProps.isLastMessage !== nextProps.isLastMessage) return false;

    return true;
  },
);
