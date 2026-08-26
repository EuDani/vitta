# Vitta no Android — como sair daqui com o APK no celular

O app continua sendo o mesmo `index.html`. Este projeto é só a casca que o
Android precisa para tratar o Vitta como aplicativo: ícone na tela inicial,
tela cheia sem barra de navegador, notificação de verdade, botão voltar que
se comporta como no Android e a planilha indo parar em Downloads.

Existem dois caminhos. **O primeiro não exige instalar nada no seu
computador** — é o recomendado.

---

## Caminho 1 — o GitHub monta o APK para você (recomendado)

Você já publica o Vitta no GitHub Pages, então a conta já existe. O GitHub
tem máquinas com o Android SDK pronto; é lá que o APK vai ser gerado.

### 1. Coloque estes arquivos no seu repositório

Na raiz do repositório (onde já estão `index.html` e `sw.js`), acrescente:

```
seu-repositorio/
├── index.html            ← já está aí
├── sw.js                 ← já está aí
├── android/              ← a pasta que veio junto com este arquivo
└── .github/
    └── workflows/
        └── apk.yml       ← está em android/.github/workflows/apk.yml
```

Atenção a um detalhe: o arquivo `apk.yml` precisa ficar em
`.github/workflows/apk.yml` **na raiz** do repositório, e não dentro de
`android/`. Mova-o para lá.

Pelo site do GitHub: **Add file → Upload files**, arraste a pasta `android`,
e depois crie o caminho `.github/workflows/apk.yml` colando o conteúdo do
arquivo.

### 2. Diga ao app qual é o seu endereço

Abra `android/app/src/main/res/values/strings.xml` e preencha duas linhas:

```xml
<string name="app_url">https://seuusuario.github.io/vitta/</string>
<string name="app_host">seuusuario.github.io</string>
```

Isso muda duas coisas importantes:

- o app passa a se atualizar sozinho toda vez que você publica uma versão
  nova no Pages — sem gerar APK de novo;
- o link de acesso que chega no seu e-mail volta para **dentro** do app, que
  é o que mantém a sincronização entre o celular e o computador.

Deixando em branco, o app funciona igual, mas usando a cópia embutida: nunca
se atualiza sozinho e o login precisa ser pelo código de 6 dígitos.

### 3. Baixe o APK

Vá na aba **Actions** do repositório → **Gerar o APK do Vitta** → clique na
execução mais recente → no fim da página, em **Artifacts**, baixe
`vitta-apk`. Dentro do zip está o `app-release.apk`.

Se for a primeira vez, o GitHub pode pedir para você confirmar que quer
rodar Actions no repositório. Confirme e clique em **Run workflow**.

### 4. Instale no celular

Passe o APK para o celular (cabo, Google Drive, WhatsApp para você mesmo) e
toque nele. O Android vai avisar que é um app de fora da Play Store e pedir
para liberar a instalação para o aplicativo que está abrindo o arquivo —
libere. Isso é normal para qualquer app instalado fora da loja.

Na primeira abertura ele pede permissão de notificação: **aceite**, senão os
lembretes, hábitos e medicamentos não avisam nada.

---

## Caminho 2 — gerar no seu computador

Só vale a pena se você já usa Android Studio ou quer mexer no projeto.

1. Instale o [Android Studio](https://developer.android.com/studio).
2. Copie `index.html` e `sw.js` para `android/app/src/main/assets/`.
3. Abra a pasta `android` no Android Studio e espere ele baixar o que falta.
4. Menu **Build → Build Bundle(s)/APK(s) → Build APK(s)**.
5. O arquivo sai em `android/app/build/outputs/apk/release/`.

Pelo terminal, com o SDK já instalado, é `./gradlew assembleRelease` dentro
da pasta `android`.

---

## O que este APK faz além do navegador

| | Navegador | Este APK |
|---|---|---|
| Ícone e tela cheia | só com "adicionar à tela inicial" | sempre |
| Notificação com o app fechado | depende do navegador | sim |
| Botão voltar | fecha a aba | fecha a folha, volta de tela, e só então sai |
| Baixar a planilha | às vezes falha | salva em Downloads e abre o compartilhar |
| Sem internet | tela de erro | abre a cópia que veio no APK |

---

## Perguntas que costumam aparecer

**Este APK serve para a Play Store?**
Não. Ele é assinado com a chave de depuração, que serve para instalar no seu
aparelho. Publicar na loja exige uma chave própria e uma conta de
desenvolvedor (paga, uma vez só).

**Meus dados somem quando eu reinstalar?**
Se você estiver logado com e-mail, não: eles estão no Supabase e voltam no
primeiro sync. Sem conta, os dados ficam só no aparelho e são perdidos ao
desinstalar — vale entrar com e-mail antes.

**Dá para usar o mesmo APK em mais de um celular?**
Dá, e é justamente aí que entrar com o mesmo e-mail em todos faz diferença.

**O app pesa quanto?**
Cerca de 3 MB. Quase tudo é o próprio Android; o Vitta são 270 KB.
