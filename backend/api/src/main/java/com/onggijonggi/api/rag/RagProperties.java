package com.onggijonggi.api.rag;

import com.onggijonggi.common.document.ChunkIndexContract;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Class Name : RagProperties.java
 * Description : 방 문서 검색(#344) 설정(app.rag.*). 검색 기준 수치(search)는 평가 세트(rag-eval, 가상 규정 5개·질문 37개)로 정한
 *               값이다 — 벡터 0.50·키워드 75%에서 답 있는 질문 30/31, 무관 질문 오채택 0/6. 실제 문서가 들어오면 ragEval로 다시 맞춘다.
 *               임베딩 주소가 비면 검색은 UNAVAILABLE이다 — 채팅·문서 등록은 영향받지 않는다.
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
		@DefaultValue Elasticsearch elasticsearch,
		@DefaultValue Embedding embedding,
		@DefaultValue Rewrite rewrite,
		@DefaultValue Search search,
		@DefaultValue("true") boolean searchApiEnabled) {

	/** 검색은 항상 별칭으로 한다. 인덱스 이름·매핑은 ETL이 공용 매핑(es-thr-doc-chunk-index.json)으로 만든다. */
	public record Elasticsearch(@DefaultValue("http://localhost:9200") String url, @DefaultValue(ChunkIndexContract.ALIAS) String alias,
			@DefaultValue("5s") Duration timeout) { }

	/** 질문 임베딩. 문서를 임베딩한 ETL과 같은 엔드포인트·모델이어야 한다(다른 모델의 벡터를 섞지 않는다). */
	public record Embedding(@DefaultValue("") String url, @DefaultValue("bge-m3") String model,
			@DefaultValue("1024") int dimensions, @DefaultValue("5s") Duration timeout) { }

	/**
	 * 후속 질문을 독립된 검색 문장으로 다시 쓰는 LLM 호출. model이 비면 그 요청의 대화 모델을 쓴다 — 이미 같은 대화를 받는 모델이라
	 * 새 외부 전송 경로가 생기지 않는다. 운영에서 사내 모델로 고정하려면 지정한다. history는 보여 줄 최근 메시지 수다.
	 */
	public record Rewrite(@DefaultValue("") String model, @DefaultValue("5s") Duration timeout, @DefaultValue("6") int history) { }

	/**
	 * 채널별 관련성 기준과 결합. 벡터는 코사인 유사도 하한, 키워드는 질문 낱말 중 맞아야 하는 비율(minimum_should_match)이다 — 두 점수는
	 * 척도가 달라 따로 건다. 키워드에 BM25 점수 하한을 쓰지 않는 이유: 점수가 색인 전체의 문서 수(IDF)에 따라 흔들려, 문서가 적은
	 * 색인에서는 정확히 맞은 고유명사도 하한 아래로 떨어진다.
	 * 기준을 통과한 후보를 순위 기반(RRF)으로 합쳐 topK개, 문서당 perDocument개까지 돌려준다.
	 * threads·queue는 검색 전용 스레드 수와 대기 상한이다 — 검색은 외부 호출을 블로킹으로 기다리므로(최악 약 20초, 태그 채널을 켜면
	 * Elasticsearch 호출 두 번이 더해져 약 30초) BFF 공용
	 * boundedElastic(모든 DB 호출이 쓴다)과 나눈다. 대기 상한을 넘으면 UNAVAILABLE이다.
	 */
	public record Search(@DefaultValue("5") int topK, @DefaultValue("3") int perDocument, @DefaultValue("20") int candidates,
			@DefaultValue("100") int numCandidates, @DefaultValue("0.5") double vectorMinSimilarity,
			@DefaultValue("75%") String keywordMinimumShouldMatch, @DefaultValue("60") int rrfK,
			@DefaultValue("8") int threads, @DefaultValue("100") int queue) { }
}
