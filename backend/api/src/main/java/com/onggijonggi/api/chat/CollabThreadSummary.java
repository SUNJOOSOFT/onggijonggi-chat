package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Thr;
import java.util.List;
import java.util.UUID;

/**
 * Class Name : CollabThreadSummary.java
 * Description : GET /api/collab/threads 응답 항목. 필드 구성은 01·CLIENT의
 *               CollabThreadSummary(frontend/lib/api/collab.ts)와 같아야 한다.
 *
 *               participants는 방을 제목만으로 가려내기 어려워 화면이 함께 보여주는 표시 이름이다.
 *               app_user에 이름 컬럼을 두지 않고 Keycloak을 정본으로 삼아 요청 시점에 채운다(이슈 #128).
 *
 *               workspaceId·workspaceName은 방이 놓인 워크스페이스다. 화면이 목록을 워크스페이스별로 묶는 데 쓴다.
 *               워크스페이스 트리가 없는 배포의 방은 둘 다 null이다.
 */
public record CollabThreadSummary(
		UUID id,
		String title,
		List<String> participants,
		UUID workspaceId,
		String workspaceName
) {

	static CollabThreadSummary from(Thr thr, List<String> participants, String workspaceName) {
		return new CollabThreadSummary(thr.getId(), thr.getTitle(), participants, thr.getWorkspaceNodeId(),
				workspaceName);
	}

}
