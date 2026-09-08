# 옹기종기 브랜드 자산

이 디렉터리는 옹기종기 브랜드 자산의 정본을 보관한다. 제품 화면에 먼저 넣기보다,
여기에서 용도에 맞는 자산을 골라 재사용한다. 제품 적용은 별도 단계에서 진행한다.

## 기본 규칙

- 심벌은 공유 대화 맥락 안에 사람과 AI 참여자가 모인 모습을 뜻한다. `onggijonggi-mark.svg`는 32px 이상,
  `onggijonggi-mark-16.svg`는 16px 전용 축약형이다.
- 주 워드마크는 Title Case `Onggijonggi`다. 한글 `옹기종기`는 소개·슬로건의 일반 텍스트로만 쓴다.
- `OGJG`는 좁은 라벨에서 Geist 텍스트로만 쓰는 보조 약어다. 독립 로고 SVG나 파비콘으로 만들지 않는다.
- 심벌과 워드마크를 새로 조합하거나 재그리지 않는다. 아래 조합 로고 파일을 그대로 사용한다.

## 파일과 용도

| 자산 | 파일 | 용도 |
| --- | --- | --- |
| 컬러 심벌 | `onggijonggi-mark.svg` | 32px 이상에서 쓰는 P3 기본 심벌 |
| 16px 심벌 | `onggijonggi-mark-16.svg` | 16px 전용 축약 심벌 및 파비콘 프레임 기준 원본 |
| 컬러 워드마크 | `logos/onggijonggi-wordmark.svg` | 심벌을 이미 인접하게 쓴 문서·화면에서 이름만 표시할 때 |
| 검정·흰색 워드마크 | `logos/onggijonggi-wordmark-black.svg`, `logos/onggijonggi-wordmark-white.svg` | 단색 인쇄 또는 컬러 사용이 제한된 배경 |
| 컬러 가로 조합 | `logos/onggijonggi-logo-horizontal.svg` | 기본 로고. README, 문서 머리말, 넓은 헤더 |
| 다크 가로 조합 | `logos/onggijonggi-logo-horizontal-dark.svg` | 어두운 배경. P3 심벌과 흰색 워드마크 |
| 검정·흰색 가로 조합 | `logos/onggijonggi-logo-horizontal-black.svg`, `logos/onggijonggi-logo-horizontal-white.svg` | 단색 인쇄·제한된 배경 |
| 컬러 세로 조합 | `logos/onggijonggi-logo-vertical.svg` | 가로 폭이 제한된 프로젝트 목록·표지 |
| 다크·검정·흰색 세로 조합 | `logos/onggijonggi-logo-vertical-dark.svg`, `logos/onggijonggi-logo-vertical-black.svg`, `logos/onggijonggi-logo-vertical-white.svg` | 세로 조합의 배경별 변형 |

모든 조합 로고와 워드마크는 투명 배경 SVG다. 컬러형은 밝은 중립 배경에서, 다크형은 어두운 배경에서 사용한다.
단색형은 색을 더 넣을 수 없는 환경에만 사용한다.

## 구성과 안전 여백

- 가로 조합은 심벌의 실제 외곽 높이를 워드마크 대문자 높이의 약 1.2배로 두고, 둘 사이를 약 0.32배 띄운다. SVG 아트보드 크기가 아니라 실제 도형 외곽을 기준으로 판단한다.
- 세로 조합은 심벌 아래에 워드마크를 중앙 정렬한다.
- 모든 조합의 외곽에는 워드마크 대문자 높이의 0.5배 이상을 비워 둔다. 다른 텍스트·테두리·이미지를 이 여백 안에 넣지 않는다.
- 최소 권장 표시 폭은 가로 조합 160px, 세로 조합 150px, 워드마크 120px이다. 이보다 작아지면 워드마크 대신 심벌을 사용한다.

## 금지 사항

- 색을 바꾸거나, 그라데이션·그림자·윤곽선을 더하거나, 가로세로 비율을 바꾸지 않는다.
- 심벌을 기능 버튼·메뉴 아이콘처럼 쓰지 않는다. 기능 아이콘은 기존 Lucide 체계를 유지한다.
- 파비콘을 조합 로고로 대체하거나, 조합 로고를 파비콘으로 축소하지 않는다.
- `onggijonggi-chat`은 저장소·기술 식별자이므로 로고의 이름 요소로 사용하지 않는다.

## 접근성

정보를 전달하는 로고 이미지의 대체 텍스트는 `옹기종기`를 사용한다. 같은 이름이 인접한 텍스트로 이미
읽히는 장식용 이미지는 빈 대체 텍스트를 사용한다.

## 제작 출처와 라이선스

워드마크는 저장소의 Geist 계열과 같은 공식 **Geist 1.400** 배포본의 Semibold 600 윤곽을 바탕으로,
자간만 12/1000em 광학 보정해 패스로 변환했다. 최종 SVG에는 `text`, `font-family`, 외부 이미지,
외부 폰트 또는 링크가 없다.

Geist의 저작권과 SIL Open Font License 1.1 고지는 [../fonts/OFL.txt](../fonts/OFL.txt)에 보관한다.
브랜드 SVG 자산은 저장소의 [Apache-2.0 라이선스](../../../LICENSE)를 따른다. 다만 Apache-2.0은
프로젝트 이름이나 로고의 상표 사용 권한을 자동으로 부여하지 않으므로, 공개 전 별도 로고 사용 정책을
검토한다.
