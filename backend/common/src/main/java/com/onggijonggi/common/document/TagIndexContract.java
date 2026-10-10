package com.onggijonggi.common.document;

import java.util.List;
import java.util.UUID;

/**
 * Class Name : TagIndexContract.java
 * Description : 방 문서 태그 검색 인덱스의 계약(#362) — 매핑 리소스(es-thr-doc-tag-index.json)의 필드 이름, 별칭, 색인 문서 ID 규칙.
 *               태그(카테고리·핵심 키워드·요약)는 문서 단위 신호라 조각 인덱스와 따로 둔다: 처리 회차마다 문서 하나를 색인하고, 검색의
 *               태그 채널이 (문서, 현재 회차) 범위로 찾는다. 적재(ETL 태깅 작업)와 검색(BFF)이 같은 이름을 쓰게 한 곳에 둔다.
 *               필드를 바꾸면 매핑 JSON과 이 상수를 함께 바꾸고 인덱스 이름(버전)을 올린다(TagPromptTest가 둘을 대조한다).
 */
public final class TagIndexContract {

	/** 쓰기·검색은 항상 이 별칭으로 한다. 인덱스 이름(버전)은 ETL 설정이 정한다. */
	public static final String ALIAS = "thr_doc_tag";
	/** 매핑 리소스(공용 모듈의 classpath). */
	public static final String MAPPING = "es-thr-doc-tag-index.json";

	public static final String DOC_ID = "doc_id";
	public static final String THR_ID = "thr_id";
	public static final String TNN_ID = "tnn_id";
	public static final String RUN_SEQ = "run_seq";
	/** 카테고리(설정 목록 중 하나 또는 TagPrompt.UNCLASSIFIED). */
	public static final String CATEGORY = "ctg";
	/** 핵심 키워드. 검색 대상. */
	public static final String KEYWORDS = "kyw";
	/** 짧은 요약. 검색 대상. */
	public static final String SUMMARY = "smm";
	/** 뽑을 때의 태깅 설정 지문. */
	public static final String CONFIG = "tag_cnf";

	/** 매핑에 있어야 하는 필드 전체. */
	public static final List<String> FIELDS = List.of(DOC_ID, THR_ID, TNN_ID, RUN_SEQ, CATEGORY, KEYWORDS, SUMMARY, CONFIG);

	private TagIndexContract() {
	}

	/** 색인 문서 ID. 문서·회차로 정해져 같은 회차를 다시 태깅하면 덮어쓴다. */
	public static String tagId(UUID document, int runSeq) {
		return document + ":" + runSeq;
	}
}
