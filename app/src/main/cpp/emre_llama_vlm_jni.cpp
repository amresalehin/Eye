#include <jni.h>
#include <android/log.h>

#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "EmreLlamaVlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlamaVlmSession {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    mtmd_context * mtmd = nullptr;
    int n_threads = 4;
    std::mutex mutex;

    ~LlamaVlmSession() {
        if (mtmd != nullptr) {
            mtmd_free(mtmd);
            mtmd = nullptr;
        }
        if (ctx != nullptr) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model != nullptr) {
            llama_model_free(model);
            model = nullptr;
        }
    }
};

std::string jstringToStd(JNIEnv * env, jstring jstr) {
    if (jstr == nullptr) return "";
    const char * chars = env->GetStringUTFChars(jstr, nullptr);
    if (chars == nullptr) return "";
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_amresalehin_emregallery_ai_LlamaCppNativeBridge_nativeInit(
    JNIEnv * env,
    jobject /* thiz */,
    jstring jModelPath,
    jstring jMmprojPath,
    jint nCtx,
    jint nThreads
) {
    static std::once_flag backendOnce;
    std::call_once(backendOnce, []() {
        llama_backend_init();
    });

    const std::string modelPath = jstringToStd(env, jModelPath);
    const std::string mmprojPath = jstringToStd(env, jMmprojPath);

    auto * session = new LlamaVlmSession();
    session->n_threads = nThreads > 0 ? nThreads : 4;

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;
    mparams.n_gpu_layers = 0; // Safe CPU default across all Android arm64 devices

    session->model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (session->model == nullptr) {
        LOGE("Failed to load GGUF model: %s", modelPath.c_str());
        delete session;
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx > 512 ? static_cast<uint32_t>(nCtx) : 2048u;
    cparams.n_batch = 512;
    cparams.n_threads = session->n_threads;
    cparams.n_threads_batch = session->n_threads;

    session->ctx = llama_init_from_model(session->model, cparams);
    if (session->ctx == nullptr) {
        LOGE("Failed to create llama_context");
        delete session;
        return 0L;
    }

    mtmd_context_params mtmdParams = mtmd_context_params_default();
    mtmdParams.use_gpu = false;
    mtmdParams.n_threads = session->n_threads;
    mtmdParams.print_timings = false;

    session->mtmd = mtmd_init_from_file(mmprojPath.c_str(), session->model, mtmdParams);
    if (session->mtmd == nullptr) {
        LOGE("Failed to load mmproj GGUF: %s", mmprojPath.c_str());
        delete session;
        return 0L;
    }

    LOGI("Initialized llama.cpp + mtmd VLM session (ctx=%u, threads=%d)", cparams.n_ctx, session->n_threads);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jstring JNICALL
Java_com_amresalehin_emregallery_ai_LlamaCppNativeBridge_nativeAnalyzeImage(
    JNIEnv * env,
    jobject /* thiz */,
    jlong handle,
    jbyteArray jImageBytes,
    jstring jPrompt,
    jint maxTokens
) {
    if (handle == 0L || jImageBytes == nullptr || jPrompt == nullptr) {
        return env->NewStringUTF("ERROR: Invalid native VLM arguments");
    }

    auto * session = reinterpret_cast<LlamaVlmSession *>(handle);
    std::lock_guard<std::mutex> lock(session->mutex);

    const std::string userPrompt = jstringToStd(env, jPrompt);
    const jsize imgLen = env->GetArrayLength(jImageBytes);
    std::vector<unsigned char> imgBuf(static_cast<size_t>(imgLen));
    env->GetByteArrayRegion(jImageBytes, 0, imgLen, reinterpret_cast<jbyte *>(imgBuf.data()));

    // Clear KV cache for a fresh single-image turn
    llama_memory_clear(llama_get_memory(session->ctx), true);

    mtmd_bitmap * bitmap = mtmd_helper_bitmap_init_from_buf(session->mtmd, imgBuf.data(), imgBuf.size());
    if (bitmap == nullptr) {
        return env->NewStringUTF("ERROR: Failed to decode image buffer in libmtmd");
    }

    const char * marker = mtmd_default_marker();
    std::string fullPrompt = std::string(marker) + "\n" + userPrompt + "\n";

    // Apply model's embedded chat template if available
    const char * tmpl = llama_model_chat_template(session->model, nullptr);
    if (tmpl != nullptr && std::strlen(tmpl) > 0) {
        llama_chat_message msg{};
        msg.role = "user";
        msg.content = fullPrompt.c_str();
        std::vector<char> formatted(fullPrompt.size() * 2 + 512);
        int32_t res = llama_chat_apply_template(tmpl, &msg, 1, true, formatted.data(), static_cast<int32_t>(formatted.size()));
        if (res > 0) {
            if (static_cast<size_t>(res) > formatted.size()) {
                formatted.resize(static_cast<size_t>(res) + 1);
                res = llama_chat_apply_template(tmpl, &msg, 1, true, formatted.data(), static_cast<int32_t>(formatted.size()));
            }
            if (res > 0) {
                fullPrompt.assign(formatted.data(), static_cast<size_t>(res));
            }
        }
    }

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    mtmd_input_text inputText{};
    inputText.text = fullPrompt.c_str();
    inputText.add_special = true;
    inputText.parse_special = true;

    const mtmd_bitmap * bitmaps[1] = { bitmap };
    const int32_t tokRes = mtmd_tokenize(session->mtmd, chunks, &inputText, bitmaps, 1);
    mtmd_bitmap_free(bitmap);

    if (tokRes != 0) {
        mtmd_input_chunks_free(chunks);
        return env->NewStringUTF("ERROR: mtmd_tokenize failed");
    }

    llama_pos nPast = 0;
    const int32_t evalRes = mtmd_helper_eval_chunks(
        session->mtmd,
        session->ctx,
        chunks,
        nPast,
        0,
        512,
        true,
        &nPast
    );
    mtmd_input_chunks_free(chunks);

    if (evalRes != 0) {
        return env->NewStringUTF("ERROR: mtmd_helper_eval_chunks failed");
    }

    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

    std::string generated;
    const int limit = maxTokens > 0 ? maxTokens : 256;
    llama_batch batch = llama_batch_init(1, 0, 1);

    for (int i = 0; i < limit; ++i) {
        const llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }
        char piece[256];
        const int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (n > 0) {
            generated.append(piece, static_cast<size_t>(n));
        }

        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = nPast++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        if (llama_decode(session->ctx, batch) != 0) {
            break;
        }
    }

    llama_batch_free(batch);
    llama_sampler_free(sampler);

    return env->NewStringUTF(generated.c_str());
}

JNIEXPORT void JNICALL
Java_com_amresalehin_emregallery_ai_LlamaCppNativeBridge_nativeRelease(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle
) {
    if (handle != 0L) {
        auto * session = reinterpret_cast<LlamaVlmSession *>(handle);
        delete session;
    }
}

} // extern "C"
