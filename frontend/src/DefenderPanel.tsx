import { useState } from "react";
import { dashboardMetrics } from "./dashboard";
import { api, dateTime, inView, localDay, type Row } from "./api";
import { Card, Field, Form, Empty } from "./components";
export default function DefenderPanel({
  section,
  data,
  reload,
}: {
  section: string;
  data: Record<string, Row[]>;
  reload: () => Promise<void>;
}) {
  const {
    affiliations = [],
    appointments = [],
    slots = [],
    blocks = [],
  } = data;
  const [view, setView] = useState("week");
  const [day, setDay] = useState(localDay(new Date().toISOString()));
  const [selected, setSelected] = useState<Row | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function action(path: string, body?: unknown) {
    setBusy(true);
    setError("");
    try {
      await api("/defender/" + path, "POST", body);
      await reload();
      setSelected(null);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const active = appointments.filter((a) => a.status === "CONFIRMADO");
  const visible = appointments.filter((a) => inView(a.starts_at, day, view));
  const metrics = dashboardMetrics(
    appointments.map((a) => ({ starts_at: a.starts_at, status: a.status })),
  );
  return (
    <>
      {section === "overview" && (
        <>
          <div className="stats">
            {[
              ["Atendimentos previstos para hoje", metrics.expected],
              ["Confirmados hoje", metrics.confirmed],
              ["Realizados hoje", metrics.completed],
              ["Não comparecimentos hoje", metrics.absent],
              ["Cancelados hoje", metrics.cancelled],
            ].map(([l, v]) => (
              <div className="stat" key={l}>
                <span>{l}</span>
                <strong>{v}</strong>
              </div>
            ))}
          </div>
          <Card title="Seus vínculos" tag="Autorizados pelo RH">
            <div className="unit-grid">
              {affiliations.map((a) => (
                <article className="unit" key={a.id}>
                  <span className="eyebrow">
                    {a.area === "CIVEL" ? "Cível" : "Criminal"}
                  </span>
                  <h3>{a.unit_name}</h3>
                  <p>Você pode publicar horários nesta unidade e área.</p>
                </article>
              ))}
            </div>
            {!affiliations.length && (
              <Empty text="Solicite ao RH um vínculo institucional para publicar sua agenda." />
            )}
          </Card>
        </>
      )}
      {(section === "agenda" || section === "overview") && (
        <Card
          title={
            section === "overview" ? "Atendimentos da semana" : "Minha agenda"
          }
          tag="Horário de Fortaleza"
        >
          <div className="toolbar">
            <input
              aria-label="Data de referência"
              type="date"
              value={day}
              onChange={(e) => setDay(e.target.value)}
            />
            <div className="segmented">
              {[
                ["day", "Dia"],
                ["week", "Semana"],
                ["all", "Todos"],
              ].map(([v, l]) => (
                <button
                  className={view === v ? "active" : "ghost"}
                  key={v}
                  onClick={() => setView(v)}
                >
                  {l}
                </button>
              ))}
            </div>
          </div>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Data e horário</th>
                  <th>Cidadão</th>
                  <th>Unidade / área</th>
                  <th>Situação</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {visible.map((a) => (
                  <tr key={a.id}>
                    <td>{dateTime(a.starts_at)}</td>
                    <td>
                      <strong>
                        {a.first_name} {a.last_name}
                      </strong>
                    </td>
                    <td>
                      {a.unit_name}
                      <small>{a.area}</small>
                    </td>
                    <td>
                      <span className={"badge " + a.status.toLowerCase()}>
                        {a.status.replaceAll("_", " ")}
                      </span>
                    </td>
                    <td>
                      <button
                        className="text-button"
                        onClick={() => {
                          setSelected(a);
                          setError("");
                        }}
                      >
                        Detalhes →
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!visible.length && (
            <Empty text="Nenhum atendimento neste período. As reservas feitas pelo WhatsApp aparecerão aqui." />
          )}
        </Card>
      )}
      {section === "availability" && (
        <>
          <div className="split">
            <Card title="Publicar disponibilidade" tag="Avulsa ou semanal">
              <Form
                label="Publicar horários"
                onSubmit={async (f) => {
                  const r = Object.fromEntries(f);
                  await api("/defender/slots", "POST", {
                    ...r,
                    minutes: Number(r.minutes),
                    repeatUntil: r.repeatUntil || null,
                  });
                  await reload();
                }}
              >
                <Field label="Unidade e área">
                  <select name="affiliationId" required>
                    <option value="">Selecione um vínculo</option>
                    {affiliations.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.unit_name} · {a.area}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="Primeiro dia">
                  <input name="date" type="date" required />
                </Field>
                <Field label="Início">
                  <input name="start" type="time" required />
                </Field>
                <Field label="Fim">
                  <input name="end" type="time" required />
                </Field>
                <Field label="Duração em minutos">
                  <input
                    name="minutes"
                    type="number"
                    min={5}
                    max={240}
                    defaultValue={30}
                    required
                  />
                </Field>
                <Field label="Repetir semanalmente até (opcional)">
                  <input name="repeatUntil" type="date" />
                </Field>
              </Form>
              <p className="hint">
                Horários de Fortaleza. Deixe a recorrência vazia para publicar
                apenas um dia.
              </p>
            </Card>
            <Card title="Bloquear período">
              <Form
                label="Criar bloqueio"
                onSubmit={async (f) => {
                  await api("/defender/blocks", "POST", {
                    start: f.get("start") + "-03:00",
                    end: f.get("end") + "-03:00",
                    reason: f.get("reason"),
                  });
                  await reload();
                }}
              >
                <Field label="Início do bloqueio">
                  <input name="start" type="datetime-local" required />
                </Field>
                <Field label="Fim do bloqueio">
                  <input name="end" type="datetime-local" required />
                </Field>
                <Field label="Motivo">
                  <input name="reason" maxLength={250} required />
                </Field>
              </Form>
              <p className="hint">
                Reservas confirmadas precisam ser tratadas antes do bloqueio.
              </p>
              {blocks.map((b) => (
                <p className="block-note" key={b.id}>
                  {dateTime(b.starts_at)} — {b.reason}
                </p>
              ))}
            </Card>
          </div>
          <Card title="Recorrências e publicações">
            <p className="hint">
              Para substituir uma série, retire-a e publique novos horários.
              Reservas confirmadas impedem a retirada até serem tratadas.
            </p>
            {[
              ...new Set(
                slots.filter((s) => s.published).map((s) => s.series_id),
              ),
            ].map((id) => (
              <div className="list-row" key={id}>
                <span>
                  {dateTime(slots.find((s) => s.series_id === id)!.starts_at)} ·{" "}
                  {slots.filter((s) => s.series_id === id).length} vagas
                </span>
                <button
                  className="text-button"
                  onClick={async () => {
                    if (
                      window.confirm(
                        "Retirar todos os horários desta publicação?",
                      )
                    )
                      try {
                        await api("/defender/series/" + id, "DELETE");
                        await reload();
                      } catch (e) {
                        window.alert((e as Error).message);
                      }
                  }}
                >
                  Retirar publicação
                </button>
              </div>
            ))}
          </Card>
          <Card title="Horários publicados">
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Data</th>
                    <th>Unidade</th>
                    <th>Área</th>
                    <th>Publicação</th>
                  </tr>
                </thead>
                <tbody>
                  {slots.map((s) => (
                    <tr key={s.id}>
                      <td>{dateTime(s.starts_at)}</td>
                      <td>{s.unit_name}</td>
                      <td>{s.area}</td>
                      <td>
                        {s.published ? "Publicado" : "Retirado / bloqueado"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {!slots.length && <Empty />}
          </Card>
        </>
      )}
      {selected && (
        <div className="modal-backdrop">
          <section
            role="dialog"
            aria-modal="true"
            aria-label="Detalhes do atendimento"
            className="modal"
          >
            <button
              className="close ghost"
              aria-label="Fechar"
              onClick={() => setSelected(null)}
            >
              ×
            </button>
            <span className="eyebrow">Atendimento</span>
            <h2>
              {selected.first_name} {selected.last_name}
            </h2>
            <p>
              {dateTime(selected.starts_at)} · {selected.unit_name}
            </p>
            <p>WhatsApp: {selected.phone}</p>
            <p className="protocol">Protocolo: {selected.protocol}</p>
            <p>
              {selected.status.replaceAll("_", " ")}{" "}
              {selected.attended_at ? "· Comparecimento registrado" : ""}
            </p>
            {selected.previous_id && (
              <p className="hint">
                Remarcação do atendimento {selected.previous_id}
              </p>
            )}
            {error && (
              <p className="error" role="alert">
                {error}
              </p>
            )}
            {selected.status === "CONFIRMADO" && (
              <>
                <div className="actions">
                  <button
                    disabled={busy}
                    onClick={() =>
                      action(`appointments/${selected.id}/status`, {
                        status: "COMPARECEU",
                      })
                    }
                  >
                    Registrar presença
                  </button>
                  <button
                    disabled={busy}
                    onClick={() =>
                      action(`appointments/${selected.id}/status`, {
                        status: "REALIZADO",
                      })
                    }
                  >
                    Concluir atendimento
                  </button>
                  <button
                    className="secondary"
                    disabled={busy}
                    onClick={() =>
                      action(`appointments/${selected.id}/status`, {
                        status: "NAO_COMPARECEU",
                      })
                    }
                  >
                    Não compareceu
                  </button>
                </div>
                <hr />
                <Form
                  label="Confirmar remarcação e notificar"
                  onSubmit={async (f) => {
                    await api(
                      `/defender/appointments/${selected.id}/reschedule`,
                      "POST",
                      { slotId: f.get("slotId") },
                    );
                    await reload();
                    setSelected(null);
                  }}
                >
                  <Field label="Remarcar para outra vaga">
                    <select name="slotId" required>
                      <option value="">Selecione</option>
                      {slots
                        .filter(
                          (s) =>
                            s.published &&
                            s.area === selected.area &&
                            new Date(s.starts_at) > new Date() &&
                            !active.some((a) => a.slot_id === s.id),
                        )
                        .map((s) => (
                          <option key={s.id} value={s.id}>
                            {dateTime(s.starts_at)} · {s.unit_name}
                          </option>
                        ))}
                    </select>
                  </Field>
                </Form>
                <button
                  className="danger"
                  disabled={busy}
                  onClick={() => {
                    if (
                      window.confirm(
                        "Cancelar este atendimento e notificar o cidadão?",
                      )
                    )
                      void action(`appointments/${selected.id}/cancel`);
                  }}
                >
                  Cancelar e notificar cidadão
                </button>
              </>
            )}
          </section>
        </div>
      )}
    </>
  );
}
