/********************************************************
 파일명 : utils.ts (lib)
 설 명 : 여러 컴포넌트가 공용으로 쓰는 범용 유틸. 특정 도메인(BFF 계약 등)에 종속되지 않는 순수 함수만 둔다.
 *********************************************************/

import { type ClassValue, clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

/** Tailwind 클래스 충돌을 해소하며 조건부 클래스명을 합친다(clsx + tailwind-merge). */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** RFC4122 v4 형태의 UUID를 생성한다(세션 id 등에 사용). */
export function generateUUID(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}
