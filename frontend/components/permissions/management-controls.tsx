'use client';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import { type ReactNode, useRef, useState } from 'react';
import { toast } from 'sonner';

export const fieldClass =
  'h-9 rounded-md border border-input bg-background px-2 text-sm disabled:opacity-50';

/** 저장과 재조회를 분리하고 커밋된 변경을 다시 보내지 않는다. */
export function ChangeButton({
  label,
  description,
  disabled,
  change,
  refresh,
}: {
  label: string;
  description: string;
  disabled?: boolean;
  change: () => Promise<unknown>;
  refresh: () => Promise<unknown>;
}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const lock = useRef(false);
  async function save() {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setMessage('');
    try {
      await change();
      setOpen(false);
      setMessage('변경 완료');
      // 재조회가 이 행을 제거해도 커밋된 변경 결과는 유지한다.
      toast.success(`${label}: 변경 완료`);
      try {
        await refresh();
      } catch {
        const notice =
          '변경 완료, 최신 정보 조회 실패. 변경을 다시 보내지 말고 목록을 다시 조회하세요.';
        setMessage(notice);
        toast.warning(`${label}: ${notice}`);
      }
    } catch (error) {
      setMessage(
        error instanceof Error
          ? error.message
          : '변경 결과를 확인하지 못했습니다. 목록을 다시 조회하세요.',
      );
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  return (
    <div className="inline-flex flex-col items-start gap-1">
      <Button
        size="sm"
        disabled={disabled || busy}
        onClick={() => {
          setMessage('');
          setOpen(true);
        }}
      >
        {busy ? '저장 중…' : label}
      </Button>
      {message && !open && (
        <p role="status" className="max-w-sm text-sm">
          {message}
        </p>
      )}
      <AlertDialog
        open={open}
        onOpenChange={(value) => {
          if (!busy) setOpen(value);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{label}</AlertDialogTitle>
            <AlertDialogDescription>
              {description} 다른 관리자의 변경 이후 저장하면 현재 값을 다시
              변경할 수 있습니다.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {message && (
            <p role="status" className="text-sm">
              {message}
            </p>
          )}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={busy}>취소</AlertDialogCancel>
            <AlertDialogAction
              disabled={busy || disabled}
              onClick={(event) => {
                event.preventDefault();
                void save();
              }}
            >
              확인하고 저장
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

export function Section({
  title,
  children,
}: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3 border-t pt-4">
      <h2 className="text-base font-semibold">{title}</h2>
      {children}
    </section>
  );
}
