'use client';

/********************************************************
 파일명 : attachments.tsx
 설 명 : 첨부 UI 조각. 입력창의 클립 버튼·올려 둔 파일 칩과, 말풍선에 붙는 파일 이름 칩.
 1:1(multimodal-input)과 협업방(collab-input·collab-room)이 같은 모양을 쓴다.
 *********************************************************/

import { useRef } from 'react';

import { ACCEPTED_FILE_TYPES } from '@/lib/api/attachments';
import type { PendingAttachment } from '@/lib/chat/use-attachments';
import type { MessageAttachment } from '@/lib/transport/frames';
import { cn } from '@/lib/utils';
import { CrossSmallIcon, FileIcon, LoaderIcon, PaperclipIcon } from './icons';
import { Button } from './ui/button';

/** 파일을 고르는 클립 버튼. 고른 파일은 onPick으로 넘기고 바로 비워 같은 파일을 다시 고를 수 있게 한다. */
export function AttachButton({
  onPick,
  disabled,
}: {
  onPick: (files: File[]) => void;
  disabled?: boolean;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  return (
    <>
      <input
        ref={inputRef}
        type="file"
        multiple
        accept={ACCEPTED_FILE_TYPES}
        className="hidden"
        tabIndex={-1}
        onChange={(event) => {
          const files = Array.from(event.target.files ?? []);
          event.target.value = '';
          if (files.length > 0) onPick(files);
        }}
      />
      <Button
        type="button"
        variant="ghost"
        className="h-fit rounded-md p-[7px] hover:bg-zinc-200 dark:border-zinc-700 hover:dark:bg-zinc-900"
        aria-label="파일 첨부"
        title="파일 첨부 (txt·md·csv·pdf·docx)"
        disabled={disabled}
        onClick={(event) => {
          event.preventDefault();
          inputRef.current?.click();
        }}
      >
        <PaperclipIcon size={14} />
      </Button>
    </>
  );
}

/** 입력창 위에 올려 둔 파일들. 올라가는 중이면 도는 아이콘, 끝나면 파일 아이콘을 보인다. */
export function PendingAttachments({
  items,
  onRemove,
}: {
  items: PendingAttachment[];
  onRemove: (key: string) => void;
}) {
  if (items.length === 0) return null;
  return (
    <ul className="flex flex-wrap gap-2" aria-label="첨부할 파일">
      {items.map((item) => (
        <li
          key={item.key}
          className="flex max-w-60 items-center gap-1 rounded-md border bg-background py-1 pl-2 pr-1 text-xs"
        >
          <span
            className={cn('shrink-0', {
              'animate-spin': item.uploaded === null,
            })}
          >
            {item.uploaded === null ? (
              <LoaderIcon size={12} />
            ) : (
              <FileIcon size={12} />
            )}
          </span>
          <span className="truncate" title={item.fileName}>
            {item.fileName}
          </span>
          <button
            type="button"
            className="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-muted"
            aria-label={`${item.fileName} 빼기`}
            onClick={() => onRemove(item.key)}
          >
            <CrossSmallIcon size={12} />
          </button>
        </li>
      ))}
    </ul>
  );
}

/** 말풍선에 붙는 파일 이름들. 내려받기는 없다 — 서버가 원본을 보관하지 않는다. */
export function MessageAttachments({
  attachments,
  className,
}: {
  attachments: MessageAttachment[];
  className?: string;
}) {
  if (attachments.length === 0) return null;
  return (
    <ul className={cn('flex flex-wrap gap-2', className)} aria-label="첨부 파일">
      {attachments.map((attachment) => (
        <li
          key={attachment.id}
          className="flex max-w-60 items-center gap-1 rounded-md border bg-background px-2 py-1 text-xs text-foreground"
        >
          <span className="shrink-0">
            <FileIcon size={12} />
          </span>
          <span className="truncate" title={attachment.fileName}>
            {attachment.fileName}
          </span>
        </li>
      ))}
    </ul>
  );
}
