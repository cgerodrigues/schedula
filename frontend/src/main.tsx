import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { api, type Row } from "./api";
import { Field, Form } from "./components";
import { navigate, unitsPath, defendersPath, useLocation } from "./navigation";
import InstitutionPanel from "./InstitutionPanel";
import DefenderPanel from "./DefenderPanel";
import "./style.css";
import { Activation, PasswordChange } from "./AccountAccess";
export function App() {
  const [activationToken, setActivationToken] = useState(
    () =>
      new URLSearchParams(window.location.hash.slice(1)).get("activate") || "",
  );
  useEffect(() => {
    if (activationToken)
      window.history.replaceState(
        null,
        "",
        window.location.pathname + window.location.search,
      );
  }, [activationToken]);
  useEffect(() => {
    const receiveInvite = () => {
      const token = new URLSearchParams(window.location.hash.slice(1)).get(
        "activate",
      );
      if (token) setActivationToken(token);
    };
    window.addEventListener("hashchange", receiveInvite);
    return () => window.removeEventListener("hashchange", receiveInvite);
  }, []);
  const [user, setUser] = useState<Row | null>(null);
  const [loading, setLoading] = useState(true);
  const location = useLocation();
  const section = location.path.startsWith(unitsPath)
    ? "units"
    : location.path.startsWith(defendersPath)
      ? "defenders"
      : location.state.section || "overview";
  const setSection = (key: string) =>
    navigate(
      key === "units" ? unitsPath : key === "defenders" ? defendersPath : "/",
      { section: key },
    );
  const [data, setData] = useState<Record<string, Row[]>>({});
  const [error, setError] = useState("");
  async function identify() {
    try {
      setUser(await api<Row>("/auth/me"));
    } catch {
      setUser(null);
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    void identify();
  }, []);
  async function reload() {
    if (
      !user ||
      (user.role === "RH" && ["units", "defenders"].includes(section))
    )
      return;
    const role = user.role === "RH" ? "rh" : "defender";
    const resources =
      role === "rh"
        ? ["units", section === "overview" ? "defenders/indicators" : "defenders", "affiliations", "notifications"]
        : ["affiliations", "appointments", "slots", "blocks"];
    const results = await Promise.all(
      resources.map(async (key) => [key, await api("/" + role + "/" + key)]),
    );
    setData(Object.fromEntries(results));
  }
  useEffect(() => {
    if (user) {
      setData({});
      setError("");
      void reload().catch((e) => setError(e.message));
    }
  }, [user, section]);
  if (activationToken)
    return <Activation key={activationToken} token={activationToken} />;
  if (loading) return <div className="loading">Carregando Schedula…</div>;
  if (!user)
    return (
      <div className="login-layout">
        <div className="login-story">
          <div className="brand">
            <span className="logo">S</span> schedula
            <span className="brand-dot">.</span>
          </div>
          <span className="eyebrow">DEFENSORIA PÚBLICA</span>
          <h1>
            Mais acesso.
            <br />
            Mais cuidado.
            <br />
            <em>Tempo para atender.</em>
          </h1>
          <p>
            Uma agenda conectada à sua equipe e às pessoas que precisam de
            atendimento.
          </p>
          <div className="login-footer">
            Gestão institucional · Agenda · Atendimento
          </div>
        </div>
        <main className="login-form">
          <span className="eyebrow">BEM-VINDO DE VOLTA</span>
          <h2>Acesse seu painel</h2>
          <p className="muted">
            Use as credenciais fornecidas pela instituição.
          </p>
          <Form
            label="Entrar no Schedula →"
            onSubmit={async (f) => {
              await api(
                "/auth/login",
                "POST",
                new URLSearchParams({
                  username: String(f.get("username")),
                  password: String(f.get("password")),
                }),
              );
              await identify();
            }}
          >
            <Field label="Usuário">
              <input name="username" autoComplete="username" required />
            </Field>
            <Field label="Senha">
              <input
                name="password"
                type="password"
                autoComplete="current-password"
                required
              />
            </Field>
          </Form>
          <p className="hint">
            Acesso restrito ao RH e aos defensores. O atendimento ao cidadão
            acontece pelo WhatsApp.
          </p>
        </main>
      </div>
    );
  const nav =
    user.role === "RH"
      ? [
          ["overview", "Visão geral", "◈"],
          ["units", "Unidades", "▦"],
          ["defenders", "Defensores", "♙"],
          ["affiliations", "Vínculos e áreas", "⇄"],
        ]
      : [
          ["overview", "Visão geral", "◈"],
          ["agenda", "Minha agenda", "▦"],
          ["availability", "Disponibilidade", "◷"],
        ];
  nav.push(["password", "Alterar minha senha", "⚿"]);
  return (
    <div className={`app-layout${user.role === "RH" ? " rh-layout" : ""}`}>
      <aside>
        <div className="brand">
          <span className="logo">S</span> schedula
          <span className="brand-dot">.</span>
        </div>
        <span className="sidebar-caption">
          {user.role === "RH" ? "GESTÃO INSTITUCIONAL" : "PAINEL DO DEFENSOR"}
        </span>
        <nav>
          {nav.map(([key, label, icon]) => (
            <button
              key={key}
              className={section === key ? "nav-item selected" : "nav-item"}
              onClick={() => setSection(key)}
            >
              <span aria-hidden="true">{icon}</span>
              {label}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <span className="status-dot" /> Defensoria Pública
          <p>Atendimento que aproxima.</p>
        </div>
      </aside>
      <main className="workspace">
        <header className={user.role === "RH" && section === "defenders" ? "defenders-header" : undefined}>
          <div>
            <span className="eyebrow">
              SCHEDULA / {user.role === "RH" ? "INSTITUIÇÃO" : "DEFENSOR"}
            </span>
            <h1>{nav.find((n) => n[0] === section)?.[1]}</h1>
            {user.role === "RH" && section === "defenders" && (
              <p className="defenders-subtitle">Organize a rede. Amplie o acesso ao atendimento.</p>
            )}
          </div>
          <div className="user">
            <span className="avatar">{user.name.slice(0, 1)}</span>
            <div>
              <strong>{user.name}</strong>
              <small>
                {user.role === "RH"
                  ? "Administrador institucional"
                  : "Defensor público"}
              </small>
            </div>
            <button
              className="ghost"
              onClick={async () => {
                await api("/auth/logout", "POST");
                setUser(null);
                setData({});
              }}
            >
              Sair
            </button>
          </div>
        </header>
        {!(user.role === "RH" && section === "defenders") && <div className="page-intro">
          <p>
            {user.role === "RH"
              ? "Organize a rede. Amplie o acesso ao atendimento."
              : "Seu tempo organizado, seu atendimento mais próximo."}
          </p>
          {!["units", "defenders"].includes(section) && (
            <button
              className="text-button"
              onClick={() => {
                setError("");
                void reload().catch((e) => setError(e.message));
              }}
            >
              Atualizar ↻
            </button>
          )}
        </div>}
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        {["units", "defenders"].includes(section) && user.role !== "RH" ? (
          <div role="alert">
            Você não tem permissão para acessar a gestão de{" "}
            {section === "units" ? "unidades" : "defensores"}.
            <button className="ghost" onClick={() => setSection("overview")}>
              Voltar ao painel
            </button>
          </div>
        ) : section === "password" ? (
          <PasswordChange
            onChanged={() => {
              setUser(null);
              setData({});
              setSection("overview");
            }}
          />
        ) : user.role === "RH" ? (
          <InstitutionPanel section={section} data={data} reload={reload} />
        ) : (
          <DefenderPanel section={section} data={data} reload={reload} />
        )}
        <footer>
          Schedula · Plataforma de agendamento para Defensorias Públicas
        </footer>
      </main>
    </div>
  );
}
const root = document.getElementById("root");
if (root)
  createRoot(root).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  );
