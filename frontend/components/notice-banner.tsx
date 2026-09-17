/********************************************************
 파일명 : notice-banner.tsx
 설 명 : 시스템 알림(#29) 배너. 방 위에 얹혀 닫기 전까지 남는다 — 사후에 오는 알림이라 지나가면
 놓치기 때문이다. traceId는 문의할 때 쓰라고 title로만 남긴다. 협업방(collab-room.tsx)과
 DIRECT 1:1(chat.tsx, 이슈 #162)이 같은 SystemNoticeFrame(severity:'warning') 계약을 보여주는
 자리라 컴포넌트를 공유한다.
 *********************************************************/

import type { SystemNotice } from '@/lib/chat/room-state';

export function NoticeBanner({
  notice,
  onDismiss,
}: {
  notice: SystemNotice;
  onDismiss: () => void;
}) {
  return (
    <div
      role="alert"
      title={notice.traceId}
      className="flex items-center gap-3 border-b border-destructive/40 bg-destructive/10 px-4 py-2 text-xs text-destructive"
    >
      <span className="flex-1">{notice.message}</span>
      <button
        type="button"
        className="shrink-0 underline underline-offset-2"
        onClick={onDismiss}
      >
        닫기
      </button>
    </div>
  );
}
