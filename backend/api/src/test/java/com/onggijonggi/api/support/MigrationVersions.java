package com.onggijonggi.api.support;

/**
 * Class Name : MigrationVersions.java
 * Description : 테스트가 "특정 시점의 스키마"를 만들 때 쓰는 Flyway 버전. migration을 새로 끼워 넣을 때 한곳만 고친다.
 */
public final class MigrationVersions {

	/** 기존 Thread 절체 migration 바로 앞 버전. 이 버전까지가 절체 전 스키마다. */
	public static final String BEFORE_CUTOVER = "20260929055051721";

	private MigrationVersions() {
	}
}
