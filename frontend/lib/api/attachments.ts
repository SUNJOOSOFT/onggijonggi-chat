/********************************************************
 파일명 : attachments.ts (lib/api)
 설 명 : 채팅 첨부 업로드. 파일을 먼저 올려 id를 받고, 발화(chat.message)의 attachmentIds에
 그 id를 실어 보낸다. 서버가 텍스트를 뽑아 두었다가 AI 문맥에 넣는다 — 원본은 보관하지 않는다.
 *********************************************************/

import type { MessageAttachment } from '@/lib/transport/frames';
import { ATTACHMENTS_PATH, bffUrl } from './config';
import { friendlyMessageForCode, parseErrorEnvelope } from './errors';
import { authFetch } from './http';

/** 서버가 받는 형식. 고르는 창에서 미리 거르는 용도이고, 판정은 서버가 한다. */
export const ACCEPTED_FILE_TYPES = '.txt,.md,.csv,.pdf,.docx';

/** 업로드가 거절된 이유. message는 사용자에게 그대로 보여줄 문구다. */
export class AttachmentUploadError extends Error {}

export async function uploadAttachment(
  file: File,
): Promise<MessageAttachment> {
  const body = new FormData();
  body.append('file', file);
  // Content-Type은 비워 둔다 — 브라우저가 multipart 경계(boundary)를 붙여 채운다.
  const response = await authFetch(bffUrl(ATTACHMENTS_PATH), {
    method: 'POST',
    body,
  });
  if (!response.ok) {
    const envelope = parseErrorEnvelope(await response.text());
    throw new AttachmentUploadError(friendlyMessageForCode(envelope?.code));
  }
  return (await response.json()) as MessageAttachment;
}
