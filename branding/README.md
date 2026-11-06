# 고이 워드마크

`goi-logo.svg`는 투명 배경의 `goi` 워드마크이고, `goi-logo-dark.svg`는 같은 형태를 다크 배경 색으로 칠한 것이다. 둘 다 `build_logo.py`가 만들며, 고칠 때는 스크립트의 상수를 바꾸고 `uv run branding/build_logo.py`로 다시 만든다.

## 형태

- 비례: Pleos 워드마크(https://pleos.ai 의 로고 SVG)의 x-height, 글자 폭, o의 곡률(위아래가 살짝 평평한 타원)을 그대로 쓴다. 획은 Pleos(30.2)보다 굵은 36(`WEIGHT`)이고, 바깥 크기는 두고 안쪽으로 굵어진다.
- g: Pleos `P`를 좌우로 뒤집은 구조다. P가 보울이 닫히는 자리에서 스템을 45° 두 쐐기로 끊듯이, g도 보울이 스템에 닫히는 베이스라인에서 같은 각도로 끊는다. 위 조각(보울+윗 스템)과 아래 조각(디센더)은 한 점에서만 만난다.
- g–o: g 스템 윗면이 o의 윗 획으로 이어진다. 그 아래 g 스템과 o 사이 틈은 끊김 쐐기로 흘러내린다.
- i: 점은 원이고, 점만 강조색이다. 지름은 획 굵기의 1.18배(`DOT_SCALE`)다. 원은 같은 폭의 막대보다, 파랑은 검정보다 작아 보이므로 수치를 맞추지 않고 눈으로 봤을 때 i 획과 균형 잡혀 보이는 크기로 키웠다.

## 색(틴트)

| 부분 | 요소 | 기본값 | 바꾸는 법 |
|---|---|---|---|
| 글자 | `#goi-ink`, `.goi-ink` | `currentColor`(단독으로 열면 검정). CSS를 읽지 않는 도구는 `fill="#000000"` | CSS 변수 `--goi-ink` 또는 `color` |
| i 점 | `#goi-accent`, `.goi-accent` | `#0064FF`(Pleos `informative_positive` light) | CSS 변수 `--goi-accent` |

다크 모드 예: `--goi-ink: #FFFFFF; --goi-accent: #0082FF;`(Pleos `informative_positive` dark). CSS 변수를 줄 수 없는 `<img>` 자리(저장소 README 등)에는 이 값으로 칠한 `goi-logo-dark.svg`를 `<picture>`의 `prefers-color-scheme: dark` 소스로 쓴다.
CSS 변수와 `color`는 SVG를 HTML에 인라인으로 넣었을 때만 먹는다. `<img>`로 넣거나 Android VectorDrawable로 가져올 때는 두 `fill` 값을 바꾼다.
