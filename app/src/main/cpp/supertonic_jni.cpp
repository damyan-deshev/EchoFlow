#include <jni.h>

#include <MNN/expr/Executor.hpp>
#include <MNN/expr/ExecutorScope.hpp>
#include <MNN/expr/ExprCreator.hpp>
#include <MNN/expr/Module.hpp>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

using MNN::Express::Executor;
using MNN::Express::ExecutorScope;
using MNN::Express::Module;
using MNN::Express::VARP;
using MNN::Express::_Input;

namespace {

constexpr int kSampleRate = 44100;
constexpr int kBaseChunkSize = 512;
constexpr int kChunkCompressFactor = 6;
constexpr int kLatentDim = 24 * kChunkCompressFactor;

std::string to_string(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("Could not read Java string");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throw_java(JNIEnv* env, const std::string& message) {
    jclass error = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(error, message.c_str());
}

void put_u16(std::vector<uint8_t>& out, size_t offset, uint16_t value) {
    out[offset] = static_cast<uint8_t>(value & 0xff);
    out[offset + 1] = static_cast<uint8_t>((value >> 8) & 0xff);
}

void put_u32(std::vector<uint8_t>& out, size_t offset, uint32_t value) {
    put_u16(out, offset, static_cast<uint16_t>(value & 0xffff));
    put_u16(out, offset + 2, static_cast<uint16_t>((value >> 16) & 0xffff));
}

std::vector<uint8_t> make_wav(const std::vector<float>& audio) {
    const uint32_t data_bytes = static_cast<uint32_t>(audio.size() * sizeof(int16_t));
    std::vector<uint8_t> wav(44 + data_bytes);
    std::memcpy(wav.data(), "RIFF", 4);
    put_u32(wav, 4, 36 + data_bytes);
    std::memcpy(wav.data() + 8, "WAVEfmt ", 8);
    put_u32(wav, 16, 16);
    put_u16(wav, 20, 1);
    put_u16(wav, 22, 1);
    put_u32(wav, 24, kSampleRate);
    put_u32(wav, 28, kSampleRate * sizeof(int16_t));
    put_u16(wav, 32, sizeof(int16_t));
    put_u16(wav, 34, 16);
    std::memcpy(wav.data() + 36, "data", 4);
    put_u32(wav, 40, data_bytes);
    auto* pcm = reinterpret_cast<int16_t*>(wav.data() + 44);
    for (size_t i = 0; i < audio.size(); ++i) {
        const float sample = std::clamp(audio[i], -1.0f, 1.0f);
        pcm[i] = static_cast<int16_t>(std::lrint(sample * 32767.0f));
    }
    return wav;
}

class Engine {
public:
    Engine(const std::string& model_dir, const std::string& cache_path) {
        MNN::BackendConfig backend;
        backend.precision = MNN::BackendConfig::Precision_Low;
        backend.memory = MNN::BackendConfig::Memory_Low;

        executor_ = Executor::newExecutor(MNN_FORWARD_OPENCL, backend, 4);
        if (!executor_) throw std::runtime_error("Could not create MNN OpenCL executor");
        ExecutorScope scope(executor_);

        MNN::ScheduleConfig schedule;
        schedule.type = MNN_FORWARD_OPENCL;
        schedule.backupType = MNN_FORWARD_OPENCL;
        schedule.numThread = 4;
        schedule.backendConfig = &backend;
        runtime_.reset(Executor::RuntimeManager::createRuntimeManager(schedule),
                       Executor::RuntimeManager::destroy);
        if (!runtime_) throw std::runtime_error("Could not create MNN OpenCL runtime");
        runtime_->setCache(cache_path);

        fp16_dir_ = model_dir + "/mnn_models/fp16/";
    }

    std::vector<uint8_t> synthesize(
            const std::vector<int>& text_ids,
            const std::vector<float>& text_mask,
            const std::vector<float>& style_ttl,
            int ttl_dim0,
            int ttl_dim1,
            const std::vector<float>& style_dp,
            int dp_dim0,
            int dp_dim1,
            int steps,
            float speed,
            int64_t seed) {
        ExecutorScope scope(executor_);
        const int text_len = static_cast<int>(text_ids.size());
        if (text_len == 0 || text_mask.size() != text_ids.size()) {
            throw std::runtime_error("Invalid tokenized text");
        }

        // MNN Express modules retain concrete input shapes after a forward pass. Recreate these
        // cheap graph wrappers for every variable-sized utterance while retaining the expensive
        // OpenCL runtime and compiled-kernel cache for the entire playlist.
        auto duration = load(fp16_dir_ + "duration_predictor.mnn",
                             {"text_ids", "style_dp", "text_mask"}, {"duration"});
        auto text_encoder = load(fp16_dir_ + "text_encoder.mnn",
                                 {"text_ids", "style_ttl", "text_mask"}, {"text_emb"});
        auto vector_estimator = load(
            fp16_dir_ + "vector_estimator.mnn",
            {"noisy_latent", "text_emb", "style_ttl", "latent_mask", "text_mask",
             "current_step", "total_step"},
            {"denoised_latent"});
        auto vocoder = load(fp16_dir_ + "vocoder.mnn", {"latent"}, {"wav_tts"});

        auto text_ids_var = input_int({1, text_len}, text_ids);
        auto text_mask_var = input_float({1, 1, text_len}, text_mask);
        auto style_dp_var = input_float({1, dp_dim0, dp_dim1}, style_dp);
        auto style_ttl_var = input_float({1, ttl_dim0, ttl_dim1}, style_ttl);

        auto duration_outputs = duration->onForward({text_ids_var, style_dp_var, text_mask_var});
        if (duration_outputs.empty()) throw std::runtime_error("Duration predictor returned no output");
        const float duration_seconds = duration_outputs[0]->readMap<float>()[0] / speed;
        if (!std::isfinite(duration_seconds) || duration_seconds <= 0.0f) {
            throw std::runtime_error("Duration predictor returned an invalid duration");
        }

        auto text_outputs = text_encoder->onForward({text_ids_var, style_ttl_var, text_mask_var});
        if (text_outputs.empty()) throw std::runtime_error("Text encoder returned no output");
        auto text_emb = text_outputs[0];

        const int chunk_size = kBaseChunkSize * kChunkCompressFactor;
        const int wav_len = static_cast<int>(duration_seconds * kSampleRate);
        const int latent_len = std::max(1, (wav_len + chunk_size - 1) / chunk_size);
        std::vector<float> latent(kLatentDim * latent_len);
        std::mt19937 rng(static_cast<uint32_t>(seed));
        std::normal_distribution<float> normal(0.0f, 1.0f);
        for (float& value : latent) value = normal(rng);

        std::vector<float> latent_mask(latent_len, 1.0f);
        auto latent_mask_var = input_float({1, 1, latent_len}, latent_mask);
        auto total_step_var = input_float({1}, {static_cast<float>(steps)});

        for (int step = 0; step < steps; ++step) {
            auto latent_var = input_float({1, kLatentDim, latent_len}, latent);
            auto current_step_var = input_float({1}, {static_cast<float>(step)});
            auto outputs = vector_estimator->onForward(
                {latent_var, text_emb, style_ttl_var, latent_mask_var, text_mask_var,
                 current_step_var, total_step_var});
            if (outputs.empty()) throw std::runtime_error("Vector estimator returned no output");
            copy_output(outputs[0], latent);
        }

        auto latent_var = input_float({1, kLatentDim, latent_len}, latent);
        auto audio_outputs = vocoder->onForward({latent_var});
        if (audio_outputs.empty()) throw std::runtime_error("Vocoder returned no output");
        std::vector<float> audio;
        copy_output(audio_outputs[0], audio);
        return make_wav(audio);
    }

    void save_cache() {
        runtime_->updateCache();
    }

private:
    using ModulePtr = std::shared_ptr<Module>;

    ModulePtr load(const std::string& path,
                   const std::vector<std::string>& inputs,
                   const std::vector<std::string>& outputs) {
        Module* raw = Module::load(inputs, outputs, path.c_str(), runtime_);
        if (!raw) throw std::runtime_error("Could not load MNN model: " + path);
        return ModulePtr(raw, Module::destroy);
    }

    static VARP input_float(const std::vector<int>& shape, const std::vector<float>& values) {
        auto input = _Input(shape, MNN::Express::NCHW, halide_type_of<float>());
        if (input->getInfo()->size != values.size()) throw std::runtime_error("Float tensor shape mismatch");
        std::memcpy(input->writeMap<float>(), values.data(), values.size() * sizeof(float));
        return input;
    }

    static VARP input_int(const std::vector<int>& shape, const std::vector<int>& values) {
        auto input = _Input(shape, MNN::Express::NCHW, halide_type_of<int>());
        if (input->getInfo()->size != values.size()) throw std::runtime_error("Integer tensor shape mismatch");
        std::memcpy(input->writeMap<int>(), values.data(), values.size() * sizeof(int));
        return input;
    }

    static void copy_output(const VARP& output, std::vector<float>& values) {
        const int size = output->getInfo()->size;
        values.resize(size);
        std::memcpy(values.data(), output->readMap<float>(), size * sizeof(float));
    }

    std::shared_ptr<Executor> executor_;
    std::shared_ptr<Executor::RuntimeManager> runtime_;
    std::string fp16_dir_;
};

template <typename T>
std::vector<T> copy_array(JNIEnv* env, jintArray array);

template <>
std::vector<int> copy_array<int>(JNIEnv* env, jintArray array) {
    const jsize size = env->GetArrayLength(array);
    std::vector<int> values(size);
    env->GetIntArrayRegion(array, 0, size, reinterpret_cast<jint*>(values.data()));
    return values;
}

std::vector<float> copy_float_array(JNIEnv* env, jfloatArray array) {
    const jsize size = env->GetArrayLength(array);
    std::vector<float> values(size);
    env->GetFloatArrayRegion(array, 0, size, values.data());
    return values;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_echoflow_data_LocalSupertonicNative_nativeCreate(
        JNIEnv* env, jobject, jstring model_dir, jstring cache_path) {
    try {
        return reinterpret_cast<jlong>(
            new Engine(to_string(env, model_dir), to_string(env, cache_path)));
    } catch (const std::exception& error) {
        throw_java(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_echoflow_data_LocalSupertonicNative_nativeSynthesize(
        JNIEnv* env,
        jobject,
        jlong handle,
        jintArray text_ids,
        jfloatArray text_mask,
        jfloatArray style_ttl,
        jint ttl_dim0,
        jint ttl_dim1,
        jfloatArray style_dp,
        jint dp_dim0,
        jint dp_dim1,
        jint steps,
        jfloat speed,
        jlong seed) {
    try {
        auto* engine = reinterpret_cast<Engine*>(handle);
        if (!engine) throw std::runtime_error("Supertonic engine is closed");
        auto wav = engine->synthesize(
            copy_array<int>(env, text_ids),
            copy_float_array(env, text_mask),
            copy_float_array(env, style_ttl), ttl_dim0, ttl_dim1,
            copy_float_array(env, style_dp), dp_dim0, dp_dim1,
            std::clamp(static_cast<int>(steps), 1, 100),
            std::clamp(static_cast<float>(speed), 0.7f, 2.0f),
            static_cast<int64_t>(seed));
        jbyteArray result = env->NewByteArray(static_cast<jsize>(wav.size()));
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(wav.size()),
                                reinterpret_cast<const jbyte*>(wav.data()));
        return result;
    } catch (const std::exception& error) {
        throw_java(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_echoflow_data_LocalSupertonicNative_nativeCloseAndSaveCache(
        JNIEnv* env, jobject, jlong handle) {
    try {
        std::unique_ptr<Engine> engine(reinterpret_cast<Engine*>(handle));
        if (engine) engine->save_cache();
    } catch (const std::exception& error) {
        throw_java(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_echoflow_data_LocalSupertonicNative_nativeClose(
        JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Engine*>(handle);
}
