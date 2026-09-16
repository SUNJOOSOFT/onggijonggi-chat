'use client';

/********************************************************
 파일명 : collab-input.tsx (components/collab)
 설 명 : 협업방 입력창과 `@AI` 안내(이슈 #19 완료 기준 ③).

 협업방은 1:1 채팅과 달리 "보냈는데 AI가 답하지 않는" 것이 정상 동작이라, 지금 쓰는 문장이
 AI를 부르는지 아닌지를 보내기 전에 보여주는 것이 이 화면의 핵심 안내다. 판정은 어디까지나
 서버 몫이므로(#17) 여기 표시는 도움말이지 보장이 아니다 — mention.ts 주석 참고.

 1:1의 multimodal-input.tsx를 재사용하지 않은 이유는 그쪽이 AI SDK의 useChat 핸들러(append·
 handleSubmit·stop)와 localStorage 초안 보관까지 묶고 있어서다. 협업방은 전송이 WS 한 줄이고,
 보내지 못했을 때(끊김) 그 자리에서 알려야 한다는 점이 다르다.
 *********************************************************/

import { useRef, useState } from 'react';
import { toast } from 'sonner';

import { ArrowUpIcon, StopIcon } from '@/components/icons';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { AI_MENTION, mentionsAi } from '@/lib/collab/mention';

export function CollabInput({
  canSend,
  onSend,
  onCancel,
}: {
  /** 연결이 열려 있는지. 닫혀 있으면 보내기를 막는다. */
  canSend: boolean;
  /** 실제 전송. 끊겨 있어 못 보냈으면 false를 돌려준다(큐잉하지 않는다). */
  onSend: (content: string) => boolean;
  /** 내가 부른 AI 답변이 흐르는 동안만 주어진다. 있으면 보내기 대신 중지를 그린다. */
  onCancel?: () => void;
}) {
  const [input, setInput] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const callsAi = mentionsAi(input);

  const submit = () => {
    // 공백뿐인 입력만 막고, 정상 content의 앞뒤 공백은 지우지 않고 원문 그대로 보낸다 —
    // 방에는 원문을 그대로 방송해야(#17 프로토콜) 내가 보낸 것과 남이 보는 것이 정확히
    // 같아진다. trim()은 여기 빈 값 판정에만 쓰고 보내는 값에는 적용하지 않는다.
    if (input.trim() === '') return;

    if (!onSend(input)) {
      // 재연결될 때까지 쌓아 두지 않는다 — 뒤늦게 나가면 대화 순서가 어긋난다(ws-connection.ts).
      toast.error('연결이 끊겨 보내지 못했습니다. 다시 연결되면 보내주세요.');
      return;
    }
    setInput('');
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
        <span className={callsAi ? 'text-foreground' : 'text-muted-foreground'}>
          {callsAi
            ? 'AI가 이 메시지에 답합니다.'
            : `참여자에게만 보입니다. ${AI_MENTION} 를 붙이면 AI가 답합니다.`}
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

      <div className="relative w-full">
        <Textarea
          ref={textareaRef}
          aria-label="협업방 메시지 입력"
          placeholder={
            canSend ? '메시지를 입력하세요' : '연결되면 보낼 수 있습니다'
          }
          value={input}
          onChange={(event) => setInput(event.target.value)}
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
              disabled={!canSend || input.trim() === ''}
            >
              <ArrowUpIcon size={14} />
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
