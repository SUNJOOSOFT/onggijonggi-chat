import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/auth', () => ({ auth: vi.fn() }));

import { auth } from '@/auth';
import {
  fetchModelsForServer,
  fetchSessionMessagesForServer,
  fetchSessionsForServer,
} from './server-history';

const mockedAuth = vi.mocked(auth);

describe('authorizedGet의 네트워크 장애 처리', () => {
  beforeEach(() => {
    mockedAuth.mockReset();
    vi.stubGlobal('fetch', vi.fn());
  });

  it('BFF 연결이 거부되면(fetch 예외) 세션 목록 조회는 빈 배열로 끝난다', async () => {
    mockedAuth.mockResolvedValue({ accessToken: 'token' } as never);
    vi.mocked(fetch).mockRejectedValue(
      new TypeError('fetch failed: ConnectionRefused'),
    );

    await expect(fetchSessionsForServer()).resolves.toEqual([]);
  });

  it('BFF 연결이 거부되면 모델 목록 조회도 빈 배열로 끝난다', async () => {
    mockedAuth.mockResolvedValue({ accessToken: 'token' } as never);
    vi.mocked(fetch).mockRejectedValue(new TypeError('ConnectionRefused'));

    await expect(fetchModelsForServer()).resolves.toEqual([]);
  });

  it('BFF 연결이 거부되면 세션 메시지 조회도 빈 배열로 끝난다', async () => {
    mockedAuth.mockResolvedValue({ accessToken: 'token' } as never);
    vi.mocked(fetch).mockRejectedValue(new TypeError('ConnectionRefused'));

    await expect(
      fetchSessionMessagesForServer('11111111-1111-4111-8111-111111111111'),
    ).resolves.toEqual([]);
  });

  it('정상 응답이면 그대로 전달된다', async () => {
    mockedAuth.mockResolvedValue({ accessToken: 'token' } as never);
    vi.mocked(fetch).mockResolvedValue(Response.json([{ id: 's1' }]));

    await expect(fetchSessionsForServer()).resolves.toEqual([{ id: 's1' }]);
  });
});
