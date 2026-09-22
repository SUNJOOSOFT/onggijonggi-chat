package com.onggijonggi.common.authz;

/**
 * Class Name : Rank.java
 * Description : 직급과 그 서열. 서열 숫자가 작을수록 높다(팀장 1 ~ 사원 6). Casbin ABAC 판정에는 이 숫자를
 *               넘기고 규칙은 "서열 N 이하"로 적는다. DB(org_unit_mbr.rank, rank_grn.rank)에는 코드만 둔다.
 */
public enum Rank {
	/** 팀장 */
	TL(1),
	/** 부장 */
	B(2),
	/** 차장 */
	C(3),
	/** 과장 */
	K(4),
	/** 대리 */
	D(5),
	/** 사원 */
	S(6);

	private final int order;

	Rank(int order) {
		this.order = order;
	}

	public int order() {
		return order;
	}
}
