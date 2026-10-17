import { useEffect, useId, useRef, useState } from "react";
import { api, ApiError } from "./api";
import { Card, Field, Form } from "./components";
import { humanError, Notification } from "./Notification";
export function passwordRequirements(password: string) {
  return {
    minimum: [...password].length >= 12,
    bytes: new TextEncoder().encode(password).length <= 72,
  };
}
export function passwordFieldsMatch(f: FormData) {
  if (f.get("password") !== f.get("confirmation"))
    throw new Error("As senhas não coincidem.");
  const checks = passwordRequirements(String(f.get("password") || ""));
  if (!checks.minimum || !checks.bytes)
    throw new Error(
      "A senha deve ter ao menos 12 caracteres e no máximo 72 bytes UTF-8.",
    );
}
function NewPasswordFields({
  onValidity,
}: {
  onValidity?: (valid: boolean) => void;
}) {
  const fieldId = useId();
  const [password, setPassword] = useState(""),
    [confirmation, setConfirmation] = useState("");
  const [showPassword, setShowPassword] = useState(false),
    [showConfirmation, setShowConfirmation] = useState(false);
  const checks = passwordRequirements(password),
    matches = !!password && password === confirmation;
  const valid = checks.minimum && checks.bytes && matches;
  useEffect(() => {
    onValidity?.(valid);
  }, [valid, onValidity]);
  return <>{[
    { name: "password", label: "Nova senha", value: password, set: setPassword, show: showPassword, toggle: setShowPassword, accessible: "senha" },
    { name: "confirmation", label: "Confirmar senha", value: confirmation, set: setConfirmation, show: showConfirmation, toggle: setShowConfirmation, accessible: "senha repetida" },
  ].map(field => <div className="password-field" key={field.name}>
    <label htmlFor={fieldId + field.name}>{field.label}</label>
    <div className="password-input">
      <input id={fieldId + field.name} name={field.name} type={field.show ? "text" : "password"} autoComplete="new-password" required
        value={field.value} onChange={e => field.set(e.target.value)} aria-describedby={fieldId + field.name + "-feedback"} />
      <button type="button" className="password-eye" aria-label={`${field.show ? "Ocultar" : "Mostrar"} ${field.accessible}`}
        aria-pressed={field.show} onClick={() => field.toggle(v => !v)}>
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
          <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>
          {field.show && <path d="m3 3 18 18"/>}
        </svg>
      </button>
    </div>
    <div id={fieldId + field.name + "-feedback"} className="password-feedback" aria-live="polite">
      {field.name === "password" ? <>
        <span className={checks.minimum ? "met" : ""}>{checks.minimum ? "✓" : "○"} Mínimo de 12 caracteres</span>
        {!checks.bytes && <p className="password-too-long">Senha muito longa. Escolha uma senha menor.</p>}
      </> : <span className={matches ? "met" : ""}>{matches ? "✓ Senhas coincidem" : "○ As senhas precisam coincidir"}</span>}
    </div>
  </div>)}</>;
}

export function Activation({ token }: { token: string }) {
  const [state, setState] = useState("checking"),
    [error, setError] = useState("");
  const [identity, setIdentity] = useState<{
    name: string;
    username: string;
  }>();
  const [valid, setValid] = useState(false),
    [busy, setBusy] = useState(false);
  const saving = useRef(false);
  const [attempt, setAttempt] = useState(0);
  const unavailable = (e: unknown) => e instanceof ApiError && [400, 404, 409, 410, 422].includes(e.status);
  useEffect(() => {
    let active = true;
    setState("checking");
    setIdentity(undefined);
    setError("");
    api<{ name: string; username: string }>("/auth/activation/check", "POST", {
      token,
    })
      .then((result) => {
        if (!result?.name || !result?.username)
          throw new Error(
            "Não foi possível validar a identificação do convite.",
          );
        if (active) {
          setIdentity(result);
          setState("ready");
        }
      })
      .catch((e) => {
        if (active) {
          setState(unavailable(e) ? "invalid" : "retry");
        }
      });
    return () => {
      active = false;
    };
  }, [token, attempt]);
  return (
    <main className="login-form activation-page">
      <Card title={state === "done" ? "Conta ativada com sucesso!" : state === "invalid" ? "Convite indisponível" : state === "retry" ? "Não foi possível verificar o convite" : state === "checking" ? "Validando convite" : "Ativar minha conta"}>
        {state === "checking" && <p role="status">Verificando convite…</p>}
        {state === "invalid" && <div className="activation-result">
          <p>Este link é inválido, expirou, foi revogado ou já foi utilizado.</p>
          <p>Solicite um novo convite ao RH da sua instituição.</p>
        </div>}
        {state === "retry" && <div className="activation-result">
          <p role="alert">Não foi possível conectar ao serviço. Tente novamente.</p>
          <button onClick={() => setAttempt(v => v + 1)}>Tentar novamente</button>
        </div>}
        {state === "ready" && (
          <>
            <p>Crie uma senha para acessar o Schedula.</p>
            <dl className="activation-identity">
              <dt>Nome completo</dt>
              <dd>{identity?.name}</dd>
              <dt>Usuário</dt>
              <dd>{identity?.username}</dd>
            </dl>
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                if (saving.current || !valid) return;
                const form = new FormData(e.currentTarget);
                setError("");
                try {
                  passwordFieldsMatch(form);
                  saving.current = true;
                  setBusy(true);
                  await api("/auth/activation", "POST", {
                    token,
                    password: form.get("password"),
                    confirmation: form.get("confirmation"),
                  });
                  setState("done");
                  setIdentity(undefined);
                } catch (e) {
                  if (unavailable(e)) {
                    // A rejected password can share a status with an unavailable invitation.
                    try {
                      await api("/auth/activation/check", "POST", { token });
                      setError(humanError(e));
                    } catch (checkError) {
                      if (unavailable(checkError)) { setState("invalid"); setIdentity(undefined); }
                      else setError(humanError(checkError));
                    }
                  } else setError(humanError(e));
                } finally {
                  saving.current = false;
                  setBusy(false);
                }
              }}
            >
              <Notification message={error} onDismiss={() => setError("")} />
              <fieldset className="defender-fields" disabled={busy}>
                <NewPasswordFields onValidity={setValid} />
              </fieldset>
              <button disabled={busy || !valid}>
                {busy ? "Ativando…" : "Ativar conta"}
              </button>
            </form>
          </>
        )}
        {state === "done" && <div className="activation-result">
          <p role="status"><span className="activation-check" aria-hidden="true">✓</span> Sua conta está pronta.</p>
          <p>Entre com seu usuário e a senha que acabou de definir.</p>
          <button onClick={() => window.location.assign("/")}>Ir para o login</button>
        </div>}

      </Card>
    </main>
  );
}
export function PasswordChange({ onChanged }: { onChanged: () => void }) {
  return (
    <Card title="Alterar minha senha">
      <p>
        Após a alteração, sua sessão será encerrada. Entre novamente com a nova
        senha.
      </p>
      <Form
        label="Alterar senha e sair"
        onSubmit={async (f) => {
          passwordFieldsMatch(f);
          await api("/auth/password", "POST", Object.fromEntries(f));
          onChanged();
        }}
      >
        <Field label="Senha atual">
          <input
            name="currentPassword"
            type="password"
            autoComplete="current-password"
            required
          />
        </Field>
        <NewPasswordFields />
      </Form>
    </Card>
  );
}
