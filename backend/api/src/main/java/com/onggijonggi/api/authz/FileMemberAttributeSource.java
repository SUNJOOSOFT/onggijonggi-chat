package com.onggijonggi.api.authz;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Rank;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Class Name : FileMemberAttributeSource.java
 * Description : 03·CORE 전달받은 속성 파일(app.rbac.members-path, CSV username,team,rank)을 읽는다. 부를 때마다 파일을
 *               다시 읽으므로 파일을 고치고 casbin(또는 bff)을 재시작하면 반영된다.
 *               줄마다 검증하고(칸 수, 아이디 중복, 없는·여러 Tenant에 있는·비활성 팀, 없는 직급) 하나라도 틀리면 전체를
 *               거부한다. 아이디는 Keycloak 계정 전체 목록에서 username이 정확히 같은 계정의 subject로 바꾼다(매핑).
 *               Keycloak에 없거나 꺼진 계정의 줄은 건너뛴다 — 로그인할 수 없는 사람이라 권한이 샐 일이 없고, 계정을
 *               나중에 만드는 설치 순서에서도 다른 사람의 적재를 막지 않는다. 아이디(username)는 사용자가 바꿀 수 없어
 *               이메일처럼 남이 비운 값을 가져가 권한을 얻는 경로가 없다. 경로가 비어 있으면 빈 목록이다.
 */
@Component
public class FileMemberAttributeSource implements MemberAttributeSource {

	private static final Logger log = LoggerFactory.getLogger(FileMemberAttributeSource.class);

	static final String HEADER = "username,team,rank";
	static final int MAX_ROWS = 5000;
	private static final Duration KEYCLOAK_TIMEOUT = Duration.ofSeconds(10);

	private final String path;
	private final OrgUnitRepository orgUnits;
	private final KeycloakAdminClient keycloak;

	public FileMemberAttributeSource(@Value("${app.rbac.members-path:}") String path, OrgUnitRepository orgUnits,
			KeycloakAdminClient keycloak) {
		this.path = path;
		this.orgUnits = orgUnits;
		this.keycloak = keycloak;
	}

	/** 형식 검증을 통과한 한 줄. */
	private record Parsed(String username, OrgUnit orgUnit, Rank rank) {
	}

	@Override
	public Load load() {
		if (path.isBlank()) return new Load(List.of(), List.of(), "");
		Path file = Path.of(path);
		if (!Files.exists(file)) {
			log.warn("사람 속성 파일이 없다 — 아무도 팀·직급이 없다: {}", path);
			return new Load(List.of(), List.of(), "");
		}
		byte[] content;
		try {
			content = Files.readAllBytes(file);
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
		List<String> problems = new ArrayList<>();
		List<Parsed> parsed = parse(new String(content, StandardCharsets.UTF_8), problems);
		if (!problems.isEmpty()) throw new MemberAttributeSourceException(problems);

		Map<String, KeycloakAdminClient.KeycloakUser> accounts = new HashMap<>();
		for (KeycloakAdminClient.KeycloakUser user : keycloak.users().block(KEYCLOAK_TIMEOUT)) {
			if (user.username() != null) accounts.put(user.username().toLowerCase(Locale.ROOT), user);
		}
		List<MemberAttribute> members = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		for (Parsed row : parsed) {
			KeycloakAdminClient.KeycloakUser account = accounts.get(row.username());
			if (account == null || !Boolean.TRUE.equals(account.enabled())) {
				skipped.add(row.username());
				continue;
			}
			members.add(new MemberAttribute(account.id(), row.orgUnit().getTenantId(), row.orgUnit().getId(), row.rank()));
		}
		if (!skipped.isEmpty()) log.warn("Keycloak에 없거나 꺼진 계정이라 건너뛴 아이디: {}", skipped);
		return new Load(List.copyOf(members), List.copyOf(skipped), fingerprint(content));
	}

	private List<Parsed> parse(String csv, List<String> problems) {
		String[] lines = csv.replace("﻿", "").split("\\r?\\n", -1);
		if (lines.length == 0 || !lines[0].trim().toLowerCase(Locale.ROOT).replace(" ", "").equals(HEADER)) {
			problems.add("1번째 줄: 첫 줄은 " + HEADER + "여야 한다");
			return List.of();
		}
		Map<String, List<OrgUnit>> unitsByKey = new HashMap<>();
		for (OrgUnit unit : orgUnits.findAll()) unitsByKey.computeIfAbsent(unit.getKey(), key -> new ArrayList<>()).add(unit);
		Map<String, Integer> firstLineByUsername = new HashMap<>();
		List<Parsed> parsed = new ArrayList<>();
		for (int index = 1; index < lines.length; index++) {
			int line = index + 1;
			if (lines[index].isBlank()) continue;
			if (parsed.size() >= MAX_ROWS) {
				problems.add(line + "번째 줄: " + MAX_ROWS + "줄까지 넣을 수 있다");
				break;
			}
			String[] cells = lines[index].split(",", -1);
			if (cells.length != 3) {
				problems.add(line + "번째 줄: 칸이 3개(" + HEADER + ")여야 한다");
				continue;
			}
			String username = cells[0].trim().toLowerCase(Locale.ROOT);
			String team = cells[1].trim();
			String rankText = cells[2].trim().toUpperCase(Locale.ROOT);
			boolean valid = true;
			if (username.isEmpty()) {
				problems.add(line + "번째 줄: 아이디가 비어 있다");
				valid = false;
			} else if (firstLineByUsername.putIfAbsent(username, line) != null) {
				problems.add(line + "번째 줄: 같은 아이디가 " + firstLineByUsername.get(username) + "번째 줄에도 있다: " + username);
				valid = false;
			}
			OrgUnit unit = null;
			List<OrgUnit> candidates = unitsByKey.getOrDefault(team, List.of());
			if (candidates.isEmpty()) {
				problems.add(line + "번째 줄: 없는 팀이다: " + team);
				valid = false;
			} else if (candidates.size() > 1) {
				problems.add(line + "번째 줄: 같은 팀 코드가 여러 고객사에 있어 고를 수 없다: " + team);
				valid = false;
			} else if (candidates.get(0).getStatus() != OrgUnitStatus.ACTIVE) {
				problems.add(line + "번째 줄: 비활성 팀이다: " + team);
				valid = false;
			} else {
				unit = candidates.get(0);
			}
			Rank rank = null;
			try {
				rank = Rank.valueOf(rankText);
			} catch (IllegalArgumentException invalid) {
				problems.add(line + "번째 줄: 없는 직급이다(TL·B·C·K·D·S): " + cells[2].trim());
				valid = false;
			}
			if (valid) parsed.add(new Parsed(username, unit, rank));
		}
		return parsed;
	}

	private static String fingerprint(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)).substring(0, 12);
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
