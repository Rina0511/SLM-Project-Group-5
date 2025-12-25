package edu.utem.ftmk.slm02

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * BITP 3453 Mobile Application Development
 *
 * @author Emaliana Kasmuri, FTMK, UTeM
 *
 * Purpose:
 * A simple activity demonstrating use of a native library.
 */
class MainActivity : AppCompatActivity() {


    companion object {

        // Load primary native library for JNI functions, core GGML tensor librarym CPU specific
        // implementation and library LLaMa model interaction
        init {
            System.loadLibrary("native-lib")
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml-cpu")
            System.loadLibrary("ggml")
            System.loadLibrary("llama")
        }
    }

    // Native function declaration
    external fun echoFromNative(input: String): String


    /**
     * Main entry point to the application
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Native function invocation
        val result = echoFromNative("hello")
        Log.i("JNI_TEST", result)

    }

    /*private fun copyModelIfNeeded(context: Context) {
        val modelName = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        val outFile = File(context.filesDir, modelName)

        if (outFile.exists()) return

        context.assets.open(modelName).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }*/
}
