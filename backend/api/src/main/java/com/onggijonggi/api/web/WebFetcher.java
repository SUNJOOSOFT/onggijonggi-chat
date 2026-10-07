package com.onggijonggi.api.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * Class Name : WebFetcher.java
 * Description : 공인 주소의 웹 페이지를 읽어 글자만 돌려준다. 리다이렉트는 직접 따라가며 매번 PublicUrlGuard로 다시 본다 —
 *               HttpClient가 알아서 따라가면 공인 주소가 내부 주소로 보내는 리다이렉트를 막지 못한다.
 *               HTML은 스크립트·스타일을 뺀 본문 글자로, text/*와 JSON은 그대로 읽는다. 그 밖의 형식(PDF·이미지 등)은 거부한다.
 *               본문은 maxBytes까지만 내려받는다.
 */
class WebFetcher {

	/** 읽은 페이지. finalUrl은 리다이렉트를 따라간 마지막 주소다. */
	record Page(URI finalUrl, String title, String text) { }

	private static final String USER_AGENT = "Mozilla/5.0 (compatible; onggijonggi-chat)";

	private final HttpClient client;
	private final PublicUrlGuard guard;
	private final WebProperties.Fetch settings;

	WebFetcher(HttpClient client, PublicUrlGuard guard, WebProperties.Fetch settings) {
		this.client = client;
		this.guard = guard;
		this.settings = settings;
	}

	Page fetch(URI start) {
		URI current = start;
		for (int hop = 0; hop <= settings.maxRedirects(); hop++) {
			guard.check(current);
			HttpResponse<InputStream> response = send(current);
			int status = response.statusCode();
			if (status >= 300 && status < 400) {
				String location = response.headers().firstValue("Location").orElse(null);
				close(response);
				if (location == null) throw new WebToolException("리다이렉트에 이동할 주소가 없다");
				try {
					current = current.resolve(location);
				} catch (IllegalArgumentException malformed) {
					throw new WebToolException("리다이렉트 주소가 올바르지 않다");
				}
				continue;
			}
			if (status != 200) {
				close(response);
				throw new WebToolException("페이지를 읽지 못했다(HTTP " + status + ")");
			}
			return read(current, response);
		}
		throw new WebToolException("리다이렉트가 너무 많다");
	}

	private HttpResponse<InputStream> send(URI uri) {
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(settings.timeout())
				.header("User-Agent", USER_AGENT)
				.header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,application/json;q=0.8")
				.GET().build();
		try {
			return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (IOException error) {
			throw new WebToolException("페이지에 연결하지 못했다", error);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new WebToolException("읽기가 중단됐다", interrupted);
		}
	}

	private Page read(URI uri, HttpResponse<InputStream> response) {
		String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
		boolean html = contentType.contains("text/html") || contentType.contains("application/xhtml");
		boolean plain = contentType.startsWith("text/") || contentType.contains("json");
		if (!html && !plain) {
			close(response);
			throw new WebToolException("글자로 읽을 수 없는 형식이다: " + (contentType.isBlank() ? "알 수 없음" : contentType.split(";")[0]));
		}
		byte[] bytes;
		try (InputStream body = response.body()) {
			bytes = body.readNBytes(settings.maxBytes());
		} catch (IOException error) {
			throw new WebToolException("페이지를 끝까지 읽지 못했다", error);
		}
		Charset charset = charset(contentType);
		if (!html) return new Page(uri, "", new String(bytes, charset == null ? StandardCharsets.UTF_8 : charset));
		Document document;
		try {
			// 헤더에 문자셋이 없으면 jsoup이 BOM·<meta charset>으로 정한다(EUC-KR로 된 국내 페이지 등).
			document = Jsoup.parse(new ByteArrayInputStream(bytes), charset == null ? null : charset.name(), uri.toString());
		} catch (IOException impossible) {
			throw new WebToolException("페이지를 해석하지 못했다", impossible);
		}
		document.select("script, style, noscript, svg, iframe").remove();
		return new Page(uri, document.title(), document.body() == null ? "" : document.body().text());
	}

	/** Content-Type 헤더의 문자셋. 없거나 모르는 이름이면 null이다. */
	private static Charset charset(String contentType) {
		int at = contentType.indexOf("charset=");
		if (at < 0) return null;
		String name = contentType.substring(at + 8).split("[;\\s]")[0].replace("\"", "");
		try {
			return Charset.forName(name);
		} catch (RuntimeException unsupported) {
			return null;
		}
	}

	private static void close(HttpResponse<InputStream> response) {
		try {
			response.body().close();
		} catch (IOException ignored) {
			// 버릴 본문이다.
		}
	}
}
