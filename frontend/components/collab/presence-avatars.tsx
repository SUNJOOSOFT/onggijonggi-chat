'use client';

/********************************************************
 파일명 : presence-avatars.tsx (components/collab)
 설 명 : 협업방 헤더의 접속자 표시(#26). 겹친 이니셜 동그라미와 "N명 접속"을 보이고, 누르면 이름 목록을 펼친다.
 "지금 방에 붙어 있는 사람"이라, 방에 들어올 자격이 있는 참여자 명단(#23, 참여자 관리)과는 다른 목록이다.
 이름은 프레임이 실어 온 값이고, subject는 동명이인을 가릴 수 있게 title로 남긴다.
 *********************************************************/

import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import type { PresenceParticipant } from '@/lib/transport/frames';
import { cn } from '@/lib/utils';

/** 헤더에 그리는 동그라미 수. 나머지는 +N으로 줄인다. */
const SHOWN = 3;
const COLORS = [
  'bg-indigo-600',
  'bg-teal-600',
  'bg-amber-700',
  'bg-rose-600',
  'bg-sky-600',
  'bg-violet-600',
];

/** 같은 사람은 어느 화면에서나 같은 색이 되도록 subject로 고른다. */
function color(subject: string): string {
  let hash = 0;
  for (const char of subject) hash = (hash * 31 + char.charCodeAt(0)) | 0;
  return COLORS[Math.abs(hash) % COLORS.length];
}

function Avatar({ participant }: { participant: PresenceParticipant }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        '-ml-1.5 inline-flex size-6 items-center justify-center rounded-full border-2 border-background text-[11px] font-semibold text-white first:ml-0',
        color(participant.subject),
      )}
    >
      {participant.displayName.slice(0, 1)}
    </span>
  );
}

export function PresenceAvatars({
  participants,
}: {
  participants: PresenceParticipant[];
}) {
  const rest = participants.length - SHOWN;
  return (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="gap-1.5 px-1.5"
          aria-label={`접속중 ${participants.length}명`}
          disabled={participants.length === 0}
        >
          <span className="flex items-center">
            {participants.slice(0, SHOWN).map((participant) => (
              <Avatar key={participant.subject} participant={participant} />
            ))}
            {rest > 0 && (
              <span className="-ml-1.5 inline-flex size-6 items-center justify-center rounded-full border-2 border-background bg-muted text-[11px] font-semibold">
                +{rest}
              </span>
            )}
          </span>
          <span className="hidden text-xs text-muted-foreground sm:inline">
            {participants.length}명 접속
          </span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        <DropdownMenuLabel className="text-xs font-medium text-muted-foreground">
          접속중 {participants.length}
        </DropdownMenuLabel>
        <ul className="max-h-64 overflow-y-auto pb-1">
          {participants.map((participant) => (
            <li
              key={participant.subject}
              title={participant.subject}
              className="flex items-center gap-2 px-2 py-1 text-sm"
            >
              <span className="size-2 shrink-0 rounded-full bg-emerald-500" />
              {participant.displayName}
            </li>
          ))}
        </ul>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
