package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : RbacBootstrapConfigReaderTest.java
 * Description : bootstrap YAML 읽기를 실제 파일로 검증한다. 형식 오류는 DB를 건드리기 전에 설정 예외로 멈춰야 하고,
 *               지문(cnf_fgpt)은 의미가 같은 설정에서만 같아야 한다(공백·주석·순서는 무관, 값이 바뀌면 다름).
 */
class RbacBootstrapConfigReaderTest {

	private static final String VALID = """
			reconcile:
			  enabled: true
			  dpl_id: "2026-09-21-01"
			tenants:
			  - tnn_key: acme
			    name: ACME
			    org_units:
			      - key: sales
			        name: 영업본부
			      - key: ops
			        name: 운영본부
			        status: INACTIVE
			    nodes:
			      - node_key: sales-hq
			        kind: ORG
			        parent: root
			        name: 영업본부
			      - node_key: team
			        kind: WORK
			        parent: sales-hq
			        name: 영업팀
			    grants:
			      - org_unit: sales
			        role: VIEWER
			        node: common
			      - org_unit: sales
			        role: ADMIN
			        node: sales-hq
			""";

	@TempDir
	Path directory;

	private Path write(String content) throws IOException {
		Path file = directory.resolve("rbac.yml");
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	private RbacBootstrapConfigReader readerFor(Path file) {
		return new RbacBootstrapConfigReader(file.toString(), new JsonMapper());
	}

	private RbacBootstrapConfigReader.LoadedBootstrapSpec load(String yaml) throws IOException {
		return readerFor(write(yaml)).loadWithFingerprint().orElseThrow();
	}

	private void assertRejected(String yaml, String fragment) throws IOException {
		RbacBootstrapConfigReader reader = readerFor(write(yaml));
		assertThatThrownBy(reader::loadWithFingerprint).isInstanceOf(RbacBootstrapConfigurationException.class)
				.satisfies(error -> assertThat(String.join(" | ", ((RbacBootstrapConfigurationException) error).getProblems()))
						.contains(fragment));
	}

	// ------------------------------------------------------------------ 설정이 없는 경우

	@Test
	void aBlankPathMeansNoConfigurationAndDoesNothing() {
		assertThat(new RbacBootstrapConfigReader("", new JsonMapper()).loadWithFingerprint()).isEmpty();
		assertThat(new RbacBootstrapConfigReader("   ", new JsonMapper()).loadWithFingerprint()).isEmpty();
		assertThat(new RbacBootstrapConfigReader(null, new JsonMapper()).loadWithFingerprint()).isEmpty();
	}

	@Test
	void anEmptyOrCommentOnlyFileMeansNoConfiguration() throws IOException {
		assertThat(readerFor(write("")).loadWithFingerprint()).isEmpty();
		assertThat(readerFor(write("# 아직 선언 없음\n")).loadWithFingerprint()).isEmpty();
	}

	@Test
	void aConfiguredPathThatIsNotAFileFailsLoudlyInsteadOfSkipping() {
		// 경로를 줬는데 파일이 없으면 "설정 없음"으로 넘어가지 않는다 — 마운트 실수가 조용히 묻히면 안 된다.
		assertThatThrownBy(() -> readerFor(directory.resolve("missing.yml")).loadWithFingerprint())
				.isInstanceOf(RbacBootstrapConfigurationException.class);
		assertThatThrownBy(() -> readerFor(directory).loadWithFingerprint())
				.isInstanceOf(RbacBootstrapConfigurationException.class);
	}

	// ------------------------------------------------------------------ 해석

	@Test
	void parsesAllSectionsAndAppliesDefaults() throws IOException {
		RbacBootstrapSpec spec = load(VALID).spec();

		assertThat(spec.reconcile().enabled()).isTrue();
		assertThat(spec.reconcile().deploymentId()).isEqualTo("2026-09-21-01");
		assertThat(spec.reconcile().applies()).isTrue();
		assertThat(spec.tenants()).hasSize(1);
		RbacBootstrapSpec.TenantSpec tenant = spec.tenants().get(0);
		assertThat(tenant.key()).isEqualTo("acme");
		assertThat(tenant.status()).as("status를 생략하면 ACTIVE").isEqualTo("ACTIVE");
		assertThat(tenant.orgUnits()).extracting(RbacBootstrapSpec.OrgUnitSpec::key, RbacBootstrapSpec.OrgUnitSpec::status)
				.containsExactly(org.assertj.core.groups.Tuple.tuple("sales", "ACTIVE"),
						org.assertj.core.groups.Tuple.tuple("ops", "INACTIVE"));
		assertThat(tenant.nodes()).extracting(RbacBootstrapSpec.NodeSpec::key, RbacBootstrapSpec.NodeSpec::parent)
				.containsExactly(org.assertj.core.groups.Tuple.tuple("sales-hq", "root"),
						org.assertj.core.groups.Tuple.tuple("team", "sales-hq"));
		assertThat(tenant.grants()).hasSize(2);
	}

	@Test
	void reconcileIsOffWhenTheBlockIsMissingOrIncomplete() throws IOException {
		String tenantsOnly = "tenants:\n  - tnn_key: acme\n    name: ACME\n";
		RbacBootstrapSpec none = load(tenantsOnly).spec();
		assertThat(none.reconcile().enabled()).isFalse();
		assertThat(none.reconcile().applies()).isFalse();

		// enabled만 있고 배포 ID가 없으면 적용하지 않는다(drift 판정만).
		RbacBootstrapSpec noId = load("reconcile:\n  enabled: true\n" + tenantsOnly).spec();
		assertThat(noId.reconcile().enabled()).isTrue();
		assertThat(noId.reconcile().applies()).isFalse();

		// 배포 ID만 있고 enabled가 없으면 적용하지 않는다.
		RbacBootstrapSpec noEnabled = load("reconcile:\n  dpl_id: \"x\"\n" + tenantsOnly).spec();
		assertThat(noEnabled.reconcile().applies()).isFalse();
	}

	// ------------------------------------------------------------------ 거부

	@Test
	void unknownKeysAreRejectedAtEveryLevelBecauseTheyAreUsuallyTypos() throws IOException {
		assertRejected("tenant:\n  - tnn_key: acme\n    name: ACME\n", "알 수 없는 키가 있다: tenant");
		assertRejected("reconcile:\n  enable: true\ntenants:\n  - tnn_key: acme\n    name: ACME\n", "알 수 없는 키가 있다: enable");
		assertRejected("tenants:\n  - tnn_key: acme\n    name: ACME\n    orgunits: []\n", "알 수 없는 키가 있다: orgunits");
		assertRejected("tenants:\n  - tnn_key: acme\n    name: ACME\n    org_units:\n      - key: a\n        name: A\n        color: red\n",
				"알 수 없는 키가 있다: color");
		assertRejected(
				"tenants:\n  - tnn_key: acme\n    name: ACME\n    nodes:\n      - node_key: a\n        kind: ORG\n        parent: root\n        name: A\n        owner: x\n",
				"알 수 없는 키가 있다: owner");
		assertRejected("tenants:\n  - tnn_key: acme\n    name: ACME\n    grants:\n      - org_unit: a\n        role: ADMIN\n        node: n\n        deny: true\n",
				"알 수 없는 키가 있다: deny");
	}

	@Test
	void requiredStringsMustBePresentNonBlankAndActuallyStrings() throws IOException {
		assertRejected("tenants:\n  - name: ACME\n", "tenants[].tnn_key");
		assertRejected("tenants:\n  - tnn_key: acme\n", "tenant acme.name");
		assertRejected("tenants:\n  - tnn_key: acme\n    name: \"  \"\n", "tenant acme.name");
		// 따옴표 없는 숫자는 YAML이 숫자로 읽는다 — 키가 조용히 바뀌지 않게 문자열이 아니면 거부한다.
		assertRejected("tenants:\n  - tnn_key: 2024\n    name: ACME\n", "tenants[].tnn_key");
	}

	@Test
	void structuralTypesAreChecked() throws IOException {
		assertRejected("tenants: acme\n", "tenants는 목록이어야 한다");
		assertRejected("tenants:\n  - just-a-string\n", "tenants[]는 객체여야 한다");
		assertRejected("reconcile: yes-please\ntenants:\n  - tnn_key: acme\n    name: ACME\n", "reconcile는 객체여야 한다");
		assertRejected("reconcile:\n  enabled: \"true\"\ntenants:\n  - tnn_key: acme\n    name: ACME\n",
				"reconcile.enabled는 true 또는 false여야 한다");
	}

	@Test
	void deploymentIdLongerThan128CharactersIsRejected() throws IOException {
		String longId = "d".repeat(129);
		assertRejected("reconcile:\n  enabled: true\n  dpl_id: \"" + longId + "\"\ntenants:\n  - tnn_key: acme\n    name: ACME\n",
				"reconcile.dpl_id는 128자 이하여야 한다");
		String maxId = "d".repeat(128);
		assertThat(load("reconcile:\n  enabled: true\n  dpl_id: \"" + maxId + "\"\ntenants:\n  - tnn_key: acme\n    name: ACME\n")
				.spec().reconcile().deploymentId()).hasSize(128);
	}

	@Test
	void malformedYamlIsReportedAsAConfigurationError() throws IOException {
		assertRejected("tenants:\n  - tnn_key: [unclosed\n", "해석할 수 없다");
	}

	@Test
	void filesOverOneMebibyteAreRejectedBeforeReading() throws IOException {
		String padding = "# " + "x".repeat(1024) + "\n";
		StringBuilder big = new StringBuilder();
		while (big.length() <= 1024 * 1024) big.append(padding);
		assertRejected(big.toString(), "바이트를 넘는다");
	}

	// ------------------------------------------------------------------ 지문

	@Test
	void rankGrantsAreParsedWithAnOptionalTeam() throws IOException {
		RbacBootstrapSpec.TenantSpec tenant = load(VALID + "    rank_grants:\n"
				+ "      - { org_unit: sales, rank: K, node: team }\n"
				+ "      - { rank: TL, node: team }\n").spec().tenants().get(0);

		assertThat(tenant.rankGrants()).containsExactly(new RbacBootstrapSpec.RankGrantSpec("sales", "K", "team"),
				new RbacBootstrapSpec.RankGrantSpec(null, "TL", "team"));
		assertRejected(VALID + "    rank_grants:\n      - { rank: K, node: team, role: ADMIN }\n", "알 수 없는 키");
	}

	@Test
	void aConfigurationWithoutRankGrantsKeepsItsFingerprint() throws IOException {
		// 직급 규칙을 지원하기 전에 배포된 설정의 cnf_fgpt가 바뀌지 않아야 한다. 빈 목록도 없는 것과 같다.
		assertThat(load(VALID + "    rank_grants: []\n").fingerprint()).isEqualTo(load(VALID).fingerprint());
		assertThat(load(VALID + "    rank_grants:\n      - { rank: TL, node: team }\n").fingerprint())
				.isNotEqualTo(load(VALID).fingerprint());
	}

	@Test
	void theFingerprintIsAStableSha256Hex() throws IOException {
		String first = load(VALID).fingerprint();
		assertThat(first).matches("[0-9a-f]{64}");
		assertThat(load(VALID).fingerprint()).isEqualTo(first);
	}

	@Test
	void whitespaceCommentsAndKeyOrderDoNotChangeTheFingerprint() throws IOException {
		String reordered = """
				# 주석은 지문에 영향이 없다
				tenants:
				  - name: ACME
				    tnn_key: acme
				    grants:
				      - node: sales-hq
				        role: ADMIN
				        org_unit: sales
				      - node: common
				        role: VIEWER
				        org_unit: sales
				    nodes:
				      - name: 영업팀
				        parent: sales-hq
				        kind: WORK
				        node_key: team
				      - name: 영업본부
				        parent: root
				        kind: ORG
				        node_key: sales-hq
				    org_units:
				      - name: 운영본부
				        key: ops
				        status: INACTIVE
				      - name: 영업본부
				        key: sales


				reconcile:
				  dpl_id: "2026-09-21-01"
				  enabled: true
				""";
		assertThat(load(reordered).fingerprint()).isEqualTo(load(VALID).fingerprint());
	}

	@Test
	void spellingOutADefaultDoesNotChangeTheFingerprint() throws IOException {
		String explicit = VALID.replace("    name: ACME\n", "    name: ACME\n    status: ACTIVE\n");
		assertThat(load(explicit).fingerprint()).isEqualTo(load(VALID).fingerprint());
	}

	@Test
	void anyMeaningfulChangeChangesTheFingerprint() throws IOException {
		String base = load(VALID).fingerprint();
		assertThat(load(VALID.replace("영업팀", "영업1팀")).fingerprint()).as("표시명").isNotEqualTo(base);
		assertThat(load(VALID.replace("parent: sales-hq", "parent: root")).fingerprint()).as("부모").isNotEqualTo(base);
		assertThat(load(VALID.replace("role: ADMIN", "role: CONTRIBUTOR")).fingerprint()).as("역할").isNotEqualTo(base);
		assertThat(load(VALID.replace("2026-09-21-01", "2026-09-21-02")).fingerprint()).as("배포 ID").isNotEqualTo(base);
		assertThat(load(VALID.replace("enabled: true", "enabled: false")).fingerprint()).as("reconcile 켜짐").isNotEqualTo(base);
		assertThat(load(VALID.replace("status: INACTIVE", "status: ACTIVE")).fingerprint()).as("상태").isNotEqualTo(base);
	}

	@Test
	void theFingerprintFollowsTheBytesReadOnceNotALaterFileState() throws IOException {
		Path file = write(VALID);
		RbacBootstrapConfigReader reader = readerFor(file);
		Optional<RbacBootstrapConfigReader.LoadedBootstrapSpec> loaded = reader.loadWithFingerprint();

		// 읽은 뒤 파일이 바뀌어도 이미 돌려준 (spec, 지문) 쌍은 서로 일치한다.
		Files.writeString(file, VALID.replace("영업팀", "다른 이름"), StandardCharsets.UTF_8);
		assertThat(reader.fingerprint(loaded.orElseThrow().spec())).isEqualTo(loaded.orElseThrow().fingerprint());
	}
}
