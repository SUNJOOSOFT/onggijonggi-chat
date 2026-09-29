package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.auth.CurrentActorProvider;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : MsgFileController.java
 * Description : 채팅 첨부 파일 업로드. 방과 묶지 않는다 — 1:1의 첫 발화는 방이 아직 없어서다. 올린
 *               사람만 자기 발화에 실을 수 있고, 어느 방에 붙을지는 발화할 때 정해진다
 *               (MsgFileService.resolveForMessageBlocking).
 */
@RestController
public class MsgFileController {

	private final MsgFileService msgFileService;
	private final CurrentActorProvider currentActorProvider;

	public MsgFileController(MsgFileService msgFileService, CurrentActorProvider currentActorProvider) {
		this.msgFileService = msgFileService;
		this.currentActorProvider = currentActorProvider;
	}

	/** 한도를 넘는 파일은 끝까지 모으지 않고 넘는 순간 멈춘다(DataBufferUtils.join의 상한). */
	@PostMapping(path = "/api/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public Mono<MsgFileView> upload(@RequestPart("file") FilePart file) {
		return currentActorProvider.currentActor().map(CurrentActor::userId)
				.flatMap(userId -> DataBufferUtils.join(file.content(), MsgFileService.MAX_FILE_BYTES)
						.map(buffer -> {
							byte[] bytes = new byte[buffer.readableByteCount()];
							buffer.read(bytes);
							DataBufferUtils.release(buffer);
							return bytes;
						})
						.onErrorMap(DataBufferLimitException.class,
								error -> new MsgFileRejectedException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
										"파일은 " + MsgFileService.MAX_FILE_BYTES / (1024 * 1024) + "MB까지 올릴 수 있습니다."))
						.publishOn(Schedulers.boundedElastic())
						.map(bytes -> msgFileService.uploadBlocking(userId, file.filename(), bytes)))
				.map(MsgFileView::from);
	}

}
