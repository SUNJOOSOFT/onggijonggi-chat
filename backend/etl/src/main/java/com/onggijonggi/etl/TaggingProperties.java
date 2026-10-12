package com.onggijonggi.etl;

import com.onggijonggi.common.document.TagPrompt;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Class Name : TaggingProperties.java
 * Description : 방 문서 태깅 설정(app.etl.tagging.*, #362). url·model이 비면 태깅 작업이 돌지 않는다. 태깅은 문서 본문 전체를 LLM에
 *               보내므로 url에는 사내 모델 서버만 둔다(게이트웨이·외부 공급자를 거치지 않는다).
 *               카테고리 목록·키워드 수·요약 길이·모델이 바뀌면 설정 지문이 바뀌어 태그만 다시 뽑힌다(조각·임베딩은 그대로).
 */
@ConfigurationProperties("app.etl.tagging")
public record TaggingProperties(
		/** 사내 LLM 서버(OpenAI 호환 /v1/chat/completions)의 주소. */
		@DefaultValue("") String url,
		/** 그 서버의 모델 이름. */
		@DefaultValue("") String model,
		/** 요청 하나의 응답 대기 상한. 긴 입력은 수십 초가 걸린다. */
		@DefaultValue("120s") Duration timeout,
		/** 카테고리 목록. 모델은 이 중 하나 또는 미분류를 고른다. 배포마다 바꿀 수 있다. 비면 기본 목록(TagPrompt.DEFAULT_CATEGORIES)이다. */
		List<String> categories,
		@DefaultValue("10") int maxKeywords,
		@DefaultValue("200") int summaryMaxChars,
		/** 응답 토큰 상한(최종 태그·덩어리 요약 공통). */
		@DefaultValue("1024") int maxTokens,
		/** 이 길이(글자) 안의 문서는 한 번에 태깅한다. 넘으면 나눠 요약한 뒤 다시 요약한다. 모델 한도보다 낮게 둔다 — 너무 긴 입력은 느리고 중간을 놓친다. */
		@DefaultValue("40000") int singleCallChars,
		/** 나눠 요약할 때 덩어리 하나의 길이(글자). */
		@DefaultValue("12000") int partChars,
		/** 덩어리 수 상한. 넘으면 문서 전체에서 고르게 골라 쓴다 — 큰 파일 하나가 모델 서버를 붙잡지 않게. */
		@DefaultValue("20") int maxParts,
		/** 한 번에 고르는 태깅 대상 수. */
		@DefaultValue("20") int batch,
		/** 일시 장애(태깅 서버·원본 저장소)로 실패한 회차의 첫 재시도 간격. 실패할 때마다 두 배로 늘려 retryDelay까지 간다. */
		@DefaultValue("1m") Duration retryFirstDelay,
		/** 일시 장애 재시도 간격의 상한. */
		@DefaultValue("1h") Duration retryDelay,
		/** 영구 실패(입력 거절·원본 없음 등)를 다시 시도하기까지의 간격. 태깅 설정이 바뀌면 이 간격을 기다리지 않고 다시 한다. */
		@DefaultValue("7d") Duration permanentRetryDelay,
		/** 할 일이 없거나 준비가 안 됐을 때 다시 볼 간격. */
		@DefaultValue("30s") Duration idleDelay) {

	public TaggingProperties {
		// 비었거나(환경 변수를 빈 값으로 넘김) 공백뿐이면 기본 목록을 쓴다 — 빈 목록이면 모든 문서가 미분류로 굳는다.
		// 같은 이름이 두 번 들어가도 한 번만 쓴다 — 설정 지문이 바뀌어 전체가 불필요하게 다시 태깅되지 않게.
		List<String> named = categories == null ? List.of() : categories.stream().map(String::strip).filter(name -> !name.isEmpty()).distinct().toList();
		categories = named.isEmpty() ? TagPrompt.DEFAULT_CATEGORIES : named;
	}

	public boolean enabled() {
		return !url.isBlank() && !model.isBlank();
	}

	public TagPrompt.Settings settings() {
		return new TagPrompt.Settings(categories, maxKeywords, summaryMaxChars);
	}

	/** 지금 설정의 태깅 지문. 태그 행의 tag_cnf와 다르면 태그만 다시 뽑는다. */
	public String fingerprint() {
		return settings().fingerprint(model);
	}
}
