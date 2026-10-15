'use client';

/********************************************************
 파일명 : collab-input.tsx (components/collab)
 설 명 : 협업방 입력창과 `@AI` 안내(이슈 #19 완료 기준 ③).

 협업방은 1:1 채팅과 달리 "보냈는데 AI가 답하지 않는" 것이 정상 동작이라, 지금 쓰는 문장이
 AI를 부르는지 아닌지를 보내기 전에 보여주는 것이 이 화면의 핵심 안내다. 판정은 어디까지나
 서버 몫이므로(#17) 여기 표시는 도움말이지 보장이 아니다 — mention.ts 주석 참고.

 1:1의 multimodal-input.tsx와 따로 두는 이유는 두 가지다. 그쪽은 localStorage 초안 보관을 함께
 지고, 이쪽은 보내지 못했을 때(끊김) 그 자리에서 알려야 한다. 전송 계약 자체는 이제 양쪽 다
 "완성된 발화 하나"라 합칠 여지가 생겼지만 이번 범위는 아니다.
 *********************************************************/

import { useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';

import { AttachButton, PendingAttachments } from '@/components/attachments';
import { ArrowUpIcon, StopIcon } from '@/components/icons';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { TYPING_RESEND_MS } from '@/lib/chat/typing';
import { useAttachments } from '@/lib/chat/use-attachments';
import { AI_MENTION, mentionsAi } from '@/lib/collab/mention';

export function CollabInput({
  canSend,
  onSend,
  onCancel,
  typingLabel,
  onTyping,
}: {
  /** 연결이 열려 있는지. 닫혀 있으면 보내기를 막는다. */
  canSend: boolean;
  /** 실제 전송. 끊겨 있어 못 보냈으면 false를 돌려준다(큐잉하지 않는다). attachmentIds는 다 올라간 첨부다. */
  onSend: (content: string, attachmentIds: string[]) => boolean;
  /** 내가 부른 AI 답변이 흐르는 동안만 주어진다. 있으면 보내기 대신 중지를 그린다. */
  onCancel?: () => void;
  /** 다른 사람이 입력 중이면 그 문구(lib/chat/typing.ts의 typingLabel). 안내 줄 자리에 대신 보인다. */
  typingLabel?: string | null;
  /** 입력창에 글자가 있는지 바뀔 때마다(키를 누를 때마다) 부른다. 첨부가 올라가는 동안에도 부른다.
   * 얼마나 자주 알릴지는 받는 쪽이 정한다. */
  onTyping?: (active: boolean) => void;
}) {
  const [input, setInput] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const attachments = useAttachments();
  const callsAi = mentionsAi(input);
  // 첨부만 있어도 보낼 수 있다(AI는 부르지 않는다 — 나중에 @AI로 물으면 문맥으로 읽는다).
  // 올라가는 중인 첨부가 있으면 기다린다 — 지금 보내면 그 파일이 빠진다.
  const hasContent = input.trim() !== '' || attachments.uploaded.length > 0;
  const canSubmit = hasContent && !attachments.uploading;

  // 첨부가 올라가는 동안은 키를 누르지 않아도 입력 중으로 알린다. 받는 쪽에는 파일 이름도, 올리는
  // 중이라는 것도 드러내지 않는다 — 보내지 않은 첨부는 아직 초안이다.
  const onTypingRef = useRef(onTyping);
  onTypingRef.current = onTyping;
  const uploading = attachments.uploading;
  useEffect(() => {
    if (!uploading) return;
    const notify = () => onTypingRef.current?.(true);
    notify();
    const timer = setInterval(notify, TYPING_RESEND_MS);
    return () => clearInterval(timer);
  }, [uploading]);

  const submit = () => {
    // 공백뿐인 입력만 막고, 정상 content의 앞뒤 공백은 지우지 않고 원문 그대로 보낸다 —
    // 방에는 원문을 그대로 방송해야(#17 프로토콜) 내가 보낸 것과 남이 보는 것이 정확히
    // 같아진다. trim()은 여기 빈 값 판정에만 쓰고 보내는 값에는 적용하지 않는다.
    if (!canSubmit) return;

    if (
      !onSend(
        input,
        attachments.uploaded.map((attachment) => attachment.id),
      )
    ) {
      // 재연결될 때까지 쌓아 두지 않는다 — 뒤늦게 나가면 대화 순서가 어긋난다(ws-connection.ts).
      toast.error('연결이 끊겨 보내지 못했습니다. 다시 연결되면 보내주세요.');
      return;
    }
    setInput('');
    attachments.clear();
  };

  /** 입력창 맨 앞에 멘션을 넣어준다 — 표기를 외우지 않아도 AI를 부를 수 있게 하는 안내의 일부다.
   * 뒤 공백을 지우지 않는 것은 곧바로 이어 칠 때 `@AI본문`으로 붙어버리기 때문이다. */
  const prependMention = () => {
    setInput((current) =>
      mentionsAi(current) ? current : `${AI_MENTION} ${current}`,
    );
    textareaRef.current?.focus();
  };

  return (
    <div className="flex w-full flex-col gap-2">
      <div className="flex items-center justify-between gap-2 text-xs">
        {/* 다른 사람의 입력 중 표시는 따로 줄을 두지 않고 이 안내 자리를 빌린다. 내가 AI를 부르는 중이면
            그 안내를 지우지 않고 옆에 붙인다 — 보내기 전에 알아야 하는 것이라 줄이지도 않고, 폭이 모자라면
            입력 중 문구가 말줄임된다. */}
        <span className="flex min-w-0 items-center gap-1.5 text-muted-foreground">
          {callsAi && (
            <span className="shrink-0 text-foreground">
              AI가 이 메시지에 답합니다.
            </span>
          )}
          {typingLabel ? (
            <span
              aria-live="polite"
              className="flex min-w-0 items-center gap-1.5"
            >
              {callsAi && <span aria-hidden="true">·</span>}
              <TypingDots />
              <span className="truncate">{typingLabel}</span>
            </span>
          ) : (
            !callsAi && (
              <span>
                참여자에게만 보입니다. {AI_MENTION} 를 붙이면 AI가 답합니다.
              </span>
            )
          )}
        </span>
        <Button
          type="button"
          variant="ghost"
          className="h-7 px-2 text-xs"
          onClick={prependMention}
          disabled={callsAi}
        >
          {AI_MENTION} 부르기
        </Button>
      </div>

      <PendingAttachments
        items={attachments.items}
        onRemove={attachments.remove}
      />
      {/* 보낸 첨부는 방 문서 패널에서 다른 참여자도 원본을 내려받는다. 보내기 전에 알린다. */}
      {attachments.items.length > 0 && (
        <p className="px-1 text-xs text-muted-foreground">
          보내면 이 방 참여자가 원본을 내려받을 수 있습니다.
        </p>
      )}

      <div className="relative w-full">
        <Textarea
          ref={textareaRef}
          aria-label="협업방 메시지 입력"
          placeholder={
            canSend ? '메시지를 입력하세요' : '연결되면 보낼 수 있습니다'
          }
          value={input}
          onChange={(event) => {
            setInput(event.target.value);
            onTyping?.(event.target.value.trim() !== '' || uploading);
          }}
          className="max-h-[40dvh] min-h-[80px] resize-none overflow-auto rounded-2xl bg-muted pb-10 !text-base dark:border-zinc-700"
          rows={2}
          disabled={!canSend}
          onKeyDown={(event) => {
            // 한글 IME 조합 중의 Enter는 글자를 확정하는 키다 — 여기서 가로채면 마지막 글자를
            // 잃는다(이슈 #66에서 1:1 입력창이 겪은 것과 같은 함정).
            if (
              event.key === 'Enter' &&
              !event.shiftKey &&
              !event.nativeEvent.isComposing
            ) {
              event.preventDefault();
              submit();
            }
          }}
        />

        <div className="absolute bottom-0 left-0 flex w-fit flex-row justify-start p-2">
          <AttachButton onPick={attachments.add} disabled={!canSend} />
        </div>

        <div className="absolute bottom-0 right-0 flex w-fit flex-row justify-end p-2">
          {/* 답변이 흐르는 동안에는 보내기 자리를 중지가 대신한다. 아이콘·모양은 1:1 입력창의
              StopButton/SendButton과 같은 값을 쓴다 — 두 화면을 오갈 때 같은 자리에 같은 것이
              있어야 한다. 그쪽 컴포넌트를 그대로 가져오지 않은 것은 setMessages·submitForm 등
              1:1 전용 props와 memo 비교자(이슈 #94)에 묶여 있어서다.

              자리를 나란히 두지 않은 것은, 답변 도중에 다시 보내면 턴이 대기열에 쌓여 중지가
              무슨 턴을 멈추는지 흐려져서다. */}
          {onCancel ? (
            <Button
              type="button"
              className="h-fit rounded-full border p-1.5 dark:border-zinc-600"
              aria-label="AI 응답 생성 중지"
              onClick={onCancel}
              disabled={!canSend}
            >
              <StopIcon size={14} />
            </Button>
          ) : (
            <Button
              type="button"
              className="h-fit rounded-full border p-1.5 dark:border-zinc-600"
              aria-label="메시지 보내기"
              onClick={submit}
              disabled={!canSend || !canSubmit}
            >
              <ArrowUpIcon size={14} />
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}

/** 입력 중 표시 앞의 점 세 개. 움직임을 줄이도록 설정한 사용자에게는 멈춰 보인다. */
function TypingDots() {
  return (
    <span aria-hidden="true" className="flex shrink-0 items-end gap-0.5">
      {['0ms', '150ms', '300ms'].map((delay) => (
        <span
          key={delay}
          className="size-1 animate-bounce rounded-full bg-current motion-reduce:animate-none"
          style={{ animationDelay: delay }}
        />
      ))}
    </span>
  );
}
