#include "gpu-lab-capabilities.h"
#define CL_TARGET_OPENCL_VERSION 120
#include <CL/cl.h>
#include <CL/cl_ext.h>
#include <array>
#include <chrono>
#include <cstring>
#include <dlfcn.h>
#include <sstream>
#include <algorithm>

namespace {
using Clock = std::chrono::steady_clock;
long long micros(Clock::time_point t) { return std::chrono::duration_cast<std::chrono::microseconds>(Clock::now()-t).count(); }
// All driver text is reduced to a small printable identifier alphabet, never logs or paths.
std::string identifier(const char* s, size_t n) {
    std::string out;
    for (size_t i=0; i<n && s[i] && out.size()<80; ++i) {
        const unsigned char c=s[i];
        out += ((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c==' '||c=='.'||c=='-'||c=='_'||c=='('||c==')') ? char(c) : '_';
    }
    return out;
}
bool extension(const char* text, const char* wanted) {
    const size_t n=std::strlen(wanted);
    for (const char* p=text; (p=std::strstr(p,wanted)); ++p)
        if ((p==text || p[-1]==' ') && (p[n]==' ' || p[n]=='\0')) return true;
    return false;
}
struct OpenCl {
    void* lib=nullptr;
    // Quarantine outstanding driver work until this disposable worker exits.
    bool pending=false;
#define FN(name) decltype(&::name) name=nullptr
    FN(clGetPlatformIDs); FN(clGetDeviceIDs); FN(clGetDeviceInfo);
    FN(clCreateContext); FN(clCreateCommandQueue); FN(clCreateBuffer);
    FN(clCreateProgramWithSource); FN(clBuildProgram); FN(clCreateKernel);
    FN(clSetKernelArg); FN(clEnqueueNDRangeKernel); FN(clEnqueueReadBuffer); FN(clFinish);
    FN(clReleaseMemObject); FN(clReleaseKernel); FN(clReleaseProgram);
    FN(clReleaseCommandQueue); FN(clReleaseContext);
#undef FN
    cl_context context=nullptr; cl_command_queue queue=nullptr;
    cl_mem buffer=nullptr; cl_program program=nullptr; cl_kernel kernel=nullptr;
    ~OpenCl() {
        if (pending) return;
        if (kernel) clReleaseKernel(kernel);
        if (program) clReleaseProgram(program);
        if (buffer) clReleaseMemObject(buffer);
        if (queue) clReleaseCommandQueue(queue);
        if (context) clReleaseContext(context);
        if (lib) dlclose(lib);
    }
    bool symbols() {
#define LOAD(name) name=reinterpret_cast<decltype(name)>(dlsym(lib,#name)); if (!name) return false
        LOAD(clGetPlatformIDs); LOAD(clGetDeviceIDs); LOAD(clGetDeviceInfo);
        LOAD(clCreateContext); LOAD(clCreateCommandQueue); LOAD(clCreateBuffer);
        LOAD(clCreateProgramWithSource); LOAD(clBuildProgram); LOAD(clCreateKernel);
        LOAD(clSetKernelArg); LOAD(clEnqueueNDRangeKernel); LOAD(clEnqueueReadBuffer); LOAD(clFinish);
        LOAD(clReleaseMemObject); LOAD(clReleaseKernel); LOAD(clReleaseProgram);
        LOAD(clReleaseCommandQueue); LOAD(clReleaseContext);
#undef LOAD
        return true;
    }
};
}

std::string altiro_opencl_capabilities(bool (*cancelled)(void*), void* opaque) {
    OpenCl cl;
    const auto stop=[&] { return cancelled && cancelled(opaque); };
    std::ostringstream fields;
    fields << "\"api\":\"opencl\"";
    auto result=[&](const char* reason, bool verified=false) {
        return "{"+fields.str()+",\"status\":\""+reason+"\",\"verified\":"+(verified?"true":"false")+"}";
    };
    if (stop()) return result("cancelled");
    // Android's ordinary app linker namespace applies. No vendor/private path fallback.
    cl.lib=dlopen("libOpenCL.so",RTLD_NOW|RTLD_LOCAL);
    if (!cl.lib) return result("library_unavailable_in_app");
    if (!cl.symbols()) return result("symbols_unavailable");
    std::array<cl_platform_id,8> platforms{};
    cl_uint count=0;
    if (cl.clGetPlatformIDs(platforms.size(),platforms.data(),&count)!=CL_SUCCESS) return result("platform_query_failed");
    if (count>platforms.size()) return result("platform_limit_exceeded");
    cl_device_id selected=nullptr;
    for (cl_uint p=0;p<count&&!selected;++p) {
        if(stop()) return result("cancelled");
        std::array<cl_device_id,16> devices{}; cl_uint n=0;
        const auto err=cl.clGetDeviceIDs(platforms[p],CL_DEVICE_TYPE_GPU,devices.size(),devices.data(),&n);
        if(err==CL_DEVICE_NOT_FOUND) continue;
        if(err!=CL_SUCCESS) return result("device_query_failed");
        if(n>devices.size()) return result("device_limit_exceeded");
        for(cl_uint d=0;d<n;++d) {
            cl_device_type type=0; cl_bool available=CL_FALSE, compiler=CL_FALSE;
            if(cl.clGetDeviceInfo(devices[d],CL_DEVICE_TYPE,sizeof(type),&type,nullptr)!=CL_SUCCESS ||
               cl.clGetDeviceInfo(devices[d],CL_DEVICE_AVAILABLE,sizeof(available),&available,nullptr)!=CL_SUCCESS ||
               cl.clGetDeviceInfo(devices[d],CL_DEVICE_COMPILER_AVAILABLE,sizeof(compiler),&compiler,nullptr)!=CL_SUCCESS) continue;
            if((type&CL_DEVICE_TYPE_GPU) && !(type&CL_DEVICE_TYPE_CPU) && available && compiler) { selected=devices[d]; break; }
        }
    }
    if(!selected) return result("gpu_with_compiler_unavailable");
    fields << ",\"gpuSelected\":true";
    for(auto item : {std::pair<cl_device_info,const char*>{CL_DEVICE_NAME,"deviceName"}, {CL_DEVICE_VENDOR,"deviceVendor"}, {CL_DRIVER_VERSION,"driverVersion"}}) {
        std::array<char,256> text{};
        if(cl.clGetDeviceInfo(selected,item.first,text.size()-1,text.data(),nullptr)==CL_SUCCESS)
            fields << ",\"" << item.second << "\":\"" << identifier(text.data(),text.size()) << "\"";
    }
    std::array<char,16384> extensions{};
    const bool ext_known=cl.clGetDeviceInfo(selected,CL_DEVICE_EXTENSIONS,extensions.size()-1,extensions.data(),nullptr)==CL_SUCCESS;
    fields << ",\"extensionsKnown\":" << (ext_known?"true":"false");
    if(ext_known) {
        for(auto item : {std::pair<const char*,const char*>{"cl_khr_fp16","fp16Extension"}, {"cl_khr_integer_dot_product","integerDotExtension"}, {"cl_arm_matrix_multiply","matrixMultiplyExtension"}})
            fields << ",\"" << item.second << "\":" << (extension(extensions.data(),item.first)?"true":"false");
    }
    cl_device_fp_config half=0;
    const bool half_known=cl.clGetDeviceInfo(selected,CL_DEVICE_HALF_FP_CONFIG,sizeof(half),&half,nullptr)==CL_SUCCESS;
    fields << ",\"fp16ConfigKnown\":" << (half_known?"true":"false");
    if(half_known) fields << ",\"fp16Arithmetic\":" << (half?"true":"false");
    size_t group=0; cl_ulong local=0;
    if(cl.clGetDeviceInfo(selected,CL_DEVICE_MAX_WORK_GROUP_SIZE,sizeof(group),&group,nullptr)==CL_SUCCESS)
        fields << ",\"maxWorkGroupSize\":" << std::min<size_t>(group,2147483647);
    if(cl.clGetDeviceInfo(selected,CL_DEVICE_LOCAL_MEM_SIZE,sizeof(local),&local,nullptr)==CL_SUCCESS)
        fields << ",\"localMemoryBytes\":" << std::min<cl_ulong>(local,2147483647);
    if(stop()) return result("cancelled");
    cl_int err=CL_SUCCESS;
    cl.context=cl.clCreateContext(nullptr,1,&selected,nullptr,nullptr,&err);
    if(!cl.context||err!=CL_SUCCESS) return result("context_failed");
    cl.queue=cl.clCreateCommandQueue(cl.context,selected,0,&err);
    if(!cl.queue||err!=CL_SUCCESS) return result("queue_failed");
    std::array<float,32> values{};
    cl.buffer=cl.clCreateBuffer(cl.context,CL_MEM_READ_WRITE|CL_MEM_COPY_HOST_PTR,sizeof(values),values.data(),&err);
    if(!cl.buffer||err!=CL_SUCCESS) return result("buffer_failed");
    const char* source="__kernel void probe(__global float* out) { size_t i=get_global_id(0); out[i]=(float)i*2.0f+1.0f; }";
    cl.program=cl.clCreateProgramWithSource(cl.context,1,&source,nullptr,&err);
    if(!cl.program||err!=CL_SUCCESS) return result("program_failed");
    if(stop()) return result("cancelled");
    auto start=Clock::now();
    err=cl.clBuildProgram(cl.program,1,&selected,"",nullptr,nullptr);
    fields << ",\"compileUs\":" << micros(start);
    if(stop()) return result("cancelled");
    if(err!=CL_SUCCESS) return result("compile_failed");
    cl.kernel=cl.clCreateKernel(cl.program,"probe",&err);
    if(!cl.kernel||err!=CL_SUCCESS) return result("kernel_failed");
    if(cl.clSetKernelArg(cl.kernel,0,sizeof(cl.buffer),&cl.buffer)!=CL_SUCCESS) return result("argument_failed");
    if(stop()) return result("cancelled");
    const size_t global=values.size();
    start=Clock::now();
    if(cl.clEnqueueNDRangeKernel(cl.queue,cl.kernel,1,nullptr,&global,nullptr,0,nullptr,nullptr)!=CL_SUCCESS) return result("enqueue_failed");
    cl.pending=true;
    if(stop()) return result("cancelled");
    err=cl.clFinish(cl.queue);
    if(err==CL_SUCCESS) cl.pending=false;
    fields << ",\"executeUs\":" << micros(start);
    if(stop()) return result("cancelled");
    if(err!=CL_SUCCESS) return result("finish_failed");
    cl.pending=true;
    err=cl.clEnqueueReadBuffer(cl.queue,cl.buffer,CL_TRUE,0,sizeof(values),values.data(),0,nullptr,nullptr);
    if(err==CL_SUCCESS) cl.pending=false;
    if(stop()) return result("cancelled");
    if(err!=CL_SUCCESS) return result("read_failed");
    for(size_t i=0;i<values.size();++i) if(values[i]!=float(i*2+1)) return result("verification_failed");
    return result("verified",true);
}

#ifdef ALTIRO_VULKAN
std::string altiro_vulkan_integer_capabilities(VkPhysicalDevice device, uint32_t instance_api_version) {
    VkPhysicalDeviceProperties base{};
    vkGetPhysicalDeviceProperties(device,&base);
    std::ostringstream out;
    out << "{\"api\":\"vulkan\",\"deviceName\":\"" << identifier(base.deviceName,sizeof(base.deviceName)) << "\"";
    if(instance_api_version<VK_API_VERSION_1_1) return out.str()+",\"status\":\"properties2_unavailable\"}";
    uint32_t count=0;
    if(vkEnumerateDeviceExtensionProperties(device,nullptr,&count,nullptr)!=VK_SUCCESS || count>512)
        return out.str()+",\"status\":\"extension_query_failed\"}";
    std::array<VkExtensionProperties,512> extensions{};
    if(vkEnumerateDeviceExtensionProperties(device,nullptr,&count,extensions.data())!=VK_SUCCESS || count>extensions.size())
        return out.str()+",\"status\":\"extension_query_failed\"}";
    auto has=[&](const char* name) { for(uint32_t i=0;i<count;++i) if(std::strcmp(extensions[i].extensionName,name)==0) return true; return false; };
    const bool int8_query=base.apiVersion>=VK_API_VERSION_1_2 || has(VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME);
    const bool dot_query=base.apiVersion>=VK_API_VERSION_1_3 || has(VK_KHR_SHADER_INTEGER_DOT_PRODUCT_EXTENSION_NAME);
    VkPhysicalDeviceShaderFloat16Int8Features int8{}; int8.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES;
    VkPhysicalDeviceShaderIntegerDotProductFeatures dot{}; dot.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_INTEGER_DOT_PRODUCT_FEATURES;
    VkPhysicalDeviceFeatures2 features{}; features.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    if(int8_query) { int8.pNext=features.pNext; features.pNext=&int8; }
    if(dot_query) { dot.pNext=features.pNext; features.pNext=&dot; }
    vkGetPhysicalDeviceFeatures2(device,&features);
    out << ",\"int8QueryAvailable\":" << (int8_query?"true":"false") << ",\"dotQueryAvailable\":" << (dot_query?"true":"false");
    if(int8_query) out << ",\"shaderInt8\":" << (int8.shaderInt8?"true":"false") << ",\"shaderFloat16\":" << (int8.shaderFloat16?"true":"false");
    if(dot_query) {
        out << ",\"shaderIntegerDotProduct\":" << (dot.shaderIntegerDotProduct?"true":"false");
        VkPhysicalDeviceShaderIntegerDotProductProperties dotprops{}; dotprops.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_INTEGER_DOT_PRODUCT_PROPERTIES;
        VkPhysicalDeviceProperties2 props{}; props.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2; props.pNext=&dotprops;
        vkGetPhysicalDeviceProperties2(device,&props);
#define PROP(name) out << ",\"" #name "\":" << (dotprops.name?"true":"false")
        PROP(integerDotProduct8BitSignedAccelerated);
        PROP(integerDotProduct8BitUnsignedAccelerated);
        PROP(integerDotProduct8BitMixedSignednessAccelerated);
        PROP(integerDotProduct4x8BitPackedSignedAccelerated);
        PROP(integerDotProduct4x8BitPackedUnsignedAccelerated);
        PROP(integerDotProduct4x8BitPackedMixedSignednessAccelerated);
        PROP(integerDotProductAccumulatingSaturating4x8BitPackedSignedAccelerated);
#undef PROP
    }
    out << ",\"status\":\"queried\"}";
    return out.str();
}
namespace {
std::string probe_vulkan(bool (*cancelled)(void*),void* opaque) {
    auto stop=[&] {return cancelled&&cancelled(opaque);};
    auto failure=[](const char* reason) {return std::string("{\"api\":\"vulkan\",\"status\":\"")+reason+"\"}";};
    if(stop()) return failure("cancelled");
    uint32_t version=VK_API_VERSION_1_0;
    const auto enumerate=reinterpret_cast<PFN_vkEnumerateInstanceVersion>(vkGetInstanceProcAddr(nullptr,"vkEnumerateInstanceVersion"));
    if(enumerate && enumerate(&version)!=VK_SUCCESS) return failure("version_query_failed");
    if(version<VK_API_VERSION_1_1) return failure("properties2_unavailable");
    // Request only 1.1; promoted structures are queried according to physical API or extension availability.
    VkApplicationInfo app{}; app.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO; app.apiVersion=VK_API_VERSION_1_1;
    VkInstanceCreateInfo info{}; info.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO; info.pApplicationInfo=&app;
    struct Instance { VkInstance value=VK_NULL_HANDLE; ~Instance(){if(value)vkDestroyInstance(value,nullptr);} } instance;
    if(vkCreateInstance(&info,nullptr,&instance.value)!=VK_SUCCESS) return failure("instance_failed");
    if(stop()) return failure("cancelled");
    std::array<VkPhysicalDevice,16> devices{}; uint32_t count=devices.size();
    if(vkEnumeratePhysicalDevices(instance.value,&count,devices.data())!=VK_SUCCESS || count>devices.size()) return failure("device_query_failed");
    for(uint32_t i=0;i<count;++i) {
        if(stop()) return failure("cancelled");
        VkPhysicalDeviceProperties properties{}; vkGetPhysicalDeviceProperties(devices[i],&properties);
        if(properties.deviceType==VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU || properties.deviceType==VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
            auto result=altiro_vulkan_integer_capabilities(devices[i],app.apiVersion);
            return stop()?failure("cancelled"):result;
        }
    }
    return failure("gpu_unavailable");
}
}
#endif
std::string altiro_gpu_capabilities(bool (*cancelled)(void*),void* opaque) {
    const std::string opencl=altiro_opencl_capabilities(cancelled,opaque);
#ifdef ALTIRO_VULKAN
    const std::string vulkan=probe_vulkan(cancelled,opaque);
#else
    const std::string vulkan="{\"api\":\"vulkan\",\"status\":\"not_built\"}";
#endif
    return "{\"opencl\":"+opencl+",\"vulkan\":"+vulkan+"}";
}
