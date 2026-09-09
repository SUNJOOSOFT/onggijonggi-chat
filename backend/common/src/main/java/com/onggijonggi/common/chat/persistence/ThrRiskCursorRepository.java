package com.onggijonggi.common.chat.persistence;

import com.onggijonggi.common.chat.domain.ThrRiskCursor;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : ThrRiskCursorRepository.java
 * Description : thr_risk_crs JPA 레포지토리.
 */
public interface ThrRiskCursorRepository extends JpaRepository<ThrRiskCursor, UUID> {

}
