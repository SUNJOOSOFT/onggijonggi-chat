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
 */
public record CollabThreadSummary(
		UUID id,
		String title,
		List<String> participants
) {

	static CollabThreadSummary from(Thr thr, List<String> participants) {
		return new CollabThreadSummary(thr.getId(), thr.getTitle(), participants);
	}

}
