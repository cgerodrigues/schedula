import { useEffect, useState } from "react";
import { api, type Row } from "./api";
import { Card, Field } from "./components";
import { areasOf, displayPhone } from "./unitFields";
import { navigate, unitsPath, useLocation } from "./navigation";
import { humanError, Notification } from "./Notification";
export type UnitPage = {
  items: Row[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
};
export default function UnitList() {
  const location = useLocation();
  const params = new URLSearchParams(location.search);
  const [name, setName] = useState(params.get("name") || "");
  const [municipality, setMunicipality] = useState(
    params.get("municipality") || "",
  );
  const [page, setPage] = useState<UnitPage>();
  const [busy, setBusy] = useState(true),
    [error, setError] = useState("");
  const [failed, setFailed] = useState(false);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const q = new URLSearchParams(location.search);
    setName(q.get("name") || "");
    setMunicipality(q.get("municipality") || "");
  }, [location.search]);
  function update(values: Record<string, string>, replace = false) {
    const q = new URLSearchParams(location.search);
    Object.entries(values).forEach(([k, v]) => (v ? q.set(k, v) : q.delete(k)));
    navigate(unitsPath + (q.size ? "?" + q : ""), {}, replace);
  }
  useEffect(() => {
    const q = new URLSearchParams(location.search);
    if (
      name === (q.get("name") || "") &&
      municipality === (q.get("municipality") || "")
    )
      return;
    const timer = setTimeout(
      () => update({ name, municipality, page: "1" }, true),
      350,
    );
    return () => clearTimeout(timer);
  }, [name, municipality, location.search]);
  useEffect(() => {
    let alive = true;
    setBusy(true);
    setError("");
    setFailed(false);
    setPage(undefined);
    const q = new URLSearchParams(location.search);
    q.set("size", "10");
    api<UnitPage>("/rh/units/search?" + q)
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
  const filtered = ["name", "municipality", "area", "active"].some((k) =>
    params.get(k),
  );
  const returnTo = unitsPath + location.search;
  return (
    <Card
      title="Unidades da Defensoria"
      tag={
        page
          ? `${page.total} ${page.total === 1 ? "encontrada" : "encontradas"}`
          : "Gestão institucional"
      }
    >
      <Notification
        message={location.state.message || ""}
        kind="success"
        onDismiss={() =>
          navigate(returnTo, { savedName: location.state.savedName }, true)
        }
      />
      {location.state.savedName && (
        <button
          className="text-button"
          onClick={() =>
            navigate(
              unitsPath +
                "?name=" +
                encodeURIComponent(location.state.savedName),
            )
          }
        >
          Localizar unidade salva
        </button>
      )}
      <div className="unit-toolbar">
        <button onClick={() => navigate(unitsPath + "/nova", { returnTo })}>
          + Nova unidade
        </button>
        <button className="ghost" onClick={() => setRevision((r) => r + 1)}>
          Atualizar lista
        </button>
      </div>
      <div className="unit-filters">
        <Field label="Buscar unidade">
          <input
            type="search"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </Field>
        <Field label="Filtrar município">
          <input
            type="search"
            value={municipality}
            onChange={(e) => setMunicipality(e.target.value)}
          />
        </Field>
        <Field label="Área">
          <select
            value={params.get("area") || ""}
            onChange={(e) =>
              update({ name, municipality, area: e.target.value, page: "1" })
            }
          >
            <option value="">Todas</option>
            <option value="CIVEL">Cível</option>
            <option value="CRIMINAL">Criminal</option>
          </select>
        </Field>
        <Field label="Situação">
          <select
            value={params.get("active") || ""}
            onChange={(e) =>
              update({ name, municipality, active: e.target.value, page: "1" })
            }
          >
            <option value="">Todas</option>
            <option value="true">Ativas</option>
            <option value="false">Inativas</option>
          </select>
        </Field>
      </div>
      <Notification message={error} onDismiss={() => setError("")} />
      {busy ? (
        <p role="status">Carregando unidades…</p>
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
                {filtered
                  ? "Nenhuma unidade corresponde aos filtros."
                  : "Nenhuma unidade cadastrada."}
              </p>
            ) : (
              <table className="units-table">
                <thead>
                  <tr>
                    {[
                      "Unidade",
                      "Município / UF",
                      "Áreas",
                      "Telefone",
                      "Situação",
                      "Ações",
                    ].map((h) => (
                      <th key={h}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((u) => (
                    <tr key={u.id}>
                      <td data-label="Unidade">
                        <strong>{u.name}</strong>
                      </td>
                      <td data-label="Município / UF">
                        {u.municipality} / {u.state_code}
                      </td>
                      <td data-label="Áreas">
                        {areasOf(u.areas)
                          .map((a) => (a === "CIVEL" ? "Cível" : "Criminal"))
                          .join(" · ") || "Pendente"}
                      </td>
                      <td data-label="Telefone">{displayPhone(u.phone)}</td>
                      <td data-label="Situação">
                        {u.active ? "Ativa" : "Inativa"}
                      </td>
                      <td data-label="Ações">
                        <button
                          className="ghost"
                          aria-label={`Editar ${u.name}`}
                          onClick={() =>
                            navigate(`${unitsPath}/${u.id}/editar`, {
                              returnTo,
                            })
                          }
                        >
                          Editar
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <div className="unit-pagination">
              <span>
                {page.total} {page.total === 1 ? "unidade" : "unidades"} ·
                Página {page.page} de {page.totalPages}
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
    </Card>
  );
}
