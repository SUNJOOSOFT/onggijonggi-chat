/********************************************************
 파일명 : rendered-message.ts (lib/chat)
 설 명 : 화면이 말풍선 하나를 그리는 데 필요한 최소 모양.

 렌더 계층(messages.tsx·message.tsx·message-actions.tsx·message-editor.tsx)이 AI SDK의
 Message 타입을 직접 받던 것을 대신한다. 실제로 쓰이던 필드는 id·role·content 셋뿐이었는데,
 그 타입을 받는 것만으로 화면이 특정 SDK에 묶여 있었다.

 방 상태의 RoomMessage(room-state.ts)와 따로 두는 이유는 역할이 달라서다 — 그쪽은 프레임을
 접어 둔 도메인 상태(seq·streaming·turnId·citations까지 든다)이고, 이쪽은 "한 줄을 그리는 데
 필요한 것"이다. 사람과 AI를 RoomMessage는 보낸 사람 유무로, 이쪽은 role로 가른다.
 *********************************************************/

export interface RenderedMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
}
