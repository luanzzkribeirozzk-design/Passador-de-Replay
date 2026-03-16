package com.netflix.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.netflix.app.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val FF_MAX    = "com.dts.freefiremax"
    private val FF_NORMAL = "com.dts.freefire"
    private val REPLAY_DIRS = listOf("files/replays","files/replay","files/Replays","cache/replays")

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) {
            updateShizukuStatus()
            showStatus("Shizuku autorizado! Pronto.", true)
        } else {
            showStatus("Permissao negada pelo Shizuku.", false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Shizuku.addRequestPermissionResultListener(permListener)
        binding.btnShizuku.setOnClickListener { requestShizuku() }
        binding.btnTransfer.setOnClickListener { transfer(FF_MAX, FF_NORMAL) }
        binding.btnTransferReverse.setOnClickListener { transfer(FF_NORMAL, FF_MAX) }
        updateShizukuStatus()
    }

    override fun onResume() {
        super.onResume()
        updateShizukuStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permListener)
    }

    private fun requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Shizuku nao esta rodando! Abra o app Shizuku primeiro.")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("Shizuku ja esta autorizado!")
                return
            }
            Shizuku.requestPermission(1001)
        } catch (e: Exception) {
            showStatus("Erro: ${e.message}", false)
        }
    }

    private fun transfer(fromPkg: String, toPkg: String) {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            toast("Autorize o Shizuku primeiro!")
            return
        }
        val label = if (toPkg == FF_NORMAL) "FF Max -> FF Normal" else "FF Normal -> FF Max"
        setLoading(true)
        val sb = StringBuilder("Iniciando: $label\n")
        updateLog(sb.toString())

        thread {
            var replayDir = ""
            for (dir in REPLAY_DIRS) {
                val out = shell("ls /data/data/$fromPkg/$dir 2>&1")
                if (!out.contains("No such") && !out.contains("Permission denied") && out.isNotBlank()) {
                    replayDir = dir
                    break
                }
            }

            if (replayDir.isEmpty()) {
                val ls = shell("ls /data/data/$fromPkg/files/ 2>&1")
                sb.append("Pasta files/: $ls\n")
                sb.append("Nenhuma pasta de replay encontrada.\nSalve um replay no jogo e tente novamente.")
                runOnUiThread {
                    updateLog(sb.toString())
                    showStatus("Nenhum replay encontrado.", false)
                    setLoading(false)
                }
                return@thread
            }

            val fromPath = "/data/data/$fromPkg/$replayDir"
            val toPath   = "/data/data/$toPkg/$replayDir"
            sb.append("Origem: $fromPath\nDestino: $toPath\n\n")

            shell("mkdir -p $toPath")
            shell("chmod 777 $toPath")

            val ls = shell("ls $fromPath 2>&1")
            val files = ls.lines().filter { it.isNotBlank() && !it.contains("No such") && !it.contains("denied") }

            if (files.isEmpty()) {
                sb.append("Nenhum arquivo encontrado.")
                runOnUiThread {
                    updateLog(sb.toString())
                    showStatus("Nenhum replay encontrado.", false)
                    setLoading(false)
                }
                return@thread
            }

            var copied = 0
            files.forEach { file ->
                shell("cp -f $fromPath/$file $toPath/$file")
                shell("chmod 666 $toPath/$file")
                sb.append("OK: $file\n")
                copied++
                runOnUiThread { updateLog(sb.toString()) }
            }

            sb.append("\nConcluido! $copied replay(s) copiado(s).")
            runOnUiThread {
                updateLog(sb.toString())
                showStatus("$copied replay(s) transferido(s) com sucesso!", true)
                setLoading(false)
            }
        }
    }

    private fun shell(cmd: String): String {
        return try {
            val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.trim()
        } catch (e: Exception) { "Erro: ${e.message}" }
    }

    private fun updateShizukuStatus() {
        try {
            val running = Shizuku.pingBinder()
            val granted = running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            runOnUiThread {
                binding.tvShizukuStatus.text = when {
                    granted -> "Shizuku: Autorizado"
                    running -> "Shizuku: Ativo (clique Autorizar)"
                    else    -> "Shizuku: Nao encontrado"
                }
                binding.tvShizukuStatus.setTextColor(resources.getColor(when {
                    granted -> android.R.color.holo_green_light
                    running -> android.R.color.holo_orange_light
                    else    -> android.R.color.holo_red_light
                }, null))
                binding.btnShizuku.isEnabled = running && !granted
            }
        } catch (e: Exception) {
            runOnUiThread { binding.tvShizukuStatus.text = "Shizuku nao instalado" }
        }
    }

    private fun setLoading(b: Boolean) {
        runOnUiThread {
            binding.progressBar.visibility = if (b) View.VISIBLE else View.GONE
            binding.btnTransfer.isEnabled = !b
            binding.btnTransferReverse.isEnabled = !b
        }
    }

    private fun showStatus(msg: String, ok: Boolean?) {
        runOnUiThread {
            binding.tvStatus.text = msg
            binding.tvStatus.setTextColor(resources.getColor(when (ok) {
                true  -> android.R.color.holo_green_light
                false -> android.R.color.holo_red_light
                else  -> android.R.color.holo_orange_light
            }, null))
        }
    }

    private fun updateLog(text: String) { binding.tvLog.text = text }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
