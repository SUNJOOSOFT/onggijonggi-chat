package com.onggijonggi.etl;

import static com.onggijonggi.common.document.TagIndexContract.*;

import com.onggijonggi.common.document.TagPrompt;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Class Name : TagIndex.java
 * Description : 문서 태그 검색 인덱스(#362)에 처리 회차마다 문서 하나를 쓰고 지운다. 매핑은 공용 모듈의 es-thr-doc-tag-index.json이고,
 *               쓰기·검색은 항상 별칭으로 한다. 별칭 확인·생성은 태깅 주기 시작(ensure) 한 곳에서만 한다 — 새로 만들었으면 태깅 작업이
 *               DB(정본)의 태그로 다시 채운다(restore). 쓰는 도중 별칭이 사라지면 일시 장애로 끝내고 다음 주기가 다시 만든다.
 *               조각 인덱스(ChunkIndex)와 달리 이전 버전에서 옮기는 일은 아직 없다 — 매핑을 바꿀 때 인덱스 이름을 올리고 태그를 다시 뽑게
 *               하면 된다(설정 지문).
 */
@Component
public class TagIndex {

	/** _bulk 본문 형식. charset을 밝힌다(ChunkIndex와 같다). */
	private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson;charset=UTF-8");

	private final RestClient client;
	private final ObjectMapper json;
	private final String alias;
	private final String index;

	public TagIndex(EtlProperties properties, ObjectMapper json) {
		this.client = HttpCalls.client(properties.elasticsearch().url(), properties.requestTimeout());
		this.alias = properties.elasticsearch().tagAlias();
		this.index = properties.elasticsearch().tagIndex();
		this.json = json;
	}

	/**
	 * 별칭을 확인하고, 없으면 인덱스와 별칭을 만든다. 새로 만들었으면 true — 태그 색인만 사라진 경우(색인 삭제·스냅샷 복원)
	 * DB의 태그로 다시 채우라는 뜻이다. 태깅 주기마다 부른다.
	 */
	public boolean ensure() {
		try {
			JsonNode aliases;
			try {
				aliases = json.readTree(client.get().uri("/_alias/{alias}", alias).retrieve().body(String.class));
			} catch (HttpClientErrorException.NotFound missing) {
				aliases = json.createObjectNode();
			}
			boolean created = aliases.isEmpty();
			if (created) create();
			return created;
		} catch (RuntimeException error) {
			throw HttpCalls.classify("TAG_INDEX", error);
		}
	}

	private void create() {
		ObjectNode body = (ObjectNode) mapping();
		body.putObject("aliases").putObject(alias);
		try {
			client.put().uri("/{index}", index).contentType(MediaType.APPLICATION_JSON).body(utf8(json.writeValueAsString(body)))
					.retrieve().toBodilessEntity();
		} catch (HttpClientErrorException.BadRequest rejected) {
			String reason = rejected.getResponseBodyAsString();
			if (!reason.contains("resource_already_exists_exception"))
				throw EtlFailure.permanent("TAG_INDEX_REJECTED", "태그 인덱스 생성 거절: " + HttpCalls.abbreviate(reason), rejected);
			client.put().uri("/{index}/_alias/{alias}", index, alias).retrieve().toBodilessEntity();
		}
	}

	/**
	 * 한 회차의 태그를 쓴다(같은 회차면 덮어쓴다). refresh는 기다리지 않는다 — Elasticsearch가 1초 안에 검색에 반영하고, 기다리면
	 * 태깅이 문서마다 약 1초씩 늘었다(한 번에 문서 하나씩 하므로 첫 배포 대량 태깅이 그만큼 길어진다).
	 */
	public void write(TagStore.Target target, TagPrompt.Tags tags, String fingerprint) {
		Map<String, Object> document = document(target.document(), target.thread(), target.tenant(), target.runSeq(), tags, fingerprint);
		try {
			client.put().uri("/{alias}/_doc/{id}?require_alias=true", alias, tagId(target.document(), target.runSeq()))
					.contentType(MediaType.APPLICATION_JSON).body(utf8(json.writeValueAsString(document))).retrieve().toBodilessEntity();
		} catch (HttpClientErrorException.NotFound aliasMissing) {
			throw EtlFailure.transientFailure("TAG_INDEX_UNAVAILABLE", "태그 인덱스 별칭이 없다 — 다음 태깅 주기에 다시 만든다", aliasMissing);
		} catch (RuntimeException error) {
			throw HttpCalls.classify("TAG_INDEX", error);
		}
	}

	/** DB에 남은 태그 여러 건을 한 요청(_bulk)으로 다시 쓴다(태그 색인을 새로 만든 뒤 채울 때). 한 건이라도 실패하면 일시 장애다. */
	public void restore(List<TagStore.Stored> stored) {
		if (stored.isEmpty()) return;
		StringBuilder lines = new StringBuilder();
		for (TagStore.Stored tag : stored) {
			lines.append(json.writeValueAsString(Map.of("index", Map.of("_id", tagId(tag.document(), tag.runSeq()))))).append('\n');
			lines.append(json.writeValueAsString(document(tag.document(), tag.thread(), tag.tenant(), tag.runSeq(), tag.tags(), tag.fingerprint())))
					.append('\n');
		}
		try {
			JsonNode response = json.readTree(client.post().uri("/{alias}/_bulk?refresh=true&require_alias=true", alias)
					.contentType(NDJSON).body(utf8(lines.toString())).retrieve().body(String.class));
			if (response.path("errors").asBoolean(true))
				throw EtlFailure.transientFailure("TAG_INDEX_UNAVAILABLE", "태그 색인 다시 채우기 일부 실패", null);
		} catch (HttpClientErrorException.NotFound aliasMissing) {
			throw EtlFailure.transientFailure("TAG_INDEX_UNAVAILABLE", "태그 인덱스 별칭이 없다 — 다음 태깅 주기에 다시 만든다", aliasMissing);
		} catch (RuntimeException error) {
			throw HttpCalls.classify("TAG_INDEX", error);
		}
	}

	private static Map<String, Object> document(UUID doc, UUID thread, UUID tenant, int runSeq, TagPrompt.Tags tags, String fingerprint) {
		Map<String, Object> document = new LinkedHashMap<>();
		document.put(DOC_ID, doc.toString());
		document.put(THR_ID, thread.toString());
		document.put(TNN_ID, tenant.toString());
		document.put(RUN_SEQ, runSeq);
		document.put(CATEGORY, tags.category());
		document.put(KEYWORDS, tags.keywords());
		document.put(SUMMARY, tags.summary());
		document.put(CONFIG, fingerprint);
		return document;
	}

	/** 한 회차의 태그 문서를 지운다. 멱등이다 — 문서나 인덱스가 없으면 지울 것도 없다. */
	public void delete(UUID document, int runSeq) {
		try {
			client.delete().uri("/{alias}/_doc/{id}?refresh=true", alias, tagId(document, runSeq)).retrieve().toBodilessEntity();
		} catch (HttpClientErrorException.NotFound gone) {
			// 이미 없거나(태깅 전·태깅 꺼짐) 인덱스가 없다.
		} catch (RuntimeException error) {
			throw HttpCalls.classify("TAG_INDEX", error);
		}
	}

	private static byte[] utf8(String body) {
		return body.getBytes(StandardCharsets.UTF_8);
	}

	private JsonNode mapping() {
		try (InputStream in = new ClassPathResource(MAPPING).getInputStream()) {
			return json.readTree(in);
		} catch (IOException error) {
			throw new IllegalStateException("태그 인덱스 매핑 리소스를 읽지 못했다: " + MAPPING, error);
		}
	}
}
