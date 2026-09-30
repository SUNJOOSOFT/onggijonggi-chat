import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./http', () => ({ authFetch: vi.fn() }));

import {
  fetchPermissionsOverview,
  importMembersCsv,
  saveAssignment,
} from './permissions';
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

  it('uses a generic message for unknown server errors', async () => {
    mockedAuthFetch.mockResolvedValue(
      Response.json(
        { error: { code: 'PRIVATE_FAILURE', message: 'database password' } },
        { status: 500 },
      ),
    );

    await expect(saveAssignment('person', 'team', 'S')).rejects.toThrow(
      '일시적인 오류가 발생했어요. 잠시 후 다시 시도해 주세요.',
    );
  });

  it('does not surface a non-envelope error body', async () => {
    mockedAuthFetch.mockResolvedValue(
      new Response('private stack trace', { status: 404 }),
    );

    await expect(importMembersCsv('email,team,rank', false)).rejects.toThrow(
      '일시적인 오류가 발생했어요. 잠시 후 다시 시도해 주세요.',
    );
  });
});
