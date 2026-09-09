package com.onggijonggi.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.chat.InvitationAcceptanceService;
import com.onggijonggi.common.user.AppUser;
import com.onggijonggi.common.user.AppUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.test.StepVerifier;

/**
 * Class Name : UserIdentityServiceTest.java
 * Description : JIT 프로비저닝(resolveOrProvision)의 조회/생성 분기를 순수 단위 테스트로 검증한다.
 *               Spring 컨텍스트·실 DB 없이 AppUserRepository를 Mockito로 대체한다.
 */
@ExtendWith(MockitoExtension.class)
class UserIdentityServiceTest {

	@Mock
	private AppUserRepository appUserRepository;

	@Mock
	private InvitationAcceptanceService invitationAcceptanceService;

	@Test
	void returnsExistingUserIdWithoutCreatingNewRow() {
		AppUser existing = new AppUser("sub-1");
		when(appUserRepository.findByKeycloakSubj("sub-1")).thenReturn(Optional.of(existing));
		UserIdentityService service = new UserIdentityService(appUserRepository, invitationAcceptanceService);

		StepVerifier.create(service.resolveOrProvision("sub-1"))
				.expectNext(existing.getId())
				.verifyComplete();

		verify(appUserRepository, never()).save(any());
	}

	@Test
	void createsNewUserWhenNotFound() {
		when(appUserRepository.findByKeycloakSubj("sub-2")).thenReturn(Optional.empty());
		when(appUserRepository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
		UserIdentityService service = new UserIdentityService(appUserRepository, invitationAcceptanceService);

		StepVerifier.create(service.resolveOrProvision("sub-2"))
				.assertNext(id -> assertThat(id).isNotNull())
				.verifyComplete();

		verify(appUserRepository).save(any(AppUser.class));
	}

	/**
	* 같은 사용자의 동시 첫 요청(브라우저 탭 두 개 등)이 경합하면, 조회 시점엔 없다가 저장 시점엔
	* unique 제약(keycloak_subj)에 걸린다 — 이미 다른 요청이 먼저 만든 것이므로 예외를 삼키고
	* 그 행을 재조회해 써야 한다.
	*/
	@Test
	void recoversWhenConcurrentRequestWinsTheRace() {
		AppUser winner = new AppUser("sub-3");
		when(appUserRepository.findByKeycloakSubj("sub-3"))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(winner));
		when(appUserRepository.save(any(AppUser.class)))
				.thenThrow(new DataIntegrityViolationException("keycloak_subj unique 제약 위반"));
		UserIdentityService service = new UserIdentityService(appUserRepository, invitationAcceptanceService);

		StepVerifier.create(service.resolveOrProvision("sub-3"))
				.expectNext(winner.getId())
				.verifyComplete();
	}

}
