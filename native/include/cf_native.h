#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
// All strings are owned by the caller via cf_free. No content logging.
void cf_cancel(void);
void cf_reset_cancel(void);
char *cf_transcribe(const char *path, const float *pcm, int count, const char *language);
char *cf_summarize(const char *path, const char *prompt, int context_size);
char *cf_speakers(const char *segmentation, const char *embedding, const float *pcm, int count);
void cf_free(char *value);
#ifdef __cplusplus
}
#endif
