/********************************************************
 파일명 : ws-connection.ts (lib/api)
 설 명 : 협업채팅 WS 커넥션 계층(이슈 #4). #3이 만든 서브프로토콜 인증 진입점 위에서
 연결을 유지하고, 끊기면 사유에 따라 다르게 되살린다.

 http.ts의 authFetch가 요청 단위로 하던 "401 → 세션 재조회 → 재시도 → 그래도 안 되면
 재로그인"을 커넥션 단위로 옮긴 것이다. 다만 요청 단위와 결정적으로 다른 점이 하나 있다:
 브라우저 WebSocket API는 핸드셰이크 응답의 status code도 body도 노출하지 않는다. 그래서
 서버가 401 봉투에 담아 보내는 TOKEN_EXPIRED·TOKEN_INVALID 구분은 여기까지 오지 못하고,
 실패는 전부 "열리지 않은 채 close" 하나로 뭉개져 도착한다.

 그 대신 쓰는 신호가 close code다(이슈 #2 확정, 서버 측은 #62):
   - 4000(토큰 만료) — 서버가 exp 타이머로 끊은 것. 세션을 재조회해 곧바로 재연결한다.
   - 1000(정상 종료) — 되살리지 않는다.
   - 그 외(1006 등) — 토큰 문제로 단정할 수 없으니 백오프 재연결만 한다. 다만 연속으로 거부되면
     그때는 세션을 다시 조회한다(아래).

 "재조회 후에도 무효면 재로그인"은 4000 직후의 핸드셰이크 실패로만 판정한다 — 서버 다운과
 인증 거부가 클라이언트에서 똑같이 1006으로 보이기 때문에, 만료 통보를 받은 직후라는 문맥이
 없으면 인증 실패로 단정할 수 없다. 그 문맥 밖의 실패를 재로그인으로 처리하면 서버가 잠깐
 죽었을 뿐인데 사용자를 로그인 화면으로 쫓아내게 된다.

 다만 그 문맥 밖이라고 아무것도 안 하면, 낡은 토큰을 든 채 영원히 같은 실패를 반복하게 된다
 (PR #68 리뷰 지적). 그래서 핸드셰이크가 연속으로 거부되면 세션을 다시 조회한다 — 재로그인
 여부의 판정을 close code가 아니라 next-auth에 넘기는 것이다. 세션이 실제로 죽었으면
 (RefreshAccessTokenError·토큰 없음) freshToken()이 그 자리에서 재로그인시키고, 살아 있으면
 새 토큰을 받아 백오프 재연결을 이어간다.
 *********************************************************/

import { getSession, signIn } from 'next-auth/react';
import { decodeJwtTimes, proactiveRefreshMarginMs } from '../auth/refresh-gate';
import { bffWsUrl, collabWsPath } from './config';

/** JWT payload의 exp를 읽어 지금 대비 몇 초 남았는지. 파싱 불가·exp 없음이면 null.
 * freshToken()의 근-만료 토큰 판정(이슈 #181)에 쓴다 — `[#181][ws]` 계측 로그에도 함께 실린다.
 * JWT 디코드 자체는 lib/auth/refresh-gate.ts와 공유한다 — A(선제 리프레시)·B′(이 판정) 둘 다
 * 같은 디코더를 쓴다. */
function tokenSkewSeconds(token: string | null | undefined): number | null {
  const times = decodeJwtTimes(token);
  return times ? Math.round((times.expiresAtMs - Date.now()) / 1000) : null;
}

/** 서브프로토콜의 첫 번째 값 — 서버 WsSubProtocolBearerTokenConverter.PROTOCOL_NAME과 반드시 같아야 한다. */
const PROTOCOL_NAME = 'access_token';

/** 서버가 토큰 만료로 커넥션을 끊을 때 쓰는 커스텀 close code(이슈 #62). */
export const CLOSE_TOKEN_EXPIRED = 4000;

/** RFC 6455 정상 종료. 이 코드로 끊기면 재연결하지 않는다. */
const CLOSE_NORMAL = 1000;

/** RFC 6455 비정상 종료 — 브라우저가 close 프레임 없이 끊긴 연결(핸드셰이크 거부 포함)에 매기는 코드. */
const CLOSE_ABNORMAL = 1006;

/** 재연결 백오프 상한 — http.ts의 429 백오프와 같은 값으로 맞춘다. */
const RECONNECT_BACKOFF_CAP_MS = 10_000;

/** 핸드셰이크가 이만큼 연속으로 거부되면 세션을 다시 조회한다. 1회로 하면 서버가 잠깐 흔들릴 때마다
 * /api/auth/session을 두드리게 되고, 너무 늘리면 낡은 토큰으로 헛도는 시간이 길어진다. 3회면 백오프
 * 곡선상 약 7초다. */
const HANDSHAKE_FAILURES_BEFORE_REFRESH = 3;

/** getSession()이 돌려준 토큰의 잔여 수명이 이 값(초) 미만이면 "쓸 수 있는 토큰을 못 받았다"로
 * 본다. WS 핸드셰이크 한 번에 걸리는 물리적 시간이라 토큰 수명에 비례하지 않는다(수명이 5분이든
 * 5초든 핸드셰이크 시간은 같다) — 고정값을 쓴다. lib/auth/refresh-gate.ts의 선제 리프레시
 * 마진이 동작하면 갓 조회한 토큰은 늘 이보다 넉넉히 남아 있어 여기 걸리지 않는다. 걸린다는 건
 * 리프레시가 사실상 실패했다는 뜻이라 어떤 재연결로도 못 푼다(이슈 #181). */
const MIN_USABLE_TOKEN_LIFE_S = 10;

/** 다만 토큰 수명 자체가 MIN_USABLE_TOKEN_LIFE_S보다 짧은 극단 설정에서는 위 고정 하한이
 * 정상 토큰까지 전부 근-만료로 오판하게 된다 — 그 경우에 한해 하한을 수명의 이 비율까지
 * 낮춘다(이슈 #181, 2026-09-11). */
const MIN_USABLE_TOKEN_LIFE_FRACTION = 0.3;

/** 근-만료 토큰을 이만큼 연속으로 받으면 재로그인시킨다. 1회는 마침 그 순간 만료된 경합일 수
 * 있으나, 연속이면 세션이 쓸 만한 토큰을 내주지 못하는 상태다 — #181 루프가 여기서 끝난다. */
const SHORT_LIVED_TOKENS_BEFORE_REAUTH = 2;

/** "쓸 수 있는 토큰"의 최소 잔여 수명(초). 토큰 자기 수명(exp - iat)이 MIN_USABLE_TOKEN_LIFE_S
 * 보다 짧으면(극단 설정) 고정 하한 대신 그 수명의 MIN_USABLE_TOKEN_LIFE_FRACTION을 쓴다 —
 * 안 그러면 그런 설정에서 방금 발급된 정상 토큰마저 매번 근-만료로 오판한다. iat을 모르면
 * (디코드 실패 등) 판단할 수명이 없으니 고정 하한 그대로 쓴다. */
function usableTokenLifeFloorSeconds(
  accessToken: string | null | undefined,
): number {
  const times = decodeJwtTimes(accessToken);
  if (!times || times.issuedAtMs === null) return MIN_USABLE_TOKEN_LIFE_S;
  const lifespanSeconds = (times.expiresAtMs - times.issuedAtMs) / 1000;
  return Math.min(
    MIN_USABLE_TOKEN_LIFE_S,
    lifespanSeconds * MIN_USABLE_TOKEN_LIFE_FRACTION,
  );
}

/** 표준 WebSocket에서 이 계층이 실제로 쓰는 부분만 추린 구조적 타입. vitest 환경이 'node'라
 * 전역 WebSocket이 없어, 테스트가 가짜 소켓을 끼울 수 있어야 한다. */
export interface SocketLike {
  send(data: string): void;
  addEventListener(
    type: 'open' | 'message' | 'close',
    listener: (event: { data?: unknown; code?: number }) => void,
  ): void;
  close(code?: number, reason?: string): void;
}

interface WsConnectionDeps {
  /** URL 결정까지 소켓 팩토리가 맡는다 — 그래야 테스트가 window.location에 기대지 않는다.
   * 방 경로는 openWsConnection의 threadId로 결정한다. */
  createSocket: (protocols: string[], path: string) => SocketLike;
  getSession: typeof getSession;
  signIn: typeof signIn;
  sleep: (ms: number) => Promise<void>;
}

const defaultDeps: WsConnectionDeps = {
  createSocket: (protocols, path) =>
    new WebSocket(bffWsUrl(path), protocols) as unknown as SocketLike,
  getSession,
  signIn,
  sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
};

export interface WsConnectionOptions {
  /** 서버가 보낸 텍스트 프레임 하나. 파싱은 이 계층의 일이 아니다(lib/transport/parse-frame.ts). */
  onMessage: (data: string) => void;
  /** 재로그인이 강제되는 순간 불린다 — http.ts의 onForcedReauth와 같은 목적이다. */
  onForcedReauth?: () => void;
  /** 연결이 열리고 끊길 때마다 불린다. 화면이 "연결 중" 표시와 전송 버튼 활성화를 이걸로 정한다. */
  onOpenChange?: (open: boolean) => void;
}

export interface WsConnection {
  /** 재연결 루프를 멈추고 소켓을 정상 종료(1000)한다. 이후 어떤 콜백도 불리지 않는다. */
  close: () => void;
  /**
   * 프레임 하나를 보낸다. 연결돼 있으면 true, 끊겨 있으면 **보내지 않고** false.
   *
   * 재연결될 때까지 큐에 쌓지 않는다 — 몇 초 뒤 되살아난 커넥션으로 뒤늦게 메시지가 튀어나오면
   * 협업방에서는 대화 순서가 어긋난다. 보내지 못했다는 사실을 그 자리에서 호출부에 돌려주고,
   * 다시 보낼지는 사용자가 정하게 한다.
   */
  send: (data: string) => boolean;
}

/** 재연결 대기 시간. http.ts의 retryAfterMs 폴백과 같은 지수 곡선(1s, 2s, 4s ...)에 상한을 둔다. */
export function reconnectBackoffMs(attempt: number): number {
  return Math.min(2 ** attempt * 500, RECONNECT_BACKOFF_CAP_MS);
}

/** 실제 벽시계 타이머(이슈 #188) — deps.sleep이 아니라 이걸 쓴다. deps.sleep은 테스트가 지연값과
 * 무관하게 즉시 resolve하도록 목을 세워두는 경우가 흔한데(백오프 sleep을 기다리지 않으려고),
 * 그 목을 선제 갱신 타이머에도 같이 쓰면 실제로는 몇 분 남은 토큰도 매번 "지금 갱신해야 한다"로
 * 오판해 바쁜 루프에 빠진다. 진짜 만료 마진을 재는 이 타이머는 실 setTimeout을 써서, 그런
 * 테스트에서는 (실행 시간 안에 절대 안 울리므로) 조용히 무시되고, 이 타이머 자체를 검증하는
 * 테스트만 vi.useFakeTimers()로 따로 제어한다. */
function realSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** 한 번 연결해서 끊길 때까지 기다린다. 열린 적이 있는지(opened)와 close code를 함께 돌려주는데,
 * 이 둘의 조합이 "인증이 거부됐다"와 "연결은 됐다가 끊겼다"를 가르는 유일한 단서다.
 *
 * onOpen은 close와 별개로 "이 소켓이 지금 열렸다"만 알려준다(이슈 #188) — 선제 갱신이 새 소켓을
 * 미리 열어 겹쳐 둘 때, 그 소켓이 실제로 열린 시점(그래야 옛 소켓을 닫아도 안전하다)을 알아야
 * 하는데 반환 Promise는 close 때까지 기다려서 그 용도로 못 쓴다. */
function connectOnce(
  token: string,
  path: string,
  options: WsConnectionOptions,
  deps: WsConnectionDeps,
  onSocket: (socket: SocketLike) => void,
  onOpen?: () => void,
): Promise<{ opened: boolean; code: number }> {
  return new Promise((resolve) => {
    // 표준 WebSocket API는 핸드셰이크에 Authorization 헤더를 못 실으므로 서브프로토콜 두 값으로
    // 토큰을 넘긴다 — 서버 WsSubProtocolBearerTokenConverter와 짝이다(이슈 #3).
    const socket = deps.createSocket([PROTOCOL_NAME, token], path);
    onSocket(socket);

    let opened = false;
    socket.addEventListener('open', () => {
      opened = true;
      options.onOpenChange?.(true);
      onOpen?.();
    });
    socket.addEventListener('message', (event) => {
      // 서버는 텍스트 프레임만 보낸다. Blob·ArrayBuffer가 오면 이 계층이 다룰 것이 아니다.
      if (typeof event.data === 'string') options.onMessage(event.data);
    });
    socket.addEventListener('close', (event) => {
      // [#181 계측] 브라우저가 실제로 받는 close code/reason. 서버 의도(4000 등) ↔ 수신 코드 간극 확인용.
      console.info(
        `[#181][ws] close opened=${opened} code=${event.code} reason=${JSON.stringify((event as { reason?: string }).reason ?? '')}`,
      );
      // 열린 적 없는 소켓의 close는 알릴 것이 없다 — 화면은 애초에 열림을 본 적이 없다.
      if (opened) options.onOpenChange?.(false);
      // code가 없는 close는 표준상 나오지 않지만, 온다면 정상 종료로 읽어 조용히 끊기는 것보다
      // 비정상으로 읽어 재연결을 시도하는 쪽이 안전하다.
      resolve({ opened, code: event.code ?? CLOSE_ABNORMAL });
    });
  });
}

/**
 * WS 커넥션을 열고, 끊기면 사유에 따라 되살린다. 반환된 close()를 부를 때까지 살아 있다.
 *
 * deps는 테스트용 주입 지점이다(http.ts와 같은 패턴).
 */
export function openWsConnection(
  threadId: string,
  options: WsConnectionOptions,
  deps: WsConnectionDeps = defaultDeps,
): WsConnection {
  let closedByCaller = false;
  // close() 등 "지금 붙어 있는 소켓"이 필요한 자리가 참조하는 값 — 선제 갱신(#188)의 새 소켓이
  // 아직 핸드셰이크 중이어도 곧바로 여기로 온다(생성 시점에 onSocket이 부른다).
  let socket: SocketLike | null = null;
  // send()가 실제로 write할 수 있는 소켓. isOpen과 함께 아래 scopedOptions()가 갱신한다 —
  // socket과 분리한 이유는 바로 아래 scopedOptions() 설명 참고.
  let authoritativeSocket: SocketLike | null = null;
  // send()가 봐야 하는 상태. 소켓 객체만으로는 지금 열려 있는지 알 수 없다 — SocketLike에
  // readyState를 넣지 않았다(테스트용 가짜 소켓이 그것까지 흉내 내야 하는 부담을 피한 선택이다).
  let isOpen = false;
  const path = collabWsPath(threadId);

  /**
   * connectOnce 한 번 호출마다 이 함수로 만든 전용 options를 넘긴다(이슈 #188 버그 수정).
   * 예전엔 모든 소켓이 같은 trackedOptions 객체 하나를 공유해서, 선제 갱신이 새 소켓으로
   * 갈아탄 뒤 옛 소켓이 뒤늦게(비동기로) close 이벤트를 내면 그 이벤트의 onOpenChange(false)가
   * 이미 새 소켓이 true로 만들어 둔 isOpen을 되돌려 버렸다 — 연결은 멀쩡한데 send()가 실패로
   * 보고하고 화면도 "끊김"을 깜빡였다.
   *
   * 규칙은 하나다: **열리는 쪽은 무조건 반영하고 그 소켓을 authoritativeSocket으로 승격시킨다.
   * 닫히는 쪽은 자신이 여전히 authoritativeSocket일 때만 반영한다.** 새 소켓이 열리는 순간
   * authoritativeSocket이 새 소켓으로 바뀌므로, 그 뒤에 도착하는 옛 소켓의 close는 자동으로
   * 무시된다 — 그 소켓은 더 이상 "지금의 연결"이 아니기 때문이다.
   */
  function scopedOptions(): { options: WsConnectionOptions; ref: { current: SocketLike | null } } {
    const ref: { current: SocketLike | null } = { current: null };
    return {
      ref,
      options: {
        ...options,
        onOpenChange: (open) => {
          if (open) {
            authoritativeSocket = ref.current;
            isOpen = true;
            options.onOpenChange?.(true);
            return;
          }
          if (ref.current !== authoritativeSocket) return;
          authoritativeSocket = null;
          isOpen = false;
          options.onOpenChange?.(false);
        },
      },
    };
  }

  const forceReauth = async () => {
    options.onForcedReauth?.();
    await deps.signIn('keycloak');
  };

  // freshToken()이 근-만료 토큰을 연속으로 몇 번 받았는지 — SHORT_LIVED_TOKENS_BEFORE_REAUTH에서 재로그인.
  let shortLivedTokens = 0;

  /** 세션을 다시 조회해 액세스 토큰을 얻는다. 리프레시가 이미 실패한 세션은 어떤 재연결로도
   * 살아나지 않으므로 곧장 재로그인으로 보낸다(http.ts와 같은 판단). 토큰은 왔지만 수명이
   * 얼마 안 남은 상태가 연속되면(리프레시가 사실상 안 되는 것) 역시 재로그인으로 보낸다(이슈 #181). */
  const freshToken = async (): Promise<string | null> => {
    const session = await deps.getSession();
    const lifeSeconds = tokenSkewSeconds(session?.accessToken);
    // [#181 계측] getSession()이 매번 돌려주는 토큰의 수명 + error 상태. 근-사망 토큰 무한 재발급 확인용.
    console.info(
      `[#181][ws] freshToken error=${session?.error ?? 'none'} hasToken=${!!session?.accessToken} skewSeconds=${lifeSeconds} shortLived=${shortLivedTokens}`,
    );
    if (session?.error === 'RefreshAccessTokenError' || !session?.accessToken) {
      await forceReauth();
      return null;
    }
    // close code가 아니라 토큰 자체로 판정한다 — "핸드셰이크는 통과하는데 곧 만료"인 토큰을
    // 무한히 받는 상태(#181)를 여기서 끊는다. null(파싱 불가)은 판정하지 않고 통과시킨다.
    if (
      lifeSeconds !== null &&
      lifeSeconds < usableTokenLifeFloorSeconds(session.accessToken)
    ) {
      shortLivedTokens += 1;
      if (shortLivedTokens >= SHORT_LIVED_TOKENS_BEFORE_REAUTH) {
        await forceReauth();
        return null;
      }
    } else {
      shortLivedTokens = 0;
    }
    return session.accessToken;
  };

  /**
   * 연결 하나를 열고 끊길 때까지 기다리되, 그 사이 액세스 토큰이 만료 마진 안으로 들어오면
   * 조용히 새 소켓으로 갈아탄다(이슈 #188). 새 소켓을 먼저 열어 옛 소켓과 잠깐 겹친 뒤(그래야
   * RoomSessionRegistry.add()가 "이미 다른 연결이 있다"로 보고 presence.join을 쏘지 않는다)
   * 옛 소켓을 닫는다(그래야 remove()가 "같은 사용자의 다른 연결이 남아 있다"로 보고
   * presence.leave를 쏘지 않는다) — 겹치는 순간이 없으면 이 서버 쪽 판정을 그대로 못 써서
   * 매번 입퇴장 한 쌍이 뜬다.
   *
   * 갱신에 실패하면(새 소켓이 핸드셰이크에서 거부되는 등) 지금 연결을 그대로 두고 다음 만료
   * 시점에 다시 시도한다 — 이 실패를 지금 연결의 장애로 취급하지 않는다.
   *
   * onTokenSwapped는 호출부(run 루프)의 token 변수를 갱신한다 — 다음 바깥 루프 반복이
   * 새로 연결할 때도 이 최신 토큰을 쓰게 한다.
   */
  const connectAndProactivelyRefresh = async (
    initialToken: string,
    onTokenSwapped: (token: string) => void,
  ): Promise<{ opened: boolean; code: number }> => {
    const { options: initialOptions, ref: initialRef } = scopedOptions();
    const pending = connectOnce(initialToken, path, initialOptions, deps, (s) => {
      socket = s;
      initialRef.current = s;
    });

    const times = decodeJwtTimes(initialToken);
    if (!times) {
      // exp를 모르면 언제 갱신해야 할지 정할 수 없다 — 이 연결은 선제 갱신 없이 원래 종료를
      // 그대로 기다린다.
      return await pending;
    }
    const refreshDelayMs = Math.max(
      0,
      times.expiresAtMs - proactiveRefreshMarginMs(initialToken) - Date.now(),
    );

    const raced = await Promise.race([
      pending.then((result) => ({ kind: 'closed' as const, result })),
      realSleep(refreshDelayMs).then(() => ({ kind: 'refresh-due' as const })),
    ]);
    if (raced.kind === 'closed') {
      return raced.result;
    }

    // 여기부터는 갱신 시점이 됐다는 뜻이다. 한 번만 시도한다 — 아직 핸드셰이크 중이거나
    // (isOpen이 아직 false), auth.ts 쪽 리프레시가 아직 안 끝나 같은 토큰을 또 받거나, 새
    // 소켓이 거부되면 이번엔 포기하고 지금 연결의 원래 종료를 기다린다. 이 연결이 다음에
    // 자연스럽게 재연결될 때 다시 시도된다 — 계속 재시도하려고 여기서 실 타이머로 바쁘게
    // 돌 필요는 없다.
    if (!isOpen || closedByCaller) {
      return await pending;
    }
    const newToken = await freshToken();
    if (newToken === null) return { opened: true, code: CLOSE_NORMAL };
    if (newToken === initialToken) {
      return await pending;
    }

    const oldSocket = socket;
    let swapOpened = false;
    let signalSwapOpened: (() => void) | undefined;
    const swapOpenedSignal = new Promise<void>((resolve) => {
      signalSwapOpened = resolve;
    });
    // 새 소켓을 먼저 열어 옛 소켓과 겹친다(RoomSessionRegistry.add()가 "이미 다른 연결이
    // 있다"로 보고 presence.join을 쏘지 않게 하려는 것 — 위 connectAndProactivelyRefresh
    // 설명 참고). scopedOptions()로 별도 options를 받는 이유는 그 함수 설명 참고 — 옛 소켓의
    // 뒤늦은 close가 이 소켓이 이미 연 isOpen을 되돌리지 못하게 한다.
    const { options: swapOptions, ref: swapRef } = scopedOptions();
    const swapPending = connectOnce(
      newToken,
      path,
      swapOptions,
      deps,
      (s) => {
        socket = s;
        swapRef.current = s;
      },
      () => {
        swapOpened = true;
        signalSwapOpened?.();
      },
    );
    // 새 소켓이 열리는지(성공) 또는 열리지 못한 채 닫히는지(실패) 중 먼저 오는 쪽을 본다 —
    // 실패면 swapPending 자체가 그 결과로 resolve되므로 별도 처리가 필요 없다.
    await Promise.race([swapOpenedSignal, swapPending.then(() => undefined)]);

    if (!swapOpened) {
      console.info('[#188][ws] proactive refresh handshake rejected — keeping current connection');
      socket = oldSocket;
      return await pending;
    }

    console.info('[#188][ws] proactive refresh swapped to a new connection ahead of expiry');
    oldSocket?.close(CLOSE_NORMAL);
    onTokenSwapped(newToken);
    return await swapPending;
  };

  const run = async () => {
    let token = await freshToken();
    // 만료 통보(4000)를 받은 직후인지 — 이 문맥에서만 핸드셰이크 실패를 인증 실패로 읽는다.
    let afterExpiry = false;
    let expiryRetried = false;
    let backoffAttempt = 0;
    // 한 번도 열리지 못한 핸드셰이크가 연속 몇 번인지. 열리면 0으로 되돌린다.
    let failedHandshakes = 0;

    while (token !== null && !closedByCaller) {
      const { opened, code } = await connectAndProactivelyRefresh(token, (newToken) => {
        token = newToken;
      });
      if (closedByCaller || code === CLOSE_NORMAL) return;

      // [#181 계측] 매 루프 반복의 상태 — opened가 매번 true라 백오프/가드가 리셋되는지 확인용.
      console.info(
        `[#181][ws] loop opened=${opened} code=${code} backoffAttempt=${backoffAttempt} failedHandshakes=${failedHandshakes} afterExpiry=${afterExpiry} expiryRetried=${expiryRetried}`,
      );

      if (opened) {
        backoffAttempt = 0;
        expiryRetried = false;
        failedHandshakes = 0;
        if (code === CLOSE_TOKEN_EXPIRED) {
          // 만료를 통보받았으니 재조회한 토큰으로 곧바로 다시 붙는다 — 여기서 기다릴 이유가 없다.
          afterExpiry = true;
          token = await freshToken();
          continue;
        }
        afterExpiry = false;
      } else if (afterExpiry) {
        // 재조회한 토큰으로도 핸드셰이크가 거부됐다. 한 번 더 재조회해 보고, 그래도면 재로그인.
        if (expiryRetried) {
          await forceReauth();
          return;
        }
        expiryRetried = true;
        token = await freshToken();
        continue;
      } else {
        // 만료 문맥 밖에서 계속 거부되는 경우. 재로그인을 단정할 수는 없지만 토큰을 다시 받아보는
        // 것까지는 안전하다 — 세션이 죽었다면 freshToken()이 재로그인으로 보낸다.
        failedHandshakes += 1;
        if (failedHandshakes >= HANDSHAKE_FAILURES_BEFORE_REFRESH) {
          failedHandshakes = 0;
          token = await freshToken();
        }
      }

      backoffAttempt += 1;
      await deps.sleep(reconnectBackoffMs(backoffAttempt));
    }
  };

  void run();

  return {
    close: () => {
      closedByCaller = true;
      socket?.close(CLOSE_NORMAL);
      // 선제 갱신(#188)의 새 소켓이 아직 핸드셰이크 중이면 socket이 그 소켓을 가리키고,
      // 실제로 살아있는 연결은 authoritativeSocket(옛 소켓) 쪽이다 — 위 close()가 새 소켓만
      // 닫고 옛 소켓은 안 닫혀 남는 걸 막는다. socket과 같으면(평소 상태) 이미 닫았으니
      // 다시 안 닫는다.
      if (authoritativeSocket && authoritativeSocket !== socket) {
        authoritativeSocket.close(CLOSE_NORMAL);
      }
    },
    send: (data) => {
      // socket이 아니라 authoritativeSocket을 쓴다 — 선제 갱신(#188)의 새 소켓은 핸드셰이크
      // 중에도 socket에 미리 배정되지만(close() 등이 최신 시도를 취소할 수 있어야 해서),
      // 아직 열리지 않았을 수 있다. 그 소켓에 바로 write하면 실제 WebSocket에서는
      // InvalidStateError가 난다.
      if (!isOpen || authoritativeSocket === null) return false;
      authoritativeSocket.send(data);
      return true;
    },
  };
}
