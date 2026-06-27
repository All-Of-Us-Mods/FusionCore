// Copyright (c) 2026 XtraCube. All rights reserved.
#include <dotnet.h>
#include <external/coreclrhost.h>
#include <filesystem>
#include <logger.h>
#include <thread>

#define TAG "Fusion.NET"

namespace fs = std::filesystem;

void build_tpa(const char *directory, std::string &tpaList);

int dotnet_execute_assembly(const DotNetConfig& config)
{
    log(LogLevel::INFO, TAG, "Preparing CLR properties");

#define NUM_KEYS 3

    const char *propertyKeys[NUM_KEYS] = {
            "TRUSTED_PLATFORM_ASSEMBLIES",
            "APP_PATHS",
            "APP_CONTEXT_BASE_DIRECTORY"
    };

    std::string appPaths = (config.runtimeDir + ":" + config.managedLibsDir);

    std::string tpaPath;
    build_tpa(config.managedLibsDir.c_str(), tpaPath);
    build_tpa(config.runtimeDir.c_str(), tpaPath);

    log_format(LogLevel::DEBUG, TAG, "TPA Path: {}", tpaPath);

    const char *propertyValues[NUM_KEYS] = {
            tpaPath.c_str(),
            appPaths.c_str(),
            config.managedLibsDir.c_str()
    };

    setenv("DOTNET_ReadyToRun", "0", 1);

    log(LogLevel::INFO, TAG, "Attempting CoreCLR initialization with W^X disabled");
    // Attempt without W^X first
    setenv("DOTNET_EnableWriteXorExecute", "0", 1);

    int hr = -1;
    void *hostHandle = nullptr;
    unsigned int domainId = 0;

    for (int attempt = 1; attempt <= 2; ++attempt)
    {
        hostHandle = nullptr;
        domainId = 0;
        hr = coreclr_initialize(
                config.runtimeDir.c_str(),            // AppDomain base path
                "FusionHost",                      // AppDomain friendly name
                sizeof(propertyKeys) / sizeof(char *),// Property count
                propertyKeys,                         // Property names
                propertyValues,                       // Property values
                &hostHandle,                          // Host handle
                &domainId);                           // AppDomain ID

        log_format(LogLevel::INFO, TAG,
                          "coreclr_initialize attempt {} -> hr={} ({:#010x}), hostHandle={}, domainId={}",
                          attempt,
                          hr,
                          static_cast<uint32_t>(hr),
                          hostHandle,
                          domainId);

        if (hr >= 0)
        {
            break;
        }

        if (attempt == 1 && hr == HR_INTERNAL_ERROR)
        {
            // Retry with W^X enabled
            setenv("DOTNET_EnableWriteXorExecute", "1", 1);
            log(LogLevel::WARN, TAG, "Retrying CoreCLR init with W^X enabled");
            std::this_thread::sleep_for(std::chrono::milliseconds(75));
            continue;
        }

        break;
    }

    if (hr >= 0) {
        log_format(LogLevel::INFO, TAG, "CoreCLR started; AppDomain {} created", domainId);
    } else {
        log_format(LogLevel::ERROR, TAG, "coreclr_initialize failed - status: {} ({:#010x})",
                          hr, static_cast<uint32_t>(hr));
        return hr;
    }

    entrypoint_fn managedDelegate;

    log_format(LogLevel::INFO, TAG, "Creating delegate for {}.{} in {}",
                      config.entryPointType, config.entryPointMethod, config.entryPointAssembly);

    hr = coreclr_create_delegate(
            hostHandle,
            domainId,
            config.entryPointAssembly.c_str(),
            config.entryPointType.c_str(),
            config.entryPointMethod.c_str(),
            (void**)&managedDelegate);

    if (hr >= 0)
    {
        log(LogLevel::INFO, TAG, "Managed delegate created");
    }
    else
    {
        log_format(LogLevel::ERROR, TAG, "coreclr_create_delegate failed - status: {}", hr);
        return hr;
    }

    managedDelegate();

    log(LogLevel::INFO, TAG, "Executed delegate!");
    return 0;
}

void build_tpa(const char *directory, std::string &tpaList)
{
    std::error_code ec;
    fs::path dir(directory);

    if (!fs::exists(dir, ec))
    {
        if (ec)
        {
            log_format(LogLevel::ERROR, TAG, "Error checking existence of {}: {}", directory, ec
                    .message());
        }
        else
        {
            log_format(LogLevel::WARN, TAG, "TPA directory does not exist: {}", directory);
        }
        return;
    }

    if (!fs::is_directory(dir, ec))
    {
        if (ec)
        {
            log_format(LogLevel::ERROR, TAG, "Error checking if {} is a directory: {}", directory, ec.message());
        }
        else
        {
            log_format(LogLevel::WARN, TAG, "TPA path is not a directory: {}", directory);
        }
        return;
    }

    std::vector<std::string> dllPaths;
    for (const auto& entry : fs::directory_iterator(dir, ec)) {
        if (ec)
        {
            log_format(LogLevel::ERROR, TAG, "Error iterating directory {}: {}", directory, ec.message());
            break;
        }

        if (!entry.is_regular_file())
        {
            continue;
        }

        const fs::path& p = entry.path();

        if (p.extension() == ".dll") {
            dllPaths.emplace_back(p.string());
        }
    }

    std::sort(dllPaths.begin(), dllPaths.end());

    for (const auto &dllPath : dllPaths)
    {
        if (!tpaList.empty())
        {
            tpaList.append(":");
        }
        tpaList.append(dllPath);
    }
}