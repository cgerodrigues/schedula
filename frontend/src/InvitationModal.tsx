import { useEffect, useState } from "react";
import { Modal } from "./Modal";
import { Notification } from "./Notification";
export type Invitation = { token: string; expiresAt: string };
export function InvitationModal({
  invitation,
  name,
  created = false,
  onClose,
}: {
  invitation: Invitation;
  name: string;
  created?: boolean;
  onClose: () => void;
}) {
  const [expired, setExpired] = useState(
    Date.parse(invitation.expiresAt) <= Date.now(),
  );
  const [copied, setCopied] = useState(false),
    [error, setError] = useState("");
  useEffect(() => {
    const timer = setTimeout(
      () => setExpired(true),
      Math.max(0, Date.parse(invitation.expiresAt) - Date.now()),
    );
    return () => clearTimeout(timer);
  }, [invitation.expiresAt]);
  const link = `${window.location.origin}/#activate=${encodeURIComponent(invitation.token)}`;
  return (
    <Modal title={created ? "Defensor cadastrado com sucesso!" : "Convite de ativação"} onClose={onClose}>
      <p>
        <strong>{name}</strong>
        {created ? " foi cadastrado(a)." : " — conta pendente de ativação."}
      </p>
      {expired ? (
        <p role="alert">
          Este convite expirou. Feche esta janela e gere um novo convite.
        </p>
      ) : (
        <>
          <p>
            Envie este link por um canal privado para que o defensor ative a
            conta e defina sua própria senha.
          </p>
          <p>
            Validade do convite: <strong>24 horas</strong>. Uso único.
          </p>
          <p className="hint">
            Expira em {new Date(invitation.expiresAt).toLocaleString("pt-BR")}.
            O link só é exibido agora.
          </p>
          <label className="field">
            Link de ativação
            <input readOnly value={link} onFocus={(e) => e.target.select()} />
          </label>
          <button
            type="button"
            onClick={async () => {
              setError("");
              setCopied(false);
              if (Date.parse(invitation.expiresAt) <= Date.now()) {
                setExpired(true);
                return;
              }
              try {
                await navigator.clipboard.writeText(link);
                setCopied(true);
              } catch {
                setError(
                  "Não foi possível copiar. Selecione o link e copie manualmente.",
                );
              }
            }}
          >
            Copiar link
          </button>
          <Notification
            message={copied ? "Link copiado." : ""}
            kind="success"
            onDismiss={() => setCopied(false)}
          />
          <Notification message={error} onDismiss={() => setError("")} />
        </>
      )}
      <button type="button" className="ghost" onClick={onClose}>
        Fechar
      </button>
    </Modal>
  );
}
