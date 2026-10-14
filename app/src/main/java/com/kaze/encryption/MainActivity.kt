package com.kaze.encryption

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.format.Formatter
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.kaze.encryption.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private var inputUri: Uri? = null
    private var inputName = ""
    private var inputDetail = ""
    private var busy = false
    private var pendingPassword: CharArray? = null

    private val isEncrypt get() = b.modeGroup.checkedButtonId == R.id.btnModeEncrypt
    private val isFolder get() = b.typeGroup.checkedButtonId == R.id.btnTypeFolder

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onFilePicked(it) }
        }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { onFolderPicked(it) }
        }

    private val createOutput =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) {
                runSingleFile(uri)
            } else {
                clearPassword()
                log("Cancelled. No output location was chosen.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.modeGroup.addOnButtonCheckedListener { _, _, checked -> if (checked) applyMode() }
        b.typeGroup.addOnButtonCheckedListener { _, _, checked -> if (checked) clearSelection() }

        b.cardSelect.setOnClickListener {
            if (busy) return@setOnClickListener
            if (isFolder) pickFolder.launch(null) else pickFile.launch(arrayOf("*/*"))
        }
        b.btnExecute.setOnClickListener { execute() }

        b.etPassword.doAfterTextChanged {
            b.tilPassword.error = null
            updateStrength(it?.toString().orEmpty())
        }
        b.etConfirm.doAfterTextChanged { b.tilConfirm.error = null }

        applyMode()
        refreshSelectCard()
    }

    // ---------- UI state ----------

    private fun applyMode() {
        TransitionManager.beginDelayedTransition(b.content, AutoTransition().setDuration(220))
        b.groupEncryptOnly.isVisible = isEncrypt
        b.btnExecute.text = if (isEncrypt) "Encrypt" else "Decrypt"
        b.btnExecute.setIconResource(if (isEncrypt) R.drawable.ic_lock else R.drawable.ic_unlock)
        refreshSelectCard()
    }

    private fun clearSelection() {
        inputUri = null
        inputName = ""
        inputDetail = ""
        refreshSelectCard()
    }

    private fun refreshSelectCard() {
        b.ivSelectIcon.setImageResource(if (isFolder) R.drawable.ic_folder else R.drawable.ic_file)
        if (inputUri == null) {
            val what = if (isFolder) "folder" else "file"
            b.tvSelectTitle.text = if (isEncrypt) "Choose a $what to encrypt" else "Choose an encrypted $what"
            b.tvSelectSub.text = "Tap to browse"
        } else {
            b.tvSelectTitle.text = inputName
            b.tvSelectSub.text = inputDetail
        }
    }

    private fun popIcon() {
        b.ivSelectIcon.animate().cancel()
        b.ivSelectIcon.scaleX = 0.7f
        b.ivSelectIcon.scaleY = 0.7f
        b.ivSelectIcon.animate().scaleX(1f).scaleY(1f).setDuration(260).start()
    }

    private fun updateStrength(pw: String) {
        var s = 0
        if (pw.length >= 8) s += 30
        if (pw.length >= 12) s += 20
        if (pw.any { it.isLowerCase() } && pw.any { it.isUpperCase() }) s += 15
        if (pw.any { it.isDigit() }) s += 15
        if (pw.any { !it.isLetterOrDigit() }) s += 20
        s = if (pw.isEmpty()) 0 else s.coerceAtMost(100)

        val (label, color) = when {
            pw.isEmpty() -> "" to R.color.kaze_stroke
            s < 40 -> "Weak" to R.color.kaze_err
            s < 70 -> "Okay" to R.color.kaze_brass
            else -> "Strong" to R.color.kaze_ok
        }
        b.strengthBar.setIndicatorColor(getColor(color))
        b.strengthBar.setProgressCompat(s, true)
        b.tvStrength.text = label
    }

    private fun setBusy(on: Boolean) {
        busy = on
        b.btnExecute.isEnabled = !on
        b.cardSelect.isEnabled = !on
        b.modeGroup.children.forEach { it.isEnabled = !on }
        b.typeGroup.children.forEach { it.isEnabled = !on }
        b.progress.isVisible = on
    }

    private fun log(msg: String, colorRes: Int = R.color.kaze_text_dim) {
        val s = SpannableString(msg + "\n")
        s.setSpan(ForegroundColorSpan(getColor(colorRes)), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.tvLog.append(s)
    }

    private fun snack(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_SHORT).show()

    private fun clearPassword() {
        pendingPassword?.fill('\u0000')
        pendingPassword = null
    }

    // ---------- Picking ----------

    private fun onFilePicked(uri: Uri) {
        inputUri = uri
        var name = "Selected file"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        inputName = name
        inputDetail = if (size >= 0) Formatter.formatShortFileSize(this, size) else "Ready"
        refreshSelectCard()
        popIcon()
    }

    private fun onFolderPicked(uri: Uri) {
        inputUri = uri
        val root = DocumentFile.fromTreeUri(this, uri)
        inputName = root?.name ?: "Selected folder"
        inputDetail = "Counting files…"
        refreshSelectCard()
        popIcon()
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { collect(root).size }
            if (inputUri == uri) {
                inputDetail = if (n == 1) "1 file inside" else "$n files inside"
                refreshSelectCard()
            }
        }
    }

    private fun collect(
        dir: DocumentFile?,
        out: MutableList<Pair<DocumentFile, DocumentFile>> = mutableListOf()
    ): MutableList<Pair<DocumentFile, DocumentFile>> {
        if (dir == null) return out
        for (c in dir.listFiles()) {
            if (c.isDirectory) collect(c, out) else out.add(dir to c)
        }
        return out
    }

    // ---------- Run ----------

    private fun execute() {
        if (busy) return
        val uri = inputUri
        if (uri == null) {
            snack(if (isFolder) "Choose a folder first." else "Choose a file first.")
            return
        }
        val pw = b.etPassword.text?.toString().orEmpty()
        if (pw.isEmpty()) {
            b.tilPassword.error = "Enter a password"
            return
        }
        if (isEncrypt && pw != b.etConfirm.text?.toString().orEmpty()) {
            b.tilConfirm.error = "Passwords don't match"
            return
        }
        pendingPassword = pw.toCharArray()

        if (isFolder) runFolder(uri) else createOutput.launch(suggestedName())
    }

    private fun suggestedName(): String = when {
        isEncrypt -> "$inputName$EXT"
        inputName.endsWith(EXT) -> inputName.removeSuffix(EXT)
        else -> "$inputName.decrypted"
    }

    private fun friendly(e: Throwable) = e.message ?: e.javaClass.simpleName

    private fun runSingleFile(out: Uri) {
        val src = inputUri ?: return
        val pw = pendingPassword ?: return
        val encrypt = isEncrypt

        setBusy(true)
        b.progress.isIndeterminate = true
        b.tvLog.text = ""
        log(if (encrypt) "Encrypting $inputName…" else "Decrypting $inputName…")

        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    if (encrypt) CryptoEngine.encrypt(contentResolver, src, out, pw)
                    else CryptoEngine.decrypt(contentResolver, src, out, pw)
                    null
                } catch (e: Exception) {
                    runCatching { DocumentsContract.deleteDocument(contentResolver, out) }
                    e
                }
            }
            if (error == null) {
                log(if (encrypt) "Encrypted. Saved to the location you chose." else "Decrypted. Saved to the location you chose.", R.color.kaze_ok)
                snack(if (encrypt) "Encrypted" else "Decrypted")
            } else {
                log(friendly(error), R.color.kaze_err)
            }
            clearPassword()
            setBusy(false)
        }
    }

    private fun runFolder(treeUri: Uri) {
        val pw = pendingPassword ?: return
        val encrypt = isEncrypt

        setBusy(true)
        b.progress.isIndeterminate = true
        b.tvLog.text = ""
        log("Scanning folder…")

        lifecycleScope.launch {
            val root = DocumentFile.fromTreeUri(this@MainActivity, treeUri)
            val files = withContext(Dispatchers.IO) {
                collect(root).filter { (_, f) ->
                    val n = f.name.orEmpty()
                    if (encrypt) !n.endsWith(EXT) else n.endsWith(EXT)
                }
            }

            if (files.isEmpty()) {
                log(if (encrypt) "No files to encrypt in this folder." else "No encrypted files in this folder.", R.color.kaze_brass)
            } else {
                // Switch to determinate while hidden (required by the progress component)
                b.progress.isVisible = false
                b.progress.isIndeterminate = false
                b.progress.max = files.size
                b.progress.progress = 0
                b.progress.isVisible = true

                var ok = 0
                var failed = 0
                files.forEachIndexed { i, (parent, f) ->
                    val name = f.name.orEmpty()
                    val err = withContext(Dispatchers.IO) { processOne(parent, f, encrypt, pw) }
                    if (err == null) {
                        ok++
                        log((if (encrypt) "Encrypted: " else "Decrypted: ") + name)
                    } else {
                        failed++
                        log("Failed: $name. ${friendly(err)}", R.color.kaze_err)
                    }
                    b.progress.setProgressCompat(i + 1, true)
                }
                val verb = if (encrypt) "encrypted" else "decrypted"
                log("Finished: $ok $verb, $failed failed.", if (failed == 0) R.color.kaze_ok else R.color.kaze_brass)
                if (failed == 0) snack("All files $verb")
            }
            clearPassword()
            setBusy(false)
            b.scroll.post { b.scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** Writes the result next to the original. Returns null on success. */
    private fun processOne(parent: DocumentFile, f: DocumentFile, encrypt: Boolean, pw: CharArray): Throwable? {
        val name = f.name.orEmpty()
        val outName = if (encrypt) name + EXT else name.removeSuffix(EXT)
        val out = parent.createFile("application/octet-stream", outName)
            ?: return IOException("Couldn't create $outName.")
        return try {
            if (encrypt) CryptoEngine.encrypt(contentResolver, f.uri, out.uri, pw)
            else CryptoEngine.decrypt(contentResolver, f.uri, out.uri, pw)
            null
        } catch (e: Exception) {
            runCatching { out.delete() }
            e
        }
    }

    private companion object {
        const val EXT = ".encrypted"
    }
}
