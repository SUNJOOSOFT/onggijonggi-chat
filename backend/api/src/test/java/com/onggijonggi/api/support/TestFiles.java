package com.onggijonggi.api.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Class Name : TestFiles.java
 * Description : 테스트가 bootstrap 설정처럼 내용을 나중에 쓰는 임시 파일을 만든다. JVM이 끝나면 지운다.
 */
public final class TestFiles {

	private TestFiles() {
	}

	public static Path tempYaml(String prefix) {
		try {
			Path file = Files.createTempFile(prefix, ".yml");
			file.toFile().deleteOnExit();
			return file;
		} catch (IOException error) {
			throw new IllegalStateException("임시 설정 파일을 만들 수 없다", error);
		}
	}
}
