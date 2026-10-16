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
 찾았다"와 "검색을 못 했다"를 서로 다른 문구로 구분해, 답변이 문서 기반이 아님을 알린다.
 무엇으로 답했는지는 말하지 않는다 — 이 경우 웹 검색으로 답할 수도 있어서다(이슈 #368).
 웹 출처(kind WEB, 이슈 #401)는 방 문서 근거와 섞지 않고 "🌐 웹 출처" 버튼을 따로 둔다 — 문서에서
 근거를 못 찾고 웹으로 답했을 때 "근거 N건"과 "근거를 찾지 못했다"가 함께 보이지 않게 한다. 웹 항목은
 http·https 주소만 새 탭 링크로 열고, 점수는 없어 보이지 않는다. 모르는 종류의 근거는 보이지 않는다.
 *********************************************************/

import { AnimatePresence, motion } from 'framer-motion';
import { type ReactNode, useId, useState } from 'react';

import type { Citation } from '@/lib/api/chat';
import {
  DOCUMENT_CITATION,
  WEB_CITATION,
  citationKey,
  citationKind,
  hostOf,
  safeWebUrl,
} from '@/lib/chat/citations';
import { readableUrl, withoutDisguising } from '@/lib/chat/visible-text';
import type { ChatAnswerFrame } from '@/lib/transport/frames';
import { ChevronDownIcon, InfoIcon, LoaderIcon, LockIcon } from './icons';

/** groundingNotice 값을 사람이 읽는 안내 문구로 바꾼다. */
function groundingNoticeText(
  notice: ChatAnswerFrame['groundingNotice'],
): string | null {
  if (notice === 'no_evidence')
    return '이 방에 고정된 문서에서 근거를 찾지 못했습니다.';
  if (notice === 'unavailable') return '문서 검색을 하지 못했습니다.';
  return null;
}

/** loc("page=N"·"para=N")을 사람이 읽는 위치 문구로 바꾼다(이슈 #352). 모양이 다르거나
 * 없으면(과거 메시지) null — 호출부가 위치 표시 자체를 건너뛴다. */
function formatLoc(loc: string | null | undefined): string | null {
  if (!loc) return null;
  const page = loc.match(/^page=(\d+)$/);
  if (page) return `${page[1]}쪽`;
  const para = loc.match(/^para=(\d+)$/);
  if (para) return `${para[1]}번째 문단`;
  return null;
}

/** 웹 출처를 찾은 검색어들(중복 없이, 처음 나온 순서). 검색 없이 읽은 주소는 검색어가 없다. */
function webQueries(web: Citation[]): string[] {
  const queries: string[] = [];
  for (const citation of web) {
    const query = citation.query && withoutDisguising(citation.query);
    if (query && !queries.includes(query)) {
      queries.push(query);
    }
  }
  return queries;
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
  const [documentsExpanded, setDocumentsExpanded] = useState(false);
  const [webExpanded, setWebExpanded] = useState(false);
  const documentsListId = useId();
  const webListId = useId();

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
  const documents = citations.filter(
    (citation) => citationKind(citation) === DOCUMENT_CITATION,
  );
  const web = citations.filter(
    (citation) => citationKind(citation) === WEB_CITATION,
  );
  const noticeText = groundingNoticeText(state.groundingNotice ?? null);
  if (
    documents.length === 0 &&
    web.length === 0 &&
    !state.restrictedResultsOmitted &&
    noticeText === null
  ) {
    return null;
  }
  const queries = webQueries(web);

  return (
    <div className="text-xs w-fit max-w-full">
      {(documents.length > 0 || web.length > 0) && (
        <div className="flex flex-wrap items-center">
          {documents.length > 0 && (
            <PanelToggle
              expanded={documentsExpanded}
              onToggle={() => setDocumentsExpanded((prev) => !prev)}
              controls={documentsListId}
            >
              <span aria-hidden="true">📚</span> 근거 검색됨 ({documents.length}
              건)
            </PanelToggle>
          )}
          {web.length > 0 && (
            <PanelToggle
              expanded={webExpanded}
              onToggle={() => setWebExpanded((prev) => !prev)}
              controls={webListId}
            >
              <span aria-hidden="true">🌐</span> 웹 출처 ({web.length}건)
            </PanelToggle>
          )}
        </div>
      )}

      <AnimatePresence initial={false}>
        {documentsExpanded && documents.length > 0 && (
          <motion.ul
            id={documentsListId}
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.15 }}
            className="flex flex-col gap-2 px-2 pb-2 max-w-md overflow-hidden"
          >
            {documents.map((citation, index) => {
              const loc = formatLoc(citation.loc);
              return (
                <li
                  key={citationKey(citation) ?? `document-${index}`}
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

      <AnimatePresence initial={false}>
        {webExpanded && web.length > 0 && (
          <motion.div
            id={webListId}
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.15 }}
            className="flex flex-col gap-2 px-2 pb-2 max-w-md overflow-hidden"
          >
            {queries.length > 0 && (
              <div className="text-muted-foreground [overflow-wrap:anywhere]">
                검색어: {queries.map((query) => `“${query}”`).join(', ')}
              </div>
            )}
            <ul className="flex min-w-0 flex-col gap-2">
              {web.map((citation, index) => (
                <WebSourceItem
                  key={citationKey(citation) ?? `web-${index}`}
                  citation={citation}
                />
              ))}
            </ul>
          </motion.div>
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

/** 근거 목록 하나를 접고 펴는 버튼. */
function PanelToggle({
  expanded,
  onToggle,
  controls,
  children,
}: {
  expanded: boolean;
  onToggle: () => void;
  controls: string;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-expanded={expanded}
      // 접힌 동안엔 목록이 화면에 없다 — 없는 id를 가리키지 않게 펼쳤을 때만 잇는다.
      aria-controls={expanded ? controls : undefined}
      className="flex items-center gap-1 text-muted-foreground hover:text-foreground px-2 py-1"
    >
      {children}
      <span className={expanded ? 'rotate-180' : ''}>
        <ChevronDownIcon size={12} />
      </span>
    </button>
  );
}

/**
 * 웹 출처 한 건 — 제목(http·https면 새 탭 링크)·도메인·읽음 표시·요약. 제목·요약은 웹 페이지가 정한 글자라
 * 방향 바꾸기 문자를 빼고, 공백 없는 긴 주소도 줄을 바꾼다. 도메인은 퓨니코드(xn--) 그대로 둔다 — 비슷하게 생긴
 * 다른 글자로 꾸민 도메인이 진짜처럼 보이지 않게. 링크에 마우스를 올리면 실제 주소가 보인다. 제목이 없어 도메인을
 * 제목으로 쓴 경우엔 도메인을 한 번만 보인다. 링크는 터치 화면에서도 알아보게 늘 옅은 밑줄을 긋는다.
 */
function WebSourceItem({ citation }: { citation: Citation }) {
  const href = safeWebUrl(citation.url);
  const host = hostOf(citation.url);
  const title = withoutDisguising(
    citation.title || host || readableUrl(citation.url ?? ''),
  );
  return (
    <li className="min-w-0 rounded-md border bg-muted/40 p-2 text-muted-foreground [overflow-wrap:anywhere]">
      <div className="font-medium text-foreground">
        {href ? (
          <a
            href={href}
            title={href}
            target="_blank"
            rel="noopener noreferrer"
            className="underline decoration-muted-foreground/40 underline-offset-2 hover:decoration-foreground"
          >
            {title}
            <span className="sr-only"> (새 탭에서 열림)</span>
          </a>
        ) : (
          title
        )}
        {host && !sameHost(title, host) && (
          <span className="text-muted-foreground"> · {host}</span>
        )}
        {citation.read && (
          <span
            title="AI가 이 페이지 본문을 읽었습니다"
            className="ml-1.5 rounded border px-1 text-[10px] text-muted-foreground"
          >
            읽음
          </span>
        )}
      </div>
      {citation.snippet && <div>{withoutDisguising(citation.snippet)}</div>}
    </li>
  );
}

/** 제목이 곧 도메인인지(www. 제외, 대소문자 무시) — 같으면 " · 도메인"을 덧붙이지 않는다. */
function sameHost(title: string, host: string): boolean {
  return (
    title
      .trim()
      .toLowerCase()
      .replace(/^www\./, '') === host.toLowerCase()
  );
}
