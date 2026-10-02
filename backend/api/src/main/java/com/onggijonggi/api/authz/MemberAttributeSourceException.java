package com.onggijonggi.api.authz;

import java.util.List;

/**
 * Class Name : MemberAttributeSourceException.java
 * Description : 03·CORE 전달받은 사람 속성이 틀렸다. 한 줄이라도 틀리면 전체를 적재하지 않고 틀린 줄을 모두 알린다.
 */
public class MemberAttributeSourceException extends RuntimeException {

	private final List<String> problems;

	public MemberAttributeSourceException(List<String> problems) {
		super("사람 속성이 올바르지 않다: " + problems);
		this.problems = List.copyOf(problems);
	}

	public List<String> getProblems() {
		return problems;
	}
}
