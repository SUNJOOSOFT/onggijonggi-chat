package com.onggijonggi.api.chat;

/**
 * Class Name : PongFrame.java
 * Description : 클라이언트 ping({@link InboundPing})에 대한 서버의 응답(이슈 #160).
 *
 *               WebSocket 프로토콜 ping이 아니라 애플리케이션 프레임으로 둔 이유는 브라우저가 프로토콜
 *               ping을 스크립트에 보여주지 않아서다. 백그라운드 탭이나 절전에서 깨어난 뒤 소켓이
 *               사실상 죽었는데 close가 오지 않으면, 클라이언트는 보낸 ping에 pong이 안 오는 것으로만
 *               그걸 안다.
 *
 *               이번 범위는 프레임과 즉시 응답까지다. 주기적으로 ping을 보내고 무응답이면 다시 붙는
 *               하트비트 동작은 커넥션을 사용자당 하나로 모으는 #161이 붙인다.
 *
 *               방에 속한 프레임이 아니라 threadId가 없다.
 */
public record PongFrame() implements WsFrame {
}
