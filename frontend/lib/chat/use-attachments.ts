/********************************************************
 파일명 : use-attachments.ts (lib/chat)
 설 명 : 입력창에 올려 둔 첨부 목록. 파일을 고르는 즉시 업로드하고, 보낼 때는 다 올라간 것의
 id만 발화에 싣는다. 1:1(multimodal-input)과 협업방(collab-input)이 같이 쓴다.

 보내기 전에 올리는 이유는 발화가 WS 프레임 하나라서다 — 파일 본문을 프레임에 실을 수 없어
 REST로 먼저 올리고 id만 넘긴다(lib/api/attachments.ts).
 *********************************************************/

import { useCallback, useState } from 'react';
import { toast } from 'sonner';

import { AttachmentUploadError, uploadAttachment } from '@/lib/api/attachments';
import type { MessageAttachment } from '@/lib/transport/frames';
import { generateUUID } from '@/lib/utils';

/** 발화 하나에 실을 수 있는 첨부 수. 서버(MsgFileService.MAX_FILES_PER_MESSAGE)와 같은 값이다. */
export const MAX_ATTACHMENTS = 5;

/** 입력창에 올라 있는 첨부 하나. key는 업로드가 끝나기 전에도 칩을 가리키는 로컬 값이다. */
export interface PendingAttachment {
  key: string;
  fileName: string;
  /** 업로드가 끝나면 서버 id가 채워진다. */
  uploaded: MessageAttachment | null;
}

export function useAttachments() {
  const [items, setItems] = useState<PendingAttachment[]>([]);

  const add = useCallback(
    (files: File[]) => {
      const room = MAX_ATTACHMENTS - items.length;
      if (files.length > room) {
        toast.error(`첨부는 한 번에 ${MAX_ATTACHMENTS}개까지 보낼 수 있어요.`);
      }
      for (const file of files.slice(0, Math.max(room, 0))) {
        const key = generateUUID();
        setItems((current) => [
          ...current,
          { key, fileName: file.name, uploaded: null },
        ]);
        uploadAttachment(file)
          .then((uploaded) =>
            setItems((current) =>
              current.map((item) =>
                item.key === key ? { ...item, uploaded } : item,
              ),
            ),
          )
          .catch((error: unknown) => {
            // 칩을 지우고 이유를 알린다 — 올라가지 않은 파일을 보낼 수 있는 것처럼 두지 않는다.
            setItems((current) => current.filter((item) => item.key !== key));
            toast.error(
              error instanceof AttachmentUploadError
                ? `${file.name}: ${error.message}`
                : `${file.name}을(를) 올리지 못했어요. 다시 시도해 주세요.`,
            );
          });
      }
    },
    [items.length],
  );

  const remove = useCallback((key: string) => {
    setItems((current) => current.filter((item) => item.key !== key));
  }, []);

  const clear = useCallback(() => setItems([]), []);

  return {
    items,
    add,
    remove,
    clear,
    /** 하나라도 올라가는 중이면 보내기를 막는다 — 그대로 보내면 그 파일이 빠진다. */
    uploading: items.some((item) => item.uploaded === null),
    uploaded: items.flatMap((item) => (item.uploaded ? [item.uploaded] : [])),
  };
}
