package com.onggijonggi.api.auth;

/**
 * Class Name : PersonNames.java
 * Description : 사람 이름을 화면에 쓰는 규칙 하나. 한국식으로 성+이름을 띄어 쓰지 않고 붙인다(황정민).
 *               둘 다 없으면 null이고 호출부가 username 등으로 물러난다.
 *
 *               토큰 claim(family_name·given_name)과 Keycloak Admin API(lastName·firstName)가 같은 규칙을 써야
 *               같은 사람이 실시간 말풍선과 이력에서 다른 이름으로 보이지 않는다.
 */
public final class PersonNames {

	private PersonNames() {
	}

	public static String fullName(String familyName, String givenName) {
		String name = (familyName == null ? "" : familyName.trim()) + (givenName == null ? "" : givenName.trim());
		return name.isEmpty() ? null : name;
	}
}
