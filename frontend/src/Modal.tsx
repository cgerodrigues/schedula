import { useEffect, useId, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";
export function Modal({ title, children, onClose, busy = false }: {
  title: string; children: ReactNode; onClose: () => void; busy?: boolean;
}) {
  const dialog = useRef<HTMLDialogElement>(null), id = useId();
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const element = dialog.current;
    if (element?.showModal) element.showModal();
    else element?.setAttribute("open", "");
    return () => {
      element?.close?.();
      if (previous?.isConnected) previous.focus();
      else if (previous?.id && document.getElementById(previous.id)) document.getElementById(previous.id)?.focus();
      else document.querySelector<HTMLButtonElement>("main button")?.focus();
    };
  }, []);
  return createPortal(<dialog ref={dialog} className="invitation-modal" aria-labelledby={id}
    aria-busy={busy} onCancel={e => { e.preventDefault(); if (!busy) onClose(); }}>
    <h2 id={id}>{title}</h2>{children}
  </dialog>, document.body);
}
