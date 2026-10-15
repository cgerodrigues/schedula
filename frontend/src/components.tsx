import { useState, type FormEvent, type ReactNode } from "react";
export function Field({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
    </label>
  );
}
export function Card({
  title,
  children,
  tag,
}: {
  title: string;
  children: ReactNode;
  tag?: string;
}) {
  return (
    <section className="card">
      <div className="card-heading">
        <h2>{title}</h2>
        {tag && <span className="tag">{tag}</span>}
      </div>
      {children}
    </section>
  );
}
export function Form({
  children,
  onSubmit,
  label = "Salvar",
}: {
  children: ReactNode;
  onSubmit: (data: FormData) => Promise<void>;
  label?: string;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [ok, setOk] = useState(false);
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    setBusy(true);
    setError("");
    setOk(false);
    try {
      await onSubmit(new FormData(form));
      form.reset();
      setOk(true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <form onSubmit={submit}>
      <div className="form-grid">{children}</div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {ok && (
        <p role="status" className="success">
          Salvo com sucesso.
        </p>
      )}
      <button disabled={busy}>{busy ? "Salvando…" : label}</button>
    </form>
  );
}
export function Empty({
  text = "Nenhum registro encontrado.",
}: {
  text?: string;
}) {
  return <p className="empty">{text}</p>;
}
