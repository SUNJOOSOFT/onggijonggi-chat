package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Class Name : ChatQueuedStatus.java
 * Description : ChatQueuedFrame의 상태. 프론트 frames.ts의 status: 'queued' | 'cancelled' 리터럴
 *               유니온과 JSON 표현이 정확히 같아야 한다.
 */
public enum ChatQueuedStatus {

	@JsonProperty("queued") QUEUED,
	@JsonProperty("cancelled") CANCELLED

}
