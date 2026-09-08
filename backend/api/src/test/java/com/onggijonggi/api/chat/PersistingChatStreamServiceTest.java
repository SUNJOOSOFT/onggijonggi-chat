package com.onggijonggi.api.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.auth.CurrentActorProvider;
import com.onggijonggi.common.chat.domain.ChatSess;
import com.onggijonggi.common.chat.persistence.ChatMsgRepository;
import com.onggijonggi.common.chat.persistence.ChatSessRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Class Name : PersistingChatStreamServiceTest.java
 * Description : PersistingChatStreamService 데코레이터의 오케스트레이션(프로비저닝→세션/메시지 저장→
 *               LLM 위임→assistant 저장, 저장 실패 시 채팅은 그대로 진행)을 순수 단위 테스트로 검증한다.
 *               Spring 컨텍스트·실 DB 없이 전 의존성을 Mockito로 대체한다. 인증된 요청자는
 *               CurrentActorProvider 스텁이 그대로 돌려주므로 리액터 Context에 JWT를 주입할 필요가 없다.
 */
@ExtendWith(MockitoExtension.class)
class PersistingChatStreamServiceTest {

	@Mock
	private LlmChatStreamService delegate;
	@Mock
	private CurrentActorProvider currentActorProvider;
	@Mock
	private ChatSessRepository chatSessRepository;
	@Mock
	private ChatMsgRepository chatMsgRepository;

	private PersistingChatStreamService service;

	@BeforeEach
	void setUp() {
		service = new PersistingChatStreamService(delegate, currentActorProvider, chatSessRepository, chatMsgRepository);
	}

	@Test
	void persistsUserAndAssistantMessagesAroundStream() {
		UUID sessionId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		ChatStreamRequest request = new ChatStreamRequest(sessionId, "gemma", List.of(new ChatMessage("user", "안녕")));

		when(currentActorProvider.currentActor()).thenReturn(Mono.just(new CurrentActor(userId, "sub-1", "sub-1")));
		when(chatSessRepository.findById(sessionId)).thenReturn(Optional.empty());
		when(delegate.streamChat(request)).thenReturn(Flux.just("hi", " there"));

		StepVerifier.create(service.streamChat(request))
				.expectNext("hi", " there")
				.verifyComplete();

		verify(chatSessRepository).save(argThat(sess -> sess.getId().equals(sessionId) && sess.getUserId().equals(userId)));
		verify(chatMsgRepository).save(argThat(msg -> "user".equals(msg.getRole()) && "안녕".equals(msg.getContent())));
		verify(chatMsgRepository, timeout(1000))
				.save(argThat(msg -> "assistant".equals(msg.getRole()) && "hi there".equals(msg.getContent())));
	}

	@Test
	void reusesExistingSessionWithoutCreatingNewRow() {
		UUID sessionId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		ChatStreamRequest request = new ChatStreamRequest(sessionId, "gemma", List.of(new ChatMessage("user", "또 물어봄")));

		when(currentActorProvider.currentActor()).thenReturn(Mono.just(new CurrentActor(userId, "sub-1", "sub-1")));
		when(chatSessRepository.findById(sessionId)).thenReturn(Optional.of(new ChatSess(sessionId, userId, "또 물어봄")));
		when(delegate.streamChat(request)).thenReturn(Flux.just("ok"));

		StepVerifier.create(service.streamChat(request))
				.expectNext("ok")
				.verifyComplete();

		verify(chatSessRepository, never()).save(any());
	}

	@Test
	void skipsPersistingWhenSessionOwnedByAnotherUser() {
		UUID sessionId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID otherUserId = UUID.randomUUID();
		ChatStreamRequest request = new ChatStreamRequest(sessionId, "gemma", List.of(new ChatMessage("user", "남의 세션에 끼어들기")));

		when(currentActorProvider.currentActor()).thenReturn(Mono.just(new CurrentActor(userId, "sub-1", "sub-1")));
		when(chatSessRepository.findById(sessionId)).thenReturn(Optional.of(new ChatSess(sessionId, otherUserId, "원래 제목")));
		when(delegate.streamChat(request)).thenReturn(Flux.just("ok"));

		StepVerifier.create(service.streamChat(request))
				.expectNext("ok")
				.verifyComplete();

		verify(chatSessRepository, never()).save(any());
		verify(chatMsgRepository, never()).save(any());
	}

	@Test
	void streamsNormallyEvenWhenProvisioningFails() {
		UUID sessionId = UUID.randomUUID();
		ChatStreamRequest request = new ChatStreamRequest(sessionId, "gemma", List.of(new ChatMessage("user", "안녕")));

		when(currentActorProvider.currentActor()).thenReturn(Mono.error(new RuntimeException("db down")));
		when(delegate.streamChat(request)).thenReturn(Flux.just("hi"));

		StepVerifier.create(service.streamChat(request))
				.expectNext("hi")
				.verifyComplete();

		verify(chatSessRepository, never()).save(any());
		verify(chatMsgRepository, never()).save(argThat(msg -> "user".equals(msg.getRole())));
		verify(chatMsgRepository, timeout(1000)).save(argThat(msg -> "assistant".equals(msg.getRole())));
	}

}
