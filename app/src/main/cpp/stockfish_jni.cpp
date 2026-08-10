// stockfish_jni.cpp — minimal JNI bridge to Stockfish SF18.
//
// Approach (intentionally small):
//   * Replace std::cin's streambuf with a blocking line queue.
//   * Replace std::cout's streambuf with a line splitter that forwards
//     every complete line to Kotlin via JNI upcall.
//   * Run UCIEngine::loop() on a worker thread.
//
// We do NOT reimplement Stockfish's UCI parser — we just feed it text
// and read its text back, exactly as if it were a child process.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <condition_variable>
#include <deque>
#include <iostream>
#include <memory>
#include <mutex>
#include <streambuf>
#include <string>
#include <thread>

#include "bitboard.h"
#include "position.h"
#include "tune.h"
#include "uci.h"

#define LOG_TAG "StockfishJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ---------- input: blocking line queue feeding std::cin ----------------------
class InputBuf : public std::streambuf {
public:
    void push(const std::string& line) {
        std::lock_guard<std::mutex> lk(m_);
        buffer_.append(line);
        buffer_.push_back('\n');
        setg(buffer_.data(), buffer_.data() + read_pos_, buffer_.data() + buffer_.size());
        cv_.notify_all();
    }
    void close() {
        std::lock_guard<std::mutex> lk(m_);
        closed_ = true;
        cv_.notify_all();
    }
protected:
    int_type underflow() override {
        std::unique_lock<std::mutex> lk(m_);
        cv_.wait(lk, [this]{ return read_pos_ < buffer_.size() || closed_; });
        if (read_pos_ >= buffer_.size()) return traits_type::eof();
        setg(buffer_.data(), buffer_.data() + read_pos_, buffer_.data() + buffer_.size());
        return traits_type::to_int_type(buffer_[read_pos_]);
    }
    int_type uflow() override {
        auto c = underflow();
        if (c != traits_type::eof()) { ++read_pos_; setg(buffer_.data(), buffer_.data() + read_pos_, buffer_.data() + buffer_.size()); }
        return c;
    }
private:
    std::mutex m_;
    std::condition_variable cv_;
    std::string buffer_;
    size_t read_pos_ = 0;
    bool closed_ = false;
};

// ---------- output: line splitter that calls into Java -----------------------
class OutputBuf : public std::streambuf {
public:
    using LineSink = std::function<void(const std::string&)>;
    explicit OutputBuf(LineSink sink) : sink_(std::move(sink)) {}
protected:
    int_type overflow(int_type ch) override {
        if (ch != traits_type::eof()) emit(static_cast<char>(ch));
        return ch;
    }
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        for (std::streamsize i = 0; i < n; ++i) emit(s[i]);
        return n;
    }
private:
    void emit(char c) {
        if (c == '\n') { sink_(line_); line_.clear(); }
        else if (c != '\r') line_.push_back(c);
    }
    LineSink sink_;
    std::string line_;
};

// ---------- engine handle ----------------------------------------------------
struct EngineCtx {
    JavaVM* jvm = nullptr;
    jclass  nativeClass = nullptr;          // global ref to StockfishNative
    jmethodID onLine = nullptr;

    std::unique_ptr<InputBuf>  in;
    std::unique_ptr<OutputBuf> out;
    std::streambuf* oldCin  = nullptr;
    std::streambuf* oldCout = nullptr;

    std::thread engineThread;
    std::atomic<bool> running{false};
};

EngineCtx* g_ctx = nullptr;   // single-engine app; SF18 isn't reentrant anyway

void postLineToJava(const std::string& line) {
    if (!g_ctx || !g_ctx->jvm || !g_ctx->nativeClass || !g_ctx->onLine) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_ctx->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_ctx->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    jstring js = env->NewStringUTF(line.c_str());
    env->CallStaticVoidMethod(g_ctx->nativeClass, g_ctx->onLine, js);
    env->DeleteLocalRef(js);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_ctx->jvm->DetachCurrentThread();
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_darksquare_blindfold_engine_SfishNative_nativeStart(JNIEnv* env, jclass /*clazz*/) {
    if (g_ctx && g_ctx->running.load()) return reinterpret_cast<jlong>(g_ctx);

    auto* ctx = new EngineCtx();
    env->GetJavaVM(&ctx->jvm);

    jclass local = env->FindClass("app/darksquare/blindfold/engine/SfishNative");
    if (!local) { LOGE("SfishNative class not found"); delete ctx; return 0; }
    ctx->nativeClass = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    ctx->onLine = env->GetStaticMethodID(ctx->nativeClass, "onEngineLine", "(Ljava/lang/String;)V");
    if (!ctx->onLine) { LOGE("onEngineLine not found"); delete ctx; return 0; }

    ctx->in  = std::make_unique<InputBuf>();
    ctx->out = std::make_unique<OutputBuf>(&postLineToJava);
    ctx->oldCin  = std::cin.rdbuf(ctx->in.get());
    ctx->oldCout = std::cout.rdbuf(ctx->out.get());

    g_ctx = ctx;
    ctx->running = true;

    ctx->engineThread = std::thread([ctx]{
        try {
            Stockfish::Bitboards::init();
            Stockfish::Position::init();
            int argc = 1;
            char arg0[] = "stockfish";
            char* argv[] = { arg0, nullptr };
            auto uci = std::make_unique<Stockfish::UCIEngine>(argc, argv);
            Stockfish::Tune::init(uci->engine_options());
            uci->loop();   // returns on "quit"
        } catch (const std::exception& e) {
            LOGE("engine thread crashed: %s", e.what());
        }
        ctx->running = false;
        postLineToJava("info string engine stopped");
    });

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_app_darksquare_blindfold_engine_SfishNative_nativeSend(
        JNIEnv* env, jclass /*clazz*/, jlong handle, jstring jcmd) {
    auto* ctx = reinterpret_cast<EngineCtx*>(handle);
    if (!ctx || !ctx->running.load()) return;
    const char* c = env->GetStringUTFChars(jcmd, nullptr);
    ctx->in->push(std::string(c));
    env->ReleaseStringUTFChars(jcmd, c);
}

JNIEXPORT void JNICALL
Java_app_darksquare_blindfold_engine_SfishNative_nativeStop(
        JNIEnv* env, jclass /*clazz*/, jlong handle) {
    auto* ctx = reinterpret_cast<EngineCtx*>(handle);
    if (!ctx) return;
    if (ctx->running.load()) {
        ctx->in->push("stop");
        ctx->in->push("quit");
    }
    ctx->in->close();
    if (ctx->engineThread.joinable()) ctx->engineThread.join();

    std::cin.rdbuf(ctx->oldCin);
    std::cout.rdbuf(ctx->oldCout);

    if (ctx->nativeClass) env->DeleteGlobalRef(ctx->nativeClass);
    if (g_ctx == ctx) g_ctx = nullptr;
    delete ctx;
}

} // extern "C"
