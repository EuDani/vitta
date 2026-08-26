package br.com.vitta.app

/*
 * Vitta para Android — uma casca em volta do mesmo app web.
 *
 * Por que uma casca e não um app nativo: o Vitta inteiro é um arquivo
 * HTML. Reescrever tudo em Kotlin seria manter dois apps. Aqui o
 * WebView faz o trabalho e o Android entra só onde o navegador não
 * alcança:
 *
 *   1. NOTIFICAÇÃO — Android 13+ exige permissão explícita; sem ela o
 *      service worker não avisa nada.
 *   2. BOTÃO VOLTAR — o app é uma página só: sem tratar aqui, o botão
 *      fecharia o app com a folha aberta e o formulário preenchido.
 *   3. BAIXAR A PLANILHA — o CSV é gerado como blob dentro da página.
 *      Um WebView cru ignora <a download>. Aqui o arquivo é lido e
 *      gravado em Downloads, e o Android abre a folha de compartilhar.
 *   4. OFFLINE — sem internet, cai para a cópia embutida no APK.
 */

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

  private lateinit var web: WebView
  private lateinit var refresh: SwipeRefreshLayout
  private lateinit var loader: WebViewAssetLoader
  private var carregouDaRede = false

  /** Endereço publicado do app. Vazio = usar só a cópia embutida. */
  private val enderecoPublicado: String
    get() {
      val salvo = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHAVE_URL, null)
      if (!salvo.isNullOrBlank()) return salvo
      return getString(R.string.app_url).trim()
    }

  private val copiaEmbutida = "https://appassets.androidplatform.net/assets/index.html"

  private val pedirNotificacao =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    setContentView(R.layout.activity_main)

    web = findViewById(R.id.web)
    refresh = findViewById(R.id.refresh)

    /* A cópia embutida é servida por um endereço https de verdade
       (appassets.androidplatform.net) e não por file://. Faz diferença:
       em file:// o localStorage é instável e o service worker nem
       chega a registrar — ou seja, nada de notificação offline.       */
    loader = WebViewAssetLoader.Builder()
      .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
      .build()

    with(web.settings) {
      javaScriptEnabled = true
      domStorageEnabled = true
      databaseEnabled = true
      loadWithOverviewMode = true
      useWideViewPort = true
      cacheMode = WebSettings.LOAD_DEFAULT
      mediaPlaybackRequiresUserGesture = false
      textZoom = 100                      // o app já tem tamanhos próprios
    }
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

    web.webViewClient = ClienteVitta()
    web.webChromeClient = object : WebChromeClient() {
      /* O app pede permissão de notificação pelo JavaScript; aqui a
         pergunta é repassada para o Android. */
      override fun onPermissionRequest(req: PermissionRequest) = req.deny()
    }
    web.setDownloadListener { url, _, _, mime, _ -> baixar(url, mime) }
    web.addJavascriptInterface(Ponte(), "AndroidVitta")

    refresh.setOnRefreshListener {
      web.reload()
      refresh.postDelayed({ refresh.isRefreshing = false }, 1200)
    }
    /* Só puxa para atualizar quando a página já está no topo — senão
       rolar a lista para cima recarregaria o app sem querer. */
    refresh.setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }

    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() = voltar()
    })

    Avisos.criarCanal(this)
    pedirPermissaoNotificacao()
    abrir(intent?.data)
  }

  /** Endereço inicial: o publicado, se houver; senão, a cópia do APK. */
  private fun abrir(link: Uri?) {
    val alvo = when {
      link != null && link.scheme?.startsWith("http") == true -> link.toString()
      enderecoPublicado.isNotBlank() -> enderecoPublicado
      else -> copiaEmbutida
    }
    web.loadUrl(alvo)
  }

  /* O link do e-mail de acesso chega por aqui com o app já aberto. */
  override fun onNewIntent(novo: Intent) {
    super.onNewIntent(novo)
    novo.data?.let { web.loadUrl(it.toString()) }
  }

  private fun pedirPermissaoNotificacao() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val ja = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
    if (ja != PackageManager.PERMISSION_GRANTED) pedirNotificacao.launch(Manifest.permission.POST_NOTIFICATIONS)
  }

  /* ---------- botão voltar ----------
     Em ordem: fecha a pergunta, fecha a folha, volta de tela, e só
     então sai do app. É o que a pessoa espera de um app Android — e
     evita perder um formulário preenchido com um toque distraído.   */
  private fun voltar() {
    web.evaluateJavascript(
      """
      (function () {
        try {
          if (document.querySelector('#modal2 .ask')) { fecharPergunta(); return 'ok'; }
          if (document.querySelector('#modal .sheet')) { ACT.tentarFechar(); return 'ok'; }
          if (typeof VIEW !== 'undefined' && VIEW !== 'hoje') {
            if (typeof NAVITENS !== 'undefined' && !NAVITENS.some(function (n) { return n[0] === VIEW; })) ACT.voltar();
            else go('hoje');
            return 'ok';
          }
        } catch (e) { }
        return 'sair';
      })();
      """.trimIndent()
    ) { r ->
      if (r.contains("sair")) {
        if (web.canGoBack()) web.goBack() else finish()
      }
    }
  }

  /* ---------- baixar a planilha ----------
     O CSV nasce como blob: dentro da página. O WebView não sabe salvar
     isso sozinho, então a página é instruída a ler o blob em base64 e
     devolver por esta ponte.                                          */
  private fun baixar(url: String, mime: String?) {
    when {
      url.startsWith("blob:") -> web.evaluateJavascript(jsLerBlob(url), null)
      url.startsWith("data:") -> {
        val base64 = url.substringAfter(",", "")
        salvar("vitta.csv", Base64.decode(base64, Base64.DEFAULT), mime ?: "text/csv")
      }
      else -> try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
      } catch (e: ActivityNotFoundException) {
        aviso("Nenhum aplicativo para abrir este arquivo")
      }
    }
  }

  private fun jsLerBlob(url: String) = """
    (function () {
      var x = new XMLHttpRequest();
      x.open('GET', '$url', true);
      x.responseType = 'blob';
      x.onload = function () {
        var r = new FileReader();
        r.onloadend = function () {
          var nome = 'vitta.csv';
          try { nome = (arquivoPlanilha() || {}).nome || nome; } catch (e) { }
          AndroidVitta.salvarArquivo(nome, String(r.result).split(',')[1] || '');
        };
        r.readAsDataURL(x.response);
      };
      x.send();
    })();
  """.trimIndent()

  inner class Ponte {
    /** A página avisa por aqui, já que o WebView não tem Notification. */
    @JavascriptInterface
    fun notificar(titulo: String, corpo: String, tag: String) {
      runOnUiThread { Avisos.mostrar(this@MainActivity, titulo, corpo, tag) }
    }

    /** A página entrega a agenda inteira; o Android marca os alarmes. */
    @JavascriptInterface
    fun agendarAvisos(json: String) {
      runOnUiThread { Avisos.agendar(this@MainActivity, json) }
    }

    @JavascriptInterface
    fun salvarArquivo(nome: String, base64: String) {
      val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: Exception) { null } ?: return
      runOnUiThread { salvar(nome, bytes, "text/csv") }
    }
  }

  private fun salvar(nome: String, bytes: ByteArray, mime: String) {
    try {
      val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val v = ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, nome)
          put(MediaStore.Downloads.MIME_TYPE, mime)
          put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val destino = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
          ?: return aviso("Não consegui gravar em Downloads")
        contentResolver.openOutputStream(destino)?.use { it.write(bytes) }
        v.clear(); v.put(MediaStore.Downloads.IS_PENDING, 0)
        contentResolver.update(destino, v, null, null)
        destino
      } else {
        val pasta = File(getExternalFilesDir(null), "planilhas").apply { mkdirs() }
        val arq = File(pasta, nome)
        FileOutputStream(arq).use { it.write(bytes) }
        FileProvider.getUriForFile(this, "$packageName.arquivos", arq)
      }
      aviso("Planilha salva em Downloads")
      /* Já abre o "compartilhar": quase sempre o destino é o e-mail. */
      val enviar = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Vitta — acompanhamento")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      startActivity(Intent.createChooser(enviar, "Enviar a planilha"))
    } catch (e: Exception) {
      aviso("Falha ao salvar: ${e.message}")
    }
  }

  private fun aviso(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

  private inner class ClienteVitta : WebViewClient() {

    override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? =
      loader.shouldInterceptRequest(req.url)

    /* Link para fora (documentação, Supabase) abre no navegador; o que
       é do próprio app continua dentro dele. */
    override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
      val u = req.url
      val dentro = u.host == null ||
        u.host == Uri.parse(copiaEmbutida).host ||
        (enderecoPublicado.isNotBlank() && u.host == Uri.parse(enderecoPublicado).host)
      if (dentro) return false
      return try {
        startActivity(Intent(Intent.ACTION_VIEW, u)); true
      } catch (e: ActivityNotFoundException) { false }
    }

    override fun onPageFinished(v: WebView, url: String) {
      refresh.isRefreshing = false
      if (url.startsWith("http") && !url.contains("appassets")) carregouDaRede = true
      v.visibility = View.VISIBLE
    }

    /* Sem internet: usa a cópia que veio no APK, em vez da tela de erro
       do Chrome. Os dados são os mesmos, guardados no aparelho. */
    override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
      if (!req.isForMainFrame) return
      if (!carregouDaRede || enderecoPublicado.isBlank()) {
        v.loadUrl(copiaEmbutida)
        aviso("Sem conexão — abrindo a cópia do aparelho")
      }
    }
  }

  companion object {
    const val PREFS = "vitta"
    const val CHAVE_URL = "url"
  }
}
