package com.onggijonggi.api.authz;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Rank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : MemberImportService.java
 * Description : 03·CORE 팀·직급 배정 CSV(email,team,rank) 임포트. 기본은 미리보기이고 apply일 때만 저장한다.
 *               줄마다 검증하고(없는 팀·직급, 이메일 중복, Keycloak에 없는 이메일) 하나라도 틀리면 아무것도
 *               저장하지 않고 틀린 줄을 모두 알려준다. 저장은 배정 서비스(OrgUnitMemberService)를 한 번에 불러
 *               전부 아니면 전무다. CSV에 없는 사람의 배정은 지우지 않는다. 이메일은 사람이 쓰는 값이라 CSV에만 두고,
 *               저장은 Keycloak subject로 한다(이메일이 바뀌어도 배정이 끊기지 않게).
 *               이력의 행위자는 임포트를 부른 사람(USER)이고, 요청 ID는 CSV 본문의 해시다.
 */
@Service
public class MemberImportService {

	static final String HEADER = "email,team,rank";
	static final int MAX_ROWS = 5000;

	private final KeycloakAdminClient keycloak;
	private final OrgUnitRepository orgUnits;
	private final OrgUnitMemberRepository members;
	private final OrgUnitMemberService memberService;

	public MemberImportService(KeycloakAdminClient keycloak, OrgUnitRepository orgUnits, OrgUnitMemberRepository members,
			OrgUnitMemberService memberService) {
		this.keycloak = keycloak;
		this.orgUnits = orgUnits;
		this.members = members;
		this.memberService = memberService;
	}

	public record Row(int line, String email, String team, String rank, String outcome) {
	}

	public record Problem(int line, String message) {
	}

	public record Report(boolean applied, Map<String, Integer> counts, List<Row> rows, List<Problem> problems) {
	}

	/** 형식 검증을 통과한 한 줄. subject는 Keycloak 변환 뒤 줄 번호로 따로 찾는다. */
	private record Parsed(int line, String email, String team, String rankText, OrgUnit orgUnit, Rank rank) {
	}

	public Mono<Report> run(String csv, boolean apply, UUID actorUserId) {
		List<Problem> problems = new ArrayList<>();
		return Mono.fromCallable(() -> parse(csv, problems)).subscribeOn(Schedulers.boundedElastic())
				.flatMap(parsed -> resolveSubjects(parsed, problems).map(subjects -> Map.entry(parsed, subjects)))
				.flatMap(entry -> Mono.fromCallable(() -> finish(csv, apply, actorUserId, entry.getKey(), entry.getValue(), problems))
						.subscribeOn(Schedulers.boundedElastic()));
	}

	private List<Parsed> parse(String csv, List<Problem> problems) {
		String[] lines = (csv == null ? "" : csv).replace("﻿", "").split("\\r?\\n", -1);
		if (lines.length == 0 || !lines[0].trim().toLowerCase(Locale.ROOT).replace(" ", "").equals(HEADER)) {
			problems.add(new Problem(1, "첫 줄은 " + HEADER + "여야 한다"));
			return List.of();
		}
		Map<String, List<OrgUnit>> unitsByKey = new HashMap<>();
		for (OrgUnit unit : orgUnits.findAll()) unitsByKey.computeIfAbsent(unit.getKey(), key -> new ArrayList<>()).add(unit);
		Map<String, Integer> firstLineByEmail = new HashMap<>();
		List<Parsed> parsed = new ArrayList<>();
		for (int index = 1; index < lines.length; index++) {
			int line = index + 1;
			if (lines[index].isBlank()) continue;
			if (parsed.size() >= MAX_ROWS) {
				problems.add(new Problem(line, "한 번에 " + MAX_ROWS + "줄까지 넣을 수 있다"));
				break;
			}
			String[] cells = lines[index].split(",", -1);
			if (cells.length != 3) {
				problems.add(new Problem(line, "칸이 3개(email,team,rank)여야 한다"));
				continue;
			}
			String email = cells[0].trim().toLowerCase(Locale.ROOT);
			String team = cells[1].trim();
			String rankText = cells[2].trim().toUpperCase(Locale.ROOT);
			boolean valid = true;
			if (email.isEmpty() || !email.contains("@")) {
				problems.add(new Problem(line, "이메일이 올바르지 않다: " + cells[0].trim()));
				valid = false;
			} else if (firstLineByEmail.putIfAbsent(email, line) != null) {
				problems.add(new Problem(line, "같은 이메일이 " + firstLineByEmail.get(email) + "번째 줄에도 있다: " + email));
				valid = false;
			}
			OrgUnit unit = null;
			List<OrgUnit> candidates = unitsByKey.getOrDefault(team, List.of());
			if (candidates.isEmpty()) {
				problems.add(new Problem(line, "없는 팀이다: " + team));
				valid = false;
			} else if (candidates.size() > 1) {
				problems.add(new Problem(line, "같은 팀 코드가 여러 고객사에 있어 고를 수 없다: " + team));
				valid = false;
			} else if (candidates.get(0).getStatus() != OrgUnitStatus.ACTIVE) {
				problems.add(new Problem(line, "비활성 팀이다: " + team));
				valid = false;
			} else {
				unit = candidates.get(0);
			}
			Rank rank = null;
			try {
				rank = Rank.valueOf(rankText);
			} catch (IllegalArgumentException invalid) {
				problems.add(new Problem(line, "없는 직급이다(TL·B·C·K·D·S): " + cells[2].trim()));
				valid = false;
			}
			if (valid) parsed.add(new Parsed(line, email, team, rankText, unit, rank));
		}
		return parsed;
	}

	/** 이메일을 한 줄씩 Keycloak subject로 바꾼다. 없거나 여럿이면 그 줄의 문제로 남긴다. */
	private Mono<Map<Integer, String>> resolveSubjects(List<Parsed> parsed, List<Problem> problems) {
		return Flux.fromIterable(parsed)
				.concatMap(row -> keycloak.subjectsByEmail(row.email()).map(subjects -> {
					if (subjects.isEmpty()) {
						synchronized (problems) {
							problems.add(new Problem(row.line(), "Keycloak에 없는 이메일이다: " + row.email()));
						}
					} else if (subjects.size() > 1) {
						synchronized (problems) {
							problems.add(new Problem(row.line(), "이 이메일을 쓰는 계정이 여럿이다: " + row.email()));
						}
					}
					return Map.entry(row.line(), subjects.size() == 1 ? subjects.get(0) : "");
				}))
				.collectMap(Map.Entry::getKey, Map.Entry::getValue);
	}

	private Report finish(String csv, boolean apply, UUID actorUserId, List<Parsed> parsed, Map<Integer, String> subjects,
			List<Problem> problems) {
		List<Row> rows = new ArrayList<>();
		List<OrgUnitMemberService.Change> changes = new ArrayList<>();
		for (Parsed row : parsed) {
			String subject = subjects.get(row.line());
			if (subject == null || subject.isEmpty()) continue;
			changes.add(OrgUnitMemberService.Change.assign(subject, row.orgUnit().getId(), row.rank()));
			rows.add(new Row(row.line(), row.email(), row.team(), row.rankText(), preview(subject, row).name()));
		}
		problems.sort((left, right) -> Integer.compare(left.line(), right.line()));
		boolean applied = false;
		if (apply && problems.isEmpty() && !changes.isEmpty()) {
			List<OrgUnitMemberService.Result> results = memberService.applyAll(changes,
					OrgUnitMemberService.Actor.user(actorUserId, "import:" + sha256(csv).substring(0, 16)));
			List<Row> appliedRows = new ArrayList<>();
			for (int index = 0; index < rows.size(); index++) {
				Row row = rows.get(index);
				appliedRows.add(new Row(row.line(), row.email(), row.team(), row.rank(), results.get(index).outcome().name()));
			}
			rows = appliedRows;
			applied = true;
		}
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Row row : rows) counts.merge(row.outcome(), 1, Integer::sum);
		return new Report(applied, counts, List.copyOf(rows), List.copyOf(problems));
	}

	/** 저장하지 않고 결과만 본다. 배정 서비스의 판단과 같은 규칙이다. */
	private OrgUnitMemberService.Outcome preview(String subject, Parsed row) {
		Optional<OrgUnitMember> current = members.findBySubject(subject).stream().findFirst();
		if (current.isEmpty()) return OrgUnitMemberService.Outcome.ASSIGNED;
		OrgUnitMember member = current.get();
		return member.getOrgUnitId().equals(row.orgUnit().getId()) && member.getRank() == row.rank()
				? OrgUnitMemberService.Outcome.UNCHANGED : OrgUnitMemberService.Outcome.CHANGED;
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
