/********************************************************
 파일명 : visible-text.ts (lib/chat)
 설 명 : 밖에서 온 글자(모델이 만든 검색어, 웹 페이지 제목·요약·주소)를 화면에 그대로 보여도 되게 다듬는다.
 도구 진행 표시(이슈 #397)와 웹 출처(이슈 #401)가 함께 쓴다. 서버는 방에 내보내는 값 자체를 줄이고(제어 문자,
 길이), 화면은 보이는 모양을 지킨다 — 방향 바꾸기 문자는 뒤 문구까지 뒤집어 보이게 할 수 있다.
 *********************************************************/

/**
 * 풀고 나면 보이는 모양을 바꾸는 글자인지 — 제어 문자(C0·C1), 줄·문단 구분 문자, 방향 바꾸기 문자(주소를 거꾸로
 * 보이게 꾸밀 수 있다).
 * 정규식에 제어 문자를 쓰지 않으려고(lint) 코드 값으로 본다.
 */
function isDisguising(code: number): boolean {
  return (
    code <= 0x1f ||
    (code >= 0x7f && code <= 0x9f) ||
    code === 0x2028 ||
    code === 0x2029 ||
    code === 0x200e ||
    code === 0x200f ||
    (code >= 0x202a && code <= 0x202e) ||
    (code >= 0x2066 && code <= 0x2069)
  );
}

/** 섞인 제어·방향 바꾸기 문자를 뺀다 — 남기면 앞뒤 문구까지 뒤집혀 보일 수 있다. */
export function withoutDisguising(text: string): string {
  return [...text]
    .filter((char) => !isDisguising(char.codePointAt(0) ?? 0))
    .join('');
}

/**
 * 주소의 %인코딩(한글 경로 등)을 사람이 읽을 수 있게 푼다. 풀 수 없거나, 풀면 제어·방향 바꾸기 문자가 드러나면
 * 그대로 둔다. 서버가 길이로 잘라 "…"를 붙인 주소는 끝에 반쯤 남은 %인코딩(`%EC%A1…`)을 떼고 푼다 — 그대로면
 * 주소 전체가 풀리지 않는다.
 */
export function readableUrl(url: string): string {
  const truncated = url.endsWith('…');
  const body = truncated ? url.slice(0, -1) : url;
  const decoded = decodePrefix(body, truncated ? MAX_PARTIAL_ESCAPE : 0);
  if (
    decoded === null ||
    [...decoded].some((char) => isDisguising(char.codePointAt(0) ?? 0))
  ) {
    return url;
  }
  return truncated ? `${decoded}…` : decoded;
}

/** 반쯤 잘린 %인코딩의 최대 길이 — 4바이트 글자에서 마지막 한 자리가 빠진 `%F0%9F%98%8`. */
const MAX_PARTIAL_ESCAPE = 11;

/** 끝에서 maxTrim자까지 떼어 가며 처음 풀리는 앞부분을 푼다. 끝까지 안 풀리면 null이다. */
function decodePrefix(text: string, maxTrim: number): string | null {
  for (let trim = 0; trim <= maxTrim && trim < text.length; trim++) {
    try {
      return decodeURI(text.slice(0, text.length - trim));
    } catch {
      // 더 떼어 본다.
    }
  }
  return null;
}
