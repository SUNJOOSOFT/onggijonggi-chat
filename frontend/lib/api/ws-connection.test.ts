import { describe, expect, it, vi } from 'vitest';
import {
  CLOSE_TOKEN_EXPIRED,
  type SocketLike,
  type WsConnection,
  type WsConnectionOptions,
  openWsConnection,
  reconnectBackoffMs,
} from './ws-connection';

const THREAD_ID = '11111111-1111-4111-8111-111111111111';

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
): Harness {
  const sockets: FakeSocket[] = [];
  // 준비한 세션을 순서대로 하나씩 내주고, 다 떨어지면 마지막 것을 계속 돌려준다.
  let call = 0;
  const getSession = vi.fn(async () => {
    const session = sessions[Math.min(call, sessions.length - 1)];
    call += 1;
    return session;
  });
  const signIn = vi.fn(async () => undefined);
  const sleep = vi.fn(async () => undefined);
  const onMessage = vi.fn();

  const connection = openWsConnection(
    THREAD_ID,
    { onMessage, ...extraOptions },
    {
      createSocket: (protocols, path) => {
        const socket = new FakeSocket(protocols, path);
        sockets.push(socket);
        return socket;
      },
      getSession: getSession as never,
      signIn: signIn as never,
      sleep,
    },
  );

  return {
    sockets,
    getSession,
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
      THREAD_ID,
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
  it('threadId를 경로 세그먼트로 넣어 방에 붙는다', async () => {
    const h = harness([{ accessToken: 't1' }]);
    const socket = await h.waitForSocket(1);
    expect(socket.path).toBe(`/api/ws/${THREAD_ID}`);
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

describe('openWsConnection - [#188] 선제 토큰 갱신', () => {
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
    const h = harness([{ accessToken: jwtDueInMs(150) }, { accessToken: healthy }]);

    const first = await h.waitForSocket(1);
    first.open();

    const second = await h.waitForSocket(2);
    // 새 소켓이 생긴 시점에 옛 소켓이 아직 안 닫혀 있어야 한다 — 이 겹침이 없으면
    // RoomSessionRegistry가 입장·퇴장을 각각 방송한다(ws-connection.ts의
    // connectAndProactivelyRefresh 설명 참고).
    expect(first.closedWith).toBeNull();
    expect(second.protocols).toEqual(['access_token', healthy]);

    second.open();
    await vi.waitFor(() => expect(first.closedWith).toBe(1000));
    expect(h.sockets).toHaveLength(2);

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

    h.connection.close();
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
