package br.com.vitta.app

/*
 * AVISOS NATIVOS
 *
 * O WebView do Android não implementa a API de notificação do navegador.
 * Se o app dependesse dela dentro do APK, nenhum lembrete, hábito ou
 * medicamento avisaria nada — justamente o motivo de existir um APK.
 *
 * Então a página entrega ao Android a lista do que precisa avisar e a
 * que horas, e quem toca o alarme é o AlarmManager. A diferença prática
 * é grande: o aviso chega com o app FECHADO, que o service worker
 * sozinho não garante.
 *
 * A lista fica guardada em SharedPreferences para ser reagendada depois
 * de reiniciar o aparelho — alarme não sobrevive a boot.
 */

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

object Avisos {

  private const val CANAL = "vitta-lembretes"
  private const val PREFS = "vitta"
  private const val CHAVE_LISTA = "avisos"
  private const val CHAVE_CODIGOS = "avisos_codigos"
  private const val LIMITE = 60          // teto de alarmes vivos ao mesmo tempo

  fun criarCanal(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val canal = NotificationChannel(CANAL, "Lembretes do Vitta", NotificationManager.IMPORTANCE_HIGH).apply {
      description = "Hábitos, lembretes e horários de medicamento"
      enableVibration(true)
    }
    ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
  }

  /** Mostra um aviso agora (é o que a página chama no lugar de Notification). */
  fun mostrar(ctx: Context, titulo: String, corpo: String, tag: String) {
    criarCanal(ctx)
    val abrir = PendingIntent.getActivity(
      ctx, tag.hashCode(),
      Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val n = NotificationCompat.Builder(ctx, CANAL)
      .setSmallIcon(R.drawable.ic_aviso)
      .setContentTitle(titulo)
      .setContentText(corpo)
      .setStyle(NotificationCompat.BigTextStyle().bigText(corpo))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setAutoCancel(true)
      .setContentIntent(abrir)
      .build()
    try {
      NotificationManagerCompat.from(ctx).notify(tag.hashCode(), n)
    } catch (e: SecurityException) {
      // permissão de notificação negada: nada a fazer além de não quebrar
    }
  }

  /**
   * Recebe da página a lista completa do que avisar, no formato
   *   [{ "id": "...", "hora": "07:30", "titulo": "...", "corpo": "...",
   *      "tipo": "diario"|"dias"|"mes"|"ano"|"intervalo",
   *      "dias": [1,3,5] | null,      -- tipo "dias": dias da semana, domingo=0
   *      "diaMes": 1..31 | null,      -- tipo "mes"/"ano"
   *      "mesAno": 0..11 | null,      -- tipo "ano" (0 = janeiro)
   *      "cada": 1..365 | null,       -- tipo "intervalo": a cada N dias
   *      "inicio": "AAAA-MM-DD" | null,  -- de onde conta o intervalo / a partir de quando vale
   *      "fim": "AAAA-MM-DD" | null }]   -- depois desta data não avisa mais
   * Reagenda tudo do zero: é mais simples de raciocinar do que tentar
   * descobrir o que mudou, e o custo é o mesmo.
   */
  fun agendar(ctx: Context, json: String) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putString(CHAVE_LISTA, json).apply()
    reagendar(ctx)
  }

  fun reagendar(ctx: Context) {
    val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /* cancela o que estava marcado antes */
    prefs.getString(CHAVE_CODIGOS, "")?.split(",")?.filter { it.isNotBlank() }?.forEach { c ->
      val cod = c.toIntOrNull() ?: return@forEach
      PendingIntent.getBroadcast(ctx, cod, Intent(ctx, AlarmeReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let {
        am.cancel(it); it.cancel()
      }
    }

    val lista = try { JSONArray(prefs.getString(CHAVE_LISTA, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
    val codigos = mutableListOf<Int>()
    criarCanal(ctx)

    var n = 0
    for (i in 0 until lista.length()) {
      if (n >= LIMITE) break
      val o = lista.optJSONObject(i) ?: continue
      val quando = proximaVez(o) ?: continue
      val cod = (o.optString("id") + "|" + o.optString("hora")).hashCode()
      val intent = Intent(ctx, AlarmeReceiver::class.java).apply {
        putExtra("titulo", o.optString("titulo", "Vitta"))
        putExtra("corpo", o.optString("corpo", ""))
        putExtra("tag", o.optString("id", "vitta") + o.optString("hora", ""))
      }
      val pi = PendingIntent.getBroadcast(ctx, cod, intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      /* Inexato de propósito: "às 7h30" tolera alguns minutos, e o alarme
         exato exigiria uma permissão especial no Android 12+ que assusta
         quem instala. setAndAllowWhileIdle fura o modo economia.        */
      am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
      codigos += cod
      n++
    }
    prefs.edit().putString(CHAVE_CODIGOS, codigos.joinToString(",")).apply()
  }

  /** "AAAA-MM-DD" do dia guardado no calendário — mesmo formato usado na página. */
  private fun isoDe(t: Calendar) =
    "%04d-%02d-%02d".format(t.get(Calendar.YEAR), t.get(Calendar.MONTH) + 1, t.get(Calendar.DAY_OF_MONTH))

  /** "AAAA-MM-DD" -> Calendar na meia-noite local, para contar dias de intervalo. */
  private fun calDe(iso: String): Calendar? {
    val p = iso.split("-")
    if (p.size != 3) return null
    val a = p[0].toIntOrNull() ?: return null
    val mm = p[1].toIntOrNull() ?: return null
    val d = p[2].toIntOrNull() ?: return null
    return Calendar.getInstance().apply {
      clear(); set(a, mm - 1, d, 0, 0, 0)
    }
  }
  private fun diasEntre(a: String, b: String): Long? {
    val ca = calDe(a) ?: return null
    val cb = calDe(b) ?: return null
    return Math.round((cb.timeInMillis - ca.timeInMillis) / 86400000.0)
  }

  /** Dia 31 num mês de 30 (ou fevereiro) cai no último dia dele — mesma regra
      de diaDoMesCasa() na página: quem marca "todo dia 31" quer dizer "fim do mês". */
  private fun casaDiaMes(t: Calendar, diaMes: Int?): Boolean {
    val ultimo = t.getActualMaximum(Calendar.DAY_OF_MONTH)
    val alvo = (if (diaMes == null || diaMes <= 0) 1 else diaMes).coerceIn(1, ultimo)
    return t.get(Calendar.DAY_OF_MONTH) == alvo
  }

  /** Mesma regra de repeteEm() na página, para o dia candidato `t`. */
  private fun casaRegra(o: JSONObject, t: Calendar, dataISO: String): Boolean {
    val dias: List<Int>? = o.optJSONArray("dias")?.let { a -> (0 until a.length()).map { a.optInt(it) } }
    val diaMes = if (o.isNull("diaMes")) null else o.optInt("diaMes")
    val mesAno = if (o.isNull("mesAno")) null else o.optInt("mesAno")
    val cada = if (o.isNull("cada")) null else o.optInt("cada")
    val inicio = if (o.isNull("inicio")) null else o.optString("inicio").takeIf { it.isNotBlank() }
    return when (o.optString("tipo", "diario")) {
      // Calendar.DAY_OF_WEEK: domingo = 1; no app domingo = 0
      "dias" -> dias != null && dias.contains(t.get(Calendar.DAY_OF_WEEK) - 1)
      "mes" -> casaDiaMes(t, diaMes)
      "ano" -> t.get(Calendar.MONTH) == (mesAno ?: 0) && casaDiaMes(t, diaMes)
      "intervalo" -> {
        val n = (cada ?: 1).coerceIn(1, 365)
        val base = inicio ?: dataISO
        val dif = diasEntre(base, dataISO) ?: return true
        dif >= 0 && dif % n == 0L
      }
      else -> true                    // diario (e "semana", já convertido na página): todo dia
    }
  }

  /** Próximo horário válido daqui para a frente. A janela precisa cobrir um
      ano inteiro — um lembrete anual pode ter o próximo aviso a 11 meses
      de distância, e 8 dias (como era antes) nunca alcançava isso. */
  private fun proximaVez(o: JSONObject): Long? {
    val hora = o.optString("hora")
    val partes = hora.split(":")
    if (partes.size < 2) return null
    val h = partes[0].toIntOrNull() ?: return null
    val m = partes[1].toIntOrNull() ?: return null

    val inicio = if (o.isNull("inicio")) null else o.optString("inicio").takeIf { it.isNotBlank() }
    val fim = if (o.isNull("fim")) null else o.optString("fim").takeIf { it.isNotBlank() }

    val c = Calendar.getInstance()
    for (adiar in 0..370) {
      val t = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, adiar)
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
      }
      if (t.timeInMillis <= c.timeInMillis) continue
      val dataISO = isoDe(t)
      if (inicio != null && dataISO < inicio) continue
      if (fim != null && dataISO > fim) continue
      if (!casaRegra(o, t, dataISO)) continue
      return t.timeInMillis
    }
    return null
  }
}

class AlarmeReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, i: Intent) {
    Avisos.mostrar(ctx,
      i.getStringExtra("titulo") ?: "Vitta",
      i.getStringExtra("corpo") ?: "",
      i.getStringExtra("tag") ?: "vitta")
    /* o alarme disparado morre; remarca a próxima ocorrência */
    Avisos.reagendar(ctx)
  }
}

/** Alarme não sobrevive a reiniciar o aparelho: aqui ele volta. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, i: Intent) {
    if (i.action == Intent.ACTION_BOOT_COMPLETED ||
      i.action == "android.intent.action.MY_PACKAGE_REPLACED") Avisos.reagendar(ctx)
  }
}
