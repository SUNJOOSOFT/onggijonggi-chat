package com.onggijonggi.api.authz;

import com.onggijonggi.api.authz.casbin.grpc.CasbinGrpc;
import com.onggijonggi.api.authz.casbin.grpc.EnforceRequest;
import com.onggijonggi.api.authz.casbin.grpc.NewEnforcerRequest;
import com.onggijonggi.api.authz.casbin.grpc.PolicyRequest;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Class Name : CasbinClient.java
 * Description : 03·CORE casbin-server(gRPC) 호출. 블로킹이라 부르는 쪽이 boundedElastic에서 부른다.
 *               서버는 메모리만 쓰므로 재시작하면 enforcer가 사라진다 — 판정이 "enforcer not found"로 실패하면
 *               적재 상태를 비워 다음 판정 전에 다시 적재하게 한다. 판정 호출의 모든 실패(연결·시간 초과·서버 오류)는
 *               거부(false)다. 서버는 오류가 나도 res=false와 함께 오류를 돌려주므로 오류를 허용으로 읽을 일은 없다.
 */
@Component
public class CasbinClient {

	private static final Logger log = LoggerFactory.getLogger(CasbinClient.class);

	private final CasbinProperties properties;
	private final AtomicReference<ManagedChannel> channel = new AtomicReference<>();
	/** 현재 규칙이 들어 있는 서버 쪽 enforcer 번호. null이면 아직 적재하지 않았거나 서버가 재시작해 잃었다. */
	private final AtomicReference<Integer> enforcerHandle = new AtomicReference<>();

	public CasbinClient(CasbinProperties properties) {
		this.properties = properties;
	}

	public boolean isLoaded() {
		return enforcerHandle.get() != null;
	}

	/**
	 * 새 enforcer를 만들고 규칙을 모두 넣은 뒤에야 판정에 쓰게 바꾼다 — 넣는 도중의 반쪽 규칙으로 판정하지 않는다.
	 * 이전 enforcer는 서버에 남는다(서버에 지우는 API가 없다). 규칙이 적어 부담은 작다.
	 */
	public void load(String modelText, List<CasbinPolicy.Rule> rules) {
		CasbinGrpc.CasbinBlockingStub stub = stub();
		int handle = stub.newEnforcer(NewEnforcerRequest.newBuilder()
				.setModelText(modelText)
				.setAdapterHandle(-1)
				// JSON 요청을 켜야 r.sub의 숫자 속성이 숫자로 비교된다. "ABAC::" 방식은 정책 식에서 동작하지 않는다.
				.setEnableAcceptJsonRequest(true)
				.build()).getHandler();
		for (CasbinPolicy.Rule rule : rules) {
			stub().addPolicy(PolicyRequest.newBuilder().setEnforcerHandler(handle).setPType("p").addAllParams(rule.params()).build());
		}
		enforcerHandle.set(handle);
		log.info("Casbin 규칙 적재: enforcer {}, 규칙 {}개", handle, rules.size());
	}

	/** 적재 전이거나 호출이 실패하면 false다. */
	public boolean enforce(String subjectJson, String object, String action) {
		Integer handle = enforcerHandle.get();
		if (handle == null) return false;
		try {
			return stub().enforce(EnforceRequest.newBuilder()
					.setEnforcerHandler(handle)
					.addParams(subjectJson).addParams(object).addParams(action)
					.build()).getRes();
		} catch (StatusRuntimeException error) {
			String description = error.getStatus().getDescription();
			if (description != null && description.contains("enforcer not found")) {
				// 서버가 재시작했다. 다른 스레드가 이미 새로 적재했으면 그 번호는 지우지 않는다.
				enforcerHandle.compareAndSet(handle, null);
				log.warn("Casbin 서버에 enforcer {}가 없다 — 재시작으로 보고 다시 적재한다", handle);
			} else {
				log.warn("Casbin 판정 실패 — 거부로 처리한다: {}", error.getStatus());
			}
			return false;
		} catch (RuntimeException error) {
			log.warn("Casbin 판정 실패 — 거부로 처리한다: {}", error.getMessage());
			return false;
		}
	}

	private CasbinGrpc.CasbinBlockingStub stub() {
		return CasbinGrpc.newBlockingStub(channel()).withDeadlineAfter(properties.getDeadline().toMillis(), TimeUnit.MILLISECONDS);
	}

	/** 권한 판정이 꺼진 환경에서는 한 번도 불리지 않아 연결을 만들지 않는다. */
	private ManagedChannel channel() {
		ManagedChannel current = channel.get();
		if (current != null) return current;
		if (properties.getAddress().isBlank()) {
			throw new IllegalStateException("app.casbin.address가 비어 있다 — casbin 프로필을 켰는지 확인한다");
		}
		ManagedChannel created = NettyChannelBuilder.forTarget(properties.getAddress()).usePlaintext().build();
		if (channel.compareAndSet(null, created)) return created;
		created.shutdownNow();
		return channel.get();
	}

	@PreDestroy
	void shutdown() {
		ManagedChannel current = channel.get();
		if (current != null) current.shutdownNow();
	}
}
