package com.onggijonggi.etl;

import com.onggijonggi.common.document.Chunker;
import com.onggijonggi.common.document.TagPrompt;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Class Name : Tagger.java
 * Description : 문서 하나의 태그를 뽑는다(#362): 원본을 다시 읽어 문서 처리와 같은 방식으로 추출하고, 사내 LLM에 보낸다.
 *               기준 길이 안의 문서는 한 번에, 넘는 문서는 덩어리별로 요약한 뒤 그 요약들로 최종 태그를 뽑는다. 덩어리 수가 상한을
 *               넘으면 문서 전체에서 고르게 골라 써 큰 파일 하나가 모델 서버를 오래 붙잡지 않게 한다.
 */
@Component
public class Tagger {

	private final SourceReader sources;
	private final TextExtractor extractor;
	private final TaggingClient client;
	private final TaggingProperties settings;

	public Tagger(SourceReader sources, TextExtractor extractor, TaggingClient client, TaggingProperties settings) {
		this.sources = sources;
		this.extractor = extractor;
		this.client = client;
		this.settings = settings;
	}

	public TagPrompt.Tags tag(RunStore.Job job) {
		List<Chunker.Section> sections = extractor.extract(job.fileName(), sources.read(job));
		StringBuilder text = new StringBuilder();
		for (Chunker.Section section : sections) {
			if (!text.isEmpty()) text.append("\n\n");
			text.append(section.text());
		}
		return tag(text.toString());
	}

	TagPrompt.Tags tag(String text) {
		TagPrompt.Settings rules = settings.settings();
		if (text.length() <= settings.singleCallChars())
			return TagPrompt.parse(client.complete(TagPrompt.system(rules), TagPrompt.user(text)), rules);
		List<String> summaries = new ArrayList<>();
		for (String part : parts(text, settings.partChars(), settings.maxParts()))
			summaries.add(client.complete(TagPrompt.partSystem(), TagPrompt.user(part)));
		return TagPrompt.parse(client.complete(TagPrompt.system(rules), TagPrompt.user(TagPrompt.partSummaries(summaries))), rules);
	}

	/**
	 * 텍스트를 size 글자 안팎의 덩어리로 나눈다. 가능하면 줄바꿈에서 끊는다(문단 중간에서 자르지 않게). 덩어리가 max개를 넘으면 처음·끝을
	 * 포함해 고르게 max개를 고른다.
	 */
	static List<String> parts(String text, int size, int max) {
		// 경계만 먼저 정하고 고른 덩어리만 자른다 — 큰 문서에서 쓰지 않을 덩어리 수백 개를 복사하지 않게.
		List<Integer> starts = new ArrayList<>();
		int from = 0;
		while (from < text.length()) {
			starts.add(from);
			int end = Math.min(text.length(), from + size);
			if (end < text.length()) {
				int newline = text.lastIndexOf('\n', end);
				if (newline > from + size / 2) end = newline;
			}
			from = end;
		}
		int count = starts.size();
		List<Integer> picked = new ArrayList<>();
		if (count <= max) for (int i = 0; i < count; i++) picked.add(i);
		else if (max <= 1) picked.add(0);
		else for (int i = 0; i < max; i++) picked.add((int) ((long) i * (count - 1) / (max - 1)));
		List<String> parts = new ArrayList<>(picked.size());
		for (int i : picked) parts.add(text.substring(starts.get(i), i + 1 < count ? starts.get(i + 1) : text.length()));
		return parts;
	}
}
