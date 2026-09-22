package com.onggijonggi.api.authz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : RbacBootstrapConfigReader.java
 * Description : 배포 환경에 마운트한 bootstrap YAML을 읽는다. 경로가 비어 있으면 설정이 없는 것이라 아무것도 하지 않는다.
 *               파일은 한 번만 읽어 같은 바이트로 파싱과 지문 계산을 한다(읽는 사이 파일이 바뀌면 파싱본과 지문이 어긋난다).
 *               지문(cnf_fgpt)은 원본 바이트가 아니라 **정규화한 설정**(키 정렬, 목록 정렬)의 SHA-256이다 —
 *               공백·주석·순서만 바뀐 설정은 같은 지문이다. 알 수 없는 키는 오타로 보고 거부한다.
 */
@Component
public class RbacBootstrapConfigReader {

	private static final Set<String> ROOT_KEYS = Set.of("reconcile", "tenants");
	private static final Set<String> RECONCILE_KEYS = Set.of("enabled", "dpl_id");
	private static final Set<String> TENANT_KEYS = Set.of("tnn_key", "name", "status", "org_units", "nodes", "grants");
	private static final Set<String> ORG_UNIT_KEYS = Set.of("key", "name", "status");
	private static final Set<String> NODE_KEYS = Set.of("node_key", "kind", "parent", "name", "status");
	private static final Set<String> GRANT_KEYS = Set.of("org_unit", "role", "node");
	private static final int MAX_DEPLOYMENT_ID_LENGTH = 128;
	/** 설정 파일 크기 상한(1MiB). */
	private static final long MAX_CONFIG_BYTES = 1024 * 1024;

	private final String configPath;
	private final ObjectMapper objectMapper;

	public RbacBootstrapConfigReader(@Value("${app.rbac.bootstrap-config-path:}") String configPath,
			ObjectMapper objectMapper) {
		this.configPath = configPath;
		this.objectMapper = objectMapper;
	}


	public Optional<LoadedBootstrapSpec> loadWithFingerprint() {
		if (configPath == null || configPath.isBlank()) return Optional.empty();
		Path path = Path.of(configPath);
		if (!Files.isRegularFile(path)) {
			throw new RbacBootstrapConfigurationException("bootstrap 설정 경로가 일반 파일이 아니다: " + path);
		}
		byte[] bytes;
		try {
			if (Files.size(path) > MAX_CONFIG_BYTES) {
				throw new RbacBootstrapConfigurationException("bootstrap 설정 파일이 " + MAX_CONFIG_BYTES + "바이트를 넘는다");
			}
		} catch (IOException exception) {
			throw new IllegalStateException("bootstrap 설정 파일 크기를 확인할 수 없다: " + path, exception);
		}
		try {
			bytes = Files.readAllBytes(path);
		} catch (IOException exception) {
			throw new IllegalStateException("bootstrap 설정 파일을 읽을 수 없다: " + path, exception);
		}
		Map<String, Object> root = parseYaml(bytes);
		if (root == null || root.isEmpty()) return Optional.empty();
		RbacBootstrapSpec spec = parse(root);
		return Optional.of(new LoadedBootstrapSpec(spec, fingerprint(spec)));
	}

	private Map<String, Object> parseYaml(byte[] bytes) {
		try {
			YamlMapFactoryBean factory = new YamlMapFactoryBean();
			factory.setResources(new ByteArrayResource(bytes));
			return factory.getObject();
		} catch (RuntimeException exception) {
			throw new RbacBootstrapConfigurationException("bootstrap YAML을 해석할 수 없다: " + exception.getMessage());
		}
	}

	private RbacBootstrapSpec parse(Map<String, Object> root) {
		onlyKnownKeys("설정 최상위", root, ROOT_KEYS);
		Map<String, Object> reconcile = map("reconcile", root.get("reconcile"));
		onlyKnownKeys("reconcile", reconcile, RECONCILE_KEYS);
		Object enabled = reconcile.get("enabled");
		if (enabled != null && !(enabled instanceof Boolean)) {
			throw new RbacBootstrapConfigurationException("reconcile.enabled는 true 또는 false여야 한다");
		}
		String deploymentId = reconcile.get("dpl_id") == null ? null : string("reconcile.dpl_id", reconcile.get("dpl_id"));
		if (deploymentId != null && deploymentId.length() > MAX_DEPLOYMENT_ID_LENGTH) {
			throw new RbacBootstrapConfigurationException("reconcile.dpl_id는 " + MAX_DEPLOYMENT_ID_LENGTH + "자 이하여야 한다");
		}
		List<RbacBootstrapSpec.TenantSpec> tenants = new ArrayList<>();
		for (Object value : list("tenants", root.get("tenants"))) tenants.add(tenant(map("tenants[]", value)));
		return new RbacBootstrapSpec(new RbacBootstrapSpec.Reconcile(Boolean.TRUE.equals(enabled), deploymentId),
				List.copyOf(tenants));
	}

	private RbacBootstrapSpec.TenantSpec tenant(Map<String, Object> source) {
		onlyKnownKeys("tenant", source, TENANT_KEYS);
		String key = string("tenants[].tnn_key", source.get("tnn_key"));
		String scope = "tenant " + key;
		List<RbacBootstrapSpec.OrgUnitSpec> orgUnits = new ArrayList<>();
		for (Object value : list(scope + ".org_units", source.get("org_units"))) {
			Map<String, Object> unit = map(scope + ".org_units[]", value);
			onlyKnownKeys(scope + " org_unit", unit, ORG_UNIT_KEYS);
			orgUnits.add(new RbacBootstrapSpec.OrgUnitSpec(string(scope + ".org_units[].key", unit.get("key")),
					string(scope + ".org_units[].name", unit.get("name")),
					stringOr(scope + ".org_units[].status", unit.get("status"), "ACTIVE")));
		}
		List<RbacBootstrapSpec.NodeSpec> nodes = new ArrayList<>();
		for (Object value : list(scope + ".nodes", source.get("nodes"))) {
			Map<String, Object> node = map(scope + ".nodes[]", value);
			onlyKnownKeys(scope + " node", node, NODE_KEYS);
			nodes.add(new RbacBootstrapSpec.NodeSpec(string(scope + ".nodes[].node_key", node.get("node_key")),
					string(scope + ".nodes[].kind", node.get("kind")),
					string(scope + ".nodes[].parent", node.get("parent")),
					string(scope + ".nodes[].name", node.get("name")),
					stringOr(scope + ".nodes[].status", node.get("status"), "ACTIVE")));
		}
		List<RbacBootstrapSpec.GrantSpec> grants = new ArrayList<>();
		for (Object value : list(scope + ".grants", source.get("grants"))) {
			Map<String, Object> grant = map(scope + ".grants[]", value);
			onlyKnownKeys(scope + " grant", grant, GRANT_KEYS);
			grants.add(new RbacBootstrapSpec.GrantSpec(string(scope + ".grants[].org_unit", grant.get("org_unit")),
					string(scope + ".grants[].role", grant.get("role")),
					string(scope + ".grants[].node", grant.get("node"))));
		}
		return new RbacBootstrapSpec.TenantSpec(key, string(scope + ".name", source.get("name")),
				stringOr(scope + ".status", source.get("status"), "ACTIVE"), List.copyOf(orgUnits),
				List.copyOf(nodes), List.copyOf(grants));
	}

	/** 정규화한 설정의 SHA-256. 키와 목록을 정렬해 공백·주석·순서 차이를 없앤다. */
	String fingerprint(RbacBootstrapSpec spec) {
		Map<String, Object> canonical = new TreeMap<>();
		Map<String, Object> reconcile = new TreeMap<>();
		reconcile.put("enabled", spec.reconcile().enabled());
		reconcile.put("dpl_id", spec.reconcile().deploymentId());
		canonical.put("reconcile", reconcile);
		canonical.put("tenants", spec.tenants().stream()
				.sorted(Comparator.comparing(RbacBootstrapSpec.TenantSpec::key)).map(this::canonicalTenant).toList());
		try {
			byte[] json = objectMapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("JDK는 SHA-256을 제공해야 한다", impossible);
		}
	}

	private Map<String, Object> canonicalTenant(RbacBootstrapSpec.TenantSpec tenant) {
		Map<String, Object> value = new TreeMap<>();
		value.put("tnn_key", tenant.key());
		value.put("name", tenant.name());
		value.put("status", tenant.status());
		value.put("org_units", tenant.orgUnits().stream().sorted(Comparator.comparing(RbacBootstrapSpec.OrgUnitSpec::key))
				.map(unit -> {
					Map<String, Object> item = new TreeMap<>();
					item.put("key", unit.key());
					item.put("name", unit.name());
					item.put("status", unit.status());
					return item;
				}).toList());
		value.put("nodes", tenant.nodes().stream().sorted(Comparator.comparing(RbacBootstrapSpec.NodeSpec::key))
				.map(node -> {
					Map<String, Object> item = new TreeMap<>();
					item.put("node_key", node.key());
					item.put("kind", node.kind());
					item.put("parent", node.parent());
					item.put("name", node.name());
					item.put("status", node.status());
					return item;
				}).toList());
		value.put("grants", tenant.grants().stream()
				.sorted(Comparator.comparing(RbacBootstrapSpec.GrantSpec::orgUnit)
						.thenComparing(RbacBootstrapSpec.GrantSpec::role)
						.thenComparing(RbacBootstrapSpec.GrantSpec::node))
				.map(grant -> {
					Map<String, Object> item = new TreeMap<>();
					item.put("org_unit", grant.orgUnit());
					item.put("role", grant.role());
					item.put("node", grant.node());
					return item;
				}).toList());
		return value;
	}

	private void onlyKnownKeys(String scope, Map<String, Object> value, Set<String> allowed) {
		for (String key : value.keySet()) {
			if (!allowed.contains(key)) {
				throw new RbacBootstrapConfigurationException(scope + "에 알 수 없는 키가 있다: " + key + " (허용: " + allowed + ")");
			}
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(String scope, Object value) {
		if (value == null) return Map.of();
		if (!(value instanceof Map<?, ?> raw)) throw new RbacBootstrapConfigurationException(scope + "는 객체여야 한다");
		return (Map<String, Object>) raw;
	}

	private List<Object> list(String scope, Object value) {
		if (value == null) return List.of();
		if (!(value instanceof List<?> raw)) throw new RbacBootstrapConfigurationException(scope + "는 목록이어야 한다");
		return new ArrayList<>(raw);
	}

	private String string(String scope, Object value) {
		if (!(value instanceof String text) || text.isBlank()) {
			throw new RbacBootstrapConfigurationException(scope + "는 비어 있지 않은 문자열이어야 한다(숫자·날짜처럼 읽히면 따옴표로 감싼다)");
		}
		return text;
	}

	private String stringOr(String scope, Object value, String fallback) {
		return value == null ? fallback : string(scope, value);
	}

	public record LoadedBootstrapSpec(RbacBootstrapSpec spec, String fingerprint) {
	}
}
