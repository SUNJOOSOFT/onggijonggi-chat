package com.onggijonggi.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicUrlGuardTest {

	/** DNS 없이 호스트 이름을 정한 주소로 바꾼다. IP 리터럴은 그대로 해석된다. */
	private static PublicUrlGuard resolvingTo(String address) {
		return new PublicUrlGuard(host -> new InetAddress[] {InetAddress.getByName(address)});
	}

	@ParameterizedTest
	@ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.20", "169.254.169.254", "100.64.0.1",
			"0.0.0.0", "224.0.0.1", "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1"})
	void rejectsHostsThatResolveToInternalAddresses(String address) {
		assertThatThrownBy(() -> resolvingTo(address).check(URI.create("https://example.com/")))
				.isInstanceOf(WebToolException.class).hasMessageContaining("내부 주소");
	}

	@Test
	void rejectsWhenAnyResolvedAddressIsInternal() throws Exception {
		PublicUrlGuard guard = new PublicUrlGuard(host -> new InetAddress[] {
				InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")});
		assertThatThrownBy(() -> guard.check(URI.create("https://example.com/"))).isInstanceOf(WebToolException.class);
	}

	@Test
	void acceptsPublicAddressesOnDefaultPorts() {
		PublicUrlGuard guard = resolvingTo("93.184.216.34");
		assertThat(guard.check(URI.create("https://example.com/a?b=c"))).isEqualTo(URI.create("https://example.com/a?b=c"));
		assertThat(guard.check(URI.create("http://example.com:80/"))).isNotNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"ftp://example.com/", "file:///etc/passwd", "https://example.com:8443/",
			"http://example.com:5432/", "https://user:pw@example.com/"})
	void rejectsOtherSchemesPortsAndUserInfo(String url) {
		assertThatThrownBy(() -> resolvingTo("93.184.216.34").check(URI.create(url))).isInstanceOf(WebToolException.class);
	}
}
