package com.onggijonggi.api.authz;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : RbacStateConflictException.java
 * Description : Workspace·부여·org-unit 관리(#260)가 현재 권한 구성 상태 때문에 거부될 때 쓴다 — 선언 리소스 보호, 마지막 직접
 *               ADMIN, 남은 협업방, 활성 배정이 남은 org-unit, 중복 key·이름 등. 참여자 상태 충돌(PARTICIPANT_STATE_CONFLICT)과
 *               원인이 달라 GlobalExceptionHandler가 별도 코드(RBAC_STATE_CONFLICT)를 붙인다. 409라 ResponseStatusException을 잇는다.
 */
public class RbacStateConflictException extends ResponseStatusException {

	public RbacStateConflictException() {
		super(HttpStatus.CONFLICT);
	}
}
