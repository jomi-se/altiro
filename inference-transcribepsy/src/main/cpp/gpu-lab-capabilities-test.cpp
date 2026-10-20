// Host-only fake ABI tests: no GPU execution or hardware capability claims.
// Compile with the pinned OpenCL-Headers include directory and -ldl.
#include <dlfcn.h>
#include <cstdlib>
#include <cassert>
#include <iostream>
#define CL_TARGET_OPENCL_VERSION 120
#include <CL/cl.h>
#include <CL/cl_ext.h>
#include <cstring>
#include <string>
namespace {
int live=0;
int mode=0;
int loaded=0;
bool driver_pending=false;
void process_reset(){live=0;loaded=0;driver_pending=false;}
void check_cleanup(){assert(live==(driver_pending?5:0));assert(loaded==(driver_pending?1:0));}
void* fake_open(const char* name,int) { assert(std::string(name)=="libOpenCL.so"); if(mode==1)return nullptr;++loaded;return reinterpret_cast<void*>(1); }
int fake_close(void*) {--loaded;return 0;}
cl_int CL_API_CALL platforms(cl_uint n,cl_platform_id* out,cl_uint* count) {assert(n==8);*count=mode==2?9:1;out[0]=reinterpret_cast<cl_platform_id>(1);return CL_SUCCESS;}
cl_int CL_API_CALL devices(cl_platform_id,cl_device_type type,cl_uint n,cl_device_id* out,cl_uint* count) {assert(type==CL_DEVICE_TYPE_GPU&&n==16);*count=mode==3?17:1;out[0]=reinterpret_cast<cl_device_id>(1);return CL_SUCCESS;}
cl_int CL_API_CALL info(cl_device_id,cl_device_info key,size_t n,void* out,size_t*) {
    switch(key) {
    case CL_DEVICE_TYPE: *static_cast<cl_device_type*>(out)=mode==4?CL_DEVICE_TYPE_CPU:CL_DEVICE_TYPE_GPU;break;
    case CL_DEVICE_AVAILABLE: case CL_DEVICE_COMPILER_AVAILABLE: *static_cast<cl_bool*>(out)=CL_TRUE;break;
    case CL_DEVICE_HALF_FP_CONFIG: *static_cast<cl_device_fp_config*>(out)=1;break;
    case CL_DEVICE_MAX_WORK_GROUP_SIZE: *static_cast<size_t*>(out)=256;break;
    case CL_DEVICE_LOCAL_MEM_SIZE: *static_cast<cl_ulong*>(out)=32768;break;
    default: { const char* text=key==CL_DEVICE_EXTENSIONS?"cl_khr_fp16 cl_khr_integer_dot_product cl_arm_matrix_multiply":"fake/name\"gpu"; assert(strlen(text)<n);strcpy(static_cast<char*>(out),text); }
    } return CL_SUCCESS;
}
cl_context CL_API_CALL context(const cl_context_properties*,cl_uint,const cl_device_id*,void(CL_CALLBACK*)(const char*,const void*,size_t,void*),void*,cl_int* e) {*e=0;++live;return reinterpret_cast<cl_context>(1);}
cl_command_queue CL_API_CALL queue(cl_context,cl_device_id,cl_command_queue_properties,cl_int* e) {*e=0;++live;return reinterpret_cast<cl_command_queue>(1);}
cl_mem CL_API_CALL buffer(cl_context,cl_mem_flags,size_t n,void*,cl_int* e) {assert(n==128);*e=0;++live;return reinterpret_cast<cl_mem>(1);}
cl_program CL_API_CALL program(cl_context,cl_uint,const char**,const size_t*,cl_int* e) {*e=0;++live;return reinterpret_cast<cl_program>(1);}
cl_int CL_API_CALL build(cl_program,cl_uint,const cl_device_id*,const char*,void(CL_CALLBACK*)(cl_program,void*),void*) {return mode==5?CL_BUILD_PROGRAM_FAILURE:CL_SUCCESS;}
cl_kernel CL_API_CALL kernel(cl_program,const char*,cl_int* e) {*e=0;++live;return reinterpret_cast<cl_kernel>(1);}
cl_int CL_API_CALL arg(cl_kernel,cl_uint,size_t,const void*) {return CL_SUCCESS;}
cl_int CL_API_CALL enqueue(cl_command_queue,cl_kernel,cl_uint,const size_t*,const size_t* n,const size_t*,cl_uint,const cl_event*,cl_event*) {assert(*n==32);if(mode==6)return CL_OUT_OF_RESOURCES;driver_pending=true;return CL_SUCCESS;}
cl_int CL_API_CALL finish(cl_command_queue) {if(mode==7)return CL_OUT_OF_RESOURCES;driver_pending=false;return CL_SUCCESS;}
cl_int CL_API_CALL read(cl_command_queue,cl_mem,cl_bool,size_t,size_t n,void* p,cl_uint,const cl_event*,cl_event*) {assert(n==128);driver_pending=true;if(mode==9)return CL_OUT_OF_RESOURCES;driver_pending=false;for(int i=0;i<32;++i)static_cast<float*>(p)[i]=float(i*2+1);if(mode==8)static_cast<float*>(p)[31]=0;return CL_SUCCESS;}
template<typename T> cl_int CL_API_CALL release(T) {--live;return CL_SUCCESS;}
void* fake_symbol(void*,const char* name) {
#define MAP(public_name,fn) if(strcmp(name,#public_name)==0)return reinterpret_cast<void*>(fn)
    MAP(clGetPlatformIDs,platforms);MAP(clGetDeviceIDs,devices);MAP(clGetDeviceInfo,info);
    MAP(clCreateContext,context);MAP(clCreateCommandQueue,queue);MAP(clCreateBuffer,buffer);
    MAP(clCreateProgramWithSource,program);MAP(clBuildProgram,build);MAP(clCreateKernel,kernel);
    MAP(clSetKernelArg,arg);MAP(clEnqueueNDRangeKernel,enqueue);MAP(clEnqueueReadBuffer,read);MAP(clFinish,finish);
    MAP(clReleaseMemObject,release<cl_mem>);MAP(clReleaseKernel,release<cl_kernel>);MAP(clReleaseProgram,release<cl_program>);
    MAP(clReleaseCommandQueue,release<cl_command_queue>);MAP(clReleaseContext,release<cl_context>);
#undef MAP
    return nullptr;
}
}
#define dlopen fake_open
#define dlclose fake_close
#define dlsym fake_symbol
#include "gpu-lab-capabilities.cpp"
#undef dlopen
#undef dlclose
#undef dlsym
int main() {
    const char* statuses[]={"verified","library_unavailable_in_app","platform_limit_exceeded","device_limit_exceeded","gpu_with_compiler_unavailable","compile_failed","enqueue_failed","finish_failed","verification_failed","read_failed"};
    for(mode=0;mode<10;++mode) {
        // Each invocation represents a fresh disposable worker process.
        process_reset();
        const auto json=altiro_gpu_capabilities(nullptr,nullptr);
        assert(json.find(std::string("\"status\":\"")+statuses[mode]+"\"")!=std::string::npos);
        assert(json.size()<8192); check_cleanup(); assert(json.find("fake/name")==std::string::npos);
        if(mode==0) {assert(json.find("\"verified\":true")!=std::string::npos);assert(json.find("\"integerDotExtension\":true")!=std::string::npos);}
    }
    // Exercise cancellation at each checkpoint, including after compile/enqueue/finish/read.
    mode=0;
    for(int threshold=0;threshold<15;++threshold) {
        process_reset();
        int remaining=threshold;
        altiro_gpu_capabilities([](void* p) {return --*static_cast<int*>(p)<0;},&remaining);
        check_cleanup();
    }
    std::cout << "OpenCL capability fake ABI tests passed\n";
}
