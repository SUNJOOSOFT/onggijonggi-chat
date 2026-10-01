import { ManagementShell } from '@/components/permissions/management-shell';
import { WorkspaceManagement } from '@/components/permissions/workspace-management';
export default function Page() {
  return (
    <ManagementShell title="Workspace 권한 관리" area="workspace">
      <WorkspaceManagement />
    </ManagementShell>
  );
}
