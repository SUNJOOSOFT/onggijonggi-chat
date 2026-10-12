package com.onggijonggi.etl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : FakeServices.java
 * Description : 통합 테스트용 가짜 문서 워커(원본 GET)와 OpenAI 호환 임베딩·대화(태깅) 서버. 일시 장애·차원 불일치·응답 지연을 테스트가 조절한다.
 */
final class FakeServices implements AutoCloseable {

	static final String API_KEY = "test-key";
	static final int DIMENSIONS = 1024;

	final Map<String, byte[]> sources = new ConcurrentHashMap<>();
	/** 0보다 크면 그만큼 임베딩 요청에 503을 준다. */
	final AtomicInteger embeddingFailures = new AtomicInteger();
	volatile boolean wrongDimensions;
	/** null이 아니면 임베딩 응답을 이 래치가 풀릴 때까지 미룬다. */
	volatile CountDownLatch embeddingGate;
	final AtomicInteger embeddingCalls = new AtomicInteger();
	/** 0보다 크면 그만큼 태깅(대화) 요청에 503을 준다. */
	final AtomicInteger chatFailures = new AtomicInteger();
	final AtomicInteger chatCalls = new AtomicInteger();
	/** 태깅 응답 본문(모델이 돌려준 content). */
	volatile String chatAnswer = "{\"category\":\"인사·총무\",\"keywords\":[\"연차\",\"이월\"],\"summary\":\"휴가 규정이다.\"}";

	private final HttpServer server;
	private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();
	private final ObjectMapper json = JsonMapper.builder().build();

	FakeServices() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/v1/thread-sources/", this::source);
		server.createContext("/v1/embeddings", this::embeddings);
		server.createContext("/v1/chat/completions", this::chat);
		server.setExecutor(executor);
		server.start();
	}

	String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private void source(HttpExchange exchange) throws IOException {
		if (!API_KEY.equals(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"))) {
			respond(exchange, 401, "{}");
			return;
		}
		String path = exchange.getRequestURI().getPath();
		byte[] bytes = sources.get(path.substring(path.lastIndexOf('/') + 1));
		if (bytes == null) {
			respond(exchange, 404, "{}");
			return;
		}
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private void embeddings(HttpExchange exchange) throws IOException {
		embeddingCalls.incrementAndGet();
		// 실제 임베딩 서버(vLLM·uvicorn)는 HTTP/2 업그레이드(h2c) 요청의 본문을 받지 못해 400을 준다. 같은 조건을 재현한다.
		if (exchange.getRequestHeaders().containsKey("Upgrade")) {
			respond(exchange, 400, "{\"error\":{\"message\":\"body Field required\"}}");
			return;
		}
		JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes());
		CountDownLatch gate = embeddingGate;
		if (gate != null) {
			try {
				gate.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		}
		if (embeddingFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
			respond(exchange, 503, "{\"error\":\"busy\"}");
			return;
		}
		int dimensions = wrongDimensions ? 8 : DIMENSIONS;
		List<Map<String, Object>> data = new ArrayList<>();
		JsonNode inputs = request.path("input");
		for (int i = 0; i < inputs.size(); i++) {
			float[] vector = new float[dimensions];
			java.util.Arrays.fill(vector, (float) (1 / Math.sqrt(dimensions)));
			data.add(Map.of("object", "embedding", "index", i, "embedding", vector));
		}
		respond(exchange, 200, json.writeValueAsString(Map.of("object", "list", "model", request.path("model").asString(), "data", data)));
	}

	private void chat(HttpExchange exchange) throws IOException {
		chatCalls.incrementAndGet();
		exchange.getRequestBody().readAllBytes();
		if (chatFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
			respond(exchange, 503, "{\"error\":\"busy\"}");
			return;
		}
		respond(exchange, 200, json.writeValueAsString(Map.of("choices", List.of(Map.of("index", 0, "finish_reason", "stop",
				"message", Map.of("role", "assistant", "content", chatAnswer))))));
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	@Override
	public void close() {
		server.stop(0);
		executor.shutdownNow();
	}
}
