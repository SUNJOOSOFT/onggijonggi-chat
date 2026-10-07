package com.onggijonggi.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Class Name : WebFetcherTest.java
 * Description : 로컬 HTTP 서버로 웹 읽기를 검증한다(HTML 글자 추출, meta 문자셋, 바이트 상한, 리다이렉트마다 재검사, 형식·상태 거부).
 *               주소 검사는 PublicUrlGuardTest가 따로 보므로 여기서는 경로로 거부를 흉내 낸다.
 */
class WebFetcherTest {

	private HttpServer server;
	private String base;

	/** /internal로 가는 요청만 내부 주소로 보고 막는다. */
	private final PublicUrlGuard guard = new PublicUrlGuard() {
		@Override
		URI check(URI uri) {
			if (uri.getPath().startsWith("/internal")) throw new WebToolException("내부 주소는 읽을 수 없다");
			return uri;
		}
	};

	private final WebFetcher fetcher = new WebFetcher(
			HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), guard,
			new WebProperties.Fetch(512, 8000, 2, Duration.ofSeconds(5)));

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		respond("/page", 200, "text/html; charset=utf-8",
				"<html><head><title>제목</title><script>alert(1)</script></head><body><p>본문</p><style>p{}</style></body></html>",
				StandardCharsets.UTF_8);
		respond("/euckr", 200, "text/html",
				"<html><head><meta charset=\"euc-kr\"></head><body>한글</body></html>", Charset.forName("EUC-KR"));
		respond("/big", 200, "text/plain", "a".repeat(1000), StandardCharsets.UTF_8);
		respond("/pdf", 200, "application/pdf", "%PDF", StandardCharsets.UTF_8);
		respond("/missing", 404, "text/html", "없음", StandardCharsets.UTF_8);
		redirect("/to-page", "/page");
		redirect("/to-internal", "/internal/admin");
		redirect("/loop", "/loop");
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void readsHtmlAsTextWithoutScriptsAndStyles() {
		WebFetcher.Page page = fetcher.fetch(URI.create(base + "/page"));
		assertThat(page.title()).isEqualTo("제목");
		assertThat(page.text()).isEqualTo("본문");
	}

	@Test
	void usesTheMetaCharsetWhenTheHeaderHasNone() {
		assertThat(fetcher.fetch(URI.create(base + "/euckr")).text()).isEqualTo("한글");
	}

	@Test
	void downloadsOnlyUpToTheByteLimit() {
		assertThat(fetcher.fetch(URI.create(base + "/big")).text()).hasSize(512);
	}

	@Test
	void followsRedirectsAndChecksEveryHop() {
		WebFetcher.Page page = fetcher.fetch(URI.create(base + "/to-page"));
		assertThat(page.finalUrl()).isEqualTo(URI.create(base + "/page"));

		assertThatThrownBy(() -> fetcher.fetch(URI.create(base + "/to-internal")))
				.isInstanceOf(WebToolException.class).hasMessageContaining("내부 주소");
		assertThatThrownBy(() -> fetcher.fetch(URI.create(base + "/loop")))
				.isInstanceOf(WebToolException.class).hasMessageContaining("리다이렉트가 너무 많다");
	}

	@Test
	void refusesNonTextContentAndErrorStatuses() {
		assertThatThrownBy(() -> fetcher.fetch(URI.create(base + "/pdf"))).hasMessageContaining("application/pdf");
		assertThatThrownBy(() -> fetcher.fetch(URI.create(base + "/missing"))).hasMessageContaining("HTTP 404");
	}

	private void respond(String path, int status, String contentType, String body, Charset charset) {
		server.createContext(path, exchange -> {
			byte[] bytes = body.getBytes(charset);
			exchange.getResponseHeaders().set("Content-Type", contentType);
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
	}

	private void redirect(String path, String location) {
		server.createContext(path, exchange -> {
			exchange.getResponseHeaders().set("Location", location);
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
	}
}
