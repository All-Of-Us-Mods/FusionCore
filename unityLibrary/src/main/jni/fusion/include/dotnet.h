// Copyright (c) 2026 XtraCube. All rights reserved.
#ifndef FUSIONCORE_DOTNET_H
#define FUSIONCORE_DOTNET_H

#include <string>

#define HR_INTERNAL_ERROR 0x8007054F

using entrypoint_fn = void(*)();

struct DotNetConfig
{
    std::string runtimeDir;
    std::string managedLibsDir;
    std::string entryPointAssembly;
    std::string entryPointType;
    std::string entryPointMethod;
};

int dotnet_execute_assembly(const DotNetConfig& config);

#endif //FUSIONCORE_DOTNET_H
