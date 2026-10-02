/** 실 BFF 설정 시 비활성화되는 방 문서 개발 목업이다. */
import { isMockMode } from '@/lib/api/config';
import { sharedThreadDocumentsMock } from '@/mocks/thread-documents';

export const runtime = 'nodejs';
async function handle(
  request: Request,
  context: { params: Promise<{ segments: string[] }> },
) {
  if (!isMockMode()) return new Response(null, { status: 404 });
  try {
    return await sharedThreadDocumentsMock().handle(
      request,
      (await context.params).segments,
    );
  } catch {
    return new Response(null, { status: 400 });
  }
}
export { handle as GET, handle as POST, handle as PUT, handle as DELETE };
