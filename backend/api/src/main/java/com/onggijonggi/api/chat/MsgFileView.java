package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.MsgFile;
import java.util.UUID;

/**
 * Class Name : MsgFileView.java
 * Description : 화면에 보이는 첨부 파일 한 개. 업로드 응답, chat.message 프레임, 이력 응답이 같은 모양을
 *               쓴다. 추출한 텍스트는 AI 문맥에만 쓰고 화면으로는 내려보내지 않는다.
 *
 * @param id 첨부 id. 발화의 attachmentIds에 이 값을 싣는다
 * @param fileName 올린 파일 이름
 */
public record MsgFileView(UUID id, String fileName) {

	static MsgFileView from(MsgFile file) {
		return new MsgFileView(file.getId(), file.getFileName());
	}

}
