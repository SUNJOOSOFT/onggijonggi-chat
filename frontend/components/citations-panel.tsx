'use client';

/********************************************************
 파일명 : citations-panel.tsx
 설 명 : 근거 인용 패널. 호출부가 지정한 메시지 아래 근거 문서 목록을 접이식으로 보여준다. 개인
 채팅의 병렬 조회 loading과 협업방 `chat.answer`의 즉시 success 상태를 함께 표현한다.
 restrictedResultsOmitted는 건수·제목 없이 제네릭 문구만 덧붙인다(판정은 서버 몫).
 score는 숫자 그대로 보여주고 색으로 등급화하지 않는다 — 등급 판정은 CLIENT 몫이 아니다.
 loc은 "N쪽"·"N번째 문단"으로 바꿔 보여주고, 과거 메시지처럼 없거나 모양이 다르면 생략한다(이슈 #352).
 같은 문서에서 청크 여러 개가 근거로 쓰이면 docId가 같아지므로, 목록 key는 chunkId를 우선
 쓰고 없으면(과거 메시지) 배열 index로 보강한다.
 groundingNotice는 citations가 비어 있어도 단독으로 패널을 띄운다(이슈 #353) — "근거를 못
 찾았다"와 "검색을 못 했다"를 서로 다른 문구로 구분해, 사용자가 답변이 문서 기반인지 AI의
 일반 지식인지 헷갈리지 않게 한다.
 *********************************************************/

import { AnimatePresence, motion } from 'framer-motion';
import { useId, useState } from 'react';

import type { Citation } from '@/lib/api/chat';
import type { ChatAnswerFrame } from '@/lib/transport/frames';
import { ChevronDownIcon, InfoIcon, LoaderIcon, LockIcon } from './icons';

/** groundingNotice 값을 사람이 읽는 안내 문구로 바꾼다. */
function groundingNoticeText(
  notice: ChatAnswerFrame['groundingNotice'],
): string | null {
  if (notice === 'no_evidence') return '이 방에 고정된 문서에서 근거를 찾지 못해 일반 지식으로 답했습니다.';
  if (notice === 'unavailable') return '문서 검색을 하지 못해 일반 지식으로 답했습니다.';
  return null;
}

/** loc("page=N"·"para=N")을 사람이 읽는 위치 문구로 바꾼다(이슈 #352). 모양이 다르거나
 * 없으면(과거 메시지) null — 호출부가 위치 표시 자체를 건너뛴다. */
function formatLoc(loc: string | undefined): string | null {
  if (!loc) return null;
  const page = loc.match(/^page=(\d+)$/);
  if (page) return `${page[1]}쪽`;
  const para = loc.match(/^para=(\d+)$/);
  if (para) return `${para[1]}번째 문단`;
  return null;
}

export interface CitationsState {
  status: 'loading' | 'error' | 'success';
  citations?: Citation[];
  restrictedResultsOmitted?: boolean;
  /** 방 문서 검색이 FOUND가 아니었을 때의 안내(이슈 #353). */
  groundingNotice?: ChatAnswerFrame['groundingNotice'];
  /** 에러 code 기준 친화 문구(errors.ts resolveChatError 재사용). */
  errorMessage?: string;
}

/** status에 따라 검색 중 안내, 실패 안내, 또는 접이식 근거 목록을 렌더링한다. 근거가 0건이고
 * 소프트 필터링도 없으면 빈 패널로 화면을 차지하지 않도록 아무것도 표시하지 않는다. */
export function CitationsPanel({ state }: { state: CitationsState }) {
  const [expanded, setExpanded] = useState(false);
  const listId = useId();

  if (state.status === 'loading') {
    return (
      <div className="flex items-center gap-1.5 text-xs text-muted-foreground px-2 py-1">
        <span className="animate-spin">
          <LoaderIcon size={12} />
        </span>
        근거 검색 중...
      </div>
    );
  }

  // 근거 조회 실패는 화면에 표시하지 않는다(채팅은 그대로 진행).
  const citations = state.citations ?? [];
  const noticeText = groundingNoticeText(state.groundingNotice ?? null);
  if (
    citations.length === 0 &&
    !state.restrictedResultsOmitted &&
    noticeText === null
  ) {
    return null;
  }

  return (
    <div className="text-xs w-fit max-w-full">
      {citations.length > 0 && (
        <button
          type="button"
          onClick={() => setExpanded((prev) => !prev)}
          aria-expanded={expanded}
          aria-controls={listId}
          className="flex items-center gap-1 text-muted-foreground hover:text-foreground px-2 py-1"
        >
          📚 근거 검색됨 ({citations.length}건)
          <span className={expanded ? 'rotate-180' : ''}>
            <ChevronDownIcon size={12} />
          </span>
        </button>
      )}

      <AnimatePresence initial={false}>
        {expanded && citations.length > 0 && (
          <motion.ul
            id={listId}
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.15 }}
            className="flex flex-col gap-2 px-2 pb-2 max-w-md overflow-hidden"
          >
            {citations.map((citation, index) => {
              const loc = formatLoc(citation.loc);
              return (
                <li
                  key={citation.chunkId ?? `${citation.docId}-${index}`}
                  className="rounded-md border bg-muted/40 p-2 text-muted-foreground"
                >
                  <div className="font-medium text-foreground">
                    {citation.title}
                    {loc && (
                      <span className="text-muted-foreground"> · {loc}</span>
                    )}{' '}
                    <span className="text-muted-foreground">
                      ({Math.round(citation.score * 100)}%)
                    </span>
                  </div>
                  <div>{citation.snippet}</div>
                </li>
              );
            })}
          </motion.ul>
        )}
      </AnimatePresence>

      {state.restrictedResultsOmitted && (
        <div className="flex items-center gap-1.5 text-muted-foreground px-2 py-1">
          <LockIcon size={12} />
          일부 문서는 접근 권한이 없어 결과에서 제외되었습니다.
        </div>
      )}

      {noticeText && (
        <div className="flex items-center gap-1.5 text-muted-foreground px-2 py-1">
          <InfoIcon size={12} />
          {noticeText}
        </div>
      )}
    </div>
  );
}
