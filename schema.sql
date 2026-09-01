-- ######################################################################
--
--   VITTA — banco de dados no Supabase
--   Roteiro guiado para quem nunca usou Supabase
--
--   ------------------------------------------------------------------
--   LEIA ISTO PRIMEIRO (30 segundos)
--   ------------------------------------------------------------------
--   Tudo o que começa com dois traços (--) é comentário: o banco ignora.
--   São as instruções para você. O resto é o código que cria o banco.
--
--   COMO USAR, do jeito mais simples:
--     1. Entre no seu projeto em  https://supabase.com/dashboard
--     2. No menu da esquerda, clique em  SQL Editor
--     3. Clique em  + New query   (canto superior)
--     4. Selecione TODO este arquivo (Ctrl+A / Cmd+A), copie e cole lá
--     5. Clique em  Run   (ou Ctrl+Enter / Cmd+Enter)
--     6. Espere a mensagem  Success  aparecer embaixo
--
--   Prefere ir devagar, entendendo cada pedaço? Pode. O arquivo está
--   dividido em 14 PARTES. Cole e rode uma parte de cada vez, na ordem.
--   Cada uma diz o que faz e o que você deve ver depois.
--
--   PODE RODAR QUANTAS VEZES QUISER. Nada é apagado: todo comando é
--   "crie se ainda não existir". Se você já rodou uma versão antiga
--   deste arquivo, rodar de novo só acrescenta o que faltava.
--
--   NÃO PRECISA MUDAR NADA AQUI DENTRO. Sem nome de usuário, sem senha,
--   sem endereço. É copiar e colar.
--
-- ######################################################################


-- ######################################################################
--   PARTE 1 de 14 — PERFIL
--   Guarda quem você é: peso, altura, idade, nível de atividade e as
--   metas calculadas. É uma linha só, a sua.
--
--   Traduzindo o código abaixo:
--     create table if not exists  → "crie a tabela, se ela ainda não existir"
--     public.perfil               → nome da tabela
--     user_id uuid primary key    → o dono da linha; é a chave que
--                                   identifica cada usuário no Supabase
--     default auth.uid()          → preenche sozinho com quem está logado
--     jsonb                       → um campo livre, que guarda vários
--                                   valores juntos (peso, altura, metas...)
--     timestamptz                 → data e hora com fuso horário
--
--   Depois de rodar você deve ver:  Success. No rows returned
-- ######################################################################

create table if not exists public.perfil (
  user_id uuid primary key default auth.uid() references auth.users(id) on delete cascade,
  dados   jsonb       not null default '{}'::jsonb,
  upd     timestamptz not null default now()
);


-- ######################################################################
--   PARTE 2 de 14 — FICHAS DE TREINO   (módulo Exercícios)
--   Uma linha por ficha montada no app: "Treino A — Peito e tríceps",
--   com a cor, os dias da semana e a lista de exercícios.
--
--   A coluna "exercicios" é jsonb porque cada ficha tem um número
--   diferente de exercícios. Dentro dela fica algo assim:
--     [{"nome":"Supino reto","series":4,"reps":10,"carga":50}, ...]
--
--   Repare no fim: primary key (user_id, id). A chave é o PAR.
--   O id sozinho não pode ser a chave, senão dois usuários nunca
--   poderiam ter um item com o mesmo identificador — e o app usa
--   identificadores previsíveis para os itens que já vêm prontos.
--
--   O "create index" no fim é o que deixa a busca rápida quando você
--   tiver centenas de linhas. Não muda nada no uso.
-- ######################################################################

create table if not exists public.fichas (
  id         text not null,
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  nome       text,
  cor        text,
  dias       jsonb not null default '[]'::jsonb,   -- [1,4] = segunda e quinta
  exercicios jsonb not null default '[]'::jsonb,
  upd        timestamptz not null default now(),
  primary key (user_id, id)
);
create index if not exists fichas_user_idx on public.fichas(user_id);


-- ######################################################################
--   PARTE 3 de 14 — ALIMENTOS   (biblioteca do módulo Dieta)
--   Cada linha é um alimento com os valores de UMA porção de referência.
--   Ex.: "Peito de frango grelhado", porção 100, unidade g, 165 kcal.
--   O app faz a regra de três quando você lança 200 g.
--
--   c = carboidrato, p = proteína, g = gordura (em gramas)
--   liq = quanto daquilo conta como líquido, em ml (para sucos e refris)
-- ######################################################################

create table if not exists public.alimentos (
  id      text not null,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  nome    text,
  porcao  numeric,
  un      text,               -- g | ml | un
  kcal    numeric,
  c       numeric,
  p       numeric,
  g       numeric,
  liq     numeric default 0,
  upd     timestamptz not null default now(),
  primary key (user_id, id)
);
create index if not exists alimentos_user_idx on public.alimentos(user_id);


-- ######################################################################
--   PARTE 4 de 14 — PRATOS   (combinações de alimentos e variações)
--   "Arroz, feijão, frango e salada" é um prato: uma lista de alimentos
--   com as quantidades. No app você lança o prato inteiro num toque.
--
--   variacao_de guarda o id do prato original quando você duplica um
--   prato para criar uma variação. Serve para você saber de onde veio.
-- ######################################################################

create table if not exists public.pratos (
  id          text not null,
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  nome        text,
  refeicao    text,                                 -- cafe | almoco | janta | ...
  itens       jsonb not null default '[]'::jsonb,   -- [{alimentoId, qtd}]
  variacao_de text,
  upd         timestamptz not null default now(),
  primary key (user_id, id)
);
create index if not exists pratos_user_idx on public.pratos(user_id);


-- ######################################################################
--   PARTE 5 de 14 — HÁBITOS
--   Um hábito que você acompanha por um período (ou para sempre).
--
--   A coluna "motivos" é o porquê que você escreve na criação — o app
--   mostra ele toda vez que você abre o hábito.
--   A coluna "encerrado" recebe, no fim, se o objetivo foi alcançado:
--     {"alcancado": true, "nota": "...", "data": "2026-12-01"}
-- ######################################################################

create table if not exists public.habitos (
  id        text not null,
  user_id   uuid not null default auth.uid() references auth.users(id) on delete cascade,
  nome      text,
  icone     text,
  motivos   text,
  inicio    date,
  fim       date,          -- vazio = para sempre
  freq_tipo text,          -- diario | dias | semana | intervalo | mes | ano
  dias      jsonb not null default '[]'::jsonb,
  vezes     int,
  medida    text,          -- check | escala | numero
  unidade   text,
  meta      numeric,
  hora      text,           -- 'HH:MM' do primeiro aviso (compatibilidade)
  horas     jsonb not null default '[]'::jsonb,  -- todos os horários de aviso
  cada      int,            -- freq_tipo 'intervalo': a cada N dias
  dia_mes   int,            -- freq_tipo 'mes' e 'ano': dia do mês
  mes_ano   int,            -- freq_tipo 'ano': mês (0 = janeiro)
  ativo     boolean default true,
  encerrado jsonb,
  upd       timestamptz not null default now(),
  primary key (user_id, id)
);
create index if not exists habitos_user_idx on public.habitos(user_id);


-- ######################################################################
--   PARTE 6 de 14 — LEMBRETES
--   O que você não quer esquecer, com hora e repetição.
--
--   repetir aceita:
--     'uma'    → só no dia guardado em "data"
--     'diario' → todo dia, a partir de "data"
--     'dias'       → nos dias da semana listados em "dias"
--     'intervalo'  → a cada N dias, contando de "data"; N fica em "cada"
--     'mes'        → todo mês no dia guardado em "dia_mes"
--     'ano'        → todo ano em "dia_mes" do mês "mes_ano" (0 = janeiro)
--   feito guarda os dias em que você deu o check:
--     {"2026-08-25": "2026-08-25T10:02:00Z"}
-- ######################################################################

create table if not exists public.lembretes (
  id      text not null,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  texto   text,
  hora    text,           -- 'HH:MM'
  data    date,
  fim     date,
  repetir text,
  dias    jsonb not null default '[]'::jsonb,
  cada    int,            -- repetir 'intervalo': a cada N dias
  dia_mes int,            -- repetir 'mes' e 'ano': dia do mês
  mes_ano int,            -- repetir 'ano': mês (0 = janeiro)
  antecedencia jsonb not null default '[]'::jsonb,  -- [5,15] = avisa também 5 e 15 min antes da hora
  ativo   boolean default true,
  feito   jsonb not null default '{}'::jsonb,
  criado  date,
  upd     timestamptz not null default now(),
  primary key (user_id, id)
);
create index if not exists lembretes_user_idx on public.lembretes(user_id);


-- ######################################################################
--   PARTE 7 de 14 — ATIVIDADES   (esportes e rotinas que se repetem)
--   Aqui ficam as atividades que você cadastra: alongamento, futebol,
--   pilates, o que for. Cada uma guarda o esforço, a duração habitual
--   e os dias da semana em que ela acontece — é isso que faz o app
--   sugerir a atividade sozinho no dia certo.
--
--   met  = quanto de esforço a atividade exige (3 leve … 11 intenso).
--          É o número que, junto com o seu peso, estima as calorias.
--   dias = [2,5] significa terça e sexta.
-- ######################################################################

create table if not exists public.atividades (
  id      text not null,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  nome    text,
  met     numeric,
  min     int,
  intens  numeric default 1,      -- intensidade habitual (0.8 leve, 1 normal, 1.25 forte)
  dias    jsonb not null default '[]'::jsonb,
  upd     timestamptz not null default now(),
  primary key (user_id, id)
);
create index if not exists atividades_user_idx on public.atividades(user_id);


-- ######################################################################
--   PARTE 8 de 14 — MEDICAMENTOS   (remédios, vitaminas e suplementos)
--   Uma linha por coisa que você toma. Guarda o nome, a dose, a unidade
--   (comprimido, gota, ml...), com que frequência e em que horários.
--
--   freq  = 'diario' (todo dia), 'dias' (dias fixos da semana) ou
--           'necessario' (sem rotina; você registra quando toma).
--   dias  = [1,3,5] significa segunda, quarta e sexta.
--   horarios = ["08:00","20:00"] — é a hora em que o app te avisa.
--   inicio/fim = o período do tratamento. Com "fim" preenchido, o
--           medicamento some sozinho da lista quando a data passa.
--
--   As doses efetivamente tomadas NÃO ficam aqui: ficam na tabela
--   "dias", na coluna "meds", junto com o resto do seu dia.
-- ######################################################################

create table if not exists public.medicamentos (
  id       text not null,
  user_id  uuid not null default auth.uid() references auth.users(id) on delete cascade,
  nome     text,
  tipo     text,                                  -- remedio, vitamina, suplemento, outro
  dose     numeric default 1,
  unidade  text,
  freq     text default 'diario',
  dias     jsonb not null default '[]'::jsonb,
  horarios jsonb not null default '[]'::jsonb,
  inicio   date,
  fim      date,
  obs      text,
  motivo   text,                                 -- para que serve; o objetivo que ele ajuda
  ativo    boolean not null default true,
  upd      timestamptz not null default now(),
  primary key (user_id, id)
);
create index if not exists medicamentos_user_idx on public.medicamentos(user_id);


-- ######################################################################
--   PARTE 9 de 14 — MEU CALENDÁRIO   (feriados, aniversários e datas)
--   As datas que você marca para aparecerem no bom-dia e no calendário
--   do resumo mensal.
--
--   Os feriados nacionais NÃO ficam aqui: o app calcula, inclusive os
--   que andam com a Páscoa. Aqui entram as suas — aniversário de quem
--   importa, feriado da sua cidade, "dia da terra".
--
--   anual = true  → repete todo ano; a data está em "dia" e "mes"
--   anual = false → acontece uma vez só; a data está em "data"
--   ano           → só em aniversário: o ano de nascimento, para o app
--                   poder mostrar a idade
-- ######################################################################

create table if not exists public.datas (
  id      text not null,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  nome    text,
  tipo    text,           -- feriado | aniversario | comemorativa | pessoal
  anual   boolean not null default true,
  dia     int,            -- 1..31
  mes     int,            -- 0 = janeiro
  ano     int,
  data    date,           -- quando anual = false
  obs     text,
  upd     timestamptz not null default now(),
  primary key (user_id, id)
);
create index if not exists datas_user_idx on public.datas(user_id);


-- ######################################################################
--   PARTE 10 de 14 — EXCLUSÕES   (o bilhete de "isto foi apagado")
--   Tabela pequena e chata, mas é ela que faz "excluir" funcionar de
--   verdade quando você usa dois aparelhos.
--
--   O problema que ela resolve: você apaga um alimento no celular. A
--   linha some do servidor. Só que o computador ainda tem esse alimento
--   guardado e, no próximo sync, ele reenvia — e o alimento volta. Sem
--   um registro de que aquilo foi apagado, não existe como o computador
--   saber a diferença entre "isto é novo" e "isto foi excluído".
--
--   Cada linha aqui é um bilhete: "o item tal, da tabela tal, foi
--   apagado". Todo aparelho lê esses bilhetes no sync e obedece.
-- ######################################################################

create table if not exists public.excluidos (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  tabela  text not null,        -- alimentos, fichas, pratos, habitos...
  item_id text not null,
  em      timestamptz not null default now(),
  primary key (user_id, tabela, item_id)
);
create index if not exists excluidos_user_idx on public.excluidos(user_id);


-- ######################################################################
--   PARTE 11 de 14 — DIAS   (o coração do app)
--   Uma linha por dia seu. É aqui que cai tudo o que você lança:
--   o treino com as séries, os esportes, cada refeição, a água, o
--   check de cada hábito, o peso e os ajustes daquele dia.
--
--   Repare que a chave é dupla: primary key (user_id, data). Isso quer
--   dizer "cada usuário tem no máximo uma linha por data" — é o que
--   permite o app salvar por cima do mesmo dia sem duplicar.
-- ######################################################################

create table if not exists public.dias (
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  data        date not null,
  treino      jsonb,                                -- treino do dia + séries lançadas
  esportes    jsonb not null default '[]'::jsonb,   -- futebol, caminhada...
  refeicoes   jsonb not null default '{}'::jsonb,   -- {almoco:[itens], janta:[...]}
  agua        int  not null default 0,              -- em ml
  habitos     jsonb not null default '{}'::jsonb,   -- {habitoId:{feito,qual,valor,nota}}
  meds        jsonb not null default '{}'::jsonb,   -- {medicamentoId:{tomados:["08:00",...]}}
  sono        jsonb,                                -- {deitou,acordou,qual,despertares,nota}
  peso        numeric,
  nota        text,
  ajuste_kcal numeric not null default 0,           -- gasto extra só neste dia
  ajuste_agua numeric not null default 0,           -- água extra na meta só neste dia
  upd         timestamptz not null default now(),
  primary key (user_id, data)
);
create index if not exists dias_user_data_idx on public.dias(user_id, data desc);


-- ######################################################################
--   PARTE 12 de 14 — ATUALIZAÇÃO
--   Só faz diferença para quem já rodou uma versão antiga deste arquivo.
--   Se você está começando agora, esta parte não muda nada — e tudo bem.
--
--   Três coisas acontecem aqui:
--
--   1. As colunas de ajuste diário (gasto e água) entram na tabela "dias".
--
--   2. As colunas "meds" e "sono" entram na tabela "dias": as doses que
--      você marca como tomadas e o registro da noite. Sem elas o app
--      continua funcionando, só avisa no console que não conseguiu
--      enviar essa parte do dia.
--
--   3. A chave primária das tabelas de itens passa de (id) para
--      (user_id, id). Isso conserta um erro que aparece assim no app:
--         new row violates row-level security policy (USING expression)
--      A causa: com o id sozinho como chave, o identificador tinha de ser
--      único no banco INTEIRO. Quando a mesma pessoa trocava de conta —
--      de anônima para e-mail, por exemplo — o app tentava gravar um item
--      com um identificador que já pertencia à conta antiga; o banco
--      transformava isso em uma alteração da linha alheia, e o RLS,
--      corretamente, recusava. Com a chave em par, cada conta tem o seu
--      próprio conjunto de identificadores e o conflito acaba.
-- ######################################################################

alter table public.dias add column if not exists ajuste_kcal numeric not null default 0;
alter table public.dias add column if not exists ajuste_agua numeric not null default 0;
alter table public.dias add column if not exists meds jsonb not null default '{}'::jsonb;
alter table public.dias add column if not exists sono jsonb;
alter table public.habitos add column if not exists hora text;
alter table public.habitos add column if not exists horas jsonb not null default '[]'::jsonb;
alter table public.habitos add column if not exists cada int;
alter table public.habitos add column if not exists dia_mes int;
alter table public.habitos add column if not exists mes_ano int;
alter table public.lembretes add column if not exists cada int;
alter table public.lembretes add column if not exists dia_mes int;
alter table public.lembretes add column if not exists mes_ano int;
alter table public.lembretes add column if not exists antecedencia jsonb not null default '[]'::jsonb;
do $$ begin
  if to_regclass('public.medicamentos') is not null then
    alter table public.medicamentos add column if not exists motivo text;
  end if;
end $$;

do $$
declare t text; c text; n int;
begin
  foreach t in array array['fichas','alimentos','pratos','habitos','lembretes','atividades','medicamentos','datas'] loop
    select con.conname, array_length(con.conkey, 1) into c, n
      from pg_constraint con
     where con.conrelid = ('public.' || t)::regclass and con.contype = 'p';
    if c is not null and n = 1 then                  -- ainda é a chave antiga
      execute format('alter table public.%I drop constraint %I', t, c);
      execute format('alter table public.%I alter column id set not null', t);
      execute format('alter table public.%I add primary key (user_id, id)', t);
      raise notice 'chave primaria de % atualizada para (user_id, id)', t;
    end if;
  end loop;
end $$;


-- ######################################################################
--   PARTE 13 de 14 — SEGURANÇA   (a parte mais importante)
--
--   A chave que você cola no app é pública: qualquer pessoa que abrir o
--   endereço do site consegue lê-la. Isso é normal e esperado — o que
--   impede outra pessoa de ver os seus dados é o que vem agora.
--
--   RLS (Row Level Security, "segurança em nível de linha") faz o banco
--   filtrar cada consulta pelo dono da linha. Com as regras abaixo:
--     - você só enxerga linhas onde user_id é você;
--     - você só consegue gravar linhas marcando você como dono;
--     - outra pessoa, mesmo com a chave, recebe uma lista vazia.
--
--   O bloco "do $$ ... $$" é um mini-programa que repete a mesma regra
--   para as onze tabelas, em vez de escrever 22 comandos na mão.
--   Ele apaga a regra antiga antes de criar a nova — por isso você pode
--   rodar quantas vezes quiser.
--
--   Ao rodar, o Supabase mostra várias mensagens amarelas de NOTICE
--   dizendo "policy does not exist, skipping". ISSO É NORMAL na primeira
--   vez: ele está avisando que não havia regra antiga para apagar.
-- ######################################################################

alter table public.perfil    enable row level security;
alter table public.fichas    enable row level security;
alter table public.alimentos enable row level security;
alter table public.pratos    enable row level security;
alter table public.habitos   enable row level security;
alter table public.lembretes enable row level security;
alter table public.atividades enable row level security;
alter table public.medicamentos enable row level security;
alter table public.excluidos  enable row level security;
alter table public.datas      enable row level security;
alter table public.dias      enable row level security;

do $$
declare t text;
begin
  foreach t in array array['perfil','fichas','alimentos','pratos','habitos','lembretes','atividades','medicamentos','datas','excluidos','dias'] loop
    execute format('drop policy if exists "dono_le" on public.%I', t);
    execute format('drop policy if exists "dono_escreve" on public.%I', t);
    execute format($f$create policy "dono_le" on public.%I
        for select using (auth.uid() = user_id)$f$, t);
    execute format($f$create policy "dono_escreve" on public.%I
        for all using (auth.uid() = user_id) with check (auth.uid() = user_id)$f$, t);
  end loop;
end $$;


-- ######################################################################
--   PARTE 14 de 14 — CONFERÊNCIA
--
--   Esta última consulta não cria nada: ela devolve um relatório para
--   você conferir se deu tudo certo. Depois do Run, olhe a tabela que
--   aparece embaixo do editor.
--
--   O QUE VOCÊ QUER VER: onze linhas, todas com
--       ✅ criada     na coluna "tabela"
--       ✅ protegida  na coluna "seguranca"
--
--   Se alguma aparecer com ❌, role para cima e rode de novo a PARTE
--   correspondente. Se "seguranca" vier ❌, rode de novo a PARTE 13.
-- ######################################################################

select
  nome as "tabela_do_app",
  case when to_regclass('public.' || nome) is null
       then '❌ não existe' else '✅ criada' end as "tabela",
  coalesce((select case when c.relrowsecurity then '✅ protegida' else '❌ SEM RLS' end
              from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
             where n.nspname = 'public' and c.relname = nome), '—') as "seguranca",
  coalesce((select count(*)::text from pg_policies p
             where p.schemaname = 'public' and p.tablename = nome), '0') as "regras"
from (values ('perfil'), ('fichas'), ('alimentos'), ('pratos'),
             ('habitos'), ('lembretes'), ('atividades'), ('medicamentos'),
             ('datas'), ('excluidos'), ('dias')) as t(nome);


-- ######################################################################
--   ACABOU. Próximo passo: voltar ao guia e ligar o login anônimo em
--   Authentication → Sign In / Providers → Enable Anonymous Sign-ins.
--   Sem isso o app conecta mas não consegue gravar.
-- ######################################################################
