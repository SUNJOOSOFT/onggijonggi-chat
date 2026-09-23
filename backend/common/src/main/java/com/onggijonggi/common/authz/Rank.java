package com.onggijonggi.common.authz;

/**
 * Class Name : Rank.java
 * Description : 직급과 그 서열. 서열 숫자가 작을수록 높다(팀장 1 ~ 사원 6). Casbin ABAC 판정에는 이 숫자를
 *               넘기고 규칙은 "서열 N 이하"로 적는다. DB(org_unit_mbr.rank, rank_grn.rank)에는 코드만 둔다.
 */
public enum Rank {
	TL(1, "팀장"),
	B(2, "부장"),
	C(3, "차장"),
	K(4, "과장"),
	D(5, "대리"),
	S(6, "사원");

	private final int order;
	private final String label;

	Rank(int order, String label) {
		this.order = order;
		this.label = label;
	}

	public int order() {
		return order;
	}

	/** 화면에 보이는 이름. */
	public String label() {
		return label;
	}
}
