#include <jni.h>
#include "cf_native.h"
#include <string>
#include <vector>
static std::string utf(JNIEnv *e,jstring s) {
    const char *p=e->GetStringUTFChars(s,nullptr); std::string r(p); e->ReleaseStringUTFChars(s,p); return r;
}
static jbyteArray result(JNIEnv *e,char *s) {
    if(!s) return nullptr;
    size_t len=std::char_traits<char>::length(s); auto b=e->NewByteArray(len);
    e->SetByteArrayRegion(b,0,len,reinterpret_cast<jbyte *>(s)); cf_free(s);return b;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_tech_tamerin_contextfriendpro_NativeEngine_transcribe(JNIEnv *e,jobject,jstring path,jfloatArray samples,jstring lang) {
    auto p=utf(e,path),l=utf(e,lang); int n=e->GetArrayLength(samples);
    std::vector<float> pcm(n);e->GetFloatArrayRegion(samples,0,n,pcm.data());
    return result(e,cf_transcribe(p.c_str(),pcm.data(),n,l.c_str()));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_tech_tamerin_contextfriendpro_NativeEngine_summarize(JNIEnv *e,jobject,jstring path,jbyteArray prompt) {
    auto p=utf(e,path); int n=e->GetArrayLength(prompt);std::string text(n,'\0');
    e->GetByteArrayRegion(prompt,0,n,reinterpret_cast<jbyte *>(text.data()));
    return result(e,cf_summarize(p.c_str(),text.c_str(),4096));
}
extern "C" JNIEXPORT void JNICALL Java_tech_tamerin_contextfriendpro_NativeEngine_cancel(JNIEnv *,jobject) {cf_cancel();}
extern "C" JNIEXPORT void JNICALL Java_tech_tamerin_contextfriendpro_NativeEngine_reset(JNIEnv *,jobject) {cf_reset_cancel();}
