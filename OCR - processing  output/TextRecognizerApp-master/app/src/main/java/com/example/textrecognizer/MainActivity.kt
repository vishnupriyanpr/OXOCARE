package com.oxocare.textrecognizer

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.util.Linkify
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.github.dhaval2404.imagepicker.ImagePicker
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.oxocare.textrecognizer.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recognizer: TextRecognizer
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var camera: Camera
    private var savedBitmap: Bitmap? = null

    private val TAG = "TextRecognizer"
    private val SAVED_TEXT_TAG = "SavedText"
    private val REQUEST_CODE_PERMISSIONS = 10
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        restoreState(savedInstanceState)
        setupButtons()
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        savedInstanceState?.let {
            val savedText = it.getString(SAVED_TEXT_TAG)
            binding.apply {
                if (isTextValid(savedText)) {
                    textInImageLayout.visibility = View.VISIBLE
                    textInImage.text = savedText
                }
                savedBitmap?.let { bmp ->
                    previewImage.visibility = View.VISIBLE
                    previewImage.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun setupButtons() {
        binding.apply {
            extractTextButton.setOnClickListener {
                val bmp = when {
                    previewImage.visibility == View.VISIBLE -> previewImage.drawable.toBitmap()
                    viewFinder.bitmap != null -> viewFinder.bitmap
                    else -> null
                }
                bmp?.let {
                    previewImage.setImageBitmap(it)
                    savedBitmap = it
                    runTextRecognition(it)
                } ?: showToast(getString(R.string.camera_error_default_msg))
            }

            copyToClipboard.setOnClickListener {
                val text = textInImage.text.toString()
                if (isTextValid(text)) copyToClipboard(text)
                else showToast(getString(R.string.no_text_found))
            }

            share.setOnClickListener {
                val text = textInImage.text.toString()
                if (isTextValid(text)) shareText(text)
                else showToast(getString(R.string.no_text_found))
            }

            close.setOnClickListener {
                textInImageLayout.visibility = View.GONE
            }
        }
    }

    private fun runTextRecognition(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { text ->
                binding.textInImageLayout.visibility = View.VISIBLE
                processTextRecognitionResult(text)
            }
            .addOnFailureListener {
                showToast(it.localizedMessage ?: getString(R.string.error_default_msg))
            }
    }

    private fun processTextRecognitionResult(result: Text) {
        val lines = result.text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val patientInfo = mutableListOf<String>()
        val testRows = mutableListOf<Triple<String, String?, String?>>()
        val labInfo = mutableListOf<String>()
        val other = mutableListOf<String>()

        val patientKeywords = listOf("name", "mr.", "age", "gender", "reported", "revised", "referred by", "ref by")
        val labKeywords = listOf("dr", "lab", "path", "sector", "rohini", "tel", "fax", "email", "web", "address", "reference", "status")

        for (line in lines) {
            val lower = line.lowercase()
            when {
                patientKeywords.any { lower.contains(it) } -> patientInfo.add(line)
                labKeywords.any { lower.contains(it) } -> labInfo.add(line)
                Regex("[a-zA-Z]+[\\s:]+[\\d.]+[\\s]*(mg/dl|g/dl|iu/l|%)?").containsMatchIn(lower) -> {
                    val parts = line.split(":", " ", "\t").filter { it.isNotBlank() }
                    if (parts.size >= 2) {
                        val test = parts[0].trim()
                        val value = parts[1].trim()
                        val ref = parts.getOrNull(2)?.trim()
                        testRows.add(Triple(test, value, ref))
                    } else other.add(line)
                }
                else -> other.add(line)
            }
        }

        val formattedText = StringBuilder()
        formattedText.appendLine("========== 🧾 Structured Report Output 🧾 ==========\n")

        if (patientInfo.isNotEmpty()) {
            formattedText.appendLine("🧍‍♂️ Patient Information:")
            patientInfo.forEach { formattedText.appendLine("- $it") }
            formattedText.appendLine()
        }

        if (testRows.isNotEmpty()) {
            formattedText.appendLine("🧪 Test Results:")
            formattedText.appendLine(String.format("%-30s %-15s %-20s", "Test", "Value", "Reference Range"))
            formattedText.appendLine("--------------------------------------------------------------")
            testRows.forEach {
                formattedText.appendLine(String.format("%-30s %-15s %-20s", it.first, it.second ?: "", it.third ?: ""))
            }
            formattedText.appendLine()
        }

        if (labInfo.isNotEmpty()) {
            formattedText.appendLine("🏥 Lab Details:")
            labInfo.forEach { formattedText.appendLine("- $it") }
            formattedText.appendLine()
        }

        if (other.isNotEmpty()) {
            formattedText.appendLine("📄 Additional Notes:")
            other.forEach { formattedText.appendLine("- $it") }
            formattedText.appendLine()
        }

        formattedText.appendLine("===================================================")

        val finalText = formattedText.toString().trim()
        Log.d(TAG, finalText)

        binding.textInImage.text = if (finalText.isNotEmpty()) finalText else getString(R.string.no_text_found)
        Linkify.addLinks(binding.textInImage, Linkify.ALL)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                showToast("Failed to start camera: ${exc.localizedMessage}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
        )
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            startCamera()
        } else {
            showToast(getString(R.string.permission_denied_msg))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.gallery -> {
                binding.textInImageLayout.visibility = View.GONE
                ImagePicker.with(this)
                    .galleryOnly()
                    .crop()
                    .compress(1024)
                    .maxResultSize(1080, 1080)
                    .start()
                true
            }
            R.id.camera -> {
                if (!allPermissionsGranted()) requestPermissions()
                else {
                    binding.previewImage.visibility = View.GONE
                    savedBitmap = null
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            binding.previewImage.apply {
                visibility = View.VISIBLE
                setImageURI(uri)
                savedBitmap = drawable.toBitmap()
                runTextRecognition(savedBitmap!!)
            }
        } else if (resultCode == ImagePicker.RESULT_ERROR) {
            showToast(ImagePicker.getError(data))
        } else {
            showToast("No Image Selected")
        }
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_text_title)))
    }

    private fun copyToClipboard(text: CharSequence) {
        val clipboard = ContextCompat.getSystemService(applicationContext, ClipboardManager::class.java)
        val clip = ClipData.newPlainText("label", text)
        clipboard?.setPrimaryClip(clip)
        showToast(getString(R.string.clipboard_text))
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun isTextValid(text: String?): Boolean {
        return !text.isNullOrEmpty() && text != getString(R.string.no_text_found)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val text = binding.textInImage.text.toString()
        if (isTextValid(text)) outState.putString(SAVED_TEXT_TAG, text)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}