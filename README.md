# Schedula

MVP de agendamento para Defensorias Públicas: **RH → defensor → agenda → WhatsApp → atendimento**.

Monólito modular com Java 21, Spring Boot 4.1.1, Security, Data JPA/JDBC, PostgreSQL 17, Flyway e React/TypeScript. Os painéis consultam APIs reais; conversas, reservas, auditoria e notificações são persistidas.

## Estrutura

- `backend/`: identity, institution, scheduling, appointment, notification, whatsapp e shared.
- `frontend/`: painéis responsivos de RH e defensor.
- `docs/business-rules.md`: regras, transições e identificação do cidadão.
- `docs/architecture.md`: concorrência, segurança, integração e limites.
- `docs/local-development.md`: execução, configuração Meta e demonstração.
- `compose.yaml`: PostgreSQL 17, porta local 5433, volume preservado.

## Executar localmente

Pré-requisitos: Java 21, Docker em execução, Node.js 22.12+ ou 24 e npm. Maven Wrapper incluído.

No repositório, em zsh, informe a senha do **banco já existente**, sem registrá-la no histórico:

```sh
read -s 'DB_PASSWORD?Senha do PostgreSQL: '; export DB_PASSWORD
export SCHEDULA_ADMIN_USERNAME=rh
read -s 'SCHEDULA_ADMIN_PASSWORD?Senha inicial do RH (12+ caracteres): '; export SCHEDULA_ADMIN_PASSWORD
docker compose up -d postgres
cd backend
SERVER_PORT=8081 ./mvnw spring-boot:run
```

Se o volume já existe, alterar a variável não troca a senha do PostgreSQL. Use o valor atualmente configurado. Não remova o volume. O bootstrap cria o RH somente se o usuário não existir, sem redefinir senhas. Retire a senha de bootstrap do ambiente após criar a conta.

Em outro terminal:

```sh
cd /Users/cristhiangermanorodrigues/dev/personal/schedula/frontend
npm ci
SCHEDULA_API_URL=http://localhost:8081 npm run dev
```

Acesse http://localhost:5173. Backend: http://localhost:8081. Saúde: `/actuator/health`. Contrato OpenAPI autenticado: `/api/openapi`. O proxy do Vite mantém sessão e CSRF na mesma origem.

## Testes e build

```sh
cd backend
./mvnw verify
cd ../frontend
npm ci
npm test
npm run build
```

Os testes backend usam um PostgreSQL 17 descartável via Testcontainers e não truncam o banco local. Exigem Docker e acesso inicial às imagens. JUnit Jupiter e Mockito seguem as versões gerenciadas pelo Spring Boot 4.1.1; esse BOM fornece Jupiter 6, sucessor compatível da API Jupiter solicitada, sem forçar downgrade do framework.

## Demonstrar o fluxo

1. Entre em http://localhost:5173 com o usuário e a senha definidos em `SCHEDULA_ADMIN_USERNAME` / `SCHEDULA_ADMIN_PASSWORD`. O bootstrap não altera contas existentes.
2. No RH, abra **Unidades**, selecione UF e município oficial, marque as áreas e preencha localização e contato. Não é necessário cadastrar município antes. Use dados fictícios em demonstrações. Em Defensores, informe somente nome e usuário; a conta nasce PENDING_ACTIVATION.
3. O cadastro gera o convite. Copie o link e compartilhe por canal privado. Se necessário, use Gerar novo convite (revoga o anterior) ou Revogar convite. O token só é exibido na geração e vale por 24 horas.
4. O defensor abre o convite no endereço privado do painel, informa e confirma sua senha (mínimo 12 caracteres, máximo 72 bytes UTF-8). Após ativar, realiza login; contas antigas permanecem ativas.
5. O RH vincula o defensor à unidade/área. O defensor publica vagas futuras, avulsas ou semanais.
6. Configure a conta Meta e o proxy HTTPS **somente para o webhook**, conforme `docs/local-development.md`. O endereço público do webhook não serve o painel nem os convites.
7. Pelo WhatsApp, envie `OI`, escolha município, unidade, área e vaga; informe nome, sobrenome e `SIM`. `MAIS` busca a próxima página no banco; a confirmação revalida a vaga.
8. Confira protocolo e atendimento no painel. Detalhes permite cancelamento, remarcação, presença e desfecho. Os cinco indicadores usam a data atual em America/Fortaleza.
9. RH e defensor podem usar Alterar minha senha, informando a senha atual e confirmando a nova; a sessão atual é encerrada.
10. `RETOMAR` reapresenta a etapa; `MENU` reinicia; `CANCELAR <protocolo>` cancela até o dia anterior; `URGENTE` orienta o contato regional.

Sem credenciais Meta, o fluxo completo do receptor assinado é exercitado por `HttpAndWhatsappTest`; o envio fica desabilitado e a fila permanece persistida. **Isso não equivale à validação externa do WhatsApp.** Nenhuma mensagem real é enviada pelos testes.

## Estabilização e validação

V3 acrescenta estado da conta e convites. V4 evolui unidades e catálogo IBGE; V1/V2/V3 foram preservadas. `./mvnw verify`: **107 testes aprovados**; `npm test`: **28 aprovados**; sem falhas ou testes ignorados. Build Java/TypeScript/Vite aprovado. Teste HTTP real com PostgreSQL descartável percorreu cadastro, convite, ativação, reserva por webhook assinado, remarcação e cancelamento. Painéis RH/defensor inspecionados no navegador. Nenhum envio externo Meta foi realizado.

Login: dez tentativas por endereço em cinco minutos por padrão, incluindo tentativas bem-sucedidas, com HTTP 429 e Retry-After. Variáveis e limites de instância única estão em `docs/local-development.md`.

## Gestão de unidades

Cadastro e edição usam o mesmo formulário de identificação, localização e contato. O catálogo de municípios do [IBGE](https://servicodados.ibge.gov.br/api/docs/localidades) é salvo no PostgreSQL por UF; **Sincronizar municípios** permite atualização explícita. As 27 UFs oficiais são mantidas localmente, sem dependência de rede para exibi-las. A primeira consulta de uma UF sem catálogo completo requer acesso ao IBGE. Registros anteriores são reconciliados por nome normalizado e UF, mantendo UUIDs; ambiguidades bloqueiam a sincronização.

O [ViaCEP](https://viacep.com.br/) auxilia o preenchimento ao completar oito dígitos. Falhas permitem preencher manualmente. Divergências de município exigem confirmação e validação no catálogo oficial; campos já digitados são preservados. Telefone aceita fixo e celular brasileiros, com máscara e validação de DDD; e-mail é opcional.

Áreas Cível/Criminal pertencem à unidade independentemente de defensores e vagas. Na edição, remover uma área exige regularizar vínculos e agendas; desativar exige não haver horários vigentes/futuros nem reservas confirmadas. A unidade existente sem vínculos inicia sem áreas e deve ser regularizada pelo RH. Seu endereço antigo permanece disponível para consulta, sem conversão presumida.

## Limites atuais

A comunicação real com a Meta depende de credenciais, webhook HTTPS e template aprovado. Não foi validada externamente. A identificação por telefone + nome normalizado é demonstrativa, sem comprovação de identidade civil. Não há recuperação de senha por e-mail nem implantação HTTPS inclusa. Veja os limites operacionais e decisões nos três documentos de `docs/`.
