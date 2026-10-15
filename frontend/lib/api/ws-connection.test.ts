import { afterEach, describe, expect, it, vi } from 'vitest';
import { isForcedRefreshRequest } from '../auth/refresh-gate';
import {
  CLOSE_TOKEN_EXPIRED,
  HEARTBEAT_INTERVAL_MS,
  type SocketLike,
  type WsConnection,
  type WsConnectionOptions,
  openWsConnection,
  reconnectBackoffMs,
  requestForcedRefresh,
} from './ws-connection';

// requestForcedRefresh가 CSRF 토큰을 받는 곳. 나머지 next-auth/react는 실물을 둔다 — 연결 테스트는
// getSession·signIn을 deps로 끼워 넣어 쓰지 않는다.
vi.mock('next-auth/react', async (importOriginal) => ({
  ...(await importOriginal<typeof import('next-auth/react')>()),
  getCsrfToken: vi.fn(async () => 'csrf-1'),
}));

const THREAD_ID = '11111111-1111-4111-8111-111111111111';
const OTHER_THREAD_ID = '22222222-2222-4222-8222-222222222222';

const subscribeFrame = (threadId: string) =>
  JSON.stringify({ type: 'room.subscribe', threadId });

/** tokenSkewSeconds()가 실제 JWT처럼 파싱할 수 있는 토큰. exp는 지금부터 seconds 뒤.
 * 'access_token'·'t1' 같은 문자열은 JWT가 아니라 tokenSkewSeconds()가 null을 돌려주고,
 * null이면 freshToken()의 근-만료 판정이 건너뛰어진다 — 그 판정을 실제로 태우려면 이 헬퍼가 필요하다. */
function jwtWithLife(seconds: number): string {
  const payload = btoa(
    JSON.stringify({ exp: Math.floor(Date.now() / 1000) + seconds }),
  );
  return `h.${payload}.s`;
}

/** jwtWithLife()와 달리 iat도 실어 "토큰 자기 수명"(exp - iat)까지 판정에 쓰이게 한다 —
 * usableTokenLifeFloorSeconds()의 극단 lifespan 하한 조정을 태우려면 iat가 필요하다. */
function jwtWithTimes(
  issuedSecondsAgo: number,
  expiresInSeconds: number,
): string {
  const now = Math.floor(Date.now() / 1000);
  const payload = btoa(
    JSON.stringify({
      iat: now - issuedSecondsAgo,
      exp: now + expiresInSeconds,
    }),
  );
  return `h.${payload}.s`;
}

/** 테스트가 open·message·close 시점을 직접 잡을 수 있는 가짜 소켓. vitest 환경이 'node'라
 * 전역 WebSocket이 없고, 있더라도 close code를 마음대로 만들어낼 수 없다. */
class FakeSocket implements SocketLike {
  readonly protocols: string[];
  readonly path: string;
  readonly sent: string[] = [];
  closedWith: number | null = null;
  private readonly listeners = new Map<
    string,
    ((event: { data?: unknown; code?: number }) => void)[]
  >();

  constructor(protocols: string[], path: string) {
    this.protocols = protocols;
    this.path = path;
  }

  send(data: string): void {
    this.sent.push(data);
  }

  addEventListener(
    type: 'open' | 'message' | 'close',
    listener: (event: { data?: unknown; code?: number }) => void,
  ): void {
    const existing = this.listeners.get(type) ?? [];
    existing.push(listener);
    this.listeners.set(type, existing);
  }

  close(code?: number): void {
    this.closedWith = code ?? 1000;
    this.emit('close', { code: this.closedWith });
  }

  /** 서버가 끊은 상황 — close()와 달리 closedWith를 남기지 않는다(클라이언트가 닫은 게 아니다). */
  serverClose(code: number): void {
    this.emit('close', { code });
  }

  open(): void {
    this.emit('open', {});
  }

  message(data: unknown): void {
    this.emit('message', { data });
  }

  private emit(type: string, event: { data?: unknown; code?: number }): void {
    for (const listener of this.listeners.get(type) ?? []) listener(event);
  }
}

interface Harness {
  sockets: FakeSocket[];
  getSession: ReturnType<typeof vi.fn>;
  /** 선제 갱신이 서버에 직접 요청하는 갱신(이슈 #382). getSession과 같은 세션 큐에서 꺼낸다. */
  forceRefreshSession: ReturnType<typeof vi.fn>;
  signIn: ReturnType<typeof vi.fn>;
  sleep: ReturnType<typeof vi.fn>;
  onMessage: ReturnType<typeof vi.fn>;
  connection: WsConnection;
  /** n번째 소켓이 만들어질 때까지 기다린다(1-based). 재연결 루프가 마이크로태스크로 돌기 때문. */
  waitForSocket: (n: number) => Promise<FakeSocket>;
}

function harness(
  sessions: ({
    accessToken?: string;
    error?: 'RefreshAccessTokenError';
  } | null)[],
  extraOptions: Omit<WsConnectionOptions, 'onMessage'> = {},
  /** 선제 갱신의 직접 요청을 세션 큐 대신 직접 제어할 때(응답 시점을 늦추는 테스트). */
  forceRefreshOverride?: () => Promise<unknown>,
): Harness {
  const sockets: FakeSocket[] = [];
  // 준비한 세션을 순서대로 하나씩 내주고, 다 떨어지면 마지막 것을 계속 돌려준다. 일반 조회(getSession)와
  // 선제 갱신의 직접 요청(forceRefreshSession, 이슈 #382)이 같은 큐를 쓴다 — 선제 갱신 테스트는 두 번째
  // 세션을 "갱신 결과"로 준비한다.
  let call = 0;
  const nextSession = async () => {
    const session = sessions[Math.min(call, sessions.length - 1)];
    call += 1;
    return session;
  };
  const getSession = vi.fn(nextSession);
  const forceRefreshSession = vi.fn(forceRefreshOverride ?? nextSession);
  const signIn = vi.fn(async () => undefined);
  const sleep = vi.fn(async () => undefined);
  const onMessage = vi.fn();

  const connection = openWsConnection(
    { onMessage, ...extraOptions },
    {
      createSocket: (protocols, path) => {
        const socket = new FakeSocket(protocols, path);
        sockets.push(socket);
        return socket;
      },
      getSession: getSession as never,
      forceRefreshSession: forceRefreshSession as never,
      signIn: signIn as never,
      sleep,
    },
  );

  return {
    sockets,
    getSession,
    forceRefreshSession,
    signIn,
    sleep,
    onMessage,
    connection,
    waitForSocket: (n) =>
      vi.waitFor(() => {
        const socket = sockets[n - 1];
        if (!socket) throw new Error(`${n}번째 소켓이 아직 없다`);
        return socket;
      }),
  };
}

describe('reconnectBackoffMs', () => {
  it('지수로 늘다가 상한에서 멈춘다', () => {
    expect(reconnectBackoffMs(1)).toBe(1000);
    expect(reconnectBackoffMs(2)).toBe(2000);
    expect(reconnectBackoffMs(3)).toBe(4000);
    expect(reconnectBackoffMs(10)).toBe(10_000);
  });
});

describe('openWsConnection', () => {
  it('서브프로토콜 두 값으로 토큰을 실어 연결한다', async () => {
    const h = harness([{ accessToken: 't1' }]);
    const socket = await h.waitForSocket(1);
    expect(socket.protocols).toEqual(['access_token', 't1']);
    h.connection.close();
  });

  it('텍스트 프레임만 onMessage로 흘리고 그 외 payload는 무시한다', async () => {
    const h = harness([{ accessToken: 't1' }]);
    const socket = await h.waitForSocket(1);
    socket.open();
    socket.message('{"type":"chat.message"}');
    socket.message(new ArrayBuffer(4));
    expect(h.onMessage).toHaveBeenCalledExactlyOnceWith(
      '{"type":"chat.message"}',
    );
    h.connection.close();
  });

  it('정상 종료(1000)면 재연결하지 않는다', async () => {
    const h = harness([{ accessToken: 't1' }]);
    const socket = await h.waitForSocket(1);
    socket.open();
    socket.serverClose(1000);
    await vi.waitFor(() => expect(h.sleep).not.toHaveBeenCalled());
    expect(h.sockets).toHaveLength(1);
  });

  it('토큰 만료(4000)면 세션을 재조회해 백오프 없이 곧바로 재연결한다', async () => {
    const h = harness([{ accessToken: 't1' }, { accessToken: 't2' }]);
    const first = await h.waitForSocket(1);
    first.open();
    first.serverClose(CLOSE_TOKEN_EXPIRED);

    const second = await h.waitForSocket(2);
    expect(second.protocols).toEqual(['access_token', 't2']);
    expect(h.getSession).toHaveBeenCalledTimes(2);
    expect(h.sleep).not.toHaveBeenCalled();
    h.connection.close();
  });

  it('만료가 아닌 비정상 종료(1006)는 토큰 재조회 없이 백오프 재연결만 한다', async () => {
    const h = harness([{ accessToken: 't1' }]);
    const first = await h.waitForSocket(1);
    first.open();
    first.serverClose(1006);

    const second = await h.waitForSocket(2);
    expect(second.protocols).toEqual(['access_token', 't1']);
    expect(h.sleep).toHaveBeenCalledExactlyOnceWith(1000);
    expect(h.getSession).toHaveBeenCalledTimes(1);
    h.connection.close();
  });

  it('만료 문맥 밖에서도 핸드셰이크가 3번 연속 거부되면 세션을 다시 조회한다', async () => {
    const h = harness([{ accessToken: 't1' }, { accessToken: 't2' }]);

    // 한 번도 open()하지 않는다 — 핸드셰이크가 거부되는 경우다. 4000을 받은 적이 없으므로
    // 만료 문맥(afterExpiry) 밖이고, 예전에는 이 경로가 같은 토큰으로 무한 재시도만 했다.
    for (const n of [1, 2, 3]) (await h.waitForSocket(n)).serverClose(1006);

    const fourth = await h.waitForSocket(4);
    expect(fourth.protocols).toEqual(['access_token', 't2']);
    expect(h.getSession).toHaveBeenCalledTimes(2);
    h.connection.close();
  });

  it('3번 연속 거부 시점에 세션이 죽어 있으면 재로그인시킨다', async () => {
    const h = harness([
      { accessToken: 't1' },
      { accessToken: 't1', error: 'RefreshAccessTokenError' },
    ]);

    for (const n of [1, 2, 3]) (await h.waitForSocket(n)).serverClose(1006);

    // 재조회로 판정을 next-auth에 넘긴 결과다 — close code만 보고 단정한 게 아니다.
    await vi.waitFor(() => expect(h.signIn).toHaveBeenCalledWith('keycloak'));
    expect(h.sockets).toHaveLength(3);
  });

  it('만료 통보 후 재조회한 토큰으로도 핸드셰이크가 거부되면 1회 더 재조회하고 재로그인시킨다', async () => {
    const h = harness([{ accessToken: 't1' }]);
    const first = await h.waitForSocket(1);
    first.open();
    first.serverClose(CLOSE_TOKEN_EXPIRED);

    // 2·3번째는 open 없이 끊긴다 — 브라우저가 핸드셰이크 401을 보는 방식(1006)이다.
    (await h.waitForSocket(2)).serverClose(1006);
    (await h.waitForSocket(3)).serverClose(1006);

    await vi.waitFor(() => expect(h.signIn).toHaveBeenCalledWith('keycloak'));
    expect(h.sockets).toHaveLength(3);
    expect(h.sleep).not.toHaveBeenCalled();
  });

  it('리프레시가 실패한 세션이면 연결을 시도하지 않고 곧장 재로그인시킨다', async () => {
    const onForcedReauth = vi.fn();
    const sockets: FakeSocket[] = [];
    const signIn = vi.fn(async () => undefined);

    openWsConnection(
      { onMessage: vi.fn(), onForcedReauth },
      {
        createSocket: (protocols, path) => {
          const socket = new FakeSocket(protocols, path);
          sockets.push(socket);
          return socket;
        },
        getSession: (async () => ({
          accessToken: 't1',
          error: 'RefreshAccessTokenError',
        })) as never,
        forceRefreshSession: vi.fn(async () => null),
        signIn: signIn as never,
        sleep: vi.fn(async () => undefined),
      },
    );

    await vi.waitFor(() => expect(signIn).toHaveBeenCalledWith('keycloak'));
    expect(onForcedReauth).toHaveBeenCalledOnce();
    expect(sockets).toHaveLength(0);
  });

  it('close()는 소켓을 1000으로 닫고 재연결 루프를 멈춘다', async () => {
    const h = harness([{ accessToken: 't1' }]);
    const socket = await h.waitForSocket(1);
    socket.open();
    h.connection.close();

    expect(socket.closedWith).toBe(1000);
    await vi.waitFor(() => expect(h.sleep).not.toHaveBeenCalled());
    expect(h.sockets).toHaveLength(1);
  });
});

describe('openWsConnection - [#181] 근-만료 토큰 무한 재발급', () => {
  // 서버 수정(3e26900) 후 브라우저는 만료 강제 종료 시 4000을 정확히 받는다. freshToken()이
  // 근-만료 토큰(수명 < MIN_USABLE_TOKEN_LIFE_S)을 SHORT_LIVED_TOKENS_BEFORE_REAUTH회 연속
  // 받으면 재로그인으로 루프를 끝낸다(B′, 문서 상세 3). 1006(서버 수정 전) 경로는 B′가
  // 건드리지 않으므로 현재 동작을 그대로 고정한다.

  it('4000으로 닫히고 재조회 토큰도 근-만료면, 몇 사이클 뒤 재로그인시킨다', async () => {
    const h = harness([{ accessToken: jwtWithLife(1) }]);

    const first = await h.waitForSocket(1);
    first.open();
    first.serverClose(CLOSE_TOKEN_EXPIRED);

    // freshToken()이 근-만료 토큰을 SHORT_LIVED_TOKENS_BEFORE_REAUTH회 연속 받으면 재로그인.
    await vi.waitFor(() => expect(h.signIn).toHaveBeenCalledWith('keycloak'));
    // 만료 분기라 백오프 sleep 없이 돌다가 종료한다.
    expect(h.sleep).not.toHaveBeenCalled();
    // 소켓은 한 번만 만들어지고 루프가 끝난다 — 무한 재접속이 아니다.
    expect(h.sockets).toHaveLength(1);
  });

  it('4000으로 닫혀도 재조회한 토큰 수명이 넉넉하면 재로그인 없이 재접속한다', async () => {
    const healthy = jwtWithLife(300);
    const h = harness([
      { accessToken: jwtWithLife(1) },
      { accessToken: healthy },
    ]);

    const first = await h.waitForSocket(1);
    first.open();
    first.serverClose(CLOSE_TOKEN_EXPIRED);

    const second = await h.waitForSocket(2);
    expect(second.protocols).toEqual(['access_token', healthy]);
    expect(h.signIn).not.toHaveBeenCalled();
    expect(h.sleep).not.toHaveBeenCalled();

    h.connection.close();
  });

  it('토큰 수명 자체가 짧으면(극단 설정) 근-만료 판정 하한도 그에 맞춰 낮아진다', async () => {
    // 수명 15초짜리 토큰이 8초 남았다 — 고정 하한(10초)이면 8<10이라 오판하지만, 하한이
    // 수명(15초)의 30%(4.5초)까지 낮아지므로 8초는 아직 "쓸 만한" 수명으로 본다.
    const shortLifespanHealthy = jwtWithTimes(7, 8);
    const h = harness([
      { accessToken: jwtWithLife(1) },
      { accessToken: shortLifespanHealthy },
    ]);

    const first = await h.waitForSocket(1);
    first.open();
    first.serverClose(CLOSE_TOKEN_EXPIRED);

    const second = await h.waitForSocket(2);
    expect(second.protocols).toEqual(['access_token', shortLifespanHealthy]);
    expect(h.signIn).not.toHaveBeenCalled();

    h.connection.close();
  });

  it('토큰 수명이 짧아도 그 안에서마저 근-만료면(조정된 하한 밑) 여전히 재로그인으로 끝낸다', async () => {
    // 수명 15초, 1초 남음 — 조정된 하한(4.5초)보다도 적어 여전히 근-만료로 잡혀야 한다.
    const nearDead = jwtWithTimes(14, 1);
    const h = harness([{ accessToken: nearDead }]);

    const first = await h.waitForSocket(1);
    first.open();
    first.serverClose(CLOSE_TOKEN_EXPIRED);

    await vi.waitFor(() => expect(h.signIn).toHaveBeenCalledWith('keycloak'));
    expect(h.sockets).toHaveLength(1);
  });

  it('소켓이 열리자마자 1006으로 닫히면(서버 수정 전), 백오프가 1초에서 자라지 않는다', async () => {
    const h = harness([{ accessToken: 'near-expiry' }]);

    for (const n of [1, 2, 3, 4, 5]) {
      const socket = await h.waitForSocket(n);
      socket.open();
      socket.serverClose(1006);
    }
    await h.waitForSocket(6);

    // if (opened)가 매 사이클 backoffAttempt를 0으로 리셋 → 항상 reconnectBackoffMs(1) = 1000.
    expect(h.sleep.mock.calls.length).toBeGreaterThanOrEqual(4);
    expect(h.sleep.mock.calls.every((call) => call[0] === 1000)).toBe(true);
    expect(h.signIn).not.toHaveBeenCalled();

    h.connection.close();
  });
});

describe('openWsConnection - 협업방(이슈 #19)', () => {
  it('방 없는 단일 경로로 붙는다(이슈 #161)', async () => {
    const h = harness([{ accessToken: 't1' }]);
    const socket = await h.waitForSocket(1);
    expect(socket.path).toBe('/api/ws');
    h.connection.close();
  });

  it('열려 있을 때만 보내고, 끊겨 있으면 보내지 않고 false를 준다', async () => {
    const h = harness([{ accessToken: 't1' }]);
    const socket = await h.waitForSocket(1);

    // 아직 open 이벤트가 오지 않았다 — 핸드셰이크 중에 누른 전송이다.
    expect(h.connection.send('early')).toBe(false);
    expect(socket.sent).toEqual([]);

    socket.open();
    expect(h.connection.send('hello')).toBe(true);
    expect(socket.sent).toEqual(['hello']);

    // 끊긴 뒤의 메시지는 큐에 쌓지 않는다 — 되살아난 커넥션으로 뒤늦게 나가면 순서가 어긋난다.
    socket.serverClose(1006);
    expect(h.connection.send('after close')).toBe(false);
    expect(socket.sent).toEqual(['hello']);

    h.connection.close();
  });

  it('열리고 끊길 때마다 화면에 알린다', async () => {
    const onOpenChange = vi.fn();
    const h = harness([{ accessToken: 't1' }], { onOpenChange });
    const socket = await h.waitForSocket(1);

    socket.open();
    expect(onOpenChange).toHaveBeenLastCalledWith(true);

    socket.serverClose(1006);
    expect(onOpenChange).toHaveBeenLastCalledWith(false);
    expect(onOpenChange).toHaveBeenCalledTimes(2);

    h.connection.close();
  });

  it('열린 적 없이 거부된 핸드셰이크는 닫혔다고 알리지 않는다', async () => {
    const onOpenChange = vi.fn();
    const h = harness([{ accessToken: 't1' }], { onOpenChange });
    const socket = await h.waitForSocket(1);

    socket.serverClose(1006);
    expect(onOpenChange).not.toHaveBeenCalled();

    h.connection.close();
  });
});

describe('openWsConnection - [#161] 사용자당 커넥션 하나', () => {
  it('열릴 때마다 보고 있는 방을 다시 구독하고, 화면에는 그 뒤에 열림을 알린다', async () => {
    let rooms = [THREAD_ID, OTHER_THREAD_ID];
    // 열림을 본 화면이 곧바로 보내는 발화가 구독 뒤에 나가야 한다 — 서버는 인바운드를 순서대로 처리한다.
    const h = harness([{ accessToken: 't1' }], {
      rooms: () => rooms,
      onOpenChange: (open) => {
        if (open) h.connection.send('hello');
      },
    });
    const first = await h.waitForSocket(1);
    first.open();
    expect(first.sent).toEqual([
      subscribeFrame(THREAD_ID),
      subscribeFrame(OTHER_THREAD_ID),
      'hello',
    ]);

    // 서버는 끊긴 커넥션의 방을 기억하지 않는다 — 새 소켓이 열리는 순간의 방 목록을 다시 건다.
    rooms = [OTHER_THREAD_ID];
    first.serverClose(1006);
    const second = await h.waitForSocket(2);
    second.open();
    expect(second.sent).toEqual([subscribeFrame(OTHER_THREAD_ID), 'hello']);

    h.connection.close();
  });

  it('루프가 스스로 끝나면 onEnd로 알리고, close()로 끝낸 때는 알리지 않는다', async () => {
    const endedByServer = vi.fn();
    const server = harness([{ accessToken: 't1' }], { onEnd: endedByServer });
    const socket = await server.waitForSocket(1);
    socket.open();
    socket.serverClose(1000);
    await vi.waitFor(() => expect(endedByServer).toHaveBeenCalledOnce());

    const endedByCaller = vi.fn();
    const caller = harness([{ accessToken: 't1' }], { onEnd: endedByCaller });
    (await caller.waitForSocket(1)).open();
    caller.connection.close();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(endedByCaller).not.toHaveBeenCalled();
  });

  it('ping 뒤로 한 주기 동안 아무 프레임도 없으면 소켓을 버리고 다시 붙는다', async () => {
    const onOpenChange = vi.fn();
    const h = harness([{ accessToken: 't1' }], { onOpenChange });
    const socket = await h.waitForSocket(1);

    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    try {
      socket.open();
      vi.advanceTimersByTime(HEARTBEAT_INTERVAL_MS);
      expect(socket.sent).toEqual([JSON.stringify({ type: 'ping' })]);

      // 답이 오면(pong이 아니어도 된다) 살아 있다 — 다음 주기에 다시 묻는다.
      socket.message('{"type":"pong"}');
      vi.advanceTimersByTime(HEARTBEAT_INTERVAL_MS);
      expect(socket.sent).toHaveLength(2);
      expect(socket.closedWith).toBeNull();

      // 이번엔 아무것도 오지 않았다 — close 이벤트를 기다리지 않고 끊김으로 본다.
      vi.advanceTimersByTime(HEARTBEAT_INTERVAL_MS);
      expect(socket.closedWith).toBe(1000);
      expect(onOpenChange).toHaveBeenLastCalledWith(false);
    } finally {
      vi.useRealTimers();
    }

    // 스스로 버린 소켓이라 정상 종료가 아니라 비정상 종료처럼 백오프 재연결한다.
    await h.waitForSocket(2);
    expect(h.sleep).toHaveBeenCalledExactlyOnceWith(1000);
    h.connection.close();
  });
});

describe('openWsConnection - [#188] 선제 토큰 갱신', () => {
  /** 만료 전 재시도(이슈 #382)는 실제 시간으로 약 650ms 뒤에 일어난다 — vi.waitFor 기본 한도
   * 1000ms는 부하가 큰 CI에서 빠듯하다. */
  const RETRY_WAIT = { timeout: 5_000 };

  /** 선제 갱신이 delayMs 뒤에 걸리도록 exp를 ms 단위로 정확히 역산한다. 공유 헬퍼
   * jwtWithTimes()는 초 단위로 내림해 최대 1초까지 오차가 생겨(이 테스트가 필요한 수백 ms
   * 지연에는 너무 크다), 여기서는 직접 ms로 계산한다. margin이 고정 하한(1000ms)에 걸리도록
   * 수명(lifespanMs)을 10초 미만으로 짧게 잡는다. */
  function jwtDueInMs(delayMs: number): string {
    const nowMs = Date.now();
    const lifespanMs = 6_000;
    const expiresAtMs = nowMs + 1_000 + delayMs;
    const issuedAtMs = expiresAtMs - lifespanMs;
    const payload = btoa(
      JSON.stringify({ iat: issuedAtMs / 1000, exp: expiresAtMs / 1000 }),
    );
    return `h.${payload}.s`;
  }

  it('만료 마진에 도달하면 새 소켓을 먼저 열어 옛 소켓과 겹친 뒤에야 닫는다', async () => {
    const healthy = jwtWithLife(300);
    const h = harness(
      [{ accessToken: jwtDueInMs(150) }, { accessToken: healthy }],
      { rooms: () => [THREAD_ID] },
    );

    const first = await h.waitForSocket(1);
    first.open();

    const second = await h.waitForSocket(2);
    // 새 소켓이 생긴 시점에 옛 소켓이 아직 안 닫혀 있어야 한다 — 이 겹침이 없으면
    // RoomSessionRegistry가 입장·퇴장을 각각 방송한다(ws-connection.ts의
    // connectAndProactivelyRefresh 설명 참고).
    expect(first.closedWith).toBeNull();
    expect(second.protocols).toEqual(['access_token', healthy]);

    second.open();
    // 열리기만 해서는 옛 소켓을 닫지 않는다 — 새 소켓의 구독이 아직 걸리기 전일 수 있다.
    expect(second.sent).toEqual([subscribeFrame(THREAD_ID)]);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(first.closedWith).toBeNull();

    // 서버가 구독을 마치고 그 방에 가장 먼저 보내는 프레임이 참여자 스냅샷이다.
    second.message(
      JSON.stringify({
        type: 'presence.snapshot',
        threadId: THREAD_ID,
        participants: [],
      }),
    );
    await vi.waitFor(() => expect(first.closedWith).toBe(1000));
    expect(h.sockets).toHaveLength(2);

    // 옛 소켓의 close 이벤트가 (비동기로) 뒤늦게 와도, 새 소켓이 이미 authoritative가 됐으므로
    // isOpen이 false로 되돌아가면 안 된다 — 한때 실제로 되돌아가던 회귀다.
    expect(h.connection.send('after-swap')).toBe(true);
    expect(second.sent).toEqual([subscribeFrame(THREAD_ID), 'after-swap']);

    h.connection.close();
  });

  it('갱신 시점에는 일반 조회가 아니라 서버에 갱신을 직접 요청한다(이슈 #382)', async () => {
    const healthy = jwtWithLife(300);
    const h = harness(
      [{ accessToken: jwtDueInMs(150) }, { accessToken: healthy }],
      { rooms: () => [THREAD_ID] },
    );

    const first = await h.waitForSocket(1);
    first.open();
    const second = await h.waitForSocket(2);

    // 처음 연결은 일반 조회 한 번, 갱신은 직접 요청 한 번이다.
    expect(h.getSession).toHaveBeenCalledTimes(1);
    expect(h.forceRefreshSession).toHaveBeenCalledTimes(1);
    expect(second.protocols).toEqual(['access_token', healthy]);

    h.connection.close();
  });

  it('갈아탄 새 소켓도 다음 만료 마진에서 다시 선제 갱신한다(이슈 #382)', async () => {
    // 전에는 갈아탄 뒤 새 소켓이 끊길 때까지 기다리기만 해, 두 번째 만료는 서버의 4000으로 끝났다.
    const second = jwtDueInMs(400);
    const third = jwtWithLife(300);
    const h = harness(
      [
        { accessToken: jwtDueInMs(150) },
        { accessToken: second },
        { accessToken: third },
      ],
      { rooms: () => [] },
    );

    const s1 = await h.waitForSocket(1);
    s1.open();
    const s2 = await h.waitForSocket(2);
    s2.open();
    await vi.waitFor(() => expect(s1.closedWith).toBe(1000));

    // 새 소켓(s2)의 토큰도 곧 만료 마진에 들어오므로, 끊기기 전에 한 번 더 갈아탄다.
    const s3 = await h.waitForSocket(3);
    expect(s3.protocols).toEqual(['access_token', third]);
    s3.open();
    await vi.waitFor(() => expect(s2.closedWith).toBe(1000));
    expect(h.forceRefreshSession).toHaveBeenCalledTimes(2);
    expect(h.getSession).toHaveBeenCalledTimes(1);

    h.connection.close();
  });

  it('직접 요청한 갱신이 옛 토큰 그대로면 만료 전에 한 번만 더 묻고 지금 연결을 유지한다(이슈 #382)', async () => {
    // 허용 창 밖(브라우저·서버 시계 차가 큰 경우)이라 서버가 갱신하지 않은 상황이다. #226의 200ms 뒤
    // 재시도는 없앴다 — 강제 갱신 뒤에는 "서버 갱신이 진행 중인 틈"이 없다. 다시 묻는 것은 남은
    // 시간의 절반 뒤 한 번뿐이다.
    const due = jwtDueInMs(150);
    const h = harness([{ accessToken: due }, { accessToken: due }]);

    const first = await h.waitForSocket(1);
    first.open();
    await vi.waitFor(
      () => expect(h.forceRefreshSession).toHaveBeenCalledTimes(2),
      RETRY_WAIT,
    );
    await new Promise((resolve) => setTimeout(resolve, 300));

    expect(h.forceRefreshSession).toHaveBeenCalledTimes(2);
    expect(h.sockets).toHaveLength(1);
    expect(first.closedWith).toBeNull();
    expect(h.connection.send('still-alive')).toBe(true);
    expect(h.signIn).not.toHaveBeenCalled();

    h.connection.close();
  });

  it.each([
    [
      '리프레시 실패(RefreshAccessTokenError)',
      { accessToken: 'old', error: 'RefreshAccessTokenError' as const },
    ],
    ['요청 실패(null)', null],
  ])(
    '직접 요청한 갱신이 %s면 재로그인하지 않고 지금 연결을 유지한다(이슈 #382)',
    async (_label, refreshed) => {
      const h = harness([{ accessToken: jwtDueInMs(150) }, refreshed]);

      const first = await h.waitForSocket(1);
      first.open();
      await vi.waitFor(() =>
        expect(h.forceRefreshSession).toHaveBeenCalledTimes(1),
      );
      await new Promise((resolve) => setTimeout(resolve, 50));

      expect(h.signIn).not.toHaveBeenCalled();
      expect(h.sockets).toHaveLength(1);
      expect(first.closedWith).toBeNull();
      expect(h.connection.send('still-alive')).toBe(true);

      h.connection.close();
    },
  );

  it('직접 요청한 갱신이 실패해도 만료 전에 한 번 더 시도해 성공하면 갈아탄다(이슈 #382)', async () => {
    // Keycloak 순간 끊김. 다시 시도하지 않으면 옛 토큰 만료에 서버가 4000으로 끊고, 방에 혼자면
    // 진행 중인 AI 턴이 취소된다.
    const healthy = jwtWithLife(300);
    const h = harness([
      { accessToken: jwtDueInMs(150) },
      null,
      { accessToken: healthy },
    ]);

    const first = await h.waitForSocket(1);
    first.open();
    const second = await vi.waitFor(() => {
      const socket = h.sockets[1];
      if (!socket) throw new Error('2번째 소켓이 아직 없다');
      return socket;
    }, RETRY_WAIT);
    expect(h.forceRefreshSession).toHaveBeenCalledTimes(2);
    expect(second.protocols).toEqual(['access_token', healthy]);
    second.open();

    await vi.waitFor(() => expect(first.closedWith).toBe(1000));
    expect(h.signIn).not.toHaveBeenCalled();

    h.connection.close();
  });

  it('직접 요청한 갱신에 토큰이 없으면(세션 없음) 재로그인한다(이슈 #382)', async () => {
    const h = harness([{ accessToken: jwtDueInMs(150) }, {}]);

    const first = await h.waitForSocket(1);
    first.open();

    await vi.waitFor(() => expect(h.signIn).toHaveBeenCalledWith('keycloak'));
  });

  it('갱신 실패로 연결을 유지했다가 서버가 만료로 끊으면, 재연결의 일반 조회가 실패를 보고 재로그인한다(이슈 #382)', async () => {
    const failed = {
      accessToken: 'old',
      error: 'RefreshAccessTokenError' as const,
    };
    const h = harness([{ accessToken: jwtDueInMs(150) }, failed, failed]);

    const first = await h.waitForSocket(1);
    first.open();
    await vi.waitFor(() =>
      expect(h.forceRefreshSession).toHaveBeenCalledTimes(1),
    );
    expect(h.signIn).not.toHaveBeenCalled();

    first.serverClose(4000);

    await vi.waitFor(() => expect(h.signIn).toHaveBeenCalledWith('keycloak'));
    expect(h.getSession).toHaveBeenCalledTimes(2);
  });

  it('구독한 방이 여럿이면 방마다 답(스냅샷이든 거부든)이 다 와야 옛 소켓을 닫는다', async () => {
    const h = harness(
      [{ accessToken: jwtDueInMs(150) }, { accessToken: jwtWithLife(300) }],
      { rooms: () => [THREAD_ID, OTHER_THREAD_ID] },
    );

    const first = await h.waitForSocket(1);
    first.open();
    const second = await h.waitForSocket(2);
    second.open();

    second.message(
      JSON.stringify({
        type: 'presence.snapshot',
        threadId: THREAD_ID,
        participants: [],
      }),
    );
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(first.closedWith).toBeNull();

    // 그 사이 참가자에서 빠진 방은 거부로 답한다 — 그 방에 대해서도 기다릴 것은 끝났다.
    second.message(
      JSON.stringify({
        type: 'error',
        threadId: OTHER_THREAD_ID,
        code: 'FORBIDDEN',
        message: 'x',
        traceId: 't',
      }),
    );
    await vi.waitFor(() => expect(first.closedWith).toBe(1000));

    h.connection.close();
  });

  it('presence가 없는 방(DIRECT)은 room.subscribed를 구독 답으로 읽어 갈아탄다(이슈 #382)', async () => {
    const h = harness(
      [{ accessToken: jwtDueInMs(150) }, { accessToken: jwtWithLife(300) }],
      { rooms: () => [THREAD_ID] },
    );

    const first = await h.waitForSocket(1);
    first.open();
    const second = await h.waitForSocket(2);
    second.open();

    second.message(
      JSON.stringify({ type: 'room.subscribed', threadId: THREAD_ID }),
    );
    await vi.waitFor(() => expect(first.closedWith).toBe(1000));
    expect(h.connection.send('after-swap')).toBe(true);
    expect(second.sent).toContain('after-swap');

    h.connection.close();
  });

  it('새 소켓이 열린 뒤 승격 전에 바뀐 방 구독을 승격하면서 새 소켓에 맞춘다(이슈 #382)', async () => {
    let rooms = [THREAD_ID];
    const h = harness(
      [{ accessToken: jwtDueInMs(150) }, { accessToken: jwtWithLife(300) }],
      { rooms: () => rooms },
    );

    const first = await h.waitForSocket(1);
    first.open();
    const second = await h.waitForSocket(2);
    second.open();
    expect(second.sent).toEqual([subscribeFrame(THREAD_ID)]);

    // 승격 전에 화면이 THREAD_ID를 닫고 OTHER_THREAD_ID를 연다 — 이 프레임은 아직 옛 소켓으로 간다.
    rooms = [OTHER_THREAD_ID];
    h.connection.send(
      JSON.stringify({ type: 'room.unsubscribe', threadId: THREAD_ID }),
    );
    h.connection.send(subscribeFrame(OTHER_THREAD_ID));
    expect(first.sent).toContain(subscribeFrame(OTHER_THREAD_ID));

    second.message(
      JSON.stringify({
        type: 'presence.snapshot',
        threadId: THREAD_ID,
        participants: [],
      }),
    );
    await vi.waitFor(() => expect(first.closedWith).toBe(1000));
    expect(second.sent).toEqual([
      subscribeFrame(THREAD_ID),
      subscribeFrame(OTHER_THREAD_ID),
      JSON.stringify({ type: 'room.unsubscribe', threadId: THREAD_ID }),
    ]);

    h.connection.close();
  });

  it('구독한 방이 없으면 새 소켓이 열리자마자 옛 소켓을 닫는다', async () => {
    const h = harness([
      { accessToken: jwtDueInMs(150) },
      { accessToken: jwtWithLife(300) },
    ]);

    const first = await h.waitForSocket(1);
    first.open();
    const second = await h.waitForSocket(2);
    second.open();

    await vi.waitFor(() => expect(first.closedWith).toBe(1000));
    h.connection.close();
  });

  it('새 소켓이 핸드셰이크에서 거부되면 기존 연결을 그대로 쓴다', async () => {
    // 두 번째 세션(갱신 결과로 오는 토큰)은 수명이 넉넉해야 한다 — jwtDueInMs처럼 또 근-만료면
    // freshToken()의 #181 근-만료 판정(SHORT_LIVED_TOKENS_BEFORE_REAUTH)에 연속으로 걸려,
    // 이 테스트가 검증하려는 것(핸드셰이크 거부 시 기존 연결 유지)과 무관하게 재로그인으로
    // 끝나 버린다.
    const h = harness([
      { accessToken: jwtDueInMs(150) },
      { accessToken: jwtWithLife(300) },
    ]);

    const first = await h.waitForSocket(1);
    first.open();

    const second = await h.waitForSocket(2);
    second.serverClose(1006);

    // 갱신 시도가 실패해도 기존 연결은 끊기지 않는다.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(first.closedWith).toBeNull();
    expect(h.connection.send('still-alive')).toBe(true);

    // 만료 전에 한 번 더 갱신해 새 소켓을 연다(이슈 #382).
    const third = await vi.waitFor(() => {
      const socket = h.sockets[2];
      if (!socket) throw new Error('3번째 소켓이 아직 없다');
      return socket;
    }, RETRY_WAIT);
    expect(h.forceRefreshSession).toHaveBeenCalledTimes(2);
    expect(third.protocols[1]).toBe(second.protocols[1]);

    h.connection.close();
  });

  it('새 소켓이 열렸지만 구독이 걸리기 전에 끊기면 옛 소켓을 닫지 않는다', async () => {
    // 'open'에서 옛 소켓을 닫던 때는 서버가 방에 등록하기 전에 방이 비어, 흐르던 AI 답변이
    // 취소되고 대기 턴이 버려졌다. 구독 답 없이 끝나는 새 소켓은 등록된 적이 없으니 더더욱 닫으면 안 된다.
    const onOpenChange = vi.fn();
    const h = harness(
      [{ accessToken: jwtDueInMs(150) }, { accessToken: jwtWithLife(300) }],
      { rooms: () => [THREAD_ID], onOpenChange },
    );

    const first = await h.waitForSocket(1);
    first.open();

    const second = await h.waitForSocket(2);
    second.open();
    // 커넥션 전역 오류(threadId 없음)는 방의 구독 답이 아니다.
    second.message(
      JSON.stringify({
        type: 'error',
        threadId: null,
        code: 'INTERNAL_ERROR',
        message: 'x',
        traceId: 't',
      }),
    );
    second.serverClose(1000);

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(first.closedWith).toBeNull();
    // 옛 소켓이 여전히 지금의 연결이다 — 새 소켓이 열렸다 끊겼다고 끊김으로 보이면 안 된다(이슈 #382).
    expect(h.connection.send('still-alive')).toBe(true);
    expect(first.sent).toContain('still-alive');
    expect(onOpenChange).not.toHaveBeenCalledWith(false);

    // 끊김으로 보지 않으니 만료 전 재시도도 그대로 일어난다.
    await vi.waitFor(() => {
      if (!h.sockets[2]) throw new Error('3번째 소켓이 아직 없다');
    }, RETRY_WAIT);

    h.connection.close();
  });

  it('새 소켓이 열렸지만 구독이 걸리기 전에 close()하면 옛 소켓도 함께 닫는다(이슈 #382)', async () => {
    const h = harness(
      [{ accessToken: jwtDueInMs(150) }, { accessToken: jwtWithLife(300) }],
      { rooms: () => [THREAD_ID] },
    );

    const first = await h.waitForSocket(1);
    first.open();
    const second = await h.waitForSocket(2);
    second.open();
    // 구독이 걸리기 전 send()는 옛 소켓으로 간다 — 서버에 방이 등록된 연결은 아직 옛 소켓이다.
    expect(h.connection.send('before-join')).toBe(true);
    expect(first.sent).toContain('before-join');

    h.connection.close();

    expect(second.closedWith).toBe(1000);
    expect(first.closedWith).toBe(1000);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(h.sockets).toHaveLength(2);
  });

  it('갱신 요청을 기다리는 사이 close()하면 새 소켓을 열지 않는다(이슈 #382)', async () => {
    let answer: ((session: unknown) => void) | undefined;
    const h = harness(
      [{ accessToken: jwtDueInMs(150) }],
      {},
      () =>
        new Promise((resolve) => {
          answer = resolve;
        }),
    );

    const first = await h.waitForSocket(1);
    first.open();
    await vi.waitFor(() => expect(answer).toBeDefined());

    h.connection.close();
    answer?.({ accessToken: jwtWithLife(300) });

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(h.sockets).toHaveLength(1);
    expect(first.closedWith).toBe(1000);
  });

  it('스왑이 진행 중일 때 close()를 부르면 새 소켓뿐 아니라 아직 살아있는 옛 소켓도 닫는다', async () => {
    // 두 번째 세션도 수명이 넉넉해야 한다 — 위 테스트와 같은 이유(#181 근-만료 판정 회피).
    const h = harness([
      { accessToken: jwtDueInMs(150) },
      { accessToken: jwtWithLife(300) },
    ]);

    const first = await h.waitForSocket(1);
    first.open();

    // 새 소켓이 생겼다는 건 스왑이 시작됐다는 뜻이다 — 아직 second.open()을 안 불렀으므로
    // 핸드셰이크 중이고, 이 시점의 진짜 연결은 여전히 first다.
    const second = await h.waitForSocket(2);
    expect(first.closedWith).toBeNull();

    h.connection.close();

    // 새 소켓(아직 안 열렸던 것)뿐 아니라, 실제로 살아있던 옛 소켓도 닫혀야 한다 — 하나만
    // 닫히면 다른 하나는 연결이 샌 채로 남는다(한때 first가 안 닫히던 회귀).
    expect(second.closedWith).toBe(1000);
    expect(first.closedWith).toBe(1000);
  });

  it('exp를 모르는 토큰(JWT 아님)은 선제 갱신을 시도하지 않는다', async () => {
    const h = harness([{ accessToken: 't1' }]);
    const first = await h.waitForSocket(1);
    first.open();

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(h.sockets).toHaveLength(1);

    h.connection.close();
  });
});

describe('requestForcedRefresh', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('next-auth 세션 update와 같은 POST를 보내고, 서버 jwt 콜백은 그 data를 갱신 요청으로 읽는다(이슈 #382)', async () => {
    const session = { accessToken: 'new', expires: '' };
    const fetchMock = vi.fn(async () => Response.json(session));
    vi.stubGlobal('fetch', fetchMock);

    await expect(requestForcedRefresh()).resolves.toEqual(session);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ];
    expect(url).toBe('/api/auth/session');
    expect(init.method).toBe('POST');
    const body = JSON.parse(init.body as string);
    expect(body.csrfToken).toBe('csrf-1');
    // 보내는 쪽과 auth.ts가 같은 판정을 쓴다 — 한쪽만 바뀌면 여기서 깨진다.
    expect(isForcedRefreshRequest('update', body.data)).toBe(true);
  });

  it('응답이 실패면 null을 돌려준다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 500 })),
    );
    await expect(requestForcedRefresh()).resolves.toBeNull();
  });

  it('네트워크 오류면 던지지 않고 null을 돌려준다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new TypeError('failed to fetch');
      }),
    );
    await expect(requestForcedRefresh()).resolves.toBeNull();
  });
});
