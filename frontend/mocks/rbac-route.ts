import { isMockMode } from '@/lib/api/config';
import {
  type MockPersona,
  handleRbacMock,
  sharedRbacMockState,
} from './rbac-management';

/** 실 BFF가 설정된 배포에서는 어떤 관리 목업도 실행하지 않는다. persona는 서버 전용 개발 설정이다. */
export async function mockRbacRequest(request: Request) {
  if (!isMockMode()) return new Response('Mock disabled', { status: 404 });
  const configured = process.env.RBAC_MOCK_PERSONA ?? 'both';
  const persona: MockPersona = [
    'user',
    'workspace',
    'platform',
    'both',
  ].includes(configured)
    ? (configured as MockPersona)
    : 'user';
  return handleRbacMock(request, sharedRbacMockState(), persona);
}
