#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <mutex>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "common.h"
#include "chat.h"
#include "ggml-backend.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {
constexpr const char * TAG = "EyeLocalVLM";

struct Runtime {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    mtmd_context * vision = nullptr;
    common_chat_templates_ptr templates;

    ~Runtime() {
        templates.reset();
        if (vision) mtmd_free(vision);
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};

std::mutex runtimeMutex;
std::unique_ptr<Runtime> runtime;
std::atomic_bool cancelled{false};
std::once_flag backendInit;

void logCallback(ggml_log_level level, const char * text, void *) {
    if (!text) return;
    const int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR :
            level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        __android_log_write(priority, TAG, text);
    }
}

std::string fromJava(JNIEnv * env, jstring value) {
    if (!value) throw std::runtime_error("Missing model or prompt value.");
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("Could not read a Java string.");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throwJava(JNIEnv * env, const std::string & message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message.c_str());
}

struct BitmapOwner {
    mtmd_bitmap * bitmap = nullptr;
    mtmd_helper_video * video = nullptr;
    ~BitmapOwner() {
        if (bitmap) mtmd_bitmap_free(bitmap);
        if (video) mtmd_helper_video_free(video);
    }
};

std::string tokenText(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(256);
    int length = llama_token_to_piece(vocab, token, buffer.data(),
                                      static_cast<int32_t>(buffer.size()), 0, true);
    if (length < 0) {
        buffer.resize(static_cast<size_t>(-length));
        length = llama_token_to_piece(vocab, token, buffer.data(),
                                      static_cast<int32_t>(buffer.size()), 0, true);
    }
    if (length < 0) throw std::runtime_error("Could not decode generated token.");
    return std::string(buffer.data(), static_cast<size_t>(length));
}

std::vector<BitmapOwner> decodeImages(JNIEnv * env, jobjectArray frames) {
    const jsize count = frames ? env->GetArrayLength(frames) : 0;
    if (count <= 0 || count > 4) {
        throw std::runtime_error("Local vision inference accepts between one and four image frames.");
    }
    std::vector<BitmapOwner> images;
    images.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(frames, i));
        if (!bytes) throw std::runtime_error("An image frame could not be read.");
        const jsize length = env->GetArrayLength(bytes);
        if (length <= 0) {
            env->DeleteLocalRef(bytes);
            throw std::runtime_error("An image frame is empty.");
        }
        std::vector<unsigned char> buffer(static_cast<size_t>(length));
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(buffer.data()));
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck()) throw std::runtime_error("Could not copy image frame bytes.");

        BitmapOwner owner;
        auto wrapper = mtmd_helper_bitmap_init_from_buf(
                runtime->vision, buffer.data(), buffer.size(), false,
                mtmd_helper_init_opt_default());
        owner.bitmap = wrapper.bitmap;
        owner.video = wrapper.video_ctx;
        if (!owner.bitmap) throw std::runtime_error("llama.cpp could not decode an image frame.");
        images.push_back(std::move(owner));
    }
    return images;
}
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_amresalehin_emregallery_data_ai_NativeVisionEngine_loadModel(
        JNIEnv * env, jclass, jstring modelValue, jstring projectorValue,
        jint contextSize, jint threadCount) {
    std::lock_guard<std::mutex> guard(runtimeMutex);
    try {
        std::call_once(backendInit, [] {
            llama_log_set(logCallback, nullptr);
            llama_backend_init();
            ggml_backend_load_all();
        });

        const std::string modelPath = fromJava(env, modelValue);
        const std::string projectorPath = fromJava(env, projectorValue);
        if (modelPath.empty() || projectorPath.empty()) {
            throw std::runtime_error("Select both a GGUF model and its vision projector.");
        }

        auto next = std::make_unique<Runtime>();
        auto modelParams = llama_model_default_params();
        modelParams.n_gpu_layers = 0;
        next->model = llama_model_load_from_file(modelPath.c_str(), modelParams);
        if (!next->model) throw std::runtime_error("llama.cpp could not load this GGUF model.");

        auto contextParams = llama_context_default_params();
        contextParams.n_ctx = static_cast<uint32_t>(std::clamp(contextSize, 2048, 8192));
        contextParams.n_batch = 512;
        contextParams.n_ubatch = 512;
        contextParams.n_threads = std::clamp(threadCount, 1, 8);
        contextParams.n_threads_batch = contextParams.n_threads;
        next->context = llama_init_from_model(next->model, contextParams);
        if (!next->context) throw std::runtime_error("Could not allocate the model context.");

        auto visionParams = mtmd_context_params_default();
        visionParams.use_gpu = false;
        visionParams.print_timings = false;
        visionParams.n_threads = contextParams.n_threads;
        visionParams.warmup = false;
        next->vision = mtmd_init_from_file(projectorPath.c_str(), next->model, visionParams);
        if (!next->vision || !mtmd_support_vision(next->vision)) {
            throw std::runtime_error("The selected projector is incompatible with this model.");
        }

        next->templates = common_chat_templates_init(next->model, "");
        if (!next->templates) throw std::runtime_error("The model does not contain a supported chat template.");

        runtime = std::move(next);
        cancelled = false;
    } catch (const std::exception & error) {
        runtime.reset();
        throwJava(env, error.what());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_amresalehin_emregallery_data_ai_NativeVisionEngine_generate(
        JNIEnv * env, jclass, jobjectArray frameValues, jstring promptValue, jint maxTokens) {
    std::lock_guard<std::mutex> guard(runtimeMutex);
    try {
        if (!runtime || !runtime->model || !runtime->context || !runtime->vision) {
            throw std::runtime_error("Load an on-device vision model first.");
        }
        cancelled = false;
        llama_memory_clear(llama_get_memory(runtime->context), true);

        const std::string prompt = fromJava(env, promptValue);
        if (prompt.empty()) throw std::runtime_error("The image prompt is empty.");
        auto images = decodeImages(env, frameValues);

        const std::string marker = mtmd_get_marker(runtime->vision);
        common_chat_msg userMessage;
        userMessage.role = "user";
        for (size_t i = 0; i < images.size(); ++i) userMessage.content += marker + "\n";
        userMessage.content += prompt;

        const std::vector<common_chat_msg> history;
        const std::string formatted = common_chat_format_single(
                runtime->templates.get(), history, userMessage, true, true);

        std::vector<std::string> textSegments;
        std::vector<mtmd_input_text> textInputs;
        std::vector<mtmd_input_part> parts;
        textSegments.reserve(images.size() + 1);
        textInputs.reserve(images.size() + 1);
        parts.reserve(images.size() * 2 + 1);

        size_t cursor = 0;
        for (size_t i = 0; i < images.size(); ++i) {
            const size_t position = formatted.find(marker, cursor);
            if (position == std::string::npos) {
                throw std::runtime_error("The model chat template removed an image marker.");
            }
            textSegments.push_back(formatted.substr(cursor, position - cursor));
            textInputs.push_back({textSegments.back().data(), textSegments.back().size(), false, true});
            parts.push_back({&textInputs.back(), nullptr});
            parts.push_back({nullptr, images[i].bitmap});
            cursor = position + marker.size();
        }
        textSegments.push_back(formatted.substr(cursor));
        textInputs.push_back({textSegments.back().data(), textSegments.back().size(), false, true});
        parts.push_back({&textInputs.back(), nullptr});

        std::vector<const mtmd_input_part *> partPointers;
        partPointers.reserve(parts.size());
        for (const auto & part : parts) partPointers.push_back(&part);

        mtmd_input_chunks * rawChunks = mtmd_input_chunks_init();
        if (!rawChunks) throw std::runtime_error("Could not allocate multimodal input.");
        std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunks(rawChunks, mtmd_input_chunks_free);
        if (mtmd_tokenize_from_parts(runtime->vision, chunks.get(), partPointers.data(),
                                     partPointers.size(), true) != 0) {
            throw std::runtime_error("Could not tokenize the image and prompt.");
        }

        llama_pos nextPosition = 0;
        if (mtmd_helper_eval_chunks(runtime->vision, runtime->context, chunks.get(),
                                    0, 0, 512, true, &nextPosition) != 0) {
            throw std::runtime_error("Image evaluation failed. Try a smaller image or context.");
        }

        llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        if (!sampler) throw std::runtime_error("Could not initialize the token sampler.");
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.2f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        llama_batch batch = llama_batch_init(1, 0, 1);
        const llama_vocab * vocab = llama_model_get_vocab(runtime->model);
        std::string answer;
        const int limit = std::clamp(maxTokens, 64, 1024);
        for (int i = 0; i < limit && !cancelled; ++i) {
            const llama_token token = llama_sampler_sample(sampler, runtime->context, -1);
            llama_sampler_accept(sampler, token);
            if (llama_vocab_is_eog(vocab, token)) break;

            answer += tokenText(vocab, token);
            common_batch_clear(batch);
            common_batch_add(batch, token, nextPosition++, {0}, true);
            if (llama_decode(runtime->context, batch) != 0) {
                llama_batch_free(batch);
                llama_sampler_free(sampler);
                throw std::runtime_error("The model failed while generating output.");
            }
        }
        llama_batch_free(batch);
        llama_sampler_free(sampler);
        return env->NewStringUTF(answer.c_str());
    } catch (const std::exception & error) {
        throwJava(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_amresalehin_emregallery_data_ai_NativeVisionEngine_cancel(JNIEnv *, jclass) {
    cancelled = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_amresalehin_emregallery_data_ai_NativeVisionEngine_close(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(runtimeMutex);
    runtime.reset();
}
