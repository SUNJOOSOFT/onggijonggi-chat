'use client';

/********************************************************
 파일명 : message-editor.tsx
 설 명 : message.tsx의 "메시지 수정" 모드에서 보여주는 인라인 편집 폼. 저장하면 해당 메시지 이후를
 잘라내고 수정된 내용으로 교체한 뒤 reload()로 재생성한다.
 *********************************************************/

import type { RenderedMessage } from '@/lib/chat/rendered-message';
import { Dispatch, SetStateAction, useEffect, useRef, useState } from 'react';

import { Button } from './ui/button';
import { Textarea } from './ui/textarea';

export type MessageEditorProps = {
  message: RenderedMessage;
  setMode: Dispatch<SetStateAction<'view' | 'edit'>>;
  onAppendTurn: (content: string) => void;
};

/** "Send"를 누르면 setMessages로 해당 메시지를 수정본으로 교체하고(그 뒤 메시지는 버려짐)
 * reload()로 응답을 다시 생성시킨다. "Cancel"은 편집을 버리고 보기 모드로 되돌린다. */
export function MessageEditor({
  message,
  setMode,
  onAppendTurn,
}: MessageEditorProps) {
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);

  const [draftContent, setDraftContent] = useState<string>(message.content);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (textareaRef.current) {
      adjustHeight();
      textareaRef.current.focus();
      const length = textareaRef.current.value.length;
      textareaRef.current.setSelectionRange(length, length);
    }
  }, []);

  const adjustHeight = () => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = `${textareaRef.current.scrollHeight + 2}px`;
    }
  };

  const handleInput = (event: React.ChangeEvent<HTMLTextAreaElement>) => {
    setDraftContent(event.target.value);
    adjustHeight();
  };

  return (
    <div className="flex flex-col gap-2 w-full">
      <Textarea
        ref={textareaRef}
        className="bg-transparent outline-none overflow-hidden resize-none !text-base rounded-xl w-full"
        value={draftContent}
        onChange={handleInput}
      />

      <div className="flex flex-row gap-2 justify-end">
        <Button
          variant="outline"
          className="h-fit py-2 px-3"
          onClick={() => {
            setMode('view');
          }}
        >
          Cancel
        </Button>
        <Button
          variant="default"
          className="h-fit py-2 px-3"
          disabled={isSubmitting}
          onClick={async () => {
            setIsSubmitting(true);
            onAppendTurn(draftContent);
            setMode('view');
          }}
        >
          {isSubmitting ? 'Sending...' : 'Send'}
        </Button>
      </div>
    </div>
  );
}
