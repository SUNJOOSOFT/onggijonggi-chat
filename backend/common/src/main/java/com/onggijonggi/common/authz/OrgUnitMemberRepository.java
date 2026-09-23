package com.onggijonggi.common.authz;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : OrgUnitMemberRepository.java
 * Description : org_unit_mbr 레포지토리. 지금은 subject당 한 행이지만 목록으로 돌려준다 — 겸직이 생겨도
 *               판정 코드가 배정 목록을 도는 모양 그대로 쓸 수 있게 한다.
 */
public interface OrgUnitMemberRepository extends JpaRepository<OrgUnitMember, UUID> {

	List<OrgUnitMember> findBySubject(String subject);
}
