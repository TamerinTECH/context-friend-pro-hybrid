#include "cf_native.h"
#include "sherpa-onnx/c-api/c-api.h"
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>
static char *speaker_result(const std::string &s) {auto p=(char *)std::malloc(s.size()+1);if(p)std::memcpy(p,s.c_str(),s.size()+1);return p;}
extern "C" char *cf_speakers(const char *segmentation,const char *embedding,const float *pcm,int count) {
    if(count<=0 || count>16000*60)return speaker_result("{\"error\":\"invalid_audio_window\"}");
    SherpaOnnxOfflineSpeakerDiarizationConfig config{};
    config.segmentation.pyannote.model=segmentation;config.segmentation.num_threads=2;config.segmentation.provider="cpu";
    config.embedding.model=embedding;config.embedding.num_threads=2;config.embedding.provider="cpu";
    config.clustering.num_clusters=-1;config.clustering.threshold=0.5f;config.min_duration_on=0.2f;config.min_duration_off=0.5f;
    auto sd=SherpaOnnxCreateOfflineSpeakerDiarization(&config);
    if(!sd)return speaker_result("{\"error\":\"speaker_model_load_failed\"}");
    auto result=SherpaOnnxOfflineSpeakerDiarizationProcess(sd,pcm,count);
    if(!result){SherpaOnnxDestroyOfflineSpeakerDiarization(sd);return speaker_result("{\"error\":\"speaker_processing_failed\"}");}
    auto turns=SherpaOnnxOfflineSpeakerDiarizationResultSortByStartTime(result);
    int n=SherpaOnnxOfflineSpeakerDiarizationResultGetNumSegments(result);
    int speakers=SherpaOnnxOfflineSpeakerDiarizationResultGetNumSpeakers(result);
    auto ex=SherpaOnnxCreateSpeakerEmbeddingExtractor(&config.embedding);
    if(!ex){SherpaOnnxOfflineSpeakerDiarizationDestroySegment(turns);SherpaOnnxOfflineSpeakerDiarizationDestroyResult(result);SherpaOnnxDestroyOfflineSpeakerDiarization(sd);return speaker_result("{\"error\":\"embedding_model_load_failed\"}");}
    std::string out="{\"turns\":[";
    for(int i=0;i<n;i++){if(i)out+=",";out+="{\"startMs\":"+std::to_string((int)(turns[i].start*1000))+",\"endMs\":"+std::to_string((int)(turns[i].end*1000))+",\"speaker\":"+std::to_string(turns[i].speaker)+"}";}
    out+="],\"vectors\":{";bool first=true;
    for(int s=0;s<speakers;s++) {
        std::vector<float> voice;
        for(int i=0;i<n && voice.size()<480000;i++)if(turns[i].speaker==s && turns[i].end-turns[i].start>=0.8f) {
            int start=std::clamp((int)(turns[i].start*16000),0,count),end=std::clamp((int)(turns[i].end*16000),0,count);
            end=std::min(end,start+(int)(480000-voice.size()));voice.insert(voice.end(),pcm+start,pcm+end);
        }
        if(voice.size()<16000)continue;
        auto stream=SherpaOnnxSpeakerEmbeddingExtractorCreateStream(ex);
        SherpaOnnxOnlineStreamAcceptWaveform(stream,16000,voice.data(),voice.size());SherpaOnnxOnlineStreamInputFinished(stream);
        if(SherpaOnnxSpeakerEmbeddingExtractorIsReady(ex,stream)) {
            auto v=SherpaOnnxSpeakerEmbeddingExtractorComputeEmbedding(ex,stream);
            if(v){if(!first)out+=",";first=false;out+="\""+std::to_string(s)+"\":[";
                for(int d=0;d<SherpaOnnxSpeakerEmbeddingExtractorDim(ex);d++){if(d)out+=",";out+=std::to_string(v[d]);}out+="]";
                SherpaOnnxSpeakerEmbeddingExtractorDestroyEmbedding(v);}
        }
        SherpaOnnxDestroyOnlineStream(stream);std::fill(voice.begin(),voice.end(),0);
    }
    out+="}}";SherpaOnnxDestroySpeakerEmbeddingExtractor(ex);SherpaOnnxOfflineSpeakerDiarizationDestroySegment(turns);
    SherpaOnnxOfflineSpeakerDiarizationDestroyResult(result);SherpaOnnxDestroyOfflineSpeakerDiarization(sd);return speaker_result(out);
}
