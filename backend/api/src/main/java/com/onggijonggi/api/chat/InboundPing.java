package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Class Name : InboundPing.java
 * Description : 연결이 살아 있는지 묻는 클라이언트 프레임(이슈 #160). 서버는 곧바로
 *               {@link PongFrame}으로 답한다. 내용은 없다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundPing() implements InboundFrame {
}
