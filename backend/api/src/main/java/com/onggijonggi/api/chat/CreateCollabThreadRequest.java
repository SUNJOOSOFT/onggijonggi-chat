package com.onggijonggi.api.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Class Name : CreateCollabThreadRequest.java
 * Description : 01·CLIENT가 협업방 생성을 위해 보내는 제목 입력이다. thr.title의 DB 상한과
 *               같은 255자로 검증하며, 유효한 제목의 앞뒤 공백은 호출자가 의도한 값으로 보존한다.
 * @param title 새 협업방 제목
 */
public record CreateCollabThreadRequest(
		@NotBlank @Size(max = 255) String title
) {
}
