#include "cf_native.h"
#include "whisper.h"
#include "llama.h"
#include "summary_grammar.h"
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>

static std::atomic<bool> cancelled(false);
static std::mutex inference_mutex;
static bool abort_inference(void *) { return cancelled.load(); }
static void quiet(ggml_log_level, const char *, void *) {}
static char *copy(const std::string &s) {
    auto p = static_cast<char *>(std::malloc(s.size()+1));
    if (p) std::memcpy(p,s.c_str(),s.size()+1);
    return p;
}
static std::string escaped(const char *s) {
    std::string out="\"";
    for (const unsigned char *p=(const unsigned char *)s;*p;p++) {
        switch (*p) {
            case '\"': out+="\\\""; break;
            case '\\': out+="\\\\"; break;
            case '\n': out+="\\n"; break;
            case '\r': out+="\\r"; break;
            case '\t': out+="\\t"; break;
            default: if (*p>=32) out+=static_cast<char>(*p);
        }
    }
    return out+"\"";
}
extern "C" void cf_cancel() { cancelled=true; }
extern "C" void cf_reset_cancel() { cancelled=false; }
extern "C" void cf_free(char *p) { std::free(p); }
extern "C" char *cf_transcribe(const char *path,const float *pcm,int count,const char *language) {
    std::lock_guard<std::mutex> lock(inference_mutex);
    whisper_log_set(quiet,nullptr);
    if (count<=0 || count>16000*65) return copy("{\"error\":\"invalid_audio_window\"}");
    if(std::all_of(pcm,pcm+count,[](float v){return v==0.0f;})) return copy("{\"segments\":[],\"language\":\"auto\"}");
    auto cp=whisper_context_default_params(); cp.use_gpu=false;
    auto ctx=std::unique_ptr<whisper_context,decltype(&whisper_free)>(whisper_init_from_file_with_params(path,cp),whisper_free);
    if (!ctx) return copy("{\"error\":\"model_load_failed\"}");
    auto p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads=4; p.translate=false; p.no_context=true;
    p.language=std::strcmp(language,"auto")==0?nullptr:language;
    p.detect_language=false; p.print_progress=false; p.print_realtime=false;
    p.print_timestamps=false; p.print_special=false;
    p.abort_callback=abort_inference; p.abort_callback_user_data=nullptr;
    if (whisper_full(ctx.get(),p,pcm,count)!=0 || cancelled)
        return copy("{\"error\":\"transcription_interrupted\"}");
    std::string out="{\"segments\":[";
    for (int i=0;i<whisper_full_n_segments(ctx.get());i++) {
        if(i) out+=",";
        out+="{\"startMs\":"+std::to_string(whisper_full_get_segment_t0(ctx.get(),i)*10)+
             ",\"endMs\":"+std::to_string(whisper_full_get_segment_t1(ctx.get(),i)*10)+
             ",\"text\":"+escaped(whisper_full_get_segment_text(ctx.get(),i))+"}";
    }
    out+="],\"language\":"+escaped(whisper_lang_str(whisper_full_lang_id(ctx.get())))+"}";
    return copy(out);
}
extern "C" char *cf_summarize(const char *path,const char *prompt,int context_size) {
    std::lock_guard<std::mutex> lock(inference_mutex);
    llama_log_set(quiet,nullptr); llama_backend_init();
    if(context_size<1024 || context_size>8192 || std::strlen(prompt)>100000)
        return copy("{\"error\":\"context_limit\"}");
    auto mp=llama_model_default_params(); mp.n_gpu_layers=0;
    auto model=std::unique_ptr<llama_model,decltype(&llama_model_free)>(llama_model_load_from_file(path,mp),llama_model_free);
    if(!model) return copy("{\"error\":\"model_load_failed\"}");
    const auto vocab=llama_model_get_vocab(model.get());
    char architecture[64]{};llama_model_meta_val_str(model.get(),"general.architecture",architecture,sizeof(architecture));
    std::string format;
    if(std::strcmp(architecture,"gemma4")==0) {
        // Exact single-user, no-tools, enable_thinking=false branch of the pinned
        // Google canonical template embedded in this GGUF (2026-07-09).
        // Escape '<' in content so transcript text cannot inject special tokens.
        std::string safe;for(const char *p=prompt;*p;p++){if(*p=='<')safe+="\\u003c";else safe+=*p;}
        format="<|turn>user\n"+safe+"<turn|>\n<|turn>model\n";
    } else {
        return copy("{\"error\":\"unsupported_model_architecture\"}");
    }
    const int size=(int)format.size();std::vector<char> formatted(format.begin(),format.end());formatted.push_back(0);
    int n=-llama_tokenize(vocab,formatted.data(),size,nullptr,0,true,true);
    if(n<=0 || n>context_size-1024) return copy("{\"error\":\"input_too_long\"}");
    std::vector<llama_token> tokens(n);
    llama_tokenize(vocab,formatted.data(),size,tokens.data(),n,true,true);
    auto cp=llama_context_default_params(); cp.n_ctx=context_size; cp.n_batch=256;
    cp.n_ubatch=128; cp.n_threads=4; cp.n_threads_batch=4;
    cp.abort_callback=abort_inference; cp.abort_callback_data=nullptr;
    auto ctx=std::unique_ptr<llama_context,decltype(&llama_free)>(llama_init_from_model(model.get(),cp),llama_free);
    if(!ctx) return copy("{\"error\":\"context_allocation_failed\"}");
    auto batch=std::unique_ptr<llama_batch_ext,decltype(&llama_batch_ext_free)>(llama_batch_ext_init(ctx.get()),llama_batch_ext_free);
    auto sampler=std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)>(llama_sampler_chain_init(llama_sampler_chain_default_params()),llama_sampler_free);
    auto grammar=llama_sampler_init_grammar(vocab,summary_grammar,"root");
    if(!grammar) return copy("{\"error\":\"grammar_initialization_failed\"}");
    llama_sampler_chain_add(sampler.get(),grammar);
    llama_sampler_chain_add(sampler.get(),llama_sampler_init_greedy());
    auto decode=[&](const llama_token *t,int count,int pos) {
        llama_batch_ext_clear(batch.get());
        for(int i=0;i<count;i++) {
            int idx=llama_batch_ext_add_token(batch.get(),0,t[i]);
            llama_pos p=pos+i; llama_batch_ext_set_pos(batch.get(),idx,&p);
        }
        llama_batch_ext_set_output_logits(batch.get(),count-1,true);
        return llama_process(ctx.get(),LLAMA_PROCESS_TYPE_DECODE,batch.get());
    };
    for(int offset=0;offset<n;offset+=256) {
        if(cancelled || decode(tokens.data()+offset,std::min(256,n-offset),offset))
            return copy("{\"error\":\"inference_interrupted\"}");
    }
    std::string out;
    // No output-token cap. Stop at EOS; exhausting the bounded context is a failure.
    for(int pos=n;pos<context_size;pos++) {
        if(cancelled) return copy("{\"error\":\"cancelled\"}");
        auto t=llama_sampler_sample(sampler.get(),ctx.get(),-1);
        if(llama_vocab_is_eog(vocab,t)) return copy(out);
        std::vector<char> piece(256);
        int length=llama_token_to_piece(vocab,t,piece.data(),piece.size(),0,false);
        if(length<0) {piece.resize(-length);length=llama_token_to_piece(vocab,t,piece.data(),piece.size(),0,false);}
        if(length<0) return copy("{\"error\":\"token_decode_failed\"}");
        out.append(piece.data(),length);
        if(decode(&t,1,pos)) return copy("{\"error\":\"inference_interrupted\"}");
    }
    return copy("{\"error\":\"output_exhausted_context\"}");
}
