# frontend — Next.js 채팅 UI

옹기종기는 사람과 AI가 한 대화에서 협업하는 것을 지향하는 오픈소스 LLM 에이전트 솔루션이다. 이 디렉터리는 그 채팅 UI다 — Keycloak 로그인이 붙어 있고, 브라우저는 BFF(`backend/api/`)만 호출하며, 게이트웨이·모델 자격증명은 노출되지 않는다.

- [Next.js](https://nextjs.org) App Router — React Server Components·Server Actions
- [AI SDK](https://sdk.vercel.ai/docs) `useChat` — `streamProtocol: 'text'`로 BFF의 프레이밍 없는 텍스트 스트림을 소비한다
- [shadcn/ui](https://ui.shadcn.com) — [Tailwind CSS](https://tailwindcss.com) 스타일링, [Radix UI](https://radix-ui.com) 컴포넌트 프리미티브

어떤 모델을 쓸지는 이 계층이 정하지 않는다. BFF 뒤의 LiteLLM 게이트웨이 설정(`infra/config/litellm_config.yaml`)이 결정한다.

> Vercel [`ai-chatbot`](https://github.com/vercel/ai-chatbot)(Apache-2.0) 템플릿에서 출발했다.
> 변경 내역·라이선스는 루트 [README](../README.md#라이선스)와 [`LICENSE`](LICENSE) 참고.

---

## 실행

이 화면만 따로 띄울 수는 없다. 전 라우트가 Keycloak OIDC 로그인으로 보호돼 있고
(`middleware.ts` — `/api/*`·정적 자산 제외 전부), 대화 이력·스트리밍은 BFF가 있어야 한다.
Keycloak·BFF·PostgreSQL·LiteLLM을 함께 띄우는 절차는 루트 [INSTALL.md](../INSTALL.md)에 있다.

## 목업 WS 서버

실 BFF 없이도 #16·#17의 협업채팅 프레임을 주고받아 볼 수 있는 목업이다. 화면을 띄우는 것과는
무관하다 — 위의 제약은 그대로다.

```
bun run mock:ws        # ws://localhost:4001/api/ws
```

WebSocket은 Next Route Handler로 흉내 낼 수 없어(`Request → Response` 모델이라 raw socket에 닿지
못한다) 별도 프로세스로 뜬다. 그래서 HTTP 목업 라우트(`app/(chat)/api/chat/*`)와 토글이 따로다 —
`.env.local`의 `NEXT_PUBLIC_MOCK_WS_URL`을 채우면 HTTP는 그대로 두고 WS만 이 서버로 간다.

커넥션 하나가 여러 방을 나르고, 방은 `room.subscribe` 프레임의 UUID `threadId`로 건다. 목업 사용자
이름은 선택적인 `?user=`로 정한다. 같은 방에
둘이 붙으면 서로의 발화가 보이고 `@AI`가 들어간 발화에만 답변 스트림이 흐른다. 협업 채널 목록에는
정상 스트리밍·AI 최초 오류·AI 도중 오류를 재현하는 UUID 방이 각각 제공된다.

토큰은 검증하지 않는다. 서브프로토콜이 `access_token, <아무 값>` 모양이기만 하면 붙는다.

## 동작 메모

- 사이드바 하단 "로그아웃"을 누르면 이 앱의 세션뿐 아니라 Keycloak SSO 세션까지 함께 끊긴다(다시 "로그인"을 눌러도 자동으로 재로그인되지 않고 로그인 폼이 다시 뜬다).
- BFF(`backend/api/`)는 이 토큰의 서명·issuer·audience를 검증하고 `USER` 역할을 요구한다. 토큰 없이 호출하면 401, 역할이 없으면 403이 온다.
