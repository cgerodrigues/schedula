import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { api, type Row } from "./api";
import { Card, Field } from "./components";
import { humanError, Notification } from "./Notification";
import { defendersPath, navigate, useLocation } from "./navigation";
import { Modal } from "./Modal";
import { InvitationModal, type Invitation } from "./InvitationModal";

const countLabel = (n: number) => `${n} ${n === 1 ? "defensor" : "defensores"}`;
type Page = { items: Row[]; total: number; page: number; totalPages: number };
const invitationLabels: Record<string, string> = {
  NONE: "Ativação pendente · Sem convite",
  AVAILABLE: "Ativação pendente",
  EXPIRED: "Convite expirado",
  REVOKED: "Convite revogado",
  USED: "Convite utilizado",
};
async function issue(id: string) {
  const invitation = await api<Invitation>(
    `/rh/defenders/${id}/invitation`,
    "POST",
  );
  if (
    !invitation?.token ||
    !/^[A-Za-z0-9_-]{43}$/.test(invitation.token) ||
    !Number.isFinite(Date.parse(invitation.expiresAt)) ||
    Date.parse(invitation.expiresAt) <= Date.now()
  )
    throw new Error(
      "Não foi possível obter um convite válido. Tente gerar outro convite.",
    );
  return invitation;
}
export default function DefenderManagement() {
  const location = useLocation();
  const id = location.path.match(/^\/rh\/defensores\/([^/]+)\/editar$/)?.[1];
  const isNew = location.path === defendersPath + "/novo";
  const [account, setAccount] = useState<Row>(),
    [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let alive = true;
    setAccount(undefined);
    setError("");
    if (id)
      api<Row>("/rh/defenders/" + encodeURIComponent(id))
        .then((row) => {
          if (alive) setAccount(row);
        })
        .catch((e) => {
          if (alive) setError(humanError(e));
        });
    return () => {
      alive = false;
    };
  }, [id, revision]);
  const back =
    typeof location.state.returnTo === "string" &&
    /^\/rh\/defensores(?:\?|$)/.test(location.state.returnTo)
      ? location.state.returnTo
      : defendersPath;
  if (location.path === defendersPath) return <DefenderList />;
  if (!isNew && !id)
    return (
      <Card title="Página não encontrada">
        <button onClick={() => navigate(defendersPath)}>
          Voltar aos defensores
        </button>
      </Card>
    );
  if (id && (!account || account.id !== id))
    return (
      <Card title="Editar defensor">
        {error ? (
          <>
            <Notification message={error} onDismiss={() => navigate(back)} />
            <button onClick={() => setRevision((r) => r + 1)}>
              Tentar novamente
            </button>
          </>
        ) : (
          <p role="status">Carregando defensor…</p>
        )}
        <button className="ghost" onClick={() => navigate(back)}>
          Voltar aos defensores
        </button>
      </Card>
    );
  return (
    <DefenderForm
      key={id || "new"}
      account={id ? account : undefined}
      back={back}
    />
  );
}
function DefenderForm({ account, back }: { account?: Row; back: string }) {
  const [name, setName] = useState(account?.name || ""),
    [username, setUsername] = useState("");
  const [createdId, setCreatedId] = useState<string>();
  const [invite, setInvite] = useState<Invitation>();
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const saving = useRef(false);
  const done = () =>
    navigate(back, {
      message: account
        ? "Dados do defensor atualizados."
        : "Defensor cadastrado com sucesso.",
      savedName: name,
    });
  return (
    <div className="defender-form"><Card
      title={account ? "Editar defensor" : "Novo defensor"}
      tag="Gestão institucional"
    >
      {account && (
        <p>
          <strong>{account.name}</strong> · Usuário: {account.username}
        </p>
      )}
      <p className="hint">
        O usuário é definido no cadastro e não pode ser alterado pelo RH. A
        senha é definida pelo próprio defensor.
      </p>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          if (saving.current) return;
          saving.current = true;
          setBusy(true);
          setError("");
          try {
            if (account) {
              await api(`/rh/defenders/${account.id}`, "PUT", { name });
              done();
            } else {
              let id = createdId;
              if (!id) {
                const result = await api<{ id: string }>(
                  "/rh/defenders",
                  "POST",
                  { name, username },
                );
                id = result.id;
                setCreatedId(id);
              }
              setInvite(await issue(id));
            }
          } catch (e) {
            setError(humanError(e));
          } finally {
            saving.current = false;
            setBusy(false);
          }
        }}
      >
        <fieldset className={`defender-fields ${account ? "edit-fields" : "create-fields"}`} disabled={busy || !!createdId}>
          <Field label="Nome completo">
            <input
              required
              maxLength={150}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </Field>
          {!account && (
            <Field label="Usuário de acesso">
              <input
                required
                maxLength={120}
                autoComplete="off"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              />
            </Field>
          )}
        </fieldset>
        {createdId && !invite && (
          <p role="status">
            O cadastro foi salvo. Gere o convite novamente ou volte à listagem
            para administrá-lo depois.
          </p>
        )}
        <Notification message={error} onDismiss={() => setError("")} />
        <div className="form-actions">
          <button disabled={busy || !!invite}>
            {busy
              ? "Salvando…"
              : account
                ? "Salvar alterações"
                : createdId
                  ? "Gerar convite"
                  : "Cadastrar defensor"}
          </button>
          <button
            type="button"
            className="ghost"
            disabled={busy}
            onClick={() => navigate(back)}
          >
            Cancelar
          </button>
        </div>
      </form>
      {invite && (
        <InvitationModal
          invitation={invite}
          name={name}
          created
          onClose={() => {
            setInvite(undefined);
            done();
          }}
        />
      )}
    </Card></div>
  );
}
function DefenderList() {
  const location = useLocation(),
    params = new URLSearchParams(location.search);
  const [name, setName] = useState(params.get("name") || "");
  const [page, setPage] = useState<Page>(),
    [busy, setBusy] = useState(true),
    [failed, setFailed] = useState(false);
  const [error, setError] = useState(""),
    [success, setSuccess] = useState("");
  const [revision, setRevision] = useState(0),
    [action, setAction] = useState("");
  const [confirmation, setConfirmation] = useState<{ row: Row; revoke: boolean }>();
  const [menu, setMenu] = useState<string>();
  const menuRef = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    if (!menu) return;
    const trigger = menuRef.current?.querySelector("button");
    const options = menuRef.current?.querySelector<HTMLElement>(".defender-action-options");
    if (!trigger || !options) return;
    const rect = trigger.getBoundingClientRect();
    const height = options.offsetHeight, width = options.offsetWidth;
    const top = rect.bottom + height + 8 <= window.innerHeight ? rect.bottom + 4 : rect.top - height - 4;
    options.style.top = `${Math.max(8, Math.min(top, window.innerHeight - height - 8))}px`;
    options.style.left = `${Math.max(8, Math.min(rect.right - width, window.innerWidth - width - 8))}px`;
    const dismiss = () => setMenu(undefined);
    window.addEventListener("resize", dismiss);
    window.addEventListener("scroll", dismiss, true);
    return () => { window.removeEventListener("resize", dismiss); window.removeEventListener("scroll", dismiss, true); };
  }, [menu]);
  useEffect(() => {
    if (!menu) return;
    const outside = (e: PointerEvent) => { if (!menuRef.current?.contains(e.target as Node)) setMenu(undefined); };
    document.addEventListener("pointerdown", outside);
    return () => document.removeEventListener("pointerdown", outside);
  }, [menu]);
  const acting = useRef(false),
    mounted = useRef(true);
  const [invite, setInvite] = useState<{
    invitation: Invitation;
    name: string;
  }>();
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  function closeMenu() {
    menuRef.current?.querySelector<HTMLButtonElement>("button")?.focus();
    setMenu(undefined);
  }
  const back = defendersPath + location.search;
  function update(values: Record<string, string>, replace = false) {
    const q = new URLSearchParams(location.search);
    Object.entries(values).forEach(([k, v]) => (v ? q.set(k, v) : q.delete(k)));
    navigate(defendersPath + (q.size ? "?" + q : ""), {}, replace);
  }
  useEffect(() => {
    setName(new URLSearchParams(location.search).get("name") || "");
  }, [location.search]);
  useEffect(() => {
    if (name === (new URLSearchParams(location.search).get("name") || ""))
      return;
    const timer = setTimeout(() => update({ name, page: "1" }, true), 350);
    return () => clearTimeout(timer);
  }, [name, location.search]);
  useEffect(() => {
    let alive = true;
    setBusy(true);
    setFailed(false);
    setError("");
    setPage(undefined);
    const q = new URLSearchParams(location.search);
    q.set("size", "10");
    api<Page>("/rh/defenders/search?" + q)
      .then((result) => {
        if (!alive) return;
        setPage(result);
        if (q.has("page") && q.get("page") !== String(result.page))
          update({ page: String(result.page) }, true);
      })
      .catch((e) => {
        if (alive) {
          setError(humanError(e));
          setFailed(true);
        }
      })
      .finally(() => {
        if (alive) setBusy(false);
      });
    return () => {
      alive = false;
    };
  }, [location.search, revision]);
  async function invitationAction(row: Row, revoke = false) {
    if (acting.current) return;
    acting.current = true;
    setAction(row.id);
    setError("");
    setSuccess("");
    navigate(back, {}, true);
    try {
      if (revoke) await api(`/rh/defenders/${row.id}/invitation`, "DELETE");
      else {
        const invitation = await issue(row.id);
        if (mounted.current) setInvite({ invitation, name: row.name });
      }
      if (mounted.current) {
        setConfirmation(undefined);
        if (revoke) setSuccess("Convite revogado com sucesso.");
        setRevision((r) => r + 1);
      }
    } catch (e) {
      if (mounted.current) setError(humanError(e));
    } finally {
      acting.current = false;
      if (mounted.current) setAction("");
    }
  }
  return (
    <Card
      title="Equipe de defensores"
      tag={page ? `${countLabel(page.total)} ${page.total === 1 ? "encontrado" : "encontrados"}` : "Gestão institucional"}
    >
      <Notification
        message={success || location.state.message || ""}
        kind="success"
        onDismiss={() => {
          setSuccess("");
          navigate(back, { savedName: location.state.savedName }, true);
        }}
      />
      {location.state.savedName && (
        <button
          className="text-button"
          onClick={() =>
            navigate(
              defendersPath +
                "?name=" +
                encodeURIComponent(location.state.savedName),
            )
          }
        >
          Localizar defensor salvo
        </button>
      )}
      <div className="unit-toolbar">
        <button
          onClick={() => navigate(defendersPath + "/novo", { returnTo: back })}
        >
          + Novo defensor
        </button>
        <button
          className="ghost"
          onClick={() => {
            setSuccess("");
            setRevision((r) => r + 1);
          }}
        >
          Atualizar lista
        </button>
      </div>
      <div className="unit-filters defender-filters">
        <Field label="Buscar defensor">
          <input
            type="search"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </Field>
        <Field label="Situação da conta">
          <select
            value={params.get("status") || ""}
            onChange={(e) => {
              setSuccess("");
              update({ name, status: e.target.value, page: "1" });
            }}
          >
            <option value="">Todas</option>
            <option value="PENDING_ACTIVATION">Ativação pendente</option>
            <option value="ACTIVE">Ativa</option>
          </select>
        </Field>
      </div>
      {!confirmation && <Notification message={error} onDismiss={() => setError("")} />}
      {busy ? (
        <p role="status">Carregando defensores…</p>
      ) : failed ? (
        <p>
          Não foi possível carregar a lista. Use “Atualizar lista” para tentar
          novamente.
        </p>
      ) : (
        page && (
          <>
            {!page.items.length ? (
              <p className="empty">
                {params.get("name") || params.get("status")
                  ? "Nenhum defensor corresponde aos filtros."
                  : "Nenhum defensor cadastrado."}
              </p>
            ) : (
              <table className="units-table defenders-table">
                <thead>
                  <tr>
                    <th>Nome completo</th>
                    <th>Usuário</th>
                    <th>Conta / convite</th>
                    <th>Ações</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row) => (
                    <tr key={row.id}>
                      <td data-label="Nome completo">
                        <strong>{row.name}</strong>
                      </td>
                      <td data-label="Usuário">{row.username}</td>
                      <td data-label="Conta / convite">
                        {row.status === "ACTIVE" ? "Ativa" : invitationLabels[row.invitation_status] || "Ativação pendente"}
                      </td>
                      <td data-label="Ações"><div className="defender-row-actions">
                        <button
                          className="ghost"
                          aria-label={`Editar ${row.name}`}
                          onClick={() =>
                            navigate(`${defendersPath}/${row.id}/editar`, {
                              returnTo: back,
                            })
                          }
                        >
                          Editar
                        </button>
                        {row.status === "PENDING_ACTIVATION" && (
                          <div className="defender-action-menu" ref={menu === row.id ? menuRef : undefined}
                            onKeyDown={e => { if (e.key === "Escape") { setMenu(undefined); e.currentTarget.querySelector("button")?.focus(); } }}>
                            <button id={`invitation-actions-${row.id}`} className="ghost" aria-label={`Ações de convite de ${row.name}`} aria-expanded={menu === row.id}
                              disabled={!!action} onClick={() => setMenu(menu === row.id ? undefined : row.id)}>Ações ▾</button>
                            {menu === row.id && <div className="defender-action-options">
                              <button className="text-button" onClick={() => {
                                closeMenu(); setError("");
                                if (row.invitation_status === "AVAILABLE") setConfirmation({row, revoke:false});
                                else void invitationAction(row);
                              }}>{row.invitation_status === "AVAILABLE" ? "Regenerar convite" : "Gerar convite"}</button>
                              {row.invitation_status === "AVAILABLE" && <button className="text-button danger-text" onClick={() => {
                                closeMenu(); setError(""); setConfirmation({row, revoke:true});
                              }}>Revogar convite</button>}
                            </div>}
                          </div>
                        )}
                      </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <div className="unit-pagination">
              <span>
                {countLabel(page.total)} · Página {page.page} de{" "}
                {page.totalPages}
              </span>
              <button
                className="ghost"
                disabled={page.page <= 1}
                onClick={() => update({ page: String(page.page - 1) })}
              >
                Anterior
              </button>
              <button
                className="ghost"
                disabled={page.page >= page.totalPages}
                onClick={() => update({ page: String(page.page + 1) })}
              >
                Próxima
              </button>
            </div>
          </>
        )
      )}
      {confirmation && <Modal title={confirmation.revoke ? "Revogar convite de ativação?" : "Gerar um novo convite?"}
        busy={!!action} onClose={() => { setConfirmation(undefined); setError(""); }}>
        <p>{confirmation.revoke
          ? `O convite de ${confirmation.row.name} será invalidado imediatamente. Se o link já tiver sido enviado, ele não poderá mais ser utilizado.`
          : `O convite atual de ${confirmation.row.name} será invalidado. Somente o novo link poderá ser utilizado.`}</p>
        {confirmation.revoke && <p>Você poderá gerar um novo convite posteriormente.</p>}
        <Notification message={error} onDismiss={() => setError("")} />
        <div className="form-actions">
          <button className="ghost" disabled={!!action} onClick={() => { setConfirmation(undefined); setError(""); }}>Cancelar</button>
          <button className={confirmation.revoke ? "danger-button" : ""} disabled={!!action}
            onClick={() => void invitationAction(confirmation.row, confirmation.revoke)}>
            {action ? "Processando…" : confirmation.revoke ? "Revogar convite" : "Gerar novo convite"}
          </button>
        </div>
      </Modal>}
      {invite && (
        <InvitationModal {...invite} onClose={() => setInvite(undefined)} />
      )}
    </Card>
  );
}
