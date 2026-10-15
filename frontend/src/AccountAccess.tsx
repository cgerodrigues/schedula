import { useEffect, useState } from "react";
import { api } from "./api";
import { Card, Field, Form } from "./components";
export function passwordFieldsMatch(f: FormData) {
  if (f.get("password") !== f.get("confirmation"))
    throw new Error("As senhas não coincidem.");
}
function NewPasswordFields() {
  return (
    <>
      <Field label="Nova senha (mínimo 12 caracteres)">
        <input
          name="password"
          type="password"
          minLength={12}
          maxLength={72}
          autoComplete="new-password"
          required
        />
      </Field>
      <Field label="Confirme a nova senha">
        <input
          name="confirmation"
          type="password"
          minLength={12}
          maxLength={72}
          autoComplete="new-password"
          required
        />
      </Field>
    </>
  );
}
export function Activation({ token }: { token: string }) {
  const [state, setState] = useState("checking");
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    api("/auth/activation/check", "POST", { token })
      .then(() => {
        if (active) setState("ready");
      })
      .catch((e) => {
        if (active) {
          setError(e.message);
          setState("invalid");
        }
      });
    return () => {
      active = false;
    };
  }, [token]);
  return (
    <main className="login-form">
      <Card title="Ativar minha conta">
        <p>
          Defina sua senha pessoal. O convite vale por 24 horas e pode ser usado
          uma única vez.
        </p>
        {state === "checking" && <p role="status">Verificando convite…</p>}
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        {state === "ready" && (
          <Form
            label="Ativar conta"
            onSubmit={async (f) => {
              passwordFieldsMatch(f);
              await api("/auth/activation", "POST", {
                token,
                password: f.get("password"),
                confirmation: f.get("confirmation"),
              });
              setState("done");
            }}
          >
            <NewPasswordFields />
          </Form>
        )}
        {state === "done" && (
          <p role="status" className="success">
            Conta ativada. Entre com seu usuário e a senha que acabou de
            definir.
          </p>
        )}
        <button
          className="ghost"
          onClick={() => window.location.assign(window.location.pathname)}
        >
          Ir para o login
        </button>
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
