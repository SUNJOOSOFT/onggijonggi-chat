package com.onggijonggi.api.web;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Class Name : PublicUrlGuard.java
 * Description : 웹 읽기가 사내망·서버 자신을 부르지 못하게 막는다(SSRF). http·https와 기본 포트(80·443)만 받고, 호스트가
 *               가리키는 주소가 모두 공인 주소여야 한다 — 하나라도 루프백·사설·링크로컬·CGNAT·멀티캐스트·고유 로컬(IPv6)이면 거부다.
 *               리다이렉트마다 다시 부른다. 한계: 검사 뒤 실제 연결 때 DNS가 다른 주소를 돌려주면(DNS rebinding) 막지 못한다 —
 *               운영에서 더 막으려면 bff의 외부 통신을 프록시로 제한한다.
 */
class PublicUrlGuard {

	/** 호스트 이름을 주소로 바꾼다. 테스트가 DNS 없이 바꿔 끼운다. */
	interface Resolver {
		InetAddress[] resolve(String host) throws UnknownHostException;
	}

	private final Resolver resolver;

	PublicUrlGuard() {
		this(InetAddress::getAllByName);
	}

	PublicUrlGuard(Resolver resolver) {
		this.resolver = resolver;
	}

	/** 읽어도 되는 주소면 그대로 돌려주고, 아니면 WebToolException이다. */
	URI check(URI uri) {
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) throw new WebToolException("http·https 주소만 읽을 수 있다");
		if (uri.getUserInfo() != null) throw new WebToolException("사용자 정보가 든 주소는 읽지 않는다");
		int port = uri.getPort();
		if (port != -1 && port != (scheme.equals("http") ? 80 : 443)) throw new WebToolException("기본 포트(80·443) 주소만 읽을 수 있다");
		String host = uri.getHost();
		if (host == null || host.isBlank()) throw new WebToolException("호스트가 없는 주소다");
		InetAddress[] addresses;
		try {
			addresses = resolver.resolve(host);
		} catch (UnknownHostException unknown) {
			throw new WebToolException("주소를 찾지 못했다: " + host);
		}
		if (addresses.length == 0) throw new WebToolException("주소를 찾지 못했다: " + host);
		for (InetAddress address : addresses) {
			if (!isPublic(address)) throw new WebToolException("내부 주소는 읽을 수 없다");
		}
		return uri;
	}

	static boolean isPublic(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
		byte[] bytes = address.getAddress();
		if (address instanceof Inet4Address) {
			int first = bytes[0] & 0xff, second = bytes[1] & 0xff;
			if (first == 0) return false;                                  // 0.0.0.0/8
			if (first == 100 && second >= 64 && second <= 127) return false; // 100.64.0.0/10 (CGNAT)
			if (first == 192 && second == 0 && (bytes[2] & 0xff) == 0) return false; // 192.0.0.0/24
			if (first == 198 && (second == 18 || second == 19)) return false; // 198.18.0.0/15
			return first < 240;                                            // 240.0.0.0/4 예약·브로드캐스트
		}
		if (address instanceof Inet6Address) {
			if ((bytes[0] & 0xfe) == 0xfc) return false;                   // fc00::/7 고유 로컬
			// IPv4 매핑(::ffff:a.b.c.d)은 안의 IPv4로 다시 본다. Java는 대개 Inet4Address로 바꿔 주지만 그렇지 않은 경우를 대비한다.
			boolean mapped = true;
			for (int i = 0; i < 10; i++) mapped &= bytes[i] == 0;
			if (mapped && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
				try {
					return isPublic(InetAddress.getByAddress(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]}));
				} catch (UnknownHostException impossible) {
					return false;
				}
			}
		}
		return true;
	}
}
