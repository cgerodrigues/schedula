# Regras do MVP

## Instituição e permissões (RN01–RN05)

Unidade pertence a município; RH cadastra unidades, defensores e vínculos por unidade/área. Áreas institucionais possíveis: CIVEL e CRIMINAL; cada unidade define uma ou ambas, independentemente de defensores e vagas. Um defensor pode ter vários vínculos. Apenas RH gerencia vínculos. Remoção é permitida somente sem histórico de vagas; registros usados são preservados. Endereço e telefone são obrigatórios nos novos cadastros. Contas RH são criadas pelo bootstrap configurado no ambiente.

## Disponibilidade (RN06–RN16)

Defensor publica exclusivamente para seus vínculos em unidade ativa que ofereça a área, em horário America/Fortaleza. Publicação avulsa ou semanal com data final explícita, materializando vagas de duração uniforme. Não existe janela máxima fixa para reservar: qualquer vaga publicada futura pode ser reservada. Para limitar o custo de uma requisição, cada publicação aceita até 520 semanas; publicações posteriores podem estender o calendário sem limite global.

Intervalos semiabertos `[início,fim)` permitem horários adjacentes. Sobreposição é impedida inclusive entre unidades/áreas. Bloqueio retira publicação de vagas livres, nunca apaga reservas. Se atingir reserva confirmada, toda a operação é recusada. Para substituir uma recorrência, o defensor precisa tratar explicitamente suas reservas, retirar a série e publicar outra. Histórico permanece.

## Identificação e confirmação (RN17–RN26)

Telefone autenticado pelo evento Meta + nome completo normalizado (minúsculas, sem acentos, espaços compactados) identificam o cadastro demonstrativo. Pessoas de nomes diferentes podem usar o mesmo número. Homônimos no mesmo telefone podem ser confundidos; grafias diferentes ou outro número podem contornar a identificação. Não há CPF, biometria, validação civil ou alegação de identidade comprovada. O protocolo completo mais o telefone da conversa autorizam o cancelamento pelo cidadão, com a limitação inerente a aparelhos compartilhados.

Uma reserva CONFIRMADO por vaga e por cidadão/área. Cível e Criminal podem coexistir. A seleção não segura a vaga: disponibilidade é revalidada transacionalmente ao responder SIM. Protocolo UUID único. Mensagens duplicadas são identificadas pelo ID Meta, inclusive sob concorrência, e não repetem reserva ou resposta. Um segundo SIM com outro ID, após sucesso, não cria nova reserva.

## Cancelar, remarcar e comparecer (RN27–RN43)

- Cidadão cancela somente antes do dia local do atendimento; no dia, recebe orientação de impedimento.
- Defensor pode cancelar ou remarcar um CONFIRMADO sem antecedência mínima.
- Cancelamento reabre a vaga se início for pelo menos duas horas à frente (limite inclusivo). Menos de duas horas impede também republicação sobre o intervalo cancelado.
- Remarcação exige outra vaga futura disponível do mesmo defensor e mesma área. O original fica REMARCADO e o novo CONFIRMADO aponta para `previous_id`. Falha na nova reserva desfaz a operação inteira.
- Cancelamento pelo defensor e remarcação enfileiram notificação proativa na mesma transação.
- Comparecimento é `attended_at`, mantendo CONFIRMADO até o desfecho. Só pode ser registrado a partir do início. Presença já registrada impede NAO_COMPARECEU.
- REALIZADO e NAO_COMPARECEU são desfechos a partir do início. Nenhuma penalidade é aplicada ao cidadão ausente.

Transições permitidas: CONFIRMADO → REALIZADO, NAO_COMPARECEU, CANCELADO_CIDADAO, CANCELADO_DEFENSOR ou REMARCADO. Os demais estados são terminais e não contam como ativos. Cancelamentos, remarcações, presença e conclusões mantêm auditoria. Não há exclusão de atendimentos.

## Exceções e retomada (RN44–RN50)

Sem vagas, informar telefone da unidade. `URGENTE` orienta procurar a Defensoria da região, sem encaixe automático. Conversa persiste por telefone; `RETOMAR` recupera a etapa após interrupção ou reinício do servidor. `MENU` reinicia para outra pessoa/área. Duas pessoas compartilhando um telefone devem concluir uma conversa por vez. Vagas têm paginação de dez opções com MAIS. Dados de testes são fictícios.

## Edição institucional e compatibilidade

- RH cadastra e edita unidades em uma tela; UF e município oficial devem ser compatíveis. Nomes são aparados e espaços compactados; nomes equivalentes no mesmo município são recusados, inclusive em concorrência.
- Vínculos só podem usar áreas oferecidas pela unidade. Adicionar área não cria vínculo ou vaga. Remover área é recusado com vínculos existentes, horários publicados vigentes/futuros ou reservas confirmadas. Não há exclusão automática dessas dependências.
- Desativação impede novas vagas e reservas; é recusada enquanto houver horários publicados com fim futuro ou qualquer reserva ainda confirmada, inclusive pendência antiga sem desfecho. Histórico concluído permanece.
- Mudança de município só é permitida quando não há vínculos. Vínculos com histórico não podem ser apagados; mudanças estruturais dessas unidades exigem decisão institucional fora deste fluxo.
- O WhatsApp lista municípios com unidades ativas e áreas da própria unidade. Área sem defensor ou sem vagas permanece selecionável, seguida da orientação de contato.
- Unidades legadas mantêm UUID, endereço e telefone. Campos novos desconhecidos não são inventados. RH completa CEP/endereço/áreas ao regularizar a unidade; telefone legado intacto pode ser mantido, mas sua substituição exige formato válido.
