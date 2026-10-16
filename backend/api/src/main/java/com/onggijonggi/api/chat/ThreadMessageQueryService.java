package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.AthKind;
import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.MsgFile;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.user.AppUser;
import com.onggijonggi.common.user.AppUserRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : ThreadMessageQueryService.java
 * Description : Thread 이력 조회의 공용 부분 — `CollabThreadController`의 레거시
 *               `/api/collab/threads/{id}/messages`와 `ThreadController`의 공용
 *               `/api/threads/{id}/messages`가 인가 검사만 다르게 하고 이 뒤부터는 똑같은
 *               조회·표시 이름 해석 로직을 쓴다(이슈 #219). 인가는 호출자가 이미 끝내고
 *               `participant` boolean만 건넨다 — 이 서비스는 존재·종류를 몰라도 된다.
 */
@Service
public class ThreadMessageQueryService {

	/**
	* 표시 이름 조회를 한 번에 이만큼만 내보낸다(이슈 #200). flatMap의 기본 상한은 256이라, 캐시가
	* 비어 있을 때 메시지가 많으면 Keycloak Admin API로 한꺼번에 몰려 나간다.
	*/
	private static final int DISPLAY_NAME_LOOKUP_CONCURRENCY = 8;

	private final MsgRepository msgRepository;
	private final ThrMbrRepository thrMbrRepository;
	private final AppUserRepository appUserRepository;
	private final RankedDisplayNames rankedDisplayNames;
	private final MsgFileService msgFileService;
	private final ObjectMapper objectMapper;

	public ThreadMessageQueryService(MsgRepository msgRepository, ThrMbrRepository thrMbrRepository,
			AppUserRepository appUserRepository, RankedDisplayNames rankedDisplayNames,
			MsgFileService msgFileService, ObjectMapper objectMapper) {
		this.msgRepository = msgRepository;
		this.thrMbrRepository = thrMbrRepository;
		this.appUserRepository = appUserRepository;
		this.rankedDisplayNames = rankedDisplayNames;
		this.msgFileService = msgFileService;
		this.objectMapper = objectMapper;
	}

	/**
	* 참가자가 아니거나 존재하지 않는 스레드면 404 — 존재 여부를 노출하지 않는다.
	*
	* afterSeq를 주면 그 값보다 큰 seq만 돌려준다(이슈 #190). 방 진입 때는 생략해 전부 받고,
	* 재접속 때는 마지막으로 받은 seq를 넘겨 끊긴 동안의 것만 따라잡는다 — 방송은 그 순간 붙어
	* 있는 연결에만 가고 다시 틀어주지 않으므로, 그 구멍을 메우는 경로가 이것뿐이다.
	*/
	public Flux<MsgItem> listMessagesForParticipant(UUID threadId, Long afterSeq, boolean participant) {
		if (!participant) {
			return Flux.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
		}
		if (afterSeq != null && afterSeq < 0) {
			return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST));
		}
		return Mono.fromCallable(() -> afterSeq == null
						? msgRepository.findByThrIdOrderBySeqAsc(threadId)
						: msgRepository.findByThrIdAndSeqGreaterThanOrderBySeqAsc(threadId, afterSeq))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(this::withAuthorDisplayNames)
				.flatMapMany(Flux::fromIterable);
	}

	/**
	* HUMAN 메시지의 thrMbrId → userId → keycloakSubj를 한 번에 모아 조회하고, subject도 중복
	* 없이 조회한다(#128의 CollabThreadController.summariesFor와 같은 이유) — 같은 사람이 여러
	* 메시지를 썼다고 Keycloak Admin API를 그만큼 부르면 안 된다. AGENT·SYSTEM은 thrMbrId가 없어
	* 표시 이름도 null이다. 첨부도 메시지마다 묻지 않고 한 번에 읽는다.
	*/
	private Mono<List<MsgItem>> withAuthorDisplayNames(List<Msg> messages) {
		return Mono.fromCallable(() -> resolveThrMbrIdToSubject(messages))
				.zipWith(Mono.fromCallable(() -> msgFileService.byMessageBlocking(
						messages.stream().map(Msg::getId).toList())))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(lookups -> {
					Map<UUID, String> subjectByThrMbrId = lookups.getT1();
					Map<UUID, List<MsgFile>> filesByMsgId = lookups.getT2();
					Map<UUID, MembershipNotice> noticeByMsgId = membershipNotices(messages);
					Map<UUID, ApprovalNotice> approvalByMsgId = approvalNotices(messages);
					Set<String> subjects = new HashSet<>(subjectByThrMbrId.values());
					noticeByMsgId.values().forEach(notice -> subjects.add(notice.subject()));
					approvalByMsgId.values().forEach(notice -> {
						subjects.add(notice.requesterSubject());
						subjects.add(notice.targetSubject());
					});
					return Flux.fromIterable(subjects)
							.flatMap(subject -> rankedDisplayNames.displayName(subject)
									.map(displayName -> Map.entry(subject, displayName.orElse(subject))),
									DISPLAY_NAME_LOOKUP_CONCURRENCY)
							.collectMap(Map.Entry::getKey, Map.Entry::getValue)
							.map(displayNameBySubject -> messages.stream()
									.map(msg -> MsgItem.from(msg, subjectOf(msg, subjectByThrMbrId),
											displayNameFor(msg, subjectByThrMbrId, displayNameBySubject),
											filesByMsgId.getOrDefault(msg.getId(), List.of()).stream()
													.map(MsgFileView::from).toList(),
											membershipView(noticeByMsgId.get(msg.getId()), displayNameBySubject),
											approvalView(approvalByMsgId.get(msg.getId()), displayNameBySubject),
											objectMapper))
									.toList());
				});
	}

	/** SYSTEM 메시지 중 입퇴장 줄만 골라 msgId로 묶는다. */
	private Map<UUID, MembershipNotice> membershipNotices(List<Msg> messages) {
		Map<UUID, MembershipNotice> notices = new HashMap<>();
		for (Msg msg : messages) {
			if (msg.getAthKind() != AthKind.SYSTEM) {
				continue;
			}
			MembershipNotice notice = MembershipNotice.fromPylJson(objectMapper, msg.getPylJson());
			if (notice != null) {
				notices.put(msg.getId(), notice);
			}
		}
		return notices;
	}

	/** SYSTEM 메시지 중 승인 요청·처리 줄만 골라 msgId로 묶는다. */
	private Map<UUID, ApprovalNotice> approvalNotices(List<Msg> messages) {
		Map<UUID, ApprovalNotice> notices = new HashMap<>();
		for (Msg msg : messages) {
			if (msg.getAthKind() != AthKind.SYSTEM) {
				continue;
			}
			ApprovalNotice notice = ApprovalNotice.fromPylJson(objectMapper, msg.getPylJson());
			if (notice != null) {
				notices.put(msg.getId(), notice);
			}
		}
		return notices;
	}

	private static MsgItem.Approval approvalView(ApprovalNotice notice, Map<String, String> displayNameBySubject) {
		return notice == null ? null : new MsgItem.Approval(notice.event().name(), notice.approvalId(),
				notice.requesterSubject(), displayNameBySubject.getOrDefault(notice.requesterSubject(), notice.requesterSubject()),
				notice.targetSubject(), displayNameBySubject.getOrDefault(notice.targetSubject(), notice.targetSubject()));
	}

	private static MsgItem.Membership membershipView(MembershipNotice notice, Map<String, String> displayNameBySubject) {
		return notice == null ? null : new MsgItem.Membership(notice.event().name(), notice.subject(),
				displayNameBySubject.getOrDefault(notice.subject(), notice.subject()));
	}

	/** thrMbrId가 없는(AGENT·SYSTEM) 메시지는 subject도 없어 여기서 null로 끝난다. */
	private String displayNameFor(Msg msg, Map<UUID, String> subjectByThrMbrId, Map<String, String> displayNameBySubject) {
		String subject = subjectOf(msg, subjectByThrMbrId);
		return subject == null ? null : displayNameBySubject.get(subject);
	}

	/**
	* 작성자의 subject. AGENT·SYSTEM은 thrMbrId가 null이라 맵을 찾지 않고 null이다 — 사람 메시지가 없으면
	* resolveThrMbrIdToSubject가 불변 맵(Map.of())을 돌려주는데, 불변 맵의 get(null)은 NPE라 재접속
	* 따라잡기가 AI·시스템 메시지만 받을 때 500이 났다(이슈 #381).
	*/
	private static String subjectOf(Msg msg, Map<UUID, String> subjectByThrMbrId) {
		return msg.getThrMbrId() == null ? null : subjectByThrMbrId.get(msg.getThrMbrId());
	}

	/** thrMbrId → 그 참가자의 Keycloak subject. thr_mbr을 거쳐 app_user까지 두 번 조회한다. */
	private Map<UUID, String> resolveThrMbrIdToSubject(List<Msg> messages) {
		List<UUID> thrMbrIds = messages.stream().map(Msg::getThrMbrId).filter(Objects::nonNull).distinct()
				.toList();
		if (thrMbrIds.isEmpty()) {
			return Map.of();
		}
		List<ThrMbr> thrMbrs = thrMbrRepository.findAllById(thrMbrIds);
		Map<UUID, UUID> userIdByThrMbrId = thrMbrs.stream()
				.collect(Collectors.toMap(ThrMbr::getId, ThrMbr::getUserId));
		List<UUID> userIds = List.copyOf(Set.copyOf(userIdByThrMbrId.values()));
		Map<UUID, String> subjectByUserId = appUserRepository.findAllById(userIds).stream()
				.collect(Collectors.toMap(AppUser::getId, AppUser::getKeycloakSubj));
		Map<UUID, String> subjectByThrMbrId = new HashMap<>();
		userIdByThrMbrId.forEach((thrMbrId, userId) -> {
			String subject = subjectByUserId.get(userId);
			if (subject != null) {
				subjectByThrMbrId.put(thrMbrId, subject);
			}
		});
		return subjectByThrMbrId;
	}

}
