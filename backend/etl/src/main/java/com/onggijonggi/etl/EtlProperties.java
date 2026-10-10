package com.onggijonggi.etl;

import com.onggijonggi.common.document.ChunkIndexContract;
import com.onggijonggi.common.document.Chunker;
import com.onggijonggi.common.document.TagIndexContract;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Class Name : EtlProperties.java
 * Description : ETL 워커 설정(app.etl.*). 시간 값의 관계: 선점 시한은 한 단계(원본 읽기·임베딩 배치·적재)의 최대
 *               대기보다 길어야 같은 회차를 두 워커가 집지 않는다. 단계 사이마다 시한을 다시 늘린다.
 */
@ConfigurationProperties("app.etl")
public record EtlProperties(
		Worker worker,
		Elasticsearch elasticsearch,
		Embedding embedding,
		@DefaultValue Chunk chunk,
		/** 동시에 처리하는 문서 수. 임베딩 서버가 공용 개발계라 낮게 시작한다. */
		@DefaultValue("2") int concurrency,
		/** 할 일이 없을 때 다시 볼 간격. */
		@DefaultValue("2s") Duration pollDelay,
		@DefaultValue("10m") Duration lease,
		/** 일시 오류 뒤 다음 시도까지의 간격. 최대 시도 횟수는 길이+1(첫 시도 포함)이다. */
		@DefaultValue({"30s", "2m", "10m", "30m"}) List<Duration> retryDelays,
		/** 요청 하나의 응답 대기 상한(원본·임베딩·Elasticsearch 공통). */
		@DefaultValue("60s") Duration requestTimeout) {

	public record Worker(String url, @DefaultValue("") String apiKey) { }

	public record Elasticsearch(String url, @DefaultValue(ChunkIndexContract.ALIAS) String alias,
			@DefaultValue("thr_doc_chunk_v2") String index,
			/** bulk 한 요청에 담는 청크 수. */
			@DefaultValue("200") int bulkSize,
			/** 문서 태그 검색 인덱스(#362)의 별칭과 이름(버전). 쓰기·검색은 별칭으로 한다. */
			@DefaultValue(TagIndexContract.ALIAS) String tagAlias,
			@DefaultValue("thr_doc_tag_v1") String tagIndex) { }

	public record Embedding(@DefaultValue("") String url, @DefaultValue("bge-m3") String model,
			@DefaultValue("1024") int dimensions, @DefaultValue("32") int batchSize) { }

	/** 문단 우선 분할. 값은 대표 문서·질문으로 C와 함께 조정한다. */
	public record Chunk(@DefaultValue("800") int target, @DefaultValue("1200") int max, @DefaultValue("100") int overlap) {
		public Chunker.Settings settings() {
			return new Chunker.Settings(target, max, overlap);
		}
	}
}
