// Copyright (c) 2026 XtraCube. All rights reserved.

#ifndef FUSIONCORE_TOOLS_H
#define FUSIONCORE_TOOLS_H

#include <dlfcn.h>

const char *safe_dlerror() {
    char *dlErr = dlerror();
    const char *err = dlErr ? dlErr : "Unknown error";
    return err;
}

#endif //FUSIONCORE_TOOLS_H
