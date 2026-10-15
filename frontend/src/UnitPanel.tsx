import { useEffect, useRef, useState } from "react";
import { api, type Row } from "./api";
import { Card, Field, Empty } from "./components";
import { areasOf, cepMask, phoneMask } from "./unitFields";

type Catalog = {
  municipalities: Row[];
  complete: boolean;
  syncedAt: string | null;
  warning: string;
};
type Postal = {
  postalCode: string;
  street: string;
  neighborhood: string;
  city: string;
  stateCode: string;
  ibgeCode: string;
};
type Values = {
  name: string;
  stateCode: string;
  municipalityId: string;
  areas: string[];
  active: boolean;
  postalCode: string;
  street: string;
  streetNumber: string;
  complement: string;
  neighborhood: string;
  phone: string;
  email: string;
};
const initial = (u?: Row): Values => ({
  name: u?.name || "",
  stateCode: u?.state_code || "CE",
  municipalityId: u?.municipality_id || "",
  areas: areasOf(u?.areas),
  active: u?.active ?? true,
  postalCode: cepMask(u?.postal_code || ""),
  street: u?.street || "",
  streetNumber: u?.street_number || "",
  complement: u?.complement || "",
  neighborhood: u?.neighborhood || "",
  phone: u?.phone || "",
  email: u?.email || "",
});

export default function UnitPanel({
  units,
  reload,
}: {
  units: Row[];
  reload: () => Promise<void>;
}) {
  const [editing, setEditing] = useState<Row>();
  const [generation, setGeneration] = useState(0);
  return (
    <div className="unit-workspace">
      <UnitForm
        key={`${editing?.id || "new"}-${generation}`}
        unit={editing}
        onCancel={() => {
          setEditing(undefined);
          setGeneration((g) => g + 1);
        }}
        onSaved={async () => {
          await reload();
        }}
      />
      <Card title="Unidades da Defensoria" tag={`${units.length} cadastradas`}>
        {!units.length && <Empty />}
        <div className="unit-list">
          {units.map((u) => (
            <article className="unit" key={u.id}>
              <div className="unit-heading">
                <span className="eyebrow">
                  {u.municipality} / {u.state_code}
                </span>
                <span className="tag">{u.active ? "Ativa" : "Inativa"}</span>
              </div>
              <h3>{u.name}</h3>
              <p>
                {u.street
                  ? [u.street, u.street_number, u.complement, u.neighborhood]
                      .filter(Boolean)
                      .join(", ")
                  : u.address}
              </p>
              <p>
                {u.phone}
                {u.email && " · " + u.email}
              </p>
              <p>
                {areasOf(u.areas)
                  .map((a) => (a === "CIVEL" ? "Cível" : "Criminal"))
                  .join(" · ") || "Áreas pendentes de regularização"}
              </p>
              <button
                type="button"
                className="ghost"
                onClick={() => {
                  setEditing(u);
                  setGeneration((g) => g + 1);
                }}
              >
                Editar {u.name}
              </button>
            </article>
          ))}
        </div>
      </Card>
    </div>
  );
}

export function UnitForm({
  unit,
  onSaved,
  onCancel,
}: {
  unit?: Row;
  onSaved: () => Promise<void>;
  onCancel: () => void;
}) {
  const [v, setV] = useState<Values>(() => initial(unit));
  const current = useRef(v);
  current.current = v;
  const [states, setStates] = useState<Row[]>([]);
  const [catalog, setCatalog] = useState<Catalog>();
  const catalogRef = useRef(catalog);
  catalogRef.current = catalog;
  const [catalogBusy, setCatalogBusy] = useState(false);
  const [catalogError, setCatalogError] = useState("");
  const [query, setQuery] = useState("");
  const [cepBusy, setCepBusy] = useState(false);
  const [cepMessage, setCepMessage] = useState("");
  const [pending, setPending] = useState<Postal>();
  const [busy, setBusy] = useState(false);
  const saving = useRef(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const catalogSequence = useRef(0);
  const cepSequence = useRef(0);
  const lastPostal = useRef(v.postalCode);
  const [savedId, setSavedId] = useState<string | undefined>(unit?.id);
  useEffect(() => {
    let alive = true;
    api<Row[]>("/rh/states")
      .then((s) => {
        if (alive) setStates(s);
      })
      .catch((e) => {
        if (alive) setCatalogError(e.message);
      });
    return () => {
      alive = false;
    };
  }, []);
  async function loadCatalog(uf: string, sync = false) {
    const seq = ++catalogSequence.current;
    setCatalogBusy(true);
    setCatalogError("");
    try {
      const result = await api<Catalog>(
        `/rh/catalog/${uf}${sync ? "/sync" : ""}`,
        sync ? "POST" : "GET",
      );
      if (seq === catalogSequence.current) setCatalog(result);
    } catch (e) {
      if (seq === catalogSequence.current)
        setCatalogError((e as Error).message);
    } finally {
      if (seq === catalogSequence.current) setCatalogBusy(false);
    }
  }
  useEffect(() => {
    setCatalog(undefined);
    setQuery("");
    void loadCatalog(v.stateCode);
    return () => {
      catalogSequence.current++;
    };
  }, [v.stateCode]);
  useEffect(() => {
    const seq = ++cepSequence.current;
    const cep = v.postalCode.replace(/\D/g, "");
    setPending(undefined);
    setCepMessage("");
    setCepBusy(false);
    // Opening an editor does not refresh an existing address; later changes do,
    // including returning to its original CEP after selecting another city.
    if (v.postalCode === lastPostal.current) return;
    lastPostal.current = v.postalCode;
    if (cep.length !== 8) return;
    const timer = setTimeout(async () => {
      setCepBusy(true);
      try {
        const result = await api<Postal>("/rh/postal/" + cep);
        if (seq !== cepSequence.current) return;
        const now = current.current;
        const selected = catalogRef.current?.municipalities.find(
          (m) => m.id === now.municipalityId,
        );
        if (
          result.stateCode !== now.stateCode ||
          !selected ||
          selected.ibge_code !== result.ibgeCode
        )
          setPending(result);
        else fillAddress(result);
      } catch (e) {
        if (seq === cepSequence.current) setCepMessage((e as Error).message);
      } finally {
        if (seq === cepSequence.current) setCepBusy(false);
      }
    }, 400);
    return () => {
      clearTimeout(timer);
      cepSequence.current++;
    };
  }, [v.postalCode]);
  function fillAddress(result: Postal) {
    setV((prev) => ({
      ...prev,
      street: prev.street || result.street,
      neighborhood: prev.neighborhood || result.neighborhood,
    }));
    setCepMessage(
      "CEP consultado. Confira o endereço; os campos que você já preencheu foram mantidos.",
    );
    setPending(undefined);
  }
  async function acceptPostal() {
    if (!pending) return;
    const result = pending,
      seq = cepSequence.current;
    setCepBusy(true);
    try {
      const official = await api<Catalog>("/rh/catalog/" + result.stateCode);
      if (seq !== cepSequence.current) return;
      const city = official.municipalities.find(
        (m) => m.ibge_code === result.ibgeCode,
      );
      if (!city)
        throw new Error(
          "Município do CEP não encontrado no catálogo oficial. Sincronize a UF ou preencha manualmente.",
        );
      setV((prev) => ({
        ...prev,
        stateCode: result.stateCode,
        municipalityId: city.id,
      }));
      fillAddress(result);
    } catch (e) {
      if (seq === cepSequence.current) setCepMessage((e as Error).message);
    } finally {
      if (seq === cepSequence.current) setCepBusy(false);
    }
  }
  function change<K extends keyof Values>(key: K, value: Values[K]) {
    if (key === "stateCode" || key === "municipalityId") {
      cepSequence.current++;
      setPending(undefined);
      setCepBusy(false);
    }
    setSuccess("");
    setV((prev) => ({ ...prev, [key]: value }));
  }
  const normalized = (s: string) =>
    s
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .toLocaleLowerCase("pt-BR");
  const choices =
    catalog?.municipalities.filter(
      (m) =>
        m.id === v.municipalityId ||
        normalized(m.name).includes(normalized(query)),
    ) || [];
  return (
    <Card
      title={savedId ? "Editar unidade" : "Nova unidade"}
      tag="Gestão institucional"
    >
      <p className="muted">
        Identifique a unidade, suas áreas e os contatos para o cidadão.
      </p>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          if (saving.current) return;
          setError("");
          setSuccess("");
          if (!v.areas.length) {
            setError("Selecione ao menos uma área de atendimento.");
            return;
          }
          if (pending) {
            setError(
              "Confirme ou mantenha a localização indicada na consulta do CEP antes de salvar.",
            );
            return;
          }
          saving.current = true;
          setBusy(true);
          try {
            const result = await api<{ id: string }>(
              "/rh/units" + (savedId ? "/" + savedId : ""),
              savedId ? "PUT" : "POST",
              v,
            );
            setSavedId(result.id);
            setSuccess("Unidade salva com sucesso.");
            try {
              await onSaved();
            } catch {
              setError(
                "Unidade salva, mas não foi possível atualizar a lista. Recarregue a página.",
              );
            }
          } catch (e) {
            setError((e as Error).message);
          } finally {
            saving.current = false;
            setBusy(false);
          }
        }}
      >
        <fieldset disabled={busy}>
          <legend>01 · Identificação</legend>
          <div className="form-grid">
            <Field label="Nome da unidade">
              <input
                required
                maxLength={150}
                value={v.name}
                onChange={(e) => change("name", e.target.value)}
              />
            </Field>
            <Field label="Situação">
              <select
                value={v.active ? "active" : "inactive"}
                onChange={(e) => change("active", e.target.value === "active")}
              >
                <option value="active">Ativa</option>
                <option value="inactive">Inativa</option>
              </select>
            </Field>
            <Field label="Estado">
              <select
                required
                value={v.stateCode}
                onChange={(e) => {
                  change("stateCode", e.target.value);
                  change("municipalityId", "");
                  setPending(undefined);
                }}
              >
                {!states.length && (
                  <option value={v.stateCode}>{v.stateCode}</option>
                )}
                {states.map((s) => (
                  <option key={s.code} value={s.code}>
                    {s.name} ({s.code})
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Pesquisar município">
              <input
                type="search"
                value={query}
                placeholder="Digite parte do nome"
                onChange={(e) => setQuery(e.target.value)}
              />
            </Field>
            <Field label="Município">
              <select
                required
                value={v.municipalityId}
                onChange={(e) => change("municipalityId", e.target.value)}
              >
                <option value="">Selecione o município</option>
                {unit &&
                  !choices.some((m) => m.id === unit.municipality_id) &&
                  v.stateCode === unit.state_code && (
                    <option value={unit.municipality_id}>
                      {unit.municipality} (cadastro anterior — sincronize)
                    </option>
                  )}
                {choices.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.name}
                  </option>
                ))}
              </select>
            </Field>
            <div className="catalog-status">
              <p role="status">
                {catalogBusy
                  ? "Carregando municípios…"
                  : catalog?.complete
                    ? "Catálogo oficial salvo localmente"
                    : "Catálogo ainda não sincronizado"}
              </p>
              <button
                type="button"
                className="text-button"
                disabled={catalogBusy}
                onClick={() => void loadCatalog(v.stateCode, true)}
              >
                Sincronizar municípios
              </button>
            </div>
          </div>
          {(catalogError || catalog?.warning) && (
            <p className="error" role="alert">
              {catalogError || catalog?.warning}
            </p>
          )}
          <div
            className="area-options"
            role="group"
            aria-label="Áreas de atendimento"
          >
            <span>Áreas de atendimento *</span>
            {[
              ["CIVEL", "Cível"],
              ["CRIMINAL", "Criminal"],
            ].map(([value, label]) => (
              <label key={value}>
                <input
                  type="checkbox"
                  checked={v.areas.includes(value)}
                  onChange={(e) =>
                    change(
                      "areas",
                      e.target.checked
                        ? [...v.areas, value]
                        : v.areas.filter((a) => a !== value),
                    )
                  }
                />
                {label}
              </label>
            ))}
          </div>
          <p className="hint">
            As áreas pertencem à unidade, mesmo quando ainda não há defensores
            ou horários disponíveis.
          </p>
        </fieldset>
        <fieldset disabled={busy}>
          <legend>02 · Localização</legend>
          {unit?.address && (
            <p className="legacy-address">
              Endereço anterior preservado: {unit.address}
            </p>
          )}
          <div className="form-grid">
            <Field label="CEP">
              <input
                required
                inputMode="numeric"
                autoComplete="postal-code"
                placeholder="00000-000"
                pattern="[0-9]{5}-[0-9]{3}"
                value={v.postalCode}
                onChange={(e) => change("postalCode", cepMask(e.target.value))}
              />
            </Field>
            <div className="hint" role="status">
              {cepBusy
                ? "Consultando CEP…"
                : "Consulta automática. Você também pode preencher o endereço manualmente."}
            </div>
          </div>
          {cepMessage && (
            <p className="hint" role="status">
              {cepMessage}
            </p>
          )}
          {pending && (
            <div className="postal-confirmation" role="alert">
              <p>
                O CEP indica {pending.city} / {pending.stateCode}, diferente da
                localização selecionada. Deseja usar esse município?
              </p>
              <button
                type="button"
                disabled={cepBusy}
                onClick={() => void acceptPostal()}
              >
                Usar município do CEP
              </button>
              <button
                type="button"
                className="ghost"
                onClick={() => {
                  setPending(undefined);
                  setCepMessage(
                    "Localização mantida. Confira o CEP e o endereço manualmente.",
                  );
                }}
              >
                Manter localização selecionada
              </button>
            </div>
          )}
          <div className="form-grid">
            <Field label="Logradouro">
              <input
                required
                maxLength={150}
                value={v.street}
                onChange={(e) => change("street", e.target.value)}
              />
            </Field>
            <Field label="Número">
              <input
                required
                maxLength={20}
                placeholder="Ex.: 100 ou S/N"
                value={v.streetNumber}
                onChange={(e) => change("streetNumber", e.target.value)}
              />
            </Field>
            <Field label="Complemento (opcional)">
              <input
                maxLength={100}
                value={v.complement}
                onChange={(e) => change("complement", e.target.value)}
              />
            </Field>
            <Field label="Bairro">
              <input
                required
                maxLength={100}
                value={v.neighborhood}
                onChange={(e) => change("neighborhood", e.target.value)}
              />
            </Field>
          </div>
        </fieldset>
        <fieldset disabled={busy}>
          <legend>03 · Contato</legend>
          <div className="form-grid">
            <Field label="Telefone de contato">
              <input
                required
                type="tel"
                inputMode="tel"
                maxLength={20}
                placeholder="(85) 3333-4444"
                value={v.phone}
                onChange={(e) => change("phone", phoneMask(e.target.value))}
              />
            </Field>
            <Field label="E-mail institucional (opcional)">
              <input
                type="email"
                maxLength={254}
                value={v.email}
                onChange={(e) => change("email", e.target.value)}
              />
            </Field>
          </div>
        </fieldset>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        {success && (
          <p className="success" role="status">
            {success}
          </p>
        )}
        <div className="form-actions">
          <button disabled={busy || cepBusy} type="submit">
            {busy ? "Salvando…" : "Salvar unidade"}
          </button>
          <button
            disabled={busy}
            className="ghost"
            type="button"
            onClick={onCancel}
          >
            {savedId ? "Cadastrar outra unidade" : "Limpar formulário"}
          </button>
        </div>
      </form>
    </Card>
  );
}
