#include "cf_native.h"
#include <cstdio>
#include <fstream>
#include <sstream>
int main(int argc,char **argv) {
    if(argc!=3)return 2;
    std::ifstream input(argv[2]);std::stringstream prompt;prompt<<input.rdbuf();
    cf_reset_cancel();char *result=cf_summarize(argv[1],prompt.str().c_str(),4096);
    if(!result)return 3;std::puts(result);cf_free(result);return 0;
}
