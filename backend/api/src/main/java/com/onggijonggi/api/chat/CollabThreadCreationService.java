package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Name : CollabThreadCreationService.java
 * Description : 03·CORE 협업방 생성의 원자 단위다. thr와 최초 ACTIVE OWNER 참가자 행을 함께
 *               저장해 방만 있고 입장 권한이 없는 상태를 만들지 않는다.
 */
@Service
public class CollabThreadCreationService {

	private final ThrRepository thrRepository;
	private final ThrMbrRepository thrMbrRepository;

	public CollabThreadCreationService(ThrRepository thrRepository, ThrMbrRepository thrMbrRepository) {
		this.thrRepository = thrRepository;
		this.thrMbrRepository = thrMbrRepository;
	}

	/** 호출자는 WebFlux 이벤트 루프 밖에서 이 blocking JPA 트랜잭션을 실행한다. */
	@Transactional
	public UUID createBlocking(UUID actorUserId, String title) {
		Thr thread = thrRepository.save(Thr.collab(actorUserId, title));
		thrMbrRepository.save(new ThrMbr(thread.getId(), actorUserId, ThrMbrRole.OWNER, actorUserId));
		return thread.getId();
	}
}
