# Desenvolvimento local e demonstração

## Banco e backend

Execute os comandos do README. O banco existente permanece no volume `postgres_data`; não use `down -v`. V1 e V2 não foram alteradas. V3 é aplicada pelo Flyway na primeira inicialização desta versão e preserva contas existentes como ACTIVE. V4 acrescenta catálogo IBGE e administração completa de unidades, mantendo os dados anteriores. Não edite migrations já aplicadas: próximas mudanças devem ser V5+. Senha do banco precisa ser exportada; `.env` é ignorado pelo Git, mas Spring não carrega `.env` automaticamente. O Compose carrega `.env`, o processo Java usa ambiente.

Variáveis: `DB_URL` (padrão jdbc:postgresql://localhost:5433/schedula), `DB_USERNAME` (schedula), `DB_PASSWORD` (obrigatória). `SCHEDULA_ADMIN_USERNAME` e `SCHEDULA_ADMIN_PASSWORD` criam o primeiro RH, somente quando ausente. Nenhum usuário de demonstração é criado silenciosamente no banco existente.

O cadastro de defensores é realizado pelo RH. Use telefone fictício como `00000000000` na unidade. O próprio defensor escolhe sua senha de no mínimo 12 caracteres e no máximo 72 bytes UTF-8; o RH não define senha pessoal. A senha de bootstrap não deve permanecer em scripts versionados. Para testar nova criação de RH, use um username novo; o bootstrap não redefine uma conta existente.

## Frontend

`npm ci`, `npm run dev`, abrir http://localhost:5173. Backend em 8080. `npm run build` gera `frontend/dist`. Não publique o Vite dev server como produção. O frontend usa a mesma origem para sessão/CSRF.

## Primeiro login, convite e ativação

1. Configure usuário e senha RH via ambiente, inicie o backend e abra http://localhost:5173. Use exatamente esses valores no primeiro login. Não há credencial padrão e o bootstrap não redefine contas preexistentes.
2. RH → Defensores → Cadastrar defensor: preencha nome e usuário. O sistema cadastra PENDING_ACTIVATION e gera o convite. Copie o link e compartilhe por canal privado. O token não pode ser consultado posteriormente: se perder, gere outro.
3. Gerar novo convite invalida o anterior. Revogar convite invalida sem gerar outro. Ambos exigem RH. Um convite consumido ou expirado não ativa novamente.
4. O defensor abre o link no endereço acessível **privado** do frontend, informa nova senha e confirmação e ativa. Depois entra com seu username. Não existe login automático.
5. Alterar minha senha está disponível nos dois painéis: senha atual, nova e confirmação. Após sucesso, entre novamente. O encerramento vale para a sessão atual.

No desenvolvimento o link usa a origem do navegador, normalmente localhost. Para outra máquina, use endereço privado acessível (rede interna/VPN), com HTTPS e proxy de mesma origem. Não compartilhe links localhost como se fossem acessíveis remotamente. O domínio público do webhook não atende convites nem painel. Como o fragmento é removido ao abrir a tela, uma recarga exige reabrir o convite original.

## Limitação de login

| Variável | Padrão | Significado |
|---|---:|---|
| `LOGIN_MAX_ATTEMPTS` | 10 | Total de tentativas por endereço dentro da janela |
| `LOGIN_WINDOW_SECONDS` | 300 | Janela deslizante em segundos (1 a 86400) |
| `LOGIN_MAX_ADDRESSES` | 10000 | Máximo de endereços simultâneos em memória |

Tentativas válidas também contam. HTTP 429 inclui Retry-After; aguarde antes de reenviar. Trocar username ou X-Forwarded-For não contorna o limite. Um proxy/Vite pode fazer usuários compartilharem o mesmo orçamento; ajuste para a demonstração se necessário, sem desabilitar a proteção. Controle adequado a uma instância; reinício limpa o estado. Não há Redis.

## Meta WhatsApp Cloud API

Referências oficiais: [webhooks](https://developers.facebook.com/documentation/business-messaging/whatsapp/webhooks/overview), [envio de mensagens](https://developers.facebook.com/documentation/business-messaging/whatsapp/messages/send-messages) e [validação de assinatura no SDK oficial](https://whatsapp.github.io/WhatsApp-Nodejs-SDK/api-reference/webhooks/start/). O servidor usa REST diretamente, sem automação do WhatsApp Web.

Configure posteriormente, no ambiente do backend:

| Variável | Uso |
|---|---|
| `WHATSAPP_ENABLED` | `true` habilita worker externo; padrão `false` |
| `WHATSAPP_APP_SECRET` | Segredo da aplicação para HMAC dos webhooks |
| `WHATSAPP_VERIFY_TOKEN` | Valor privado escolhido para verificação GET |
| `WHATSAPP_ACCESS_TOKEN` | Token da Meta com permissão de mensagens |
| `WHATSAPP_PHONE_NUMBER_ID` | ID do número de testes, não o número telefônico |
| `WHATSAPP_GRAPH_VERSION` | Versão Graph suportada pela conta, no formato `vN.N` |
| `WHATSAPP_TEMPLATE_NAME` | Nome exato do template aprovado, uma variável de texto no corpo |
| `WHATSAPP_TEMPLATE_LANGUAGE` | Idioma aprovado; padrão `pt_BR` |
| `SESSION_COOKIE_SECURE` | `true` ao operar por HTTPS |

1. Crie/configure aplicativo e número de testes na plataforma oficial. Cadastre destinatários de teste permitidos. Não é necessário contratar um serviço intermediário.
2. Disponibilize um endpoint HTTPS para `/webhooks/whatsapp`, sem expor banco ou endpoints sem autenticação. Configure callback, verify token e assinatura do campo `messages` na Meta.
3. Configure segredos no ambiente. A verificação GET precisa devolver exatamente o desafio. POST sem assinatura válida retorna 401.
4. Prepare um template de notificação institucional aprovado com um parâmetro de corpo para o texto do aviso. Confirme nome, idioma, conteúdo permitido e elegibilidade na conta. Sem isso, notificações proativas podem falhar.
5. Ative o worker, envie OI do destinatário permitido e siga o roteiro do README. Confira o protocolo no aparelho e o atendimento no painel do defensor.
6. Cancele/remarque pelo painel e confirme o template recebido. Acompanhe aceitação/entrega e falhas no dashboard RH. Reenvie FAILED somente após corrigir a configuração; perda de resposta externa pode gerar notificação repetida.

**Pendente externo:** credenciais, endpoint HTTPS, assinatura da conta, template aprovado e comunicação efetiva nos dois sentidos. Os testes locais não comprovam funcionamento da conta Meta, aprovação de template ou entrega no aparelho. Nenhum serviço pago foi utilizado. Custos/regras de conta devem ser conferidos pelo responsável antes de habilitar envios externos.

## Testes

`./mvnw verify` inicia PostgreSQL 17 isolado com Testcontainers, aplica V1/V2/V3/V4, valida contexto/JPA, regras, concorrência, autenticação/CSRF, titularidade, webhook HMAC, retomada, idempotência e outbox. `HttpAndWhatsappTest` passa o fluxo completo por MockMvc com envelopes Meta assinados fictícios. Não depende de credenciais reais e não envia mensagens à Meta.

`npm test` cobre calendário no fuso institucional, erro de formulário e prevenção de duplo envio. `npm run build` verifica TypeScript e gera o build. A execução real em navegador deve ser conferida após qualquer alteração visual.

## Operação e diagnóstico

- 401 administrativo: sessão ausente/expirada ou login inválido. 403: papel incorreto ou CSRF ausente.
- 409: vínculo, estado ou vaga em conflito; atualizar e consultar mensagem.
- Vaga ocupada durante conversa: enviar MENU e escolher novamente; não existe pré-reserva.
- Sem vagas: contato da unidade é informado. Em urgência, enviar URGENTE.
- Notificações PENDING com worker desativado são esperadas. FAILED por erro permanente ou após oito falhas transitórias exige correção e ação de reenvio no RH.
- Health e logs técnicos não devem receber payloads, tokens ou dados pessoais. Não habilite logs HTTP de corpo/Authorization.
- Testcontainers pode baixar imagens na primeira execução. Falta de Docker causa falha explícita, não teste ignorado.

## Proxy HTTPS público restrito ao webhook

O endpoint de demonstração público deve encaminhar **somente GET e POST no caminho exato** `/webhooks/whatsapp`. Painel, login, ativação, API administrativa e actuator ficam fora desse endereço. Autenticação da aplicação não substitui essa restrição.

Exemplo para Nginx já disponível, com domínio e certificado próprios. Não foi instalado nem publicado automaticamente. Use backend local em 8081, iniciado com `SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8081`; o frontend permanece privado. [Referência oficial de location](https://nginx.org/en/docs/http/ngx_http_core_module.html#location).

```nginx
server {
    listen 443 ssl;
    server_name webhook.seu-dominio.example;
    ssl_certificate     /caminho/do/certificado/fullchain.pem;
    ssl_certificate_key /caminho/do/certificado/privkey.pem;

    # Não registrar query de verificação, cabeçalhos ou corpo.
    access_log off;
    error_log /dev/null crit;
    client_max_body_size 256k;

    location = /webhooks/whatsapp {
        if ($request_method !~ ^(GET|POST)$) { return 405; }
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Hub-Signature-256 $http_x_hub_signature_256;
        proxy_read_timeout 30s;
    }
    location / { return 404; }
}
```

Se usar túnel HTTPS existente, aponte-o para um listener local desse proxy restrito, não diretamente para 8081 ou 5173. No listener local troque as diretivas TLS por `listen 127.0.0.1:8082;`; mantenha exatamente as restrições de caminho/método. A terminação HTTPS fica no túnel. Verifique que o provedor não captura headers, corpo ou query de verificação. Não use modo debug de tráfego.

Após configurar o endereço público, verifique sem credenciais:

```sh
curl -i https://webhook.seu-dominio.example/api/auth/csrf  # esperado 404
curl -i https://webhook.seu-dominio.example/api/rh/units  # esperado 404
curl -i https://webhook.seu-dominio.example/actuator/health  # esperado 404
curl -i -X PUT https://webhook.seu-dominio.example/webhooks/whatsapp  # esperado 405
curl -i -X POST -H 'Content-Type: application/json' --data '{}' \
  https://webhook.seu-dominio.example/webhooks/whatsapp  # esperado 401, assinatura ausente
```

A verificação válida deve ser feita pelo console Meta com verify token configurado: GET precisa retornar o desafio. POST legítimo deve ter HMAC sobre bytes exatos; não altere corpo no proxy. Depois envie OI pelo número de teste e confirme protocolo e notificação no aparelho. Evite imprimir tokens em comandos, logs ou capturas. Testes automatizados verificam assinaturas válidas e inválidas localmente; HTTPS público e comunicação Meta permanecem pendentes.

## Verificação desta estabilização (14/10/2026)

- `./mvnw verify`: **75 testes**, zero falhas/erros/ignorados; JAR produzido.
- `npm test`: **15 testes**, zero falhas; TypeScript e build Vite aprovados.
- V1/V2 conferidas por SHA-256; nenhuma alteração. V3 testada em banco vazio e em atualização de V2 com conta/vínculo existentes.
- Teste HTTP real com PostgreSQL descartável: bootstrap RH, cadastro institucional/defensor, convite, ativação, login defensor, disponibilidade, webhook assinado completo, reserva, remarcação, cancelamento e nova reserva.
- Inspeção no navegador: login RH e defensor, dashboard, formulário sem senha, vínculos, agenda diária/semanal com reserva persistida e histórico, disponibilidade e tela de alteração de senha. Ativação e troca de senha são validadas pelos testes HTTP/automatizados; não foi executado cada formulário por interação visual.
- Comparecimento, realizado e ausência, concorrência, cancelamento e idempotência permanecem cobertos pela regressão. Não houve envio à Meta.
- Banco local existente não recebeu dados fictícios desta estabilização; os dados do teste de demonstração estavam em container descartável. A V3 será aplicada ao banco local na próxima inicialização desta versão.

Para outra porta: `SERVER_PORT=8081 ./mvnw spring-boot:run`; no frontend, `SCHEDULA_API_URL=http://localhost:8081 npm run dev`.

## Validar cadastro e edição de unidades

1. Inicie o backend na porta 8081 e o frontend 5173 com `SCHEDULA_API_URL=http://localhost:8081`; mantenha PostgreSQL na porta 5433.
2. Entre como RH e abra Unidades. O menu Municípios foi retirado. Selecione estado, pesquise município e confirme o nome oficial. A primeira consulta importa a UF; outras consultas reutilizam PostgreSQL. **Sincronizar municípios** faz atualização explícita.
3. Informe nome, Cível e/ou Criminal, CEP, número (inclusive S/N), telefone e e-mail opcional. CEP completo consulta ViaCEP; corrija manualmente quando necessário. Divergência de UF/município exige escolher entre usar o município oficial do CEP ou manter a seleção e revisar o endereço.
4. Salve e confira a listagem atualizada. Use **Editar** para mudar os mesmos campos sem trocar o identificador. Para unidades legadas, confira o endereço anterior exibido e complete os campos novos; não há inferência automática das áreas sem vínculos.
5. Tente vincular defensor a área não oferecida: a interface limita opções e o backend rejeita. Com vínculo/agenda existente, tente remover a área ou desativar: a mensagem orienta a regularização, sem cancelar nem excluir dados.
6. Em celular, confira formulário em coluna única, campos e botões. Em WhatsApp, uma área oferecida sem vagas deve orientar contato telefônico, não desaparecer do menu.

Testes `CatalogIntegrationTest`, `LocationClientsTest`, `UnitIntegrationTest` e `UnitPanel.test.tsx` usam respostas simuladas para serviços externos. `MigrationCompatibilityTest` também verifica upgrade V3→V4 com usuários, convites, vínculos, agendas, reserva e auditoria preservados, além da recusa segura de nomes legados duplicados. Nenhum teste automatizado depende da disponibilidade real do IBGE ou ViaCEP.

## Cadeia TLS do IBGE neste ambiente

Na validação de 14/10/2026, o endpoint IBGE enviou apenas o certificado final, sem o intermediário Sectigo. O JDK Temurin 21.0.12.1 local rejeita essa cadeia e bloqueia busca AIA por padrão. Quando esse mesmo diagnóstico se repetir, pode-se habilitar **somente** a busca do emissor anunciado pelo certificado:

```sh
cd ~/dev/personal/schedula
set -a
source .env
set +a
cd backend
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dcom.sun.security.enableAIAcaIssuers=true -Dcom.sun.security.allowedAIALocations=http://crt.sectigo.com/SectigoPublicServerAuthenticationCADVR36.crt" \
SERVER_PORT=8081 ./mvnw spring-boot:run
```

A validação de hostname, assinatura e raízes confiáveis continua ativa; nenhum certificado é aceito indiscriminadamente e nenhum truststore global é modificado. A URL AIA transporta um certificado público cuja assinatura é validada pelo JDK. Essa configuração é específica da cadeia observada e pode deixar de ser necessária quando o servidor corrigir a cadeia. Não usar `trust-all` ou desativar TLS. Em indisponibilidade, o catálogo completo já salvo continua funcionando.

Na correção E1-01 (15/10/2026), o JDK local sem essas opções reproduziu
`SSLHandshakeException / PKIX path building failed`, antes da resposta HTTP.
Com as opções acima, a consulta real a `/api/v1/localidades/estados/PE/municipios`
retornou HTTP 200; o próprio `IbgeClient` processou 185 municípios, incluindo
Recife (2611606). Não foi necessário alterar o cliente HTTP nem o truststore.

Se o backend já estiver em execução, encerre-o com Ctrl+C no terminal original e
reinicie com o comando acima: exportar opções em outro terminal não altera a JVM
existente. O frontend deve apontar para essa porta (`SCHEDULA_API_URL=http://localhost:8081`).
O comando apenas lê `.env`; não o modifica e não exibe suas credenciais.

Homologue CE → Morada Nova → PE → Recife → CE, sem salvar unidade. Em falha,
o formulário mostra um único aviso e oferece “Tentar novamente”, sem tentativas
automáticas em loop. Os campos preenchidos são mantidos. A consulta real ao IBGE
foi validada separadamente; o processo de homologação já aberto não foi reiniciado
por esta correção. Persistência, reutilização e falhas são verificadas em testes
isolados, sem depender da rede externa.

## Validação da gestão de unidades (14/10/2026)

- Backend: 107 testes aprovados; frontend: 28 aprovados. Sem falhas ou testes ignorados. Compilação Java, TypeScript e build Vite aprovados.
- V4 aplicada primeiro em PostgreSQL descartável, incluindo upgrades de V2/V3 e recusa transacional de duplicatas legadas. Depois foi aplicada ao banco de desenvolvimento pelo Flyway.
- Comparação de todos os campos dos registros anteriores em 14 tabelas: nenhum registro perdido ou campo anterior alterado. Única ampliação de dados: catálogo oficial do Ceará, com 184 municípios e código IBGE de Fortaleza. Nenhum usuário, unidade, vínculo, agenda ou reserva de demonstração foi inserido no banco de desenvolvimento.
- Fortaleza manteve UUID `49befb4c-c662-478c-be03-f7fbd164708a` e recebeu IBGE `2304400`. A unidade manteve UUID `f3c1ac05-1b83-4478-a3f1-1fc161ca177b`, município, nome, endereço, telefone e situação. Sem vínculos anteriores, suas áreas permanecem pendentes para configuração pelo RH.
- `.env` e V1/V2/V3 conferidos por hash e preservados. Antes/depois: uma unidade, um usuário, dois eventos de auditoria; nenhum vínculo, vaga ou agendamento no banco local.
- Navegador, em banco descartável: login RH, navegação sem Municípios, pesquisa e seleção real de Fortaleza/CE e Recife/PE, cadastro, edição, endereço automático ViaCEP, correção manual, máscaras fixa/celular, áreas, situação e atualização da listagem. Validação de ausência de áreas confirmada na interface. Layout conferido em desktop de 1280px e móvel de 390px, sem transbordamento horizontal.
- Divergência de CEP, confirmação explícita, falhas externas e descarte de respostas antigas também são cobertos nos testes de interface. O aceite do aviso divergente não foi concluído no teste visual; não se confunde essa cobertura automatizada com interação real.
- Consultas reais IBGE/ViaCEP aprovadas com a configuração TLS específica descrita acima; as 27 UFs locais foram comparadas ao endpoint oficial de estados. Os testes automatizados continuam usando mocks.


### Gestão de unidades — Etapa 1

As telas usam `/rh/unidades`, `/rh/unidades/nova` e `/rh/unidades/:id/editar`.
A History API mantém URLs, filtros e navegação voltar/avançar sem uma nova dependência;
as outras seções mantêm o menu existente. O servidor Vite já fornece o fallback da SPA.
Ao hospedar `frontend/dist`, configurar fallback para `index.html` nas rotas da interface,
preservando o encaminhamento de `/api` ao backend.

A lista usa `GET /api/rh/units/search` (`name`, `municipality`, `area`, `active`, `page`,
`size`; página inicial 1, limite 100). A resposta contém `items`, `total`, `page`, `size`
e `totalPages`. Filtros são combinados no banco, com ordenação por nome e UUID.
`GET /api/rh/units/{id}` carrega a edição. O antigo `GET /api/rh/units` mantém o array.
Nenhuma migration é necessária. O catálogo existente continua sendo carregado automaticamente;
o formulário reutiliza consultas por UF durante a edição, sem botão de sincronização.

Homologação: no menu Unidades, combine filtros, avance uma página, abra/cancele uma edição,
cadastre uma unidade e use “Localizar unidade salva”. Recarregue a URL de edição e verifique
município por teclado, telefone, mensagens e largura de celular.


### Gestão de defensores — Etapa 2A

Rotas: `/rh/defensores`, `/rh/defensores/novo`, `/rh/defensores/:id/editar`.
A listagem usa `GET /api/rh/defenders/search` com `name`, `status`, `page` (início 1)
e `size` (máximo 100). O GET antigo continua retornando array. GET por ID carrega
os dados e PUT por ID aceita **somente name**; campos extras são rejeitados,
inclusive username, password, password_hash, role e status. No cadastro, apenas
name e username são aceitos. Nenhuma migration ou alteração de credenciais existentes.

O modal guarda o convite somente em memória enquanto aberto, com validade de
24 horas. Se a emissão falhar após o cadastro, “Gerar convite” reutiliza a conta
salva. `/api/auth/activation/check` agora devolve somente nome e username após
validar o token, sem cache. Ativação mantém uso único e definição de senha pelo
próprio defensor (mínimo 12 caracteres Unicode, máximo 72 bytes UTF-8).

Homologue cadastro → modal/copiar/fechar → filtros/páginas → editar nome →
regenerar/revogar convite pendente. Em conta de teste, abra um convite válido,
confira identificação, limites/confirmação/visibilidade da senha e confirme que
“Ir para o login” aparece só após sucesso. Não use contas existentes para esses testes.

Refinamento de homologação: a Visão geral usa `GET /api/rh/defenders/indicators`
(`{total}`), protegido para RH, sem carregar a coleção de defensores. Revogação
e substituição de convite válido exigem confirmação na interface; o backend
recusa revogação para contas ativas. A busca ignora acentos sem alterar nomes.
Convites indisponíveis e ativação concluída possuem estados visuais próprios;
falhas transitórias na verificação permitem tentar novamente.
