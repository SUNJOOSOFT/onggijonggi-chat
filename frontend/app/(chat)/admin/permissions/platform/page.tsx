import { ManagementShell } from '@/components/permissions/management-shell';
import { PlatformManagement } from '@/components/permissions/platform-management';
export default function Page() {
  return (
    <ManagementShell title="플랫폼 권한 관리" area="platform">
      <PlatformManagement />
    </ManagementShell>
  );
}
