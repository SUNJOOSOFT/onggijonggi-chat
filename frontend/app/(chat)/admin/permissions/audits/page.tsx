import { AuditManagement } from '@/components/permissions/audit-management';
import { ManagementShell } from '@/components/permissions/management-shell';
export default function Page() {
  return (
    <ManagementShell title="권한 변경 감사" area="audit">
      <AuditManagement />
    </ManagementShell>
  );
}
