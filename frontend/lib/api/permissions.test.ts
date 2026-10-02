import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./http', () => ({ authFetch: vi.fn() }));

import { fetchPermissionsOverview } from './permissions';
import { authFetch } from './http';

const mockedAuthFetch = vi.mocked(authFetch);

describe('permissions API errors', () => {
  beforeEach(() => {
    mockedAuthFetch.mockReset();
  });

  it('maps a forbidden response without exposing the server message', async () => {
    mockedAuthFetch.mockResolvedValue(
      Response.json(
        {
          error: {
            code: 'FORBIDDEN',
            message: 'internal authorization details',
            traceId: 'trace-123',
          },
        },
        { status: 403 },
      ),
    );

    await expect(fetchPermissionsOverview()).rejects.toThrow(
      '이 작업을 수행할 권한이 없어요. (추적 ID: trace-123)',
    );
  });

  it('tells the operator to fix the Keycloak admin client instead of retrying', async () => {
    mockedAuthFetch.mockResolvedValue(
      Response.json(
        {
          error: {
            code: 'KEYCLOAK_ADMIN_UNAVAILABLE',
            message:
              'TOKEN_REJECTED 401 /realms/app-realm/protocol/openid-connect/token',
          },
        },
        { status: 503 },
      ),
    );

    await expect(fetchPermissionsOverview()).rejects.toThrow(
      '지금은 사용자 정보를 불러올 수 없어요. 서버의 Keycloak 관리 연결 설정을 확인해야 하니 관리자에게 문의해 주세요.',
    );
  });

  it('uses a generic message for unknown server errors', async () => {
    mockedAuthFetch.mockResolvedValue(
      Response.json(
        { error: { code: 'PRIVATE_FAILURE', message: 'database password' } },
        { status: 500 },
      ),
    );

    await expect(fetchPermissionsOverview()).rejects.toThrow(
      '일시적인 오류가 발생했어요. 잠시 후 다시 시도해 주세요.',
    );
  });

  it('does not surface a non-envelope error body', async () => {
    mockedAuthFetch.mockResolvedValue(
      new Response('private stack trace', { status: 404 }),
    );

    await expect(fetchPermissionsOverview()).rejects.toThrow(
      '일시적인 오류가 발생했어요. 잠시 후 다시 시도해 주세요.',
    );
  });
});
