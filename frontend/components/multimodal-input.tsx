'use client';

/********************************************************
 파일명 : multimodal-input.tsx
 설 명 : 채팅 입력창(Textarea)과 전송/중지 버튼. 입력 중 텍스트를 localStorage에도 보관해 새로고침해도 잃지 않게 한다.

 입력 중인 텍스트는 이 컴포넌트가 직접 들고, 바깥에는 onSend(content)로 완성된 발화만 넘긴다.
 예전에는 useChat의 input·setInput·handleSubmit을 그대로 받아 전송이 AI SDK에 묶여 있었다 —
 협업방이 이 입력창을 재사용하지 못하고 collab-input.tsx를 따로 둔 이유가 그 결합이었다.
 *********************************************************/

import cx from 'classnames';
import type React from 'react';
import { memo, useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';
import { useLocalStorage, useWindowSize } from 'usehooks-ts';

import { ArrowUpIcon, StopIcon } from './icons';
import { Button } from './ui/button';
import { Textarea } from './ui/textarea';

/** 입력 내용에 맞춰 textarea 높이를 늘린다(스크롤 없이 전체 내용이 보이도록). */
const adjustHeight = (ref: React.RefObject<HTMLTextAreaElement>) => {
  if (ref.current) {
    ref.current.style.height = 'auto';
    ref.current.style.height = `${ref.current.scrollHeight + 2}px`;
  }
};

/** 전송 후 textarea 높이를 기본값으로 되돌린다. */
const resetHeight = (ref: React.RefObject<HTMLTextAreaElement>) => {
  if (ref.current) {
    ref.current.style.height = 'auto';
    ref.current.style.height = '98px';
  }
};

/** Enter(Shift 없이)로 submitForm을 트리거한다. 스트리밍 중 Enter는 전송을 막고 토스트만 띄운다.
 * 전송 시 URL을 `/chat/{chatId}`로 바꿔(history.replaceState) 새로고침해도 같은 세션을 유지한다. */
function PureMultimodalInput({
  chatId,
  isLoading,
  stop,
  onSend,
  className,
}: {
  chatId: string;
  isLoading: boolean;
  stop: () => void;
  /** 완성된 발화 하나. 어떻게 보낼지는 바깥이 정한다. */
  onSend: (content: string) => void;
  className?: string;
}) {
  const [input, setInput] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const { width } = useWindowSize();

  useEffect(() => {
    if (textareaRef.current) {
      adjustHeight(textareaRef);
    }
  }, []);

  const [localStorageInput, setLocalStorageInput] = useLocalStorage(
    `input-${chatId}`,
    '',
  );

  useEffect(() => {
    if (textareaRef.current) {
      const domValue = textareaRef.current.value;
      // 하이드레이션 처리를 위해 localStorage보다 DOM 값을 우선한다.
      const finalValue = domValue || localStorageInput || '';
      setInput(finalValue);
      adjustHeight(textareaRef);
    }
    // setInput은 useState 세터라 항상 같은 참조다 — 의존성에 넣을 필요가 없다.
  }, [localStorageInput]);

  useEffect(() => {
    setLocalStorageInput(input);
  }, [input, setLocalStorageInput]);

  const handleInput = (event: React.ChangeEvent<HTMLTextAreaElement>) => {
    setInput(event.target.value);
    adjustHeight(textareaRef);
  };

  const submitForm = useCallback(() => {
    // 빈 발화는 보내지 않는다. 버튼은 disabled라 여기 오지 않지만 Enter는 막는 곳이 없다.
    if (input.length === 0) return;
    window.history.replaceState({}, '', `/chat/${chatId}`);

    onSend(input);

    setInput('');
    setLocalStorageInput('');
    resetHeight(textareaRef);

    if (width && width > 768) {
      textareaRef.current?.focus();
    }
  }, [input, onSend, setLocalStorageInput, width, chatId]);

  return (
    <div className="relative w-full flex flex-col gap-4">
      <Textarea
        ref={textareaRef}
        aria-label="메시지 입력"
        placeholder="Send a message..."
        value={input}
        onChange={handleInput}
        className={cx(
          'min-h-[24px] max-h-[calc(75dvh)] overflow-hidden resize-none rounded-2xl !text-base bg-muted pb-10 dark:border-zinc-700',
          className,
        )}
        rows={2}
        autoFocus
        onKeyDown={(event) => {
          if (
            event.key === 'Enter' &&
            !event.shiftKey &&
            !event.nativeEvent.isComposing
          ) {
            event.preventDefault();

            if (isLoading) {
              toast.error('Please wait for the model to finish its response!');
            } else {
              submitForm();
            }
          }
        }}
      />

      <div className="absolute bottom-0 right-0 p-2 w-fit flex flex-row justify-end">
        {isLoading ? (
          <StopButton stop={stop} />
        ) : (
          <SendButton input={input} submitForm={submitForm} />
        )}
      </div>
    </div>
  );
}

export const MultimodalInput = memo(
  PureMultimodalInput,
  (prevProps, nextProps) => {
    // 입력 텍스트는 이제 내부 state라 여기서 비교하지 않는다 — state가 바뀌면 memo와 무관하게
    // 다시 그려진다.
    if (prevProps.isLoading !== nextProps.isLoading) return false;
    if (prevProps.chatId !== nextProps.chatId) return false;
    if (prevProps.stop !== nextProps.stop) return false;
    // 전송에 쓰이는 값(모델 등)은 chat.tsx가 ref로 읽으므로 낡은 클로저를 들고 있어도 정확하다.
    // 여기 비교는 그 위의 안전망이다 — 바깥 사정으로 onSend가 매 렌더 새로 만들어지는 때가
    // 와도 화면이 낡은 채로 남지는 않게 한다(이슈 #94).
    if (prevProps.onSend !== nextProps.onSend) return false;

    return true;
  },
);

function PureStopButton({
  stop,
}: {
  stop: () => void;
}) {
  return (
    <Button
      className="rounded-full p-1.5 h-fit border dark:border-zinc-600"
      aria-label="응답 생성 중지"
      onClick={(event) => {
        event.preventDefault();
        stop();
      }}
    >
      <StopIcon size={14} />
    </Button>
  );
}

const StopButton = memo(PureStopButton);

function PureSendButton({
  submitForm,
  input,
}: {
  submitForm: () => void;
  input: string;
}) {
  return (
    <Button
      className="rounded-full p-1.5 h-fit border dark:border-zinc-600"
      aria-label="메시지 보내기"
      onClick={(event) => {
        event.preventDefault();
        submitForm();
      }}
      disabled={input.length === 0}
    >
      <ArrowUpIcon size={14} />
    </Button>
  );
}

const SendButton = memo(PureSendButton, (prevProps, nextProps) => {
  if (prevProps.input !== nextProps.input) return false;
  // submitForm은 handleSubmit에 묶여 있다 — 위와 같은 이유로 함께 본다(이슈 #94).
  if (prevProps.submitForm !== nextProps.submitForm) return false;
  return true;
});
