package com.onggijonggi.api.chat;

import java.util.UUID;

/**
 * Class Name : CreateCollabThreadResponse.java
 * Description : 01·CLIENT가 생성 직후 /collab/{id}로 이동하는 데 필요한 새 협업방 식별자다.
 *               목록 전용 표시명·participants 계약과 생성 응답을 섞지 않는다.
 * @param id 새 협업방 UUID
 */
public record CreateCollabThreadResponse(UUID id) {
}
