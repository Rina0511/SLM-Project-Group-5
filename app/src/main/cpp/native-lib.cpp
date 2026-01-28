#include "llama/llama.h"
#include <vector>
#include <jni.h>
#include <string>
#include <cstring>
#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <set>
#include <sstream>


#define LOG_TAG "SLM_NATIVE"

/*llama_batch make_batch(
        const std::vector<llama_token>& tokens,
        int n_ctx) {

    llama_batch batch = llama_batch_init(
            tokens.size(),
            0,
            n_ctx
    );

    for (int i = 0; i < tokens.size(); i++) {
        batch.token[i] = tokens[i];
        batch.pos[i]   = i;
        batch.seq_id[i][0] = 0;
        batch.n_seq_id[i]  = 1;
        batch.logits[i] = false;
    }

    batch.logits[tokens.size() - 1] = true;
    return batch;
}*/

/*static const std::set<std::string> ALLOWED_ALLERGENS = {
        "milk", "egg", "peanut", "tree nut",
        "wheat", "soy", "fish", "shellfish", "sesame"
};*/

std::string runModel(const std::string& model_path, const std::string& prompt) {

    // ================= Metrics =================
    auto t_start = std::chrono::high_resolution_clock::now();
    bool first_token_seen = false;

    long ttft_ms = -1;
    long itps = -1;
    long otps = -1;
    long oet_ms = -1;

    int generated_tokens = 0;

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "runModel() started");

    // ================= Backend =================
    llama_backend_init();

    // ================= Load model =================
    llama_model_params model_params = llama_model_default_params();


    llama_model* model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (!model) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "Failed to load model");
        return "";
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);

    // ================= Context =================
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 512;
    ctx_params.n_threads = 4;

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "Failed to create context");
        return "";
    }

    // ================= Tokenize prompt =================
    std::vector<llama_token> prompt_tokens(prompt.size() + 8);

    int n_prompt = llama_tokenize(
            vocab,
            prompt.c_str(),
            prompt.size(),
            prompt_tokens.data(),
            prompt_tokens.size(),
            true,   // add BOS
            false
    );

    if (n_prompt <= 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "Tokenization failed");
        return "";
    }

    prompt_tokens.resize(n_prompt);

    // ================= Initial batch (prompt) =================
    llama_batch batch = llama_batch_init(n_prompt, 0, ctx_params.n_ctx);
    batch.n_tokens = n_prompt;

    for (int i = 0; i < n_prompt; i++) {
        batch.token[i] = prompt_tokens[i];
        batch.pos[i]   = i;
        batch.seq_id[i][0] = 0;
        batch.n_seq_id[i]  = 1;
        batch.logits[i]    = false;
    }

    // 🔑 logits only on LAST prompt token
    batch.logits[n_prompt - 1] = true;

    // ================= Prefill =================
    auto t_prefill_start = std::chrono::high_resolution_clock::now();

    if (llama_decode(ctx, batch) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "Prompt decode failed");
        return "";
    }

    auto t_prefill_end = std::chrono::high_resolution_clock::now();
    long prefill_ms =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                    t_prefill_end - t_prefill_start
            ).count();

    if (prefill_ms > 0) {
        itps = (n_prompt * 1000L) / prefill_ms;
    }

    // ================= Sampler =================
    llama_sampler* sampler = llama_sampler_init_greedy();

    // ================= Generation =================
    std::string output;
    const int max_tokens = 8;


    int n_pos = 0;
    int n_predict = max_tokens;

    auto t_gen_start = std::chrono::high_resolution_clock::now();

    while (n_pos + batch.n_tokens < n_prompt + n_predict) {

        // ---- sample token (AFTER decode) ----
        llama_token token = llama_sampler_sample(sampler, ctx, -1);

        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        // ---- TTFT ----
        if (!first_token_seen) {
            auto t_first = std::chrono::high_resolution_clock::now();
            ttft_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                    t_first - t_start
            ).count();
            first_token_seen = true;
        }

        // ---- token → text ----
        char buf[128];
        int n = llama_token_to_piece(
                vocab, token, buf, sizeof(buf), 0, true);

        if (n > 0) {
            output.append(buf, n);

            // Rule 1: stop at first newline (ONLY comma-separated list)
            if (output.find('\n') != std::string::npos) {
                break;
            }
        }

        generated_tokens++;

        // ---- prepare next batch (REFERENCE CORRECT) ----
        batch = llama_batch_get_one(&token, 1);

        // ---- advance model ----
        if (llama_decode(ctx, batch) != 0) {
            break;
        }

        n_pos += batch.n_tokens;
    }

    auto t_gen_end = std::chrono::high_resolution_clock::now();
    long gen_ms =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                    t_gen_end - t_gen_start
            ).count();

    if (gen_ms > 0) {
        otps = (generated_tokens * 1000L) / gen_ms;
    }

    oet_ms = gen_ms;

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "Raw model output: %s", output.c_str());


    // allowed allergen set
    static const std::set<std::string> ALLOWED_ALLERGENS = {
            "milk","egg","peanut","tree nut",
            "wheat","soy","fish","shellfish","sesame"
    };

    // normalize to lowercase
    std::transform(output.begin(), output.end(), output.begin(), ::tolower);

    // split and filter
    std::stringstream ss(output);
    std::string item;
    std::vector<std::string> detected;

    while (std::getline(ss, item, ',')) {

        // trim whitespace
        item.erase(
                std::remove_if(item.begin(), item.end(), [](unsigned char c){
                    return std::isspace(c) || c=='.' || c=='!' || c=='?' || c==';' || c==':' || c=='"' || c=='\'' || c=='\n' || c=='\r';
                }),
                item.end()
        );



        if (ALLOWED_ALLERGENS.count(item)) {
            detected.push_back(item);
        }
    }

    // rebuild output
    if (detected.empty()) {
        output = "EMPTY";
    } else {
        output.clear();
        for (size_t i = 0; i < detected.size(); i++) {
            if (i > 0) output += ",";
            output += detected[i];
        }
    }

    __android_log_print(
            ANDROID_LOG_INFO,
            LOG_TAG,
            "Filtered output (Rule 2 applied): %s",
            output.c_str()
    );


    // ================= Cleanup =================
    llama_sampler_free(sampler);
    //llama_batch_free(batch);
    llama_free(ctx);
    llama_free_model(model);



    // ================= Return =================
    std::string result =
            "TTFT_MS=" + std::to_string(ttft_ms) +
            ";ITPS=" + std::to_string(itps) +
            ";OTPS=" + std::to_string(otps) +
            ";OET_MS=" + std::to_string(oet_ms) +
            "|" + output;

    return result;
}




extern "C"
JNIEXPORT jstring JNICALL
Java_edu_utem_ftmk_slm02_MainActivity_inferAllergens(
        JNIEnv *env,
        jobject /* this */,
        jstring jModelPath,
        jstring jPrompt) {

    const char *cModelPath = env->GetStringUTFChars(jModelPath, nullptr);
    const char *cPrompt    = env->GetStringUTFChars(jPrompt, nullptr);

    std::string modelPath(cModelPath);
    std::string prompt(cPrompt);

    env->ReleaseStringUTFChars(jModelPath, cModelPath);
    env->ReleaseStringUTFChars(jPrompt, cPrompt);

    // Make runModel accept modelPath
    std::string result = runModel(modelPath, prompt);

    return env->NewStringUTF(result.c_str());
}

