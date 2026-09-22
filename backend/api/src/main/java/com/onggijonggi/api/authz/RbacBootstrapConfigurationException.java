package com.onggijonggi.api.authz;

import java.util.List;

/**
 * Class Name : RbacBootstrapConfigurationException.java
 * Description : bootstrap 설정이 잘못됐다. 설정 검증은 DB를 건드리기 전에 끝나므로 이 예외가 나면 아무것도 바뀌지 않았다.
 */
public class RbacBootstrapConfigurationException extends RuntimeException {

	private final List<String> problems;

	public RbacBootstrapConfigurationException(List<String> problems) {
		super("RBAC bootstrap 설정이 올바르지 않다: " + String.join("; ", problems));
		this.problems = List.copyOf(problems);
	}

	public RbacBootstrapConfigurationException(String problem) {
		this(List.of(problem));
	}

	public List<String> getProblems() {
		return problems;
	}
}
