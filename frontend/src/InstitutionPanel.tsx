import DefenderManagement from "./DefenderManagement";
import UnitPanel from "./UnitPanel";
import { areasOf } from "./unitFields";
import { useState } from "react";
import { api, type Row } from "./api";
import { Card, Field, Form, Empty } from "./components";
export default function InstitutionPanel({
  section,
  data,
  reload,
}: {
  section: string;
  data: Record<string, Row[]>;
  reload: () => Promise<void>;
}) {
  const [selectedUnit, setSelectedUnit] = useState("");
  const {
    units = [],
    defenders = [],
    affiliations = [],
    notifications = [],
  } = data;
  const save = (resource: string) => async (f: FormData) => {
    await api("/rh/" + resource, "POST", Object.fromEntries(f));
    await reload();
  };
  return (
    <>
      {section === "overview" && (
        <>
          <div className="stats">
            {[
              [
                "Municípios atendidos",
                new Set(units.map((u) => u.municipality_id)).size,
              ],
              ["Unidades", units.length],
              ["Defensores", (data["defenders/indicators"] as unknown as { total: number })?.total ?? 0],
              ["Vínculos ativos", affiliations.length],
            ].map(([l, v]) => (
              <div className="stat" key={l}>
                <span>{l}</span>
                <strong>{v}</strong>
              </div>
            ))}
          </div>
          <Card
            title="Uma rede de atendimento conectada"
            tag="Gestão institucional"
          >
            <p className="muted">
              Cadastre unidades, adicione defensores e autorize a atuação por
              área. A publicação de horários é feita pelo próprio defensor.
            </p>
            <div className="steps">
              <div>
                <b>01</b>
                <h3>Estruture a rede</h3>
                <p>Municípios e unidades com endereço e telefone.</p>
              </div>
              <div>
                <b>02</b>
                <h3>Conecte a equipe</h3>
                <p>Defensores vinculados às áreas Cível e Criminal.</p>
              </div>
              <div>
                <b>03</b>
                <h3>Abra o atendimento</h3>
                <p>Vagas publicadas ficam disponíveis no WhatsApp.</p>
              </div>
            </div>
          </Card>
          <Card title="Notificações do WhatsApp" tag="Últimos 100 envios">
            <p className="hint">
              A fila permanece pendente enquanto o envio à Meta estiver
              desativado. Falhas podem ser reenviadas após corrigir a
              configuração.
            </p>
            {notifications.map((n) => (
              <div className="list-row" key={n.id}>
                <span>
                  {n.status} · {n.attempts} tentativas{" "}
                  {n.delivery_status ? "· " + n.delivery_status : ""}
                </span>
                {n.status === "FAILED" && (
                  <button
                    className="text-button"
                    onClick={async () => {
                      try {
                        await api(
                          "/rh/notifications/" + n.id + "/retry",
                          "POST",
                        );
                        await reload();
                      } catch (e) {
                        window.alert((e as Error).message);
                      }
                    }}
                  >
                    Tentar novamente
                  </button>
                )}
              </div>
            ))}
            {!notifications.length && <Empty text="Nenhum envio registrado." />}
          </Card>
        </>
      )}
      {section === "units" && <UnitPanel />}
      {section === "defenders" && <DefenderManagement />}
      {section === "affiliations" && (
        <div className="split">
          <Card title="Autorizar atuação">
            <Form onSubmit={save("affiliations")}>
              <Field label="Defensor">
                <select name="defenderId" required>
                  <option value="">Selecione</option>
                  {defenders.map((d) => (
                    <option value={d.id} key={d.id}>
                      {d.name}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Unidade">
                <select
                  name="unitId"
                  required
                  value={selectedUnit}
                  onChange={(e) => setSelectedUnit(e.target.value)}
                >
                  <option value="">Selecione</option>
                  {units.map((u) => (
                    <option value={u.id} key={u.id}>
                      {u.name}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Área de atuação">
                <select name="area" required key={selectedUnit}>
                  <option value="">Selecione a área</option>
                  {areasOf(units.find((u) => u.id === selectedUnit)?.areas).map(
                    (a) => (
                      <option key={a} value={a}>
                        {a === "CIVEL" ? "Cível" : "Criminal"}
                      </option>
                    ),
                  )}
                </select>
              </Field>
            </Form>
          </Card>
          <Card title="Vínculos institucionais">
            {affiliations.map((a) => (
              <div className="list-row" key={a.id}>
                <div>
                  <strong>{a.defender_name}</strong>
                  <p>{a.unit_name}</p>
                </div>
                <div>
                  <span className="tag">
                    {a.area === "CIVEL" ? "Cível" : "Criminal"}
                  </span>
                  <button
                    className="text-button"
                    onClick={async () => {
                      if (
                        window.confirm(
                          "Remover vínculo? Vínculos com histórico serão preservados.",
                        )
                      )
                        try {
                          await api("/rh/affiliations/" + a.id, "DELETE");
                          await reload();
                        } catch (e) {
                          window.alert((e as Error).message);
                        }
                    }}
                  >
                    Remover
                  </button>
                </div>
              </div>
            ))}
            {!affiliations.length && <Empty />}
          </Card>
        </div>
      )}
    </>
  );
}
