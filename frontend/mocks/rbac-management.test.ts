import { expect, it } from 'vitest';
import { createRbacMockState, handleRbacMock } from './rbac-management';
function fixture<T>(value: T | undefined): T {
  if (value === undefined) throw new Error('목업 fixture가 누락됐습니다.');
  return value;
}
const request = (path: string, method = 'GET', body?: unknown) =>
  new Request(`http://localhost${path}`, {
    method,
    ...(body
      ? {
          body: JSON.stringify(body),
          headers: { 'Content-Type': 'application/json' },
        }
      : {}),
  });

it('활성 형제 이름 중복은 생성·이름 변경·재활성화·이동에서 상태와 감사를 바꾸지 않는다', async () => {
  for (const action of ['create', 'name', 'reactivate', 'parent']) {
    const state = createRbacMockState();
    state.rooms = [];
    const parent = fixture(state.nodes.find((node) => node.kind === 'ORG'));
    const grant = state.grants[0];
    const oldParent = { ...parent, id: 'old-parent', name: '이전 부모' };
    const sibling = {
      ...parent,
      id: 'sibling',
      parentId: parent.id,
      name: 'Duplicate',
    };
    const leaf = {
      ...parent,
      id: 'leaf',
      parentId: action === 'parent' ? oldParent.id : parent.id,
      name: action === 'name' ? '기존 이름' : 'DUPLICATE',
      status:
        action === 'reactivate' ? ('INACTIVE' as const) : ('ACTIVE' as const),
    };
    state.nodes.push(oldParent, sibling);
    if (action !== 'create') state.nodes.push(leaf);
    state.grants.push(
      { ...grant, id: 'old-admin', workspaceId: oldParent.id },
      { ...grant, id: 'leaf-admin', workspaceId: leaf.id },
    );
    const before = structuredClone(state);
    const response = await handleRbacMock(
      action === 'create'
        ? request('/api/rbac/workspaces', 'POST', {
            parentId: parent.id,
            kind: 'WORK',
            name: 'DUPLICATE',
          })
        : request(
            `/api/rbac/workspaces/${leaf.id}/${action}`,
            action === 'reactivate' ? 'POST' : 'PATCH',
            action === 'name'
              ? { name: 'duplicate' }
              : action === 'parent'
                ? { parentId: parent.id }
                : undefined,
          ),
      state,
      'both',
    );
    expect(response.status, action).toBe(409);
    expect(state, action).toEqual(before);
  }
});

it('이름 중복 검사는 자기 자신과 비활성 형제를 제외한다', async () => {
  const state = createRbacMockState();
  const parent = fixture(state.nodes.find((node) => node.kind === 'ORG'));
  state.nodes.push({
    ...parent,
    id: 'inactive-sibling',
    parentId: parent.id,
    name: 'Reusable',
    status: 'INACTIVE',
  });
  const created = await handleRbacMock(
    request('/api/rbac/workspaces', 'POST', {
      parentId: parent.id,
      kind: 'WORK',
      name: 'reusable',
    }),
    state,
    'both',
  );
  expect(created.status).toBe(201);
  const { id } = await created.json();
  expect(
    (
      await handleRbacMock(
        request(`/api/rbac/workspaces/${id}/name`, 'PATCH', {
          name: 'REUSABLE',
        }),
        state,
        'both',
      )
    ).status,
  ).toBe(204);
});

it('깊이 11은 허용하지만 생성·이동으로 깊이 12를 만들거나 CREATE를 표시하지 않는다', async () => {
  const state = createRbacMockState();
  state.rooms = [];
  let parent = fixture(state.nodes.find((node) => node.kind === 'ORG'));
  const oldParent = parent;
  for (let depth = 3; depth <= 11; depth++) {
    const response = await handleRbacMock(
      request('/api/rbac/workspaces', 'POST', {
        parentId: parent.id,
        kind: 'WORK',
        name: `depth-${depth}`,
      }),
      state,
      'both',
    );
    expect(response.status).toBe(201);
    const { id } = await response.json();
    parent = fixture(state.nodes.find((node) => node.id === id));
  }
  const leafResponse = await handleRbacMock(
    request('/api/rbac/workspaces', 'POST', {
      parentId: oldParent.id,
      kind: 'WORK',
      name: '이동 대상',
    }),
    state,
    'both',
  );
  const { id: leafId } = await leafResponse.json();
  const before = structuredClone(state);
  expect(
    (
      await handleRbacMock(
        request('/api/rbac/workspaces', 'POST', {
          parentId: parent.id,
          kind: 'WORK',
          name: 'depth-12',
        }),
        state,
        'both',
      )
    ).status,
  ).toBe(409);
  expect(state).toEqual(before);
  expect(
    (
      await handleRbacMock(
        request(`/api/rbac/workspaces/${leafId}/parent`, 'PATCH', {
          parentId: parent.id,
        }),
        state,
        'both',
      )
    ).status,
  ).toBe(409);
  expect(state).toEqual(before);
  const visible = await (
    await handleRbacMock(request('/api/rbac/workspaces'), state, 'both')
  ).json();
  expect(
    visible.find((node: { id: string }) => node.id === parent.id).actions,
  ).not.toContain('CREATE');
});

it('overview는 배정이 빠지거나 다시 들어온 뒤와 권한 축소 뒤 최신 VIEW를 계산하고 COMMON을 제외한다', async () => {
  const state = createRbacMockState();
  const person = state.people[0];
  const team = person.teamId;
  const node = fixture(state.nodes.find((value) => value.kind === 'ORG'));
  const overview = async () =>
    (
      await handleRbacMock(
        request('/api/platform/rbac/admin/overview'),
        state,
        'both',
      )
    ).json();
  // 배정은 화면에서 바꾸지 않는다(속성 파일). 목업 상태를 직접 바꿔 다시 읽는다.
  person.teamId = null;
  person.rank = null;
  expect((await overview()).people[0].visible).toEqual([]);
  person.teamId = team;
  person.rank = 'S';
  const result = await overview();
  expect(result.workspaces.map((value: { id: string }) => value.id)).toEqual([
    node.id,
  ]);
  expect(result.people[0].visible).toEqual([node.id]);
  state.grants = state.grants.filter((grant) => grant.workspaceId !== node.id);
  expect((await overview()).people[0].visible).toEqual([]);
  state.ranks.push({
    id: 'view-rank',
    workspaceId: node.id,
    orgUnitId: null,
    rank: 'K',
    role: 'VIEWER',
    declared: false,
  });
  person.rank = 'TL';
  expect((await overview()).people[0].visible).toEqual([node.id]);
  node.status = 'INACTIVE';
  expect((await overview()).people[0].visible).toEqual([]);
  node.status = 'ACTIVE';
  person.enabled = false;
  expect((await overview()).people[0].visible).toEqual([]);
});

it('leaf 이동은 ROOT 부모·동일 부모·복원 불가 부여를 거부한다', async () => {
  for (const scenario of ['root', 'same', 'declared', 'inactive', 'no-admin']) {
    const state = createRbacMockState();
    state.rooms = [];
    const node = fixture(state.nodes.find((value) => value.kind === 'ORG'));
    const root = fixture(state.nodes.find((value) => value.kind === 'ROOT'));
    state.ranks.push({
      id: 'root-manage',
      workspaceId: root.id,
      orgUnitId: null,
      rank: 'S',
      role: 'ADMIN',
      declared: false,
    });
    const oldParent = { ...node, id: 'old-parent', name: '이전 부모' };
    const target = { ...node, id: 'new-parent', name: '새 부모' };
    state.nodes.push(oldParent, target);
    const grant = state.grants[0];
    state.grants.push(
      { ...grant, id: 'old-admin', workspaceId: oldParent.id },
      { ...grant, id: 'new-admin', workspaceId: target.id },
    );
    if (scenario !== 'root') node.parentId = oldParent.id;
    if (scenario === 'declared') grant.declared = true;
    if (scenario === 'inactive') {
      state.organizations.push({
        id: 'inactive-org',
        key: 'inactive',
        name: '비활성 조직',
        status: 'INACTIVE',
        declared: false,
      });
      state.grants.push({
        ...grant,
        id: 'inactive-grant',
        orgUnitId: 'inactive-org',
        role: 'VIEWER',
      });
    }
    if (scenario === 'no-admin') {
      grant.role = 'VIEWER';
      state.ranks.push({
        id: 'rank-admin',
        workspaceId: node.id,
        orgUnitId: null,
        rank: 'S',
        role: 'ADMIN',
        declared: false,
      });
    }
    const previous = node.parentId;
    const response = await handleRbacMock(
      request(`/api/rbac/workspaces/${node.id}/parent`, 'PATCH', {
        parentId:
          scenario === 'root'
            ? root.id
            : scenario === 'same'
              ? oldParent.id
              : target.id,
      }),
      state,
      'workspace',
    );
    expect(response.status, scenario).toBe(409);
    expect(node.parentId).toBe(previous);
    expect(state.audits).toHaveLength(0);
  }
});

it('조직 생성은 필수 COMMON 부여도 같은 요청 감사로 기록한다', async () => {
  const state = createRbacMockState();
  const response = await handleRbacMock(
    request('/api/platform/rbac/tenants/default/org-units', 'POST', {
      key: 'new-team',
      name: '새 팀',
    }),
    state,
    'platform',
  );
  expect(response.status).toBe(201);
  expect(state.audits.map((row) => row.eventKind)).toEqual([
    'ORG_UNIT_CREATED',
    'POLICY_ADDED',
  ]);
  expect(state.audits[1].workspaceNodeId).toBe(
    state.nodes.find((node) => node.kind === 'COMMON')?.id,
  );
  expect(state.audits[1].targetRef).toMatchObject({
    org_unit_key: 'new-team',
    role: 'VIEWER',
  });
  expect(state.audits[1].beforeJson).toBeNull();
  expect(state.audits[0].requestId).toBe(state.audits[1].requestId);
});

it('subtree 비활성화는 변경된 활성 노드마다 같은 요청 감사와 전후 상태를 남긴다', async () => {
  const state = createRbacMockState();
  state.rooms = [];
  const parent = fixture(state.nodes.find((node) => node.kind === 'ORG'));
  const child = {
    ...parent,
    id: 'child',
    parentId: parent.id,
    name: '하위 노드',
  };
  state.nodes.push(child, {
    ...child,
    id: 'already-inactive',
    status: 'INACTIVE',
  });
  state.grants.push({
    ...state.grants[0],
    id: 'child-admin',
    workspaceId: child.id,
  });
  expect(
    (
      await handleRbacMock(
        request(`/api/rbac/workspaces/${parent.id}/deactivate`, 'POST'),
        state,
        'workspace',
      )
    ).status,
  ).toBe(204);
  expect(state.audits).toHaveLength(2);
  expect(new Set(state.audits.map((row) => row.workspaceNodeId))).toEqual(
    new Set([parent.id, child.id]),
  );
  expect(new Set(state.audits.map((row) => row.requestId)).size).toBe(1);
  for (const row of state.audits) {
    expect(row.beforeJson).toMatchObject({ status: 'ACTIVE' });
    expect(row.afterJson).toMatchObject({ status: 'INACTIVE' });
  }
});
it('플랫폼과 Workspace 권한을 분리하고 일반 사용자는 목록이 비어 있다', async () => {
  const state = createRbacMockState();
  expect(
    await (
      await handleRbacMock(request('/api/rbac/workspaces'), state, 'platform')
    ).json(),
  ).toEqual([]);
  expect(
    (
      await handleRbacMock(
        request('/api/platform/rbac/tenants'),
        state,
        'workspace',
      )
    ).status,
  ).toBe(403);
  expect(
    await (
      await handleRbacMock(request('/api/rbac/admin/context'), state, 'user')
    ).json(),
  ).toEqual({ workspaceManagement: false });
});

it('자기 조직 배정이 빠지면 Workspace 관리도 즉시 거부한다', async () => {
  const state = createRbacMockState();
  const node = state.nodes.find((value) => value.kind === 'ORG');
  const admin = state.people.find((value) => value.subject === 'mock-admin');
  if (!admin) throw new Error('mock-admin이 없다');
  admin.teamId = null;
  admin.rank = null;
  expect(
    await (
      await handleRbacMock(request('/api/rbac/admin/context'), state, 'both')
    ).json(),
  ).toEqual({ workspaceManagement: false });
  expect(
    (
      await handleRbacMock(
        request(`/api/rbac/workspaces/${node?.id}/grants`),
        state,
        'both',
      )
    ).status,
  ).toBe(403);
});

it('목업 감사 snapshot에는 대상만 담고 다른 자원이나 이전 감사를 복사하지 않는다', async () => {
  const state = createRbacMockState();
  const node = state.nodes.find((value) => value.kind === 'ORG');
  if (!node) throw new Error('fixture');
  await handleRbacMock(
    request(`/api/rbac/workspaces/${node.id}/name`, 'PATCH', {
      name: '이름 하나',
    }),
    state,
    'workspace',
  );
  await handleRbacMock(
    request(`/api/rbac/workspaces/${node.id}/name`, 'PATCH', {
      name: '이름 둘',
    }),
    state,
    'workspace',
  );
  expect(state.audits).toHaveLength(2);
  expect(state.audits[1].beforeJson).toMatchObject({
    id: node.id,
    name: '이름 하나',
  });
  expect(state.audits[1].afterJson).toMatchObject({
    id: node.id,
    name: '이름 둘',
  });
  expect(state.audits[1].beforeJson).not.toHaveProperty('audits');
  expect(state.audits[1].beforeJson).not.toHaveProperty('people');
});

it('변경 뒤 목록과 감사가 갱신되고 숨겨진 부모는 노출하지 않는다', async () => {
  const state = createRbacMockState();
  const nodes = await (
    await handleRbacMock(request('/api/rbac/workspaces'), state, 'workspace')
  ).json();
  const node = nodes[0];
  expect(node.parentId).toBeNull();
  expect(
    (
      await handleRbacMock(
        request(`/api/rbac/workspaces/${node.id}/name`, 'PATCH', {
          name: '변경 이름',
        }),
        state,
        'workspace',
      )
    ).status,
  ).toBe(204);
  expect(
    (
      await (
        await handleRbacMock(
          request('/api/rbac/workspaces'),
          state,
          'workspace',
        )
      ).json()
    )[0].name,
  ).toBe('변경 이름');
  const audit = await (
    await handleRbacMock(
      request(`/api/workspaces/${node.id}/authorization-audits`),
      state,
      'workspace',
    )
  ).json();
  expect(audit.items[0].eventKind).toBe('NODE_RENAMED');
});
it('선언된 부여와 마지막 팀 ADMIN 삭제를 거부한다', async () => {
  const state = createRbacMockState();
  const admin = state.grants.find((grant) => grant.role === 'ADMIN');
  expect(
    (
      await handleRbacMock(
        request(`/api/rbac/grants/${admin?.id}`, 'DELETE'),
        state,
        'workspace',
      )
    ).status,
  ).toBe(409);
  expect(state.audits).toHaveLength(0);
});
it('비활성 노드는 복구만 제공하고 상세 조회는 거부한다', async () => {
  const state = createRbacMockState();
  const node = state.nodes.find((value) => value.kind === 'ORG');
  if (!node) throw new Error();
  node.status = 'INACTIVE';
  const list = await (
    await handleRbacMock(request('/api/rbac/workspaces'), state, 'workspace')
  ).json();
  expect(list[0].actions).toEqual(['REACTIVATE']);
  expect(
    (
      await handleRbacMock(
        request(`/api/rbac/workspaces/${node.id}/grants`),
        state,
        'workspace',
      )
    ).status,
  ).toBe(403);
});

it('비활성 노드의 부여 변경·삭제와 출발 방 이동을 거부한다', async () => {
  const state = createRbacMockState();
  const node = fixture(state.nodes.find((value) => value.kind === 'ORG'));
  state.ranks.push({
    id: 'rule',
    workspaceId: node.id,
    orgUnitId: null,
    rank: 'S',
    role: 'VIEWER',
    declared: false,
  });
  node.status = 'INACTIVE';
  for (const [path, method, body] of [
    ['/api/rbac/rank-grants/rule/rank', 'PATCH', { rank: 'C' }],
    ['/api/rbac/rank-grants/rule', 'DELETE', undefined],
    [
      `/api/collab/threads/${state.rooms[0].id}/workspace`,
      'PATCH',
      {
        workspaceId: fixture(
          state.nodes.find((value) => value.kind === 'COMMON'),
        ).id,
      },
    ],
  ] as const) {
    expect(
      (await handleRbacMock(request(path, method, body), state, 'workspace'))
        .status,
    ).toBe(403);
  }
  expect(state.ranks[0].rank).toBe('S');
  expect(state.audits).toHaveLength(0);
});

it('조직 비활성화는 실제 활성 배정과 마지막 팀 ADMIN을 검사한다', async () => {
  const state = createRbacMockState();
  state.organizations.push({
    id: 'new-org',
    key: 'new-org',
    name: '새 조직',
    status: 'ACTIVE',
    declared: false,
  });
  state.people[0].teamId = 'new-org';
  const deactivate = (id: string) =>
    handleRbacMock(
      request(
        `/api/platform/rbac/tenants/default/org-units/${id}/deactivate`,
        'POST',
      ),
      state,
      'platform',
    );
  expect((await deactivate('new-org')).status).toBe(409);
  const team = fixture(state.grants.find((value) => value.role === 'ADMIN'));
  expect((await deactivate(team.orgUnitId)).status).toBe(409);
  state.people[0].teamId = null;
  state.grants.push({ ...team, id: 'other-admin', orgUnitId: 'new-org' });
  expect((await deactivate(team.orgUnitId)).status).toBe(204);
});

it('규칙 변경의 중복을 거부하고 동일 값 변경에는 감사를 추가하지 않는다', async () => {
  const state = createRbacMockState();
  const node = fixture(state.nodes.find((value) => value.kind === 'ORG'));
  state.ranks.push(
    {
      id: 'one',
      workspaceId: node.id,
      orgUnitId: null,
      rank: 'C',
      role: 'VIEWER',
      declared: false,
    },
    {
      id: 'two',
      workspaceId: node.id,
      orgUnitId: null,
      rank: 'S',
      role: 'VIEWER',
      declared: false,
    },
  );
  expect(
    (
      await handleRbacMock(
        request('/api/rbac/rank-grants/two/rank', 'PATCH', { rank: 'C' }),
        state,
        'workspace',
      )
    ).status,
  ).toBe(409);
  expect(state.ranks[1].rank).toBe('S');
  expect(
    (
      await handleRbacMock(
        request('/api/rbac/rank-grants/two/rank', 'PATCH', { rank: 'S' }),
        state,
        'workspace',
      )
    ).status,
  ).toBe(204);
  const admin = fixture(state.grants.find((value) => value.role === 'ADMIN'));
  state.grants.push({ ...admin, id: 'viewer', role: 'VIEWER' });
  expect(
    (
      await handleRbacMock(
        request('/api/rbac/grants/viewer/role', 'PATCH', { role: 'ADMIN' }),
        state,
        'workspace',
      )
    ).status,
  ).toBe(409);
  expect(state.audits).toHaveLength(0);
});

it('감사는 생성 before=null과 실제 조직·규칙·방 대상을 기록한다', async () => {
  const state = createRbacMockState();
  const node = fixture(state.nodes.find((value) => value.kind === 'ORG'));
  const response = await handleRbacMock(
    request(`/api/rbac/workspaces/${node.id}/rank-grants`, 'POST', {
      orgUnitId: null,
      rank: 'S',
      role: 'VIEWER',
    }),
    state,
    'workspace',
  );
  const rule = await response.json();
  expect(state.audits[0].beforeJson).toBeNull();
  expect(state.audits[0].targetRef).toMatchObject({ rank_grn_id: rule.id });
  expect(state.audits[0].actorUserId).not.toBe(state.organizations[0].id);
  await handleRbacMock(
    request(
      `/api/platform/rbac/tenants/default/org-units/${state.organizations[0].id}/name`,
      'PATCH',
      { name: '새 조직명' },
    ),
    state,
    'platform',
  );
  expect(state.audits[1].targetRef).toMatchObject({
    org_unit_key: 'development',
  });
  const common = fixture(state.nodes.find((value) => value.kind === 'COMMON'));
  state.grants.push({
    ...state.grants[0],
    id: 'common-admin',
    workspaceId: common.id,
  });
  await handleRbacMock(
    request(`/api/collab/threads/${state.rooms[0].id}/workspace`, 'PATCH', {
      workspaceId: common.id,
    }),
    state,
    'workspace',
  );
  expect(
    state.audits
      .slice(-2)
      .every(
        (row) =>
          (row.targetRef as { thread_id: string }).thread_id ===
          state.rooms[0].id,
      ),
  ).toBe(true);
});

it('Workspace 생성의 초기 ADMIN은 fixture가 아니라 현재 요청자 팀에 부여한다', async () => {
  const state = createRbacMockState();
  const node = fixture(state.nodes.find((value) => value.kind === 'ORG'));
  state.organizations.push({
    id: 'new-org',
    key: 'new-org',
    name: '새 팀',
    status: 'ACTIVE',
    declared: false,
  });
  state.people[0].teamId = 'new-org';
  state.grants[0].orgUnitId = 'new-org';
  const response = await handleRbacMock(
    request('/api/rbac/workspaces', 'POST', {
      parentId: node.id,
      name: '하위 팀',
      kind: 'WORK',
    }),
    state,
    'workspace',
  );
  expect(response.status).toBe(201);
  const { id } = await response.json();
  expect(
    state.grants.find((grant) => grant.workspaceId === id)?.orgUnitId,
  ).toBe('new-org');
  expect(state.audits.map((row) => row.eventKind)).toEqual([
    'NODE_CREATED',
    'POLICY_ADDED',
  ]);
  expect(state.audits[1].workspaceNodeId).toBe(id);
  expect(state.audits[1].targetRef).toMatchObject({
    org_unit_key: 'new-org',
    role: 'ADMIN',
  });
  expect(state.audits[0].requestId).toBe(state.audits[1].requestId);
  expect(
    (
      await (
        await handleRbacMock(
          request('/api/rbac/workspaces'),
          state,
          'workspace',
        )
      ).json()
    ).some((value: { id: string }) => value.id === id),
  ).toBe(true);
});
