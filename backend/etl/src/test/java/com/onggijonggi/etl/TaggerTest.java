package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.document.TagPrompt;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Class Name : TaggerTest.java
 * Description : 태깅(#362)의 길이 분기 — 기준 안은 한 번에, 넘으면 덩어리별 요약 뒤 최종 태그, 덩어리 상한에서는 고르게 골라 쓰는지.
 */
class TaggerTest {

	private final TaggingClient client = mock(TaggingClient.class);
	/** 한 번에 100자, 덩어리 40자, 덩어리 최대 3개(TaggingClientTest.properties). */
	private final Tagger tagger = new Tagger(mock(SourceReader.class), mock(TextExtractor.class), client, TaggingClientTest.properties("http://unused"));

	@Test
	void aShortDocumentIsTaggedInOneCall() {
		when(client.complete(anyString(), anyString())).thenReturn("{\"category\":\"기타\",\"keywords\":[\"연차\"],\"summary\":\"요약\"}");

		assertThat(tagger.tag("짧은 본문")).isEqualTo(new TagPrompt.Tags("기타", List.of("연차"), "요약"));
		verify(client, times(1)).complete(anyString(), eq(TagPrompt.user("짧은 본문")));
	}

	@Test
	void aLongDocumentIsSummarizedInPartsAndThenTagged() {
		when(client.complete(eq(TagPrompt.partSystem()), anyString())).thenReturn("부분 요약");
		when(client.complete(argThat(system -> !system.equals(TagPrompt.partSystem())), anyString()))
				.thenReturn("{\"category\":\"인사·총무\",\"keywords\":[],\"summary\":\"전체\"}");

		var tags = tagger.tag("가".repeat(120));

		assertThat(tags.category()).isEqualTo("인사·총무");
		verify(client, times(3)).complete(eq(TagPrompt.partSystem()), anyString());
		verify(client).complete(argThat(system -> !system.equals(TagPrompt.partSystem())),
				argThat(user -> user.contains("1. 부분 요약") && user.contains("3. 부분 요약")));
	}

	@Test
	void partsBreakAtNewlinesAndAreSampledEvenlyWhenThereAreTooMany() {
		assertThat(Tagger.parts("가".repeat(30) + "\n" + "나".repeat(30), 40, 5)).containsExactly("가".repeat(30), "\n" + "나".repeat(30));

		List<String> all = Tagger.parts("0123456789", 1, 100);
		assertThat(all).hasSize(10);
		assertThat(Tagger.parts("0123456789", 1, 4)).as("처음·끝 포함 고르게").containsExactly("0", "3", "6", "9");
		assertThat(Tagger.parts("0123456789", 1, 1)).containsExactly("0");
	}
}
