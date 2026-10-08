package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : CitationTest.java
 * Description : Citation.fromSrcJson이 chunkId·loc 필드가 없던 과거 메시지(이슈 #347 저장분)를
 *               오류 없이 복원하는지 확인한다(이슈 #352).
 */
class CitationTest {

	private final ObjectMapper objectMapper = new JsonMapper();

	@Test
	void restoresPastCitationsWithoutChunkIdOrLocAsNull() {
		String legacyJson = """
				[{"docId":"doc-001","title":"규정.pdf","snippet":"발췌","score":0.9}]""";

		List<Citation> citations = Citation.fromSrcJson(objectMapper, legacyJson);

		assertThat(citations).containsExactly(new Citation("doc-001", "규정.pdf", "발췌", 0.9, null, null));
	}

	@Test
	void restoresCitationsWithChunkIdAndLoc() {
		String json = """
				[{"docId":"doc-001","title":"규정.pdf","snippet":"발췌","score":0.9,"chunkId":"doc-001:1:0","loc":"page=3"}]""";

		List<Citation> citations = Citation.fromSrcJson(objectMapper, json);

		assertThat(citations).containsExactly(new Citation("doc-001", "규정.pdf", "발췌", 0.9, "doc-001:1:0", "page=3"));
	}

}
