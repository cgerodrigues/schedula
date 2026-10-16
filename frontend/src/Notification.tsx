import { useEffect, useRef } from "react";
export function humanError(error: unknown) {
  return error instanceof Error &&
    !(error instanceof TypeError) &&
    !(error instanceof SyntaxError)
    ? error.message
    : "Não foi possível conectar ao serviço. Tente novamente.";
}
export function Notification({
  message,
  kind = "error",
  onDismiss,
}: {
  message: string;
  kind?: "error" | "success";
  onDismiss: () => void;
}) {
  const dismiss = useRef(onDismiss);
  dismiss.current = onDismiss;
  useEffect(() => {
    if (!message || kind !== "success") return;
    const timer = setTimeout(() => dismiss.current(), 6000);
    return () => clearTimeout(timer);
  }, [message, kind]);
  if (!message) return null;
  return (
    <div
      className={`notice ${kind}`}
      role={kind === "error" ? "alert" : "status"}
    >
      <span>{message}</span>
      <button
        type="button"
        className="text-button"
        aria-label="Fechar aviso"
        onClick={onDismiss}
      >
        ×
      </button>
    </div>
  );
}
