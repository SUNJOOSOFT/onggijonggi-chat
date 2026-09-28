package com.onggijonggi.api.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Class Name : CreateCollabThreadRequest.java
 * Description : 01·CLIENT가 협업방 생성을 위해 보내는 제목 입력이다. thr.title의 DB 상한과
 *               같은 255자로 검증하며, 유효한 제목의 앞뒤 공백은 호출자가 의도한 값으로 보존한다.
 *               workspaceId는 방을 둘 워크스페이스다. 권한 판정이 켜진 배포에서는 필수이고, 빠졌을 때의 처리는
 *               ThreadWorkspaceService가 판정 스위치를 보고 정한다 — 스위치에 따라 필수 여부가 달라 여기서 검증하지 않는다.
 * @param title 새 협업방 제목
 * @param workspaceId 방을 둘 워크스페이스(GET /api/workspaces의 id)
 */
public record CreateCollabThreadRequest(
		@NotBlank @Size(max = 255) String title,
		UUID workspaceId
) {
}
