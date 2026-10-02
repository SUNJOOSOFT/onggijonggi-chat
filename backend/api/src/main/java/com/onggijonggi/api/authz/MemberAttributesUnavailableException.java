package com.onggijonggi.api.authz;

/**
 * Class Name : MemberAttributesUnavailableException.java
 * Description : 03·CORE 사람 속성을 읽을 수 없다(Casbin에 아직 적재되지 않았거나 casbin-server 장애). "속성이 없는 사람"과
 *               구분한다 — 판정 경로는 거부로, 관리·검증 경로는 일시 장애(503)로 다룬다.
 */
public class MemberAttributesUnavailableException extends RuntimeException {

	public MemberAttributesUnavailableException() {
		super("사람 속성을 읽을 수 없다(Casbin 적재 전이거나 장애)");
	}
}
