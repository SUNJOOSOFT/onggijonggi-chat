'use client';

import { Button } from '@/components/ui/button';
import {
  type PlatformOrganization,
  type Tenant,
  listTenants,
  platformOrgPath,
  rbacRequest,
} from '@/lib/api/rbac-management';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ChangeButton, Section, fieldClass } from './management-controls';
import { PermissionsAdmin } from './permissions-admin';

export function PlatformManagement() {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [tenantId, setTenantId] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    let alive = true;
    listTenants()
      .then((values) => {
        if (alive) {
          setTenants(values);
          setTenantId(values[0]?.id ?? '');
        }
      })
      .catch((reason) => {
        if (alive)
          setError(reason instanceof Error ? reason.message : '조회 실패');
      });
    return () => {
      alive = false;
    };
  }, []);
  const tenant = tenants.find((value) => value.id === tenantId);
  return (
    <>
      {error && <p role="alert">{error}</p>}
      <label>
        조직 관리 Tenant{' '}
        <select
          aria-label="조직 Tenant"
          className={fieldClass}
          value={tenantId}
          onChange={(event) => setTenantId(event.target.value)}
        >
          <option value="">선택하세요</option>
          {tenants.map((value) => (
            <option key={value.id} value={value.id}>
              {value.name} ({value.status})
            </option>
          ))}
        </select>
      </label>
      {tenant && <OrganizationManagement key={tenant.id} tenant={tenant} />}
      <Section title="기존 사람 배정·CSV">
        <PermissionsAdmin embedded />
      </Section>
    </>
  );
}

function OrganizationManagement({ tenant }: { tenant: Tenant }) {
  const [organizations, setOrganizations] = useState<PlatformOrganization[]>(
    [],
  );
  const [name, setName] = useState('');
  const [key, setKey] = useState('');
  const [error, setError] = useState('');
  const sequence = useRef(0);
  const reload = useCallback(async () => {
    const request = ++sequence.current;
    try {
      const result = await rbacRequest<PlatformOrganization[]>(
        platformOrgPath(tenant.key),
      );
      if (request === sequence.current) {
        setOrganizations(result);
        setError('');
      }
    } catch (reason) {
      if (request === sequence.current) {
        setOrganizations([]);
        setError(reason instanceof Error ? reason.message : '조회 실패');
      }
      throw reason;
    }
  }, [tenant.key]);
  useEffect(() => {
    void reload().catch(() => undefined);
    return () => {
      ++sequence.current;
    };
  }, [reload]);
  return (
    <Section title="조직 관리">
      {error && <p role="alert">{error}</p>}
      <Button onClick={() => void reload().catch(() => undefined)}>
        조직 다시 조회
      </Button>
      <div className="flex flex-wrap gap-2">
        <input
          aria-label="새 조직 key"
          className={fieldClass}
          placeholder="불변 key"
          value={key}
          onChange={(event) => setKey(event.target.value)}
        />
        <input
          aria-label="새 조직 이름"
          className={fieldClass}
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
        <ChangeButton
          label="조직 만들기"
          description="조직과 필수 COMMON VIEWER 부여를 함께 만듭니다."
          disabled={
            tenant.status !== 'ACTIVE' ||
            !!error ||
            !name.trim() ||
            !/^[a-z][a-z0-9-]{0,62}$/.test(key)
          }
          change={() =>
            rbacRequest(platformOrgPath(tenant.key), 'POST', { key, name })
          }
          refresh={reload}
        />
      </div>
      {organizations.map((value) => (
        <OrganizationRow
          key={`${value.id}-${value.name}-${value.status}`}
          organization={value}
          tenant={tenant}
          refresh={reload}
        />
      ))}
    </Section>
  );
}
function OrganizationRow({
  organization,
  tenant,
  refresh,
}: {
  organization: PlatformOrganization;
  tenant: Tenant;
  refresh: () => Promise<void>;
}) {
  const [name, setName] = useState(organization.name);
  const protectedRow = organization.declared || tenant.status !== 'ACTIVE';
  const base = `${platformOrgPath(tenant.key)}/${organization.id}`;
  return (
    <div className="flex flex-wrap items-center gap-2 border-b py-2">
      <span>
        {organization.key} · {organization.status}
        {organization.declared ? ' · 선언 조직' : ''}
      </span>
      <input
        aria-label={`${organization.key} 이름`}
        className={fieldClass}
        disabled={protectedRow}
        value={name}
        onChange={(event) => setName(event.target.value)}
      />
      <ChangeButton
        label="조직 이름 저장"
        description="조직 이름을 변경합니다. key는 바뀌지 않습니다."
        disabled={protectedRow || !name.trim() || name === organization.name}
        change={() => rbacRequest(`${base}/name`, 'PATCH', { name })}
        refresh={refresh}
      />
      <ChangeButton
        label={
          organization.status === 'ACTIVE' ? '조직 비활성화' : '조직 재활성화'
        }
        description="활성 배정과 마지막 ADMIN 보호를 검사합니다. 사용자 배정은 기존 화면에서 먼저 변경하세요."
        disabled={protectedRow}
        change={() =>
          rbacRequest(
            `${base}/${organization.status === 'ACTIVE' ? 'deactivate' : 'reactivate'}`,
            'POST',
          )
        }
        refresh={refresh}
      />
    </div>
  );
}
