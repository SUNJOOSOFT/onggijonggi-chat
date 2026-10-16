/********************************************************
 파일명 : activity.ts (lib/chat)
 설 명 : AI 턴의 도구 실행 중 표시 문구(이슈 #397). 서버는 종류 코드(chat.activity의 kind)와 대상만 보내고 문구는
 여기서 고른다 — 오류 문구(lib/api/errors.ts)와 같은 원칙이다. 모르는 코드면 일반 문구를 쓴다. 새 도구가
 생기면 서버 ToolActivity에 종류를 더하고(빠지면 ToolActivityTest가 잡는다) 아래 표에 한 줄을 더한다.

 대상 다듬기는 나눠 맡는다 — 서버는 방에 내보내는 값 자체를 줄이고(제어 문자, 주소의 쿼리·조각·사용자 정보,
 100글자), 화면은 보이는 모양을 지킨다(주소의 %인코딩 풀기, 방향 바꾸기 문자 빼기).
 *********************************************************/

const LABEL_BY_KIND: Record<string, string> = {
  WEB_SEARCH: '웹 검색 중…',
  WEB_FETCH: '페이지 읽는 중…',
  DOCUMENT_SEARCH: '방 문서 찾는 중…',
  DOCUMENT_REVISE: '개정본 만드는 중…',
};

const GENERIC_LABEL = '작업 중…';

/** 진행 중 표시 문구. 대상(검색어·주소·파일 이름)이 있으면 뒤에 붙인다. */
export function activityLabel(kind: string, target: string | null): string {
  const label = LABEL_BY_KIND[kind] ?? GENERIC_LABEL;
  if (!target) return label;
  return `${label} ${kind === 'WEB_FETCH' ? readableUrl(target) : withoutDisguising(target)}`;
}

/**
 * 풀고 나면 보이는 모양을 바꾸는 글자인지 — 제어 문자와 방향 바꾸기 문자(주소를 거꾸로 보이게 꾸밀 수 있다).
 * 정규식에 제어 문자를 쓰지 않으려고(lint) 코드 값으로 본다.
 */
function isDisguising(code: number): boolean {
  return (
    code <= 0x1f ||
    code === 0x7f ||
    code === 0x200e ||
    code === 0x200f ||
    (code >= 0x202a && code <= 0x202e) ||
    (code >= 0x2066 && code <= 0x2069)
  );
}

/** 검색어·파일 이름에 섞인 제어·방향 바꾸기 문자를 뺀다 — 남기면 앞 문구까지 뒤집혀 보일 수 있다. */
function withoutDisguising(text: string): string {
  return [...text]
    .filter((char) => !isDisguising(char.codePointAt(0) ?? 0))
    .join('');
}

/**
 * 주소의 %인코딩(한글 경로 등)을 사람이 읽을 수 있게 푼다. 풀 수 없거나, 풀면 제어·방향 바꾸기 문자가 드러나면
 * 그대로 둔다.
 */
function readableUrl(url: string): string {
  try {
    const decoded = decodeURI(url);
    return [...decoded].some((char) => isDisguising(char.codePointAt(0) ?? 0))
      ? url
      : decoded;
  } catch {
    return url;
  }
}
