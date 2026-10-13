package com.onggijonggi.api.chat;

import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class Name : AiMentionParser.java
 * Description : 협업 채팅의 서버 측 {@code @AI}·{@code @SOL}·{@code @FIN}·{@code @LAW} 멘션 계약을
 *               해석한다(이슈 #357). {@code @AI}는 역할 구분이 없는 기존 동작이라 agent가 null이다.
 */
final class AiMentionParser {

	private static final Pattern MENTION = Pattern.compile(
			"(?iu)(?<![\\p{L}\\p{N}_])@(ai|sol|fin|law)(?![A-Za-z0-9_])");

	private AiMentionParser() {
	}

	/**
	* 한 메시지에 서로 다른 종류의 멘션이 섞이면(예: {@code @FIN @LAW}) ambiguous가 true다 — 이번
	* 범위는 멘션 하나만 허용한다. 같은 종류를 여러 번 쓰는 것(예: {@code @AI ... @AI ...})은
	* ambiguous가 아니다.
	*/
	static MentionResult parse(String content) {
		Matcher matcher = MENTION.matcher(content);
		EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
		while (matcher.find()) {
			kinds.add(Kind.valueOf(matcher.group(1).toUpperCase()));
		}
		if (kinds.isEmpty()) {
			return new MentionResult(false, content, null, false);
		}
		if (kinds.size() > 1) {
			return new MentionResult(true, content, null, true);
		}
		Agent agent = kinds.iterator().next().agent;
		return new MentionResult(true, matcher.replaceAll("").trim(), agent, false);
	}

	private enum Kind {
		AI(null), SOL(Agent.SOL), FIN(Agent.FIN), LAW(Agent.LAW);

		private final Agent agent;

		Kind(Agent agent) {
			this.agent = agent;
		}
	}

	/** agent는 {@code @AI}면 null, {@code @SOL}·{@code @FIN}·{@code @LAW}면 해당 페르소나다.
	 * ambiguous면 agent·prompt는 쓰지 않는다(호출부가 MALFORMED_REQUEST로 거절한다). */
	record MentionResult(boolean mentioned, String prompt, Agent agent, boolean ambiguous) {
	}

}
