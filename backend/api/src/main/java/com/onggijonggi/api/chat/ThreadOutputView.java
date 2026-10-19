package com.onggijonggi.api.chat;

import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : ThreadOutputView.java
 * Description : 방의 "출력"(AI가 만든 문서) 한 건의 API 응답(#414). 저장소 위치는 싣지 않는다 — 내려받기는 서버가 방 접근을
 *               확인한 뒤 대신 읽는다. 만든 사람은 Keycloak subject로 가리키고, 표시 이름은 싣지 않아 화면이 참여자 명단에서
 *               찾으므로 이름이 바뀌면 지난 출력도 최신 이름을 따른다.
 * @param fileKind 파일 형식. 허용값은 thr_out.file_kind의 CHECK 제약이 정한다
 */
public record ThreadOutputView(UUID id, String fileName, String fileKind, long fileSize, String authorSubject,
		Instant createdAt) {
}
