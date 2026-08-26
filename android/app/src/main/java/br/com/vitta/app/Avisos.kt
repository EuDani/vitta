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
   *      "dias": [1,3,5] | null }]
   * "dias" ausente = todo dia. Reagenda tudo do zero: é mais simples de
   * raciocinar do que tentar descobrir o que mudou, e o custo é o mesmo.
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

  /** Próximo horário válido daqui para a frente, dentro de 8 dias. */
  private fun proximaVez(o: JSONObject): Long? {
    val hora = o.optString("hora")
    val partes = hora.split(":")
    if (partes.size < 2) return null
    val h = partes[0].toIntOrNull() ?: return null
    val m = partes[1].toIntOrNull() ?: return null

    val dias: List<Int>? = o.optJSONArray("dias")?.let { a ->
      (0 until a.length()).map { a.optInt(it) }
    }?.takeIf { it.isNotEmpty() }

    val c = Calendar.getInstance()
    for (adiar in 0..8) {
      val t = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, adiar)
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
      }
      if (t.timeInMillis <= c.timeInMillis) continue
      // Calendar.DAY_OF_WEEK: domingo = 1; no app domingo = 0
      if (dias != null && !dias.contains(t.get(Calendar.DAY_OF_WEEK) - 1)) continue
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
