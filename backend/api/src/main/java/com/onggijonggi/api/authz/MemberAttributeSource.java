package com.onggijonggi.api.authz;

import java.util.List;

/**
 * Class Name : MemberAttributeSource.java
 * Description : 03·CORE 사람 속성을 전달받는 창구. 적재(CasbinRuleLoader)가 부를 때마다 전체 목록을 다시 만든다.
 *               지금 구현은 전달받은 파일(FileMemberAttributeSource)이다. 나중에 고객사가 전달한 정보를 받는 창구도
 *               이 인터페이스로 넣는다 — 우리가 고객사 시스템에 접속해 가져오지 않는다. 블로킹이다.
 */
public interface MemberAttributeSource {

	/** 검증·매핑한 전체 목록. 검증에 실패하면 {@link MemberAttributeSourceException}, 그 밖의 장애는 그대로 던진다. */
	Load load();

	/** skipped는 Keycloak에 없거나 꺼진 아이디다(그 줄만 건너뛴다). fingerprint는 원본 내용의 해시(로그용)다. */
	record Load(List<MemberAttribute> members, List<String> skipped, String fingerprint) {
	}
}
