package com.netflix.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.netflix.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val FF_MAX_PKG    = "com.dts.freefiremax"
    private val FF_NORMAL_PKG = "com.dts.freefire"
    private val REPLAY_PATHS  = listOf(
        "files/replays",
        "files/replay",
        "cache/replays"
    )

    private val SHIZUKU_CODE = 1001

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) {
            updateShizukuStatus()
            showStatus("✅ Shizuku autorizado! Pronto para transferir.", true)
        } else {
            showStatus("❌ Permissão negada pelo Shizuku.", false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Shizuku.addRequestPermissionResultListener(permListener)
        setupButtons()
        updateShizukuStatus()
    }

    private fun setupButtons() {
        binding.btnShizuku.setOnClickListener { requestShizuku() }
        binding.btnTransfer.setOnClickListener {
            transfer(FF_MAX_PKG, FF_NORMAL_PKG, "FF Max → FF Normal")
        }
        binding.btnTransferReverse.setOnClickListener {
            transfer(FF_NORMAL_PKG, FF_MAX_PKG, "FF Normal → FF Max")
        }
    }

    private fun requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Shizuku não está rodando!\nAbra o app Shizuku primeiro.")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("Shizuku já autorizado ✅")
                updateShizukuStatus()
                return
            }
            Shizuku.requestPermission(SHIZUKU_CODE)
        } catch (e: Exception) {
            showStatus("❌ Erro: ${e.message}", false)
        }
    }

    private fun transfer(fromPkg: String, toPkg: String, label: String) {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            toast("Autorize o Shizuku primeiro!")
            return
        }
        setLoading(true)
        val log = StringBuilder()
        log.appendLine("🔄 Iniciando: $label")
        binding.tvLog.text = log.toString()

        lifecycleScope.launch(Dispatchers.IO) {
            var totalCopied = 0
            var replayPathFound = ""

            // Tenta encontrar o caminho correto dos replays
            for (subPath in REPLAY_PATHS) {
                val fromPath = "/data/data/$fromPkg/$subPath"
                val ls = shell("ls $fromPath 2>/dev/null")
                if (ls.isNotBlank() && !ls.contains("No such file") && !ls.contains("Permission denied").not()) {
                    replayPathFound = subPath
                    break
                }
            }

            if (replayPathFound.isEmpty()) {
                // Tenta listar a pasta files/ inteira
                val fromFiles = "/data/data/$fromPkg/files"
                val ls = shell("ls $fromFiles 2>/dev/null")
                withContext(Dispatchers.Main) {
                    log.appendLine("📂 Conteúdo de $fromFiles:")
                    log.appendLine(ls.ifBlank { "(vazio ou sem acesso)" })
                    log.appendLine()
                    log.appendLine("⚠️ Pasta de replays não encontrada automaticamente.")
                    log.appendLine("Tente salvar um replay no jogo e tente novamente.")
                    binding.tvLog.text = log.toString()
                    showStatus("⚠️ Nenhum replay encontrado em $fromPkg", null)
                    setLoading(false)
                }
                return@launch
            }

            val fromPath = "/data/data/$fromPkg/$replayPathFound"
            val toPath   = "/data/data/$toPkg/$replayPathFound"

            log.appendLine("📂 Origem: $fromPath")
            log.appendLine("📁 Destino: $toPath")
            withContext(Dispatchers.Main) { binding.tvLog.text = log.toString() }

            shell("mkdir -p $toPath")

            val ls = shell("ls $fromPath")
            val files = ls.trim().split("\n").filter { it.isNotBlank() && !it.contains("No such") }

            if (files.isEmpty()) {
                withContext(Dispatchers.Main) {
                    log.appendLine("❌ Nenhum replay encontrado.")
                    binding.tvLog.text = log.toString()
                    showStatus("❌ Nenhum replay encontrado.", false)
                    setLoading(false)
                }
                return@launch
            }

            log.appendLine("📋 ${files.size} arquivo(s) encontrado(s):")
            withContext(Dispatchers.Main) { binding.tvLog.text = log.toString() }

            files.forEach { file ->
                val result = shell("cp -f $fromPath/$file $toPath/$file && chmod 777 $toPath/$file")
                log.appendLine("  ✅ $file")
                totalCopied++
                withContext(Dispatchers.Main) { binding.tvLog.text = log.toString() }
            }

            log.appendLine()
            log.appendLine("🎉 Concluído! $totalCopied replay(s) transferido(s).")
            log.appendLine("Abra o ${if (toPkg == FF_NORMAL_PKG) "FF Normal" else "FF Max"} e confira!")

            withContext(Dispatchers.Main) {
                binding.tvLog.text = log.toString()
                setLoading(false)
                showStatus("✅ $totalCopied replay(s) transferido(s) com sucesso!", true)
            }
        }
    }

    private fun shell(cmd: String): String {
        return try {
            val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (e: Exception) {
            "Erro: ${e.message}"
        }
    }

    private fun updateShizukuStatus() {
        try {
            val running = Shizuku.pingBinder()
            val granted = running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            binding.tvShizukuStatus.text = when {
                granted -> "🟢 Shizuku: Autorizado"
                running -> "🟡 Shizuku: Ativo (clique Autorizar)"
                else    -> "🔴 Shizuku: Não encontrado"
            }
            binding.tvShizukuStatus.setTextColor(getColor(when {
                granted -> android.R.color.holo_green_light
                running -> android.R.color.holo_orange_light
                else    -> android.R.color.holo_red_light
            }))
            binding.btnShizuku.isEnabled = running && !granted
        } catch (e: Exception) {
            binding.tvShizukuStatus.text = "🔴 Shizuku não instalado"
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnTransfer.isEnabled = !loading
        binding.btnTransferReverse.isEnabled = !loading
    }

    private fun showStatus(msg: String, success: Boolean?) {
        runOnUiThread {
            binding.tvStatus.text = msg
            binding.tvStatus.setTextColor(getColor(when (success) {
                true  -> android.R.color.holo_green_light
                false -> android.R.color.holo_red_light
                null  -> android.R.color.holo_orange_light
            }))
        }
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        updateShizukuStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permListener)
    }
}
