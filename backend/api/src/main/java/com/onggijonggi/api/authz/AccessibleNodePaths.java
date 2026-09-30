package com.onggijonggi.api.authz;

import java.util.List;
import java.util.UUID;

/**
 * Class Name : AccessibleNodePaths.java
 * Description : WorkspaceAuthorizer.accessibleNodePaths의 결과. 판정이 꺼져 있어 제한이 없는 경우와, 판정은 켜져
 *               있지만 볼 수 있는 노드가 하나도 없는 경우를 호출부가 구분해야 해서(전자는 필터를 걸지 않고, 후자는
 *               검색 결과를 전부 비운다) Optional 하나로 뭉치지 않고 별도 타입으로 뺐다.
 */
public record AccessibleNodePaths(boolean unrestricted, List<UUID[]> paths) {

	public static AccessibleNodePaths noRestriction() {
		return new AccessibleNodePaths(true, List.of());
	}

	public static AccessibleNodePaths restrictedTo(List<UUID[]> paths) {
		return new AccessibleNodePaths(false, paths);
	}
}
