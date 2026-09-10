package com.onggijonggi.api.chat;

/**
 * Class Name : IdempotencyKeyConflictException.java
 * Description : 이미 쓰인 idempotency key에 이전과 다른 요청 내용이 온 경우(이슈 #149). 상태코드가
 *               같은 409라도 ThreadParticipantService의 참여자 상태 충돌과는 원인이 달라, 별도
 *               타입으로 둬 GlobalExceptionHandler가 서로 다른 에러 코드로 분기할 수 있게 한다.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

	public IdempotencyKeyConflictException() {
		super("이미 다른 요청 내용으로 사용된 idempotency key입니다.");
	}

}
