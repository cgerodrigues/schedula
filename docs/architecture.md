# Arquitetura

## Monólito modular

Uma aplicação Spring Boot 4.1.1, Java 21. `identity` autentica contas com Spring Data JPA e BCrypt; `institution` gerencia a rede; `scheduling` publica/bloqueia vagas; `appointment` contém transições; `whatsapp` traduz mensagens; `notification` envia pela Meta; `shared` concentra relógio, acesso SQL, erros e OpenAPI. Controllers delegam regras aos serviços. APIs não expõem entidades JPA nem hashes de senha.

JPA é utilizado para contas/autenticação. JDBC parametrizado é deliberadamente utilizado nas operações de agenda para tornar visíveis os locks, índices parciais e intervalos PostgreSQL. Ambos participam do mesmo gerenciador transacional do Spring. Flyway é a fonte do esquema; `ddl-auto=validate`, nunca create/update. V1/V2/V3 permanecem intactas; V3 adiciona estado da conta e convites, mantendo contas preexistentes ACTIVE. Requer `btree_gist`, disponível na imagem PostgreSQL oficial; o usuário que executa a migration precisa poder instalar a extensão.

## Integridade e concorrência

A exclusão GiST de intervalos publicados impede sobreposição por defensor. Índices únicos parciais garantem uma reserva ativa por vaga e cidadão/área. Vínculos possuem FKs e unicidade. Publicação, bloqueio e mudanças de agenda serializam por advisory transaction lock do defensor. Reservas serializam também a identidade do cidadão e bloqueiam a vaga com SELECT FOR UPDATE. Locks são liberados no commit/rollback. Índices são a última barreira, não há confiança em validações do navegador.

Erros de domínio previsíveis na conversa são respondidos e deduplicados. Os serviços de reserva/cancelamento do cidadão não marcam rollback nesses erros conhecidos; as validações acontecem antes da mudança de reserva. Cadastro de cidadão sem reserva pode permanecer após tentativa sem vaga. Erros de infraestrutura/constraints fazem rollback e permitem reenvio da Meta. Remarcação é totalmente atômica.

## Segurança

Sessão HttpOnly, SameSite=Strict, expiração de 30 minutos, CSRF inclusive no login e logout. Frontend obtém token novo em `/api/auth/csrf` antes das mutações. Somente webhook assinado dispensa CSRF. Endpoints RH/defensor exigem papel; operações individuais revalidam titularidade no serviço. Senhas BCrypt, bootstrap por ambiente, nenhuma credencial real no repositório. PostgreSQL Compose exposto apenas no loopback. Em HTTPS, habilitar `SESSION_COOKIE_SECURE=true`.

Erros de entrada, conflitos e recursos ausentes usam ProblemDetail sem SQL/stacktrace. Auditoria registra ator, ação, recurso e data sem corpo de mensagem, senha ou telefone. Outbox/conversa contêm dados pessoais necessários ao funcionamento e requerem controle de acesso ao banco. Políticas institucionais de retenção, backups e limitação de tráfego no proxy precisam ser definidas antes de produção. Login não inclui MFA, reset de senha ou rate limiter distribuído.

## Ativação e alteração de senha

RH cadastra nome/username, sem senha pessoal. Novos defensores são PENDING_ACTIVATION e o UserDetails do Spring desabilita seu login. A coluna de senha recebe um BCrypt de valor aleatório inacessível; o estado da conta impede autenticação até a ativação. Contas anteriores à V3 mantêm senha, papel e vínculos e recebem ACTIVE.

Convite: 32 bytes de SecureRandom, codificados em Base64 URL-safe. Apenas SHA-256 fica no banco. Prazo estrito de 24 horas, uso único, auditoria e revogação. Emissão, revogação e consumo serializam na linha da conta; emitir novo convite revoga o anterior. Conta ativa não recebe convite de ativação. O token identifica exclusivamente sua conta; o endpoint público não recebe accountId. Alteração de senha exige senha atual válida. Validação mínima: 12 caracteres Unicode, máximo 72 bytes UTF-8 para BCrypt; confirmação também é verificada pelo backend.

Frontend transporta token em `#activate=...`, remove o fragmento da barra e o mantém apenas em memória; validação/consumo são POST com CSRF. Não há tokens em query string, localStorage, logs ou respostas de listagem. Se recarregar depois que o fragmento foi removido, reabra o link original. Compartilhamento manual privado; sem e-mail ou recuperação de senha. A ativação não autentica automaticamente e encerra eventual sessão prévia do navegador. Troca de senha encerra a sessão atual após commit; outras sessões existentes não são revogadas globalmente neste MVP.

## Limite de login

Janela deslizante sincronizada em memória por `request.getRemoteAddr()`, independente do username. Limites configuráveis de tentativas, tempo e quantidade de endereços; mapa cheio rejeita novas origens até expirar entradas. Conta pendente, usuário inexistente e senha errada retornam o mesmo 401 genérico. Após limite, 429 e Retry-After. Headers encaminhados não são confiados (`server.forward-headers-strategy=none`); por trás do proxy privado, clientes podem compartilhar o limite do endereço do proxy. Reinício limpa limites; múltiplas instâncias exigiriam controle compartilhado. CSRF continua ativo.

## WhatsApp oficial

GET verifica `hub.mode`, token e desafio. POST valida HMAC SHA-256 sobre os bytes originais com comparação constante, filtra `phone_number_id` e processa eventos assinados. `whatsapp_inbox` deduplica IDs; conversa, reserva e resposta outbox são transacionais. Sem credenciais, o receptor não aceita assinaturas inválidas e o worker fica desativado. Testes usam segredo exclusivamente fictício.

Adaptador REST aponta exclusivamente para `https://graph.facebook.com/{versão}/{phone-number-id}/messages`. A versão Graph é configuração explícita. Respostas imediatas usam texto; cancelamentos/remarcações sempre usam template aprovado com uma variável no corpo. Mensagens envelhecidas na fila por mais de 23 horas também usam template. O template deve ser aprovado para esse uso real; configurar seu nome não garante aprovação.

MetaClient valida corpo, JSON, array messages não vazio e primeiro ID textual não vazio. Erros não carregam corpo remoto, credencial ou causa sensível. 401/403, outros 4xx permanentes e configuração ausente encerram como FAILED; 408, 429, 5xx, rede e resposta inválida podem ser reenviados.

Worker busca uma mensagem com FOR UPDATE SKIP LOCKED. Somente a etapa de envio captura RuntimeException: falhas inesperadas controláveis também contam tentativa; falhas de persistência continuam fazendo rollback. Backoff `min(3600, 5 × 2^tentativa)` segundos (10 na primeira), até oito tentativas. Falha permanente encerra na primeira; falha transitória encerra na oitava. Auditoria e log guardam somente ID interno, código técnico e tentativa, nunca mensagem de exceção/payload. Reenvio pelo RH zera tentativas e volta a PENDING, preservando auditoria anterior. Falha de notificação não desfaz reserva já confirmada. IDs Meta e status de entrega são persistidos. Status SENT significa aceito pela API, não entregue ao aparelho. Entrega externa é **ao menos uma tentativa**, não exatamente uma vez: perda de resposta após aceite remoto pode duplicar uma notificação, nunca a reserva. A chamada HTTP ocorre em uma transação curta limitada por timeout; escala de alto volume exigirá evolução do worker. Nenhuma chamada paga ou real foi feita nesta implementação.

## Frontend e contrato

React/TypeScript, estado local, formulários com bloqueio de envio duplicado, mensagens de erro e dados consultados do backend. Vite proxy em desenvolvimento. Em implantação, servir build e `/api` na mesma origem HTTPS. Agenda filtra dia/semana no fuso institucional; todos os indicadores são explicitamente de hoje em America/Fortaleza. Previstos = CONFIRMADO + REALIZADO + NAO_COMPARECEU. Cancelados aparecem separadamente; REMARCADO é excluído, evitando duplicar original e novo atendimento. Contrato OpenAPI 3.1 em `backend/src/main/resources/openapi.json`, autenticado via `/api/openapi`.

## Paginação de vagas

SQL ordena `(starts_at,id)` e usa cursor exclusivo com `LIMIT 11`: dez opções e uma linha para detectar próxima página. A conversa persiste início da página e cursor seguinte; MAIS avança e RETOMAR recarrega a página atual. Na última página, MAIS informa o fim e mantém opções selecionáveis. Reservas de linhas anteriores não deslocam o cursor. Novas vagas inseridas antes do cursor serão vistas em nova consulta via MENU; não há snapshot global da agenda. Filtros e revalidação transacional continuam iguais, sem antecedência máxima.

## Limites assumidos

Recorrências finitas explícitas, sem daemon de geração infinita. Listagens administrativas/agenda ainda não paginadas; adequadas à demonstração e precisam de paginação para grandes volumes. Sem identidade civil forte, encaminhamento automático de urgência, autosserviço de recuperação de senha ou infraestrutura de produção. O teste externo Meta ainda é obrigatório para afirmar integração real validada. JUnit Jupiter 6 acompanha o BOM existente do Boot 4.1.1; os testes usam APIs Jupiter e Mockito sem pin incompatível.

## Catálogo oficial e unidades (V4)

`municipality.ibge_code` é identificador externo único e opcional para registros legados; o UUID interno não muda. `municipality_catalog` registra data e quantidade apenas ao concluir a importação atômica de toda a UF. A existência de municípios individuais nunca marca catálogo completo. Sincronizações são idempotentes e serializadas por UF; reconciliação compara nome sem acentos, caixa e espaços excedentes. Correspondências ambíguas ou códigos conflitantes abortam a transação inteira, sem sobrescrever nomes nem apagar municípios antigos. Em falha externa, municípios oficiais já armazenados continuam disponíveis com aviso; sem nenhum, o usuário recebe erro claro. GET reutiliza o catálogo completo e POST permite ressincronizar.

`IbgeClient` usa conexão de 4s e requisição de 12s, aceita JSON e gzip do endpoint oficial. `PostalClient` usa conexão de 3s e requisição de 6s para ViaCEP. Não há chamadas na inicialização. Os clientes são específicos e testáveis com HttpClient simulado. UFs oficiais ficam em lista estável local; municípios vêm exclusivamente do endpoint oficial na operação normal do formulário. O endpoint antigo de cadastro manual permanece obsoleto por compatibilidade e não produz registros oficiais ou catálogo completo.

`unit_area(unit_id,area)` reutiliza os valores CIVEL/CRIMINAL do domínio e tem chave composta. V4 preenche somente áreas comprovadas pelos vínculos existentes; unidades sem vínculos ficam pendentes de regularização. Endereço estruturado, CEP e e-mail são adicionados como campos opcionais no armazenamento legado. O campo `address` original é preservado; listagem e WhatsApp preferem os campos estruturados quando presentes. O contrato de criação/edição exige endereço estruturado e pelo menos uma área. Telefone legado não alterado é preservado; novos valores são normalizados para dígitos e validados por DDD e formato fixo/celular.

Publicação e reserva adquirem lock da unidade depois do lock do defensor, além dos locks já existentes. Edição e criação de vínculo usam o mesmo lock da unidade. A edição não adquire locks de defensores, evitando inversão da ordem. Isso serializa remoção de área/desativação com novas publicações, reservas e vínculos. Índice único por município/nome normalizado protege cadastros simultâneos. Se houver duplicatas legadas que impeçam criar o índice, a migration falha transacionalmente sem apagar registros: regularização explícita é necessária antes de reaplicar.

`UnitForm` é compartilhado entre criação e edição, usa catálogo pesquisável por UF e preserva dados após falhas. Sequências de requisição invalidam resultados antigos de CEP/catálogo; campos preenchidos pelo usuário não são substituídos. Alteração de localização por CEP exige confirmação e correspondência pelo código IBGE. A gravação mantém o UUID e não altera vínculos, agendas ou histórico implicitamente.
