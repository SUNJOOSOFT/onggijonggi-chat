/********************************************************
 파일명 : messages.tsx
 설 명 : 메시지 목록 스크롤 영역. 대화가 없으면 Overview를, 있으면 메시지들을, 응답 대기 중이면 ThinkingMessage를 렌더링한다.
 *********************************************************/

import type { RenderedMessage } from '@/lib/chat/rendered-message';
import equal from 'fast-deep-equal';
import { memo, useEffect, useRef, useState } from 'react';

import type { CitationsState } from './citations-panel';
import { PreviewMessage, ThinkingMessage } from './message';
import { Overview } from './overview';
import { useScrollToBottom } from './use-scroll-to-bottom';

interface MessagesProps {
  chatId: string;
  isLoading: boolean;
  messages: Array<RenderedMessage>;
  citationsByMessageId: Record<string, CitationsState>;
  /** DIRECT 전용(이슈 #162, §3.2) — done이 아닌 message.id만 들어 있다. */
  terminalStatusByMessageId: Record<string, 'cancelled' | 'denied'>;
  failedMessageIds: string[];
  onResendFailedMessage: (messageId: string) => void;
  onAppendTurn: (content: string) => void;
}

/** 마지막 메시지가 user이고 아직 로딩 중이면 ThinkingMessage로 대기 상태를 보여준다. */
function PureMessages({
  chatId,
  isLoading,
  messages,
  citationsByMessageId,
  terminalStatusByMessageId,
  failedMessageIds,
  onResendFailedMessage,
  onAppendTurn,
}: MessagesProps) {
  const [messagesContainerRef, messagesEndRef] =
    useScrollToBottom<HTMLDivElement>();

  // 스크린리더가 스트리밍 중 문자 단위로 바뀌는 DOM을 그대로 읽으면 끼어들어 못 알아듣는다
  // — 응답 생성 시작·완료 두 시점에만 상태 텍스트를 갱신한다.
  const [statusMessage, setStatusMessage] = useState('');
  const prevIsLoadingRef = useRef(isLoading);

  useEffect(() => {
    if (isLoading && !prevIsLoadingRef.current) {
      setStatusMessage('답변을 생성하는 중입니다');
    } else if (!isLoading && prevIsLoadingRef.current) {
      setStatusMessage('답변이 도착했습니다');
    }
    prevIsLoadingRef.current = isLoading;
  }, [isLoading]);

  return (
    <div
      ref={messagesContainerRef}
      className="flex flex-col min-w-0 gap-6 flex-1 overflow-y-scroll pt-4"
    >
      <div
        role="status"
        aria-live="polite"
        aria-atomic="true"
        className="sr-only"
      >
        {statusMessage}
      </div>

      {messages.length === 0 && <Overview />}

      {messages.map((message, index) => (
        <PreviewMessage
          key={message.id}
          chatId={chatId}
          message={message}
          isLoading={isLoading && messages.length - 1 === index}
          isLastMessage={messages.length - 1 === index}
          citations={citationsByMessageId[message.id]}
          terminalStatus={terminalStatusByMessageId[message.id]}
          failed={
            index === messages.length - 1 &&
            failedMessageIds.includes(message.id)
          }
          onResend={() => onResendFailedMessage(message.id)}
          onAppendTurn={onAppendTurn}
          regenerateContent={
            message.role === 'assistant'
              ? messages
                  .slice(0, index)
                  .reverse()
                  .find((candidate) => candidate.role === 'user')?.content
              : undefined
          }
        />
      ))}

      {isLoading &&
        messages.length > 0 &&
        messages[messages.length - 1].role === 'user' && <ThinkingMessage />}

      <div
        ref={messagesEndRef}
        className="shrink-0 min-w-[24px] min-h-[24px]"
      />
    </div>
  );
}

export const Messages = memo(PureMessages, (prevProps, nextProps) => {
  if (prevProps.isLoading !== nextProps.isLoading) return false;
  if (prevProps.isLoading && nextProps.isLoading) return false;
  if (prevProps.messages.length !== nextProps.messages.length) return false;
  if (!equal(prevProps.messages, nextProps.messages)) return false;
  if (!equal(prevProps.citationsByMessageId, nextProps.citationsByMessageId))
    return false;
  if (
    !equal(
      prevProps.terminalStatusByMessageId,
      nextProps.terminalStatusByMessageId,
    )
  )
    return false;
  if (!equal(prevProps.failedMessageIds, nextProps.failedMessageIds))
    return false;

  return true;
});
