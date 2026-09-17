'use client';

/********************************************************
 파일명 : message.tsx
 설 명 : 메시지 한 건을 렌더링한다(보기/수정 모드 전환, 근거 인용 패널, 액션 버튼 포함).
 스트리밍 대기 중 보여주는 ThinkingMessage도 이 파일에서 함께 관리한다.
 *********************************************************/

import type { RenderedMessage } from '@/lib/chat/rendered-message';
import cx from 'classnames';
import equal from 'fast-deep-equal';
import { AnimatePresence, motion } from 'framer-motion';
import { memo, useState } from 'react';

import { cn } from '@/lib/utils';
import { CitationsPanel, type CitationsState } from './citations-panel';
import { PencilEditIcon, SparklesIcon } from './icons';
import { Markdown } from './markdown';
import { MessageActions } from './message-actions';
import { MessageEditor } from './message-editor';
import { Button } from './ui/button';
import { Tooltip, TooltipContent, TooltipTrigger } from './ui/tooltip';

/** user/assistant 메시지를 아바타·본문·근거 인용 패널·액션 버튼과 함께 렌더링한다.
 * mode가 'edit'이면 본문 대신 MessageEditor를 보여준다. */
const PurePreviewMessage = ({
  chatId,
  message,
  isLoading,
  isLastMessage,
  citations,
  terminalStatus,
  failed,
  onResend,
  onAppendTurn,
  regenerateContent,
}: {
  chatId: string;
  message: RenderedMessage;
  isLoading: boolean;
  isLastMessage: boolean;
  citations?: CitationsState;
  /** DIRECT 전용(이슈 #162, §3.2) — DENIED는 실패 안내, CANCELLED는 본문 유무에 따라
   * "중단됨" 배지 또는 빈 말풍선 대신 중단 안내로 갈린다. */
  terminalStatus?: 'cancelled' | 'denied';
  failed?: boolean;
  onResend?: () => void;
  onAppendTurn: (content: string) => void;
  regenerateContent?: string;
}) => {
  const [mode, setMode] = useState<'view' | 'edit'>('view');

  return (
    <AnimatePresence>
      <motion.div
        className="w-full mx-auto max-w-3xl px-4 group/message"
        initial={{ y: 5, opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        data-role={message.role}
      >
        <div
          className={cn(
            'flex gap-4 w-full group-data-[role=user]/message:ml-auto group-data-[role=user]/message:max-w-2xl',
            {
              'w-full': mode === 'edit',
              'group-data-[role=user]/message:w-fit': mode !== 'edit',
            },
          )}
        >
          {message.role === 'assistant' && (
            <div className="size-8 flex items-center rounded-full justify-center ring-1 shrink-0 ring-border bg-background">
              <div className="translate-y-px">
                <SparklesIcon size={14} />
              </div>
            </div>
          )}

          <div className="flex flex-col gap-2 w-full">
            {message.content && mode === 'view' && (
              <div className="flex flex-row gap-2 items-start">
                {message.role === 'user' && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button
                        variant="ghost"
                        className="px-2 h-fit rounded-full text-muted-foreground opacity-0 group-hover/message:opacity-100"
                        aria-label="메시지 수정"
                        onClick={() => {
                          setMode('edit');
                        }}
                      >
                        <PencilEditIcon />
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent>Edit message</TooltipContent>
                  </Tooltip>
                )}

                <div
                  className={cn('flex flex-col gap-4', {
                    'bg-primary text-primary-foreground px-3 py-2 rounded-xl':
                      message.role === 'user',
                  })}
                >
                  <Markdown>{message.content}</Markdown>
                </div>
              </div>
            )}

            {/* CANCELLED — 본문이 있으면 위에서 이미 그린 말풍선에 "중단됨"만 덧붙이고, 본문이
             * 없으면(위 블록은 message.content를 요구해 아무것도 안 그린다) 빈 말풍선 대신 이
             * 안내가 유일한 표시가 된다(이슈 #162, §3.2). */}
            {message.role === 'assistant' && terminalStatus === 'cancelled' && (
              <span className="text-sm text-muted-foreground">
                응답이 중단되었습니다.
              </span>
            )}

            {/* DENIED는 AGENT 행 자체가 본문 없이 끝나 항상 이 안내로만 보인다(이슈 #162, §3.2). */}
            {message.role === 'assistant' && terminalStatus === 'denied' && (
              <span className="text-sm text-destructive">
                요청이 많아 이번 응답은 처리되지 않았습니다. 다시 시도해 주세요.
              </span>
            )}

            {message.role === 'user' && citations && (
              <CitationsPanel state={citations} />
            )}

            {/* 재전송 중엔 숨긴다 — 다시 실패하면 onError가 failed를 재설정한다. */}
            {message.role === 'user' && failed && !isLoading && (
              <div className="flex flex-row gap-2 items-center text-sm text-destructive">
                <span>로그인이 풀려 전송되지 않았어요.</span>
                <Button
                  variant="outline"
                  className="h-fit py-1 px-2"
                  onClick={() => onResend?.()}
                >
                  재전송
                </Button>
              </div>
            )}

            {message.content && mode === 'edit' && (
              <div className="flex flex-row gap-2 items-start">
                <div className="size-8" />

                <MessageEditor
                  key={message.id}
                  message={message}
                  setMode={setMode}
                  onAppendTurn={onAppendTurn}
                />
              </div>
            )}

            <MessageActions
              key={`action-${message.id}`}
              chatId={chatId}
              message={message}
              isLoading={isLoading}
              isLastMessage={isLastMessage}
              onRegenerate={() => {
                if (regenerateContent !== undefined) {
                  onAppendTurn(regenerateContent);
                }
              }}
            />
          </div>
        </div>
      </motion.div>
    </AnimatePresence>
  );
};

export const PreviewMessage = memo(
  PurePreviewMessage,
  (prevProps, nextProps) => {
    if (prevProps.isLoading !== nextProps.isLoading) return false;
    if (prevProps.isLastMessage !== nextProps.isLastMessage) return false;
    if (prevProps.message.content !== nextProps.message.content) return false;
    if (!equal(prevProps.citations, nextProps.citations)) return false;
    if (prevProps.terminalStatus !== nextProps.terminalStatus) return false;
    if (prevProps.failed !== nextProps.failed) return false;

    return true;
  },
);

export const ThinkingMessage = () => {
  const role = 'assistant';

  return (
    <motion.div
      className="w-full mx-auto max-w-3xl px-4 group/message "
      initial={{ y: 5, opacity: 0 }}
      animate={{ y: 0, opacity: 1, transition: { delay: 1 } }}
      data-role={role}
    >
      <div
        className={cx(
          'flex gap-4 group-data-[role=user]/message:px-3 w-full group-data-[role=user]/message:w-fit group-data-[role=user]/message:ml-auto group-data-[role=user]/message:max-w-2xl group-data-[role=user]/message:py-2 rounded-xl',
          {
            'group-data-[role=user]/message:bg-muted': true,
          },
        )}
      >
        <div className="size-8 flex items-center rounded-full justify-center ring-1 shrink-0 ring-border">
          <SparklesIcon size={14} />
        </div>

        <div className="flex flex-col gap-2 w-full">
          <div className="flex flex-col gap-4 text-muted-foreground">
            Thinking...
          </div>
        </div>
      </div>
    </motion.div>
  );
};
