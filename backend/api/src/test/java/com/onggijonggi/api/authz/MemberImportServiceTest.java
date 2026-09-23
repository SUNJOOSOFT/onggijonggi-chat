package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Rank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * Class Name : MemberImportServiceTest.java
 * Description : CSV 임포트의 검증·미리보기·적용 규칙을 검증한다 — 틀린 줄은 모두 알려주고 하나라도 있으면 아무것도 저장하지 않는다,
 *               기본은 미리보기, apply면 배정 서비스를 한 번에 부른다, 행위자는 부른 사람이다.
 *               실제 저장·이력은 OrgUnitMemberServicePostgresTest가 확인한다.
 */
class MemberImportServiceTest {

	private static final UUID TENANT = UUID.randomUUID();
	private static final UUID ACTOR = UUID.randomUUID();

	private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
	private final OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
	private final OrgUnitMemberRepository members = mock(OrgUnitMemberRepository.class);
	private final OrgUnitMemberService memberService = mock(OrgUnitMemberService.class);
	private final MemberImportService service = new MemberImportService(keycloak, orgUnits, members, memberService);

	private final OrgUnit hr = new OrgUnit(TENANT, "hr", "인사팀", OrgUnitStatus.ACTIVE);
	private final OrgUnit fin = new OrgUnit(TENANT, "fin", "재무팀", OrgUnitStatus.ACTIVE);
	private final OrgUnit old = new OrgUnit(TENANT, "old", "옛 팀", OrgUnitStatus.INACTIVE);

	@BeforeEach
	void setUp() {
		when(orgUnits.findAll()).thenReturn(List.of(hr, fin, old));
		when(keycloak.subjectsByEmail(any())).thenAnswer(call -> Mono.just(List.of("sub-" + call.getArgument(0))));
		when(members.findBySubject(any())).thenReturn(List.of());
	}

	private MemberImportService.Report run(String csv, boolean apply) {
		return service.run(csv, apply, ACTOR).block();
	}

	@Test
	void everyBadLineIsReportedAndNothingIsSavedEvenWithApply() {
		String csv = String.join("\n", "email,team,rank",
				"kim@example.com,hr,TL",
				"lee@example.com,nowhere,K",
				"park@example.com,hr,X",
				"kim@example.com,fin,S",
				"not-an-email,hr,S",
				"choi@example.com,old,S",
				"too,many,cells,here");

		MemberImportService.Report report = run(csv, true);

		assertThat(report.applied()).isFalse();
		assertThat(report.problems()).extracting(MemberImportService.Problem::line).containsExactly(3, 4, 5, 6, 7, 8);
		assertThat(report.problems().get(0).message()).contains("없는 팀");
		assertThat(report.problems().get(1).message()).contains("없는 직급");
		assertThat(report.problems().get(2).message()).contains("2번째 줄에도");
		assertThat(report.problems().get(4).message()).contains("비활성 팀");
		verify(memberService, never()).applyAll(anyList(), any());
	}

	@Test
	void theHeaderMustBeEmailTeamRank() {
		assertThat(run("mail,team,rank\nkim@example.com,hr,TL", false).problems())
				.singleElement().satisfies(problem -> assertThat(problem.line()).isEqualTo(1));
	}

	@Test
	void anEmailUnknownToKeycloakIsAProblem() {
		when(keycloak.subjectsByEmail("ghost@example.com")).thenReturn(Mono.just(List.of()));
		when(keycloak.subjectsByEmail("twin@example.com")).thenReturn(Mono.just(List.of("a", "b")));

		MemberImportService.Report report = run("email,team,rank\nghost@example.com,hr,S\ntwin@example.com,hr,S\nkim@example.com,hr,S", true);

		assertThat(report.problems()).extracting(MemberImportService.Problem::message)
				.containsExactly("Keycloak에 없는 이메일이다: ghost@example.com", "이 이메일을 쓰는 계정이 여럿이다: twin@example.com");
		verify(memberService, never()).applyAll(anyList(), any());
	}

	@Test
	void theDefaultIsAPreviewThatSavesNothing() {
		when(members.findBySubject("sub-lee@example.com")).thenReturn(List.of(new OrgUnitMember(TENANT, hr.getId(), "sub-lee@example.com", Rank.K)));
		when(members.findBySubject("sub-park@example.com")).thenReturn(List.of(new OrgUnitMember(TENANT, hr.getId(), "sub-park@example.com", Rank.S)));

		// BOM·CRLF·빈 줄·대소문자·앞뒤 공백을 견딘다.
		MemberImportService.Report report = run("﻿email, team, rank\r\nKim@Example.com ,hr,tl\r\n\r\nlee@example.com,fin,K\r\npark@example.com,hr,S\r\n", false);

		assertThat(report.problems()).isEmpty();
		assertThat(report.applied()).isFalse();
		assertThat(report.rows()).extracting(MemberImportService.Row::outcome).containsExactly("ASSIGNED", "CHANGED", "UNCHANGED");
		assertThat(report.counts()).isEqualTo(Map.of("ASSIGNED", 1, "CHANGED", 1, "UNCHANGED", 1));
		verify(memberService, never()).applyAll(anyList(), any());
	}

	@Test
	@SuppressWarnings("unchecked")
	void applySavesEverythingAtOnceAsTheCallingUser() {
		when(memberService.applyAll(anyList(), any())).thenReturn(List.of(
				new OrgUnitMemberService.Result("sub-kim@example.com", OrgUnitMemberService.Outcome.ASSIGNED),
				new OrgUnitMemberService.Result("sub-lee@example.com", OrgUnitMemberService.Outcome.ASSIGNED)));

		MemberImportService.Report report = run("email,team,rank\nkim@example.com,hr,TL\nlee@example.com,fin,B", true);

		assertThat(report.applied()).isTrue();
		ArgumentCaptor<List<OrgUnitMemberService.Change>> changes = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<OrgUnitMemberService.Actor> actor = ArgumentCaptor.forClass(OrgUnitMemberService.Actor.class);
		verify(memberService).applyAll(changes.capture(), actor.capture());
		assertThat(changes.getValue()).containsExactly(
				OrgUnitMemberService.Change.assign("sub-kim@example.com", hr.getId(), Rank.TL),
				OrgUnitMemberService.Change.assign("sub-lee@example.com", fin.getId(), Rank.B));
		assertThat(actor.getValue().userId()).isEqualTo(ACTOR);
		assertThat(actor.getValue().requestId()).startsWith("import:");
	}
}
