#include "acl-kernel.h"
#include "encoder-lab.h"
#include "kernel-backend.h"
#include "run-control.h"
#include "ggml.h"
#define CL_TARGET_OPENCL_VERSION 120
#include <CL/cl.h>
#include <CL/cl_ext.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <ctime>
#include <dlfcn.h>
#include <memory>
#include <sstream>
#include <vector>
#ifdef ALTIRO_VULKAN
#include <vulkan/vulkan.h>
#include "acl-kernels.inc.h"
namespace {
using Clock=std::chrono::steady_clock;
double elapsed(Clock::time_point t){return std::chrono::duration<double,std::milli>(Clock::now()-t).count();}
double process_ms(){timespec t{};clock_gettime(CLOCK_PROCESS_CPUTIME_ID,&t);return t.tv_sec*1000.0+t.tv_nsec/1e6;}
struct Error {const char* code;};
void check(cl_int status,const char* code){if(status!=CL_SUCCESS)throw Error{code};}
std::string label(const char* text){std::string out;for(size_t i=0;text&&text[i]&&i<64;++i){const unsigned char c=text[i];out+=(c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9')||c==' '||c=='_'||c=='-'||c=='.'?char(c):'_';}return out;}
bool extension(const char* text,const char* wanted){size_t n=std::strlen(wanted);for(const char*p=text;(p=std::strstr(p,wanted));++p)if((p==text||p[-1]==' ')&&(p[n]==' '||!p[n]))return true;return false;}
struct Cl {
    void* library{};cl_device_id device{};cl_context context{};cl_command_queue queue{};
    std::atomic<bool>& cancelled;
    // Never unload a driver or release objects while submitted work may still
    // run. The isolated worker owns quarantined resources until process exit.
    bool pending=false;
    cl_uint vendor{};std::string name,vendor_name,driver;
#define FN(name) decltype(&::name) name=nullptr
    FN(clGetPlatformIDs);FN(clGetDeviceIDs);FN(clGetDeviceInfo);FN(clCreateContext);FN(clCreateCommandQueue);
    FN(clCreateBuffer);FN(clCreateProgramWithSource);FN(clBuildProgram);FN(clCreateKernel);FN(clSetKernelArg);
    FN(clEnqueueNDRangeKernel);FN(clEnqueueReadBuffer);FN(clFinish);FN(clGetEventProfilingInfo);
    FN(clReleaseEvent);FN(clReleaseMemObject);FN(clReleaseKernel);FN(clReleaseProgram);FN(clReleaseCommandQueue);FN(clReleaseContext);
#undef FN
    explicit Cl(std::atomic<bool>& c):cancelled(c){}
    ~Cl(){if(pending)return;if(queue)clReleaseCommandQueue(queue);if(context)clReleaseContext(context);if(library)dlclose(library);}
    void stop(){if(cancelled.load())throw Error{"NONE"};}
    template<class T>T info(cl_device_info key){T value{};check(clGetDeviceInfo(device,key,sizeof(value),&value,nullptr),"ACL_DEVICE_QUERY_FAILED");return value;}
    std::string text(cl_device_info key){std::array<char,256> value{};check(clGetDeviceInfo(device,key,value.size()-1,value.data(),nullptr),"ACL_DEVICE_QUERY_FAILED");return label(value.data());}
    void initialize(){
        stop();library=dlopen("libOpenCL.so",RTLD_NOW|RTLD_LOCAL);if(!library)throw Error{"ACL_LIBRARY_UNAVAILABLE"};
#define LOAD(name) name=reinterpret_cast<decltype(name)>(dlsym(library,#name));if(!name)throw Error{"ACL_SYMBOL_UNAVAILABLE"}
        LOAD(clGetPlatformIDs);LOAD(clGetDeviceIDs);LOAD(clGetDeviceInfo);LOAD(clCreateContext);LOAD(clCreateCommandQueue);
        LOAD(clCreateBuffer);LOAD(clCreateProgramWithSource);LOAD(clBuildProgram);LOAD(clCreateKernel);LOAD(clSetKernelArg);
        LOAD(clEnqueueNDRangeKernel);LOAD(clEnqueueReadBuffer);LOAD(clFinish);LOAD(clGetEventProfilingInfo);
        LOAD(clReleaseEvent);LOAD(clReleaseMemObject);LOAD(clReleaseKernel);LOAD(clReleaseProgram);LOAD(clReleaseCommandQueue);LOAD(clReleaseContext);
#undef LOAD
        std::array<cl_platform_id,8> platforms{};cl_uint count=0,gpus=0;cl_device_id selected=nullptr;
        check(clGetPlatformIDs(platforms.size(),platforms.data(),&count),"ACL_PLATFORM_QUERY_FAILED");
        if(count>platforms.size())throw Error{"ACL_DEVICE_AMBIGUOUS"};
        for(cl_uint p=0;p<count;++p){stop();std::array<cl_device_id,16> devices{};cl_uint n=0;
            const auto status=clGetDeviceIDs(platforms[p],CL_DEVICE_TYPE_GPU,devices.size(),devices.data(),&n);
            if(status==CL_DEVICE_NOT_FOUND)continue;
            check(status,"ACL_DEVICE_QUERY_FAILED");if(n>devices.size())throw Error{"ACL_DEVICE_AMBIGUOUS"};
            for(cl_uint i=0;i<n;++i){device=devices[i];const auto type=info<cl_device_type>(CL_DEVICE_TYPE);if((type&CL_DEVICE_TYPE_GPU)&&!(type&CL_DEVICE_TYPE_CPU)){++gpus;selected=device;}}
        }
        if(gpus!=1||!selected)throw Error{"ACL_DEVICE_AMBIGUOUS"};
        device=selected;
        if(!info<cl_bool>(CL_DEVICE_AVAILABLE)||!info<cl_bool>(CL_DEVICE_COMPILER_AVAILABLE))throw Error{"ACL_DEVICE_UNAVAILABLE"};
        std::array<char,16384> extensions{};check(clGetDeviceInfo(device,CL_DEVICE_EXTENSIONS,extensions.size()-1,extensions.data(),nullptr),"ACL_DEVICE_QUERY_FAILED");
        if(!extension(extensions.data(),"cl_khr_fp16")||!info<cl_device_fp_config>(CL_DEVICE_HALF_FP_CONFIG))throw Error{"ACL_FP16_UNAVAILABLE"};
        vendor=info<cl_uint>(CL_DEVICE_VENDOR_ID);name=text(CL_DEVICE_NAME);vendor_name=text(CL_DEVICE_VENDOR);driver=text(CL_DRIVER_VERSION);
        cl_int err=0;context=clCreateContext(nullptr,1,&device,nullptr,nullptr,&err);check(err,"ACL_CONTEXT_FAILED");if(!context)throw Error{"ACL_CONTEXT_FAILED"};
        queue=clCreateCommandQueue(context,device,CL_QUEUE_PROFILING_ENABLE,&err);check(err,"ACL_QUEUE_FAILED");if(!queue)throw Error{"ACL_QUEUE_FAILED"};stop();
    }
    template<class T>void arg(cl_kernel kernel,cl_uint& index,const T& value){check(clSetKernelArg(kernel,index++,sizeof(T),&value),"ACL_ARGUMENT_FAILED");}
    std::array<double,4> run_chain(cl_kernel cast_input,cl_kernel gemm,cl_kernel cast_output,size_t inputs,size_t m,size_t n){
        stop();
        struct Events {Cl& cl;std::array<cl_event,3> values{};~Events(){if(cl.pending)return;for(auto e:values)if(e)cl.clReleaseEvent(e);}} events{*this};
        const cl_kernel kernels[]{cast_input,gemm,cast_output};
        const size_t globals[3][3]{{inputs,1,1},{(m+3)/4,(n+3)/4,1},{m*n,1,1}};
        for(size_t i=0;i<3;++i){stop();check(clEnqueueNDRangeKernel(queue,kernels[i],3,nullptr,globals[i],nullptr,0,nullptr,&events.values[i]),"ACL_ENQUEUE_FAILED");pending=true;}
        stop();check(clFinish(queue),"ACL_FINISH_FAILED");pending=false;stop();
        std::array<double,4> times{-1,-1,-1,-1};std::array<cl_ulong,3> starts{},ends{};
        bool valid=true;
        for(size_t i=0;i<3;++i){
            const auto e=events.values[i];
            if(!e||clGetEventProfilingInfo(e,CL_PROFILING_COMMAND_START,sizeof(cl_ulong),&starts[i],nullptr)!=CL_SUCCESS||
               clGetEventProfilingInfo(e,CL_PROFILING_COMMAND_END,sizeof(cl_ulong),&ends[i],nullptr)!=CL_SUCCESS||ends[i]<=starts[i])valid=false;
            else times[i]=double(ends[i]-starts[i]);
        }
        if(valid&&starts[1]>=ends[0]&&starts[2]>=ends[1])times[3]=double(ends[2]-starts[0]);
        return times;
    }
    double run(cl_kernel kernel,size_t x,size_t y){
        stop();const size_t global[3]{x,y,1};
        struct Event {Cl& cl;cl_event value{};~Event(){if(cl.pending)return;if(value)cl.clReleaseEvent(value);}} event{*this};
        check(clEnqueueNDRangeKernel(queue,kernel,3,nullptr,global,nullptr,0,nullptr,&event.value),"ACL_ENQUEUE_FAILED");pending=true;
        stop();check(clFinish(queue),"ACL_FINISH_FAILED");pending=false;stop();
        cl_ulong start=0,end=0;
        if(!event.value||clGetEventProfilingInfo(event.value,CL_PROFILING_COMMAND_START,sizeof(start),&start,nullptr)!=CL_SUCCESS||
           clGetEventProfilingInfo(event.value,CL_PROFILING_COMMAND_END,sizeof(end),&end,nullptr)!=CL_SUCCESS||end<=start)return -1;
        return double(end-start);
    }
};
struct Buffer {
    Cl& cl;cl_mem value{};size_t bytes;
    Buffer(Cl& c,size_t size,void* input=nullptr):cl(c),bytes(size){cl_int err=0;value=cl.clCreateBuffer(cl.context,CL_MEM_READ_WRITE|(input?CL_MEM_COPY_HOST_PTR:0),size,input,&err);check(err,"ACL_BUFFER_FAILED");if(!value)throw Error{"ACL_BUFFER_FAILED"};}
    ~Buffer(){if(cl.pending)return;if(value)cl.clReleaseMemObject(value);}
    void read(void* output){cl.stop();cl.pending=true;check(cl.clEnqueueReadBuffer(cl.queue,value,CL_TRUE,0,bytes,output,0,nullptr,nullptr),"ACL_READ_FAILED");cl.pending=false;cl.stop();}
};
struct Program {
    Cl& cl;cl_program value{};cl_kernel kernel{};
    explicit Program(Cl& c):cl(c){}
    ~Program(){if(cl.pending)return;if(kernel)cl.clReleaseKernel(kernel);if(value)cl.clReleaseProgram(value);}
    void build(const char* source,const std::string& options,const char* name){cl.stop();cl_int err=0;value=cl.clCreateProgramWithSource(cl.context,1,&source,nullptr,&err);check(err,"ACL_PROGRAM_FAILED");if(!value)throw Error{"ACL_PROGRAM_FAILED"};
        err=cl.clBuildProgram(value,1,&cl.device,options.c_str(),nullptr,nullptr);cl.stop();check(err,"ACL_BUILD_FAILED");kernel=cl.clCreateKernel(value,name,&err);check(err,"ACL_KERNEL_FAILED");if(!kernel)throw Error{"ACL_KERNEL_FAILED"};}
};
struct Control {
    ggml_backend_t backend{};ggml_context* ctx{};ggml_backend_buffer_t buffer{};ggml_cgraph* graph{};
    ~Control(){if(buffer)ggml_backend_buffer_free(buffer);if(ctx)ggml_free(ctx);if(backend)ggml_backend_free(backend);}
};
void verify_unique_gpu_vendor(cl_uint vendor){
    struct Instance {VkInstance value{};~Instance(){if(value)vkDestroyInstance(value,nullptr);}} instance;
    VkInstanceCreateInfo info{};info.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    if(vkCreateInstance(&info,nullptr,&instance.value)!=VK_SUCCESS)throw Error{"ACL_CONTROL_DEVICE_FAILED"};
    std::array<VkPhysicalDevice,16> devices{};uint32_t count=devices.size(),gpus=0;
    if(vkEnumeratePhysicalDevices(instance.value,&count,devices.data())!=VK_SUCCESS||count>devices.size())throw Error{"ACL_DEVICE_AMBIGUOUS"};
    for(uint32_t i=0;i<count;++i){VkPhysicalDeviceProperties p{};vkGetPhysicalDeviceProperties(devices[i],&p);
        if(p.deviceType==VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU||p.deviceType==VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU){++gpus;if(p.vendorID!=vendor)throw Error{"ACL_VENDOR_MISMATCH"};}}
    if(gpus!=1)throw Error{"ACL_DEVICE_AMBIGUOUS"};
}
float datum(size_t i,uint32_t seed){uint32_t v=uint32_t(i)+seed*0x9e3779b9u;v^=v>>16;v*=0x7feb352du;v^=v>>15;v*=0x846ca68bu;v^=v>>16;return(int(v&65535)-32768)/131072.0f;}
size_t sample_index(int p,size_t m,size_t n,bool full){if(full)return p;if(p==0)return 0;if(p==1)return m*n-1;if(p==2)return m-1;if(p==3)return(n-1)*m;return(size_t(p)*104729+17)%(m*n);}
void array(std::ostringstream& out,const char* key,const std::vector<double>& values){out<<",\""<<key<<"\":[";for(size_t i=0;i<values.size();++i){if(i)out<<',';if(values[i]<0)out<<"null";else out<<values[i];}out<<']';}
template<class T> void counters(std::ostringstream& out,const char* key,const std::map<std::string,T>& values){
    out<<",\""<<key<<"\":{";size_t i=0;
    for(const auto& item:values){if(i>=64)break;if(i++)out<<',';out<<'"'<<item.first<<"\":"<<item.second;}out<<'}';
}
void tensor_args(Cl& cl,cl_kernel kernel,cl_uint& index,Buffer& buffer,cl_uint width,cl_uint height){
    cl.arg(kernel,index,buffer.value);for(cl_uint v:{width*2,width*height*2,width,height,cl_uint(1),cl_uint(0)})cl.arg(kernel,index,v);
}
void image_args(Cl& cl,cl_kernel kernel,cl_uint& index,Buffer& buffer,cl_uint width){
    cl.arg(kernel,index,buffer.value);for(cl_uint v:{cl_uint(2),cl_uint(0),width*2,cl_uint(0),cl_uint(0)})cl.arg(kernel,index,v);
}
}
#endif

const char* altiro_acl_hunt(bool gpu,int variant,bool quantized,int encoder_frames,std::atomic<bool>& cancelled,
    const std::function<void(const std::string&)>& emit){
#ifndef ALTIRO_VULKAN
    (void)gpu;(void)variant;(void)quantized;(void)encoder_frames;(void)cancelled;(void)emit;
    return "BACKEND_UNAVAILABLE";
#else
    if(!gpu||variant<90||variant>92)return "BACKEND_MISMATCH";
    if(encoder_frames<1||encoder_frames>1000||!altiro_encoder_lab)return "GRAPH_UNSUPPORTED";
    try{
        Cl cl(cancelled);cl.initialize();verify_unique_gpu_vendor(cl.vendor);cl.stop();
        const uint32_t width=variant==91?145:variant==92?201:uint32_t(encoder_frames);
        struct Shape{uint32_t m,n,k;};const Shape shapes[]{{2560,width,640},{640,width,2560},{65,65,96},{63,63,64}};
        int sequence=0;bool suite_numerics=true,suite_finite=true;
        for(const auto shape:shapes){
            cl.stop();const auto setup_start=Clock::now();
            const uint32_t m=shape.m,n=shape.n,k=shape.k,mp=(m+3)/4*4;
            const size_t cells=size_t(m)*n;const bool full=cells<=8192;const int references=full?int(cells):64;
            std::vector<float> weights(size_t(m)*k),activations(size_t(n)*k);
            for(size_t i=0;i<weights.size();++i)weights[i]=datum(i,11);
            const auto source_type=quantized?GGML_TYPE_Q8_0:GGML_TYPE_F16;
            std::vector<uint8_t> original(ggml_row_size(source_type,k)*m);
            ggml_quantize_chunk(source_type,weights.data(),original.data(),0,m,k,nullptr);
            ggml_get_type_traits(source_type)->to_float(original.data(),weights.data(),weights.size());
            std::vector<ggml_fp16_t> whalf(weights.size()),xhalf(size_t(std::max(n,4u))*k,0);
            double rounding=0;
            for(size_t i=0;i<weights.size();++i){whalf[i]=ggml_fp32_to_fp16(weights[i]);const float value=ggml_fp16_to_fp32(whalf[i]);rounding=std::max(rounding,std::abs(double(value)-weights[i]));weights[i]=value;}
            for(size_t i=0;i<activations.size();++i){xhalf[i]=ggml_fp32_to_fp16(datum(i,31));activations[i]=ggml_fp16_to_fp32(xhalf[i]);}
            const auto transpose_start=Clock::now();
            std::vector<ggml_fp16_t> transposed(size_t(mp)*k,0);
            for(uint32_t row=0;row<k;++row)for(uint32_t col=0;col<m;++col)transposed[size_t(row)*mp+col]=whalf[size_t(col)*k+row];
            const double transpose_ms=elapsed(transpose_start);
            Buffer rhs(cl,transposed.size()*2,transposed.data()),packed(cl,size_t(mp)*k*2),lhs(cl,xhalf.size()*2,xhalf.data());
            Buffer output(cl,size_t(m)*std::max(n,4u)*2),input_f32(cl,activations.size()*4,activations.data()),output_f32(cl,cells*4);
            const uint64_t resident=rhs.bytes+packed.bytes+lhs.bytes+output.bytes+input_f32.bytes+output_f32.bytes;
            const auto build_start=Clock::now();Program reshape(cl),gemm(cl),cast_input(cl),cast_output(cl);
            const std::string common="-cl-std=CL1.2 -DARM_COMPUTE_OPENCL_FP16_ENABLED -DDATA_TYPE=half -DN0=4 -DK0=4";
            reshape.build(altiro_acl_reshape_source,common+" -DRESHAPE_RHS_T -DTRANSPOSE","gemm_reshape_rhs_matrix_t");
            gemm.build(altiro_acl_gemm_source,common+" -DM0=4 -DH0=1 -DGEMM_MM_RESHAPED_ONLY_RHS_T -DPARTIAL_STORE_M0="+std::to_string(n%4)+" -DPARTIAL_STORE_N0="+std::to_string(m%4),"gemm_mm_reshaped_only_rhs_t");
            // Original glue only. Matrix multiply and RHS packing remain exact
            // pinned Arm kernels. Casts are charged on every complete call.
            const char* glue=R"CLC(
#pragma OPENCL EXTENSION cl_khr_fp16 : enable
__kernel void altiro_cast_input(__global const float* src,__global half* dst){size_t i=get_global_id(0);dst[i]=convert_half_rte(src[i]);}
__kernel void altiro_cast_output(__global const half* src,__global float* dst){size_t i=get_global_id(0);dst[i]=convert_float(src[i]);}
)CLC";
            cast_input.build(glue,"-cl-std=CL1.2","altiro_cast_input");
            cast_output.build(glue,"-cl-std=CL1.2","altiro_cast_output");
            const double build_ms=elapsed(build_start);
            cl_uint arg=0;tensor_args(cl,reshape.kernel,arg,rhs,mp,k);tensor_args(cl,reshape.kernel,arg,packed,4*k,mp/4);const cl_int h0=1;cl.arg(reshape.kernel,arg,h0);
            const auto pack_start=Clock::now();const double pack_gpu=cl.run(reshape.kernel,mp/4,k/4),pack_ms=elapsed(pack_start);
            std::vector<ggml_fp16_t> packed_values(size_t(mp)*k);packed.read(packed_values.data());
            for(uint32_t row=0;row<mp;++row)for(uint32_t kk=0;kk<k;++kk){
                const size_t index=size_t(row/4)*4*k+size_t(kk/4)*16+(row%4)*4+kk%4;
                const auto expected=row<m?whalf[size_t(row)*k+kk]:ggml_fp16_t(0);
                if(packed_values[index]!=expected)throw Error{"ACL_PACKING_MISMATCH"};
            }
            arg=0;image_args(cl,gemm.kernel,arg,lhs,k);image_args(cl,gemm.kernel,arg,packed,4*k);image_args(cl,gemm.kernel,arg,output,m);
            for(cl_uint value:{cl_uint(lhs.bytes),cl_uint(packed.bytes),cl_uint(output.bytes)})cl.arg(gemm.kernel,arg,value);
            // ACL names its row dimension M and column dimension N. Our public
            // experiment uses Y[M,N] with M contiguous, so these are exchanged.
            for(cl_int value:{cl_int(n),cl_int(m),cl_int(k)})cl.arg(gemm.kernel,arg,value);
            arg=0;cl.arg(cast_input.kernel,arg,input_f32.value);cl.arg(cast_input.kernel,arg,lhs.value);
            arg=0;cl.arg(cast_output.kernel,arg,output.value);cl.arg(cast_output.kernel,arg,output_f32.value);
            Control control;control.backend=altiro_kernel_backend(true);
            if(!control.backend||std::string(ggml_backend_name(control.backend)).rfind("Vulkan",0)!=0)throw Error{"BACKEND_MISMATCH"};
            const std::string control_name=label(ggml_backend_dev_description(ggml_backend_get_device(control.backend)));
            control.ctx=ggml_init({2*1024*1024,nullptr,true});if(!control.ctx)throw Error{"OUT_OF_MEMORY"};control.graph=ggml_new_graph_custom(control.ctx,64,false);
            auto cw=ggml_new_tensor_2d(control.ctx,GGML_TYPE_F16,k,m),cx=ggml_new_tensor_2d(control.ctx,GGML_TYPE_F32,k,n);
            auto cy=ggml_mul_mat(control.ctx,cw,cx);ggml_mul_mat_set_prec(cy,GGML_PREC_F32);ggml_build_forward_expand(control.graph,cy);
            control.buffer=ggml_backend_alloc_ctx_tensors(control.ctx,control.backend);if(!control.buffer)throw Error{"OUT_OF_MEMORY"};
            const size_t control_resident=ggml_backend_buffer_get_size(control.buffer);
            ggml_backend_tensor_set(cw,whalf.data(),0,whalf.size()*2);ggml_backend_tensor_set(cx,activations.data(),0,activations.size()*4);ggml_backend_synchronize(control.backend);
            auto& lab=*altiro_encoder_lab;lab.variant=variant;if(!altiro_before_graph(control.backend,control.graph,1))throw Error{"GRAPH_UNSUPPORTED"};
            std::vector<double> reference(references);
            for(int p=0;p<references;++p){cl.stop();const size_t at=sample_index(p,m,n,full),row=at%m,col=at/m;double sum=0;
                for(uint32_t kk=0;kk<k;++kk)sum+=double(weights[row*k+kk])*activations[col*k+kk];
                reference[p]=sum;}
            const double setup_ms=elapsed(setup_start);lab.counts.clear();lab.shaders.clear();lab.us.clear();
            std::vector<double> walls,cpus,gpus,input_gpu,compute_gpu,output_gpu,control_walls,control_cpus,control_gpus,reads;
            std::vector<float> candidate_output(cells),baseline(cells);
            bool finite=true;double rmse=0,max_abs=0,control_rmse=0,control_abs=0,control_reference_rmse=0;
            for(int repeat=0;repeat<6;++repeat){
                cl.stop();auto candidate=[&]{const auto start=Clock::now();const double cpu=process_ms();const auto ns=cl.run_chain(cast_input.kernel,gemm.kernel,cast_output.kernel,activations.size(),m,n);walls.push_back(elapsed(start));cpus.push_back(process_ms()-cpu);input_gpu.push_back(ns[0]);compute_gpu.push_back(ns[1]);output_gpu.push_back(ns[2]);gpus.push_back(ns[3]);};
                auto baseline_call=[&]{cl.stop();lab.measuring=true;lab.boundary_available=false;const auto start=Clock::now();const double cpu=process_ms();
                    const auto status=ggml_backend_graph_compute(control.backend,control.graph);ggml_backend_synchronize(control.backend);
                    control_walls.push_back(elapsed(start));control_cpus.push_back(process_ms()-cpu);control_gpus.push_back(lab.boundary_available?double(lab.boundary_gpu_ns):-1);lab.measuring=false;
                    cl.stop();if(status!=GGML_STATUS_SUCCESS)throw Error{"KERNEL_COMPUTE_FAILED"};};
                if(repeat%2){baseline_call();candidate();}else{candidate();baseline_call();}
                const auto read_start=Clock::now();output_f32.read(candidate_output.data());ggml_backend_tensor_get(cy,baseline.data(),0,cells*4);cl.stop();
                double sq=0,ref_sq=0,csq=0,crsq=0,control_ref_sq=0;
                for(size_t i=0;i<cells;++i){const float value=candidate_output[i];finite&=std::isfinite(value)&&std::isfinite(baseline[i]);
                    if(!std::isfinite(value)||!std::isfinite(baseline[i]))continue;
                    const double d=double(value)-baseline[i];csq+=d*d;crsq+=double(baseline[i])*baseline[i];control_abs=std::max(control_abs,std::abs(d));}
                for(int p=0;p<references;++p){const size_t i=sample_index(p,m,n,full);const float value=candidate_output[i];if(!std::isfinite(value)||!std::isfinite(baseline[i]))continue;
                    const double ref=reference[p],d=double(value)-ref,cd=double(baseline[i])-ref;sq+=d*d;ref_sq+=ref*ref;control_ref_sq+=cd*cd;max_abs=std::max(max_abs,std::abs(d));}
                rmse=std::max(rmse,std::sqrt(sq/std::max(ref_sq,1e-30)));control_rmse=std::max(control_rmse,std::sqrt(csq/std::max(crsq,1e-30)));
                control_reference_rmse=std::max(control_reference_rmse,std::sqrt(control_ref_sq/std::max(ref_sq,1e-30)));reads.push_back(elapsed(read_start));
            }
            std::ostringstream out;out.precision(9);
            out<<"{\"lab\":true,\"event\":\"ACL\",\"sequence\":"<<sequence++<<",\"m\":"<<m<<",\"n\":"<<n<<",\"k\":"<<k
                <<",\"encoder_frames\":"<<encoder_frames<<",\"streams\":1,\"complete_calls_per_repeat\":1,\"source_type\":\""<<(quantized?"q8_0":"f16")
                <<"\",\"storage_type\":\"f16\",\"backend\":\"OpenCL\",\"candidate_backend\":\"OpenCL\",\"control_backend\":\"Vulkan\""
                <<",\"implementation\":\"acl_reshaped_rhs_t\",\"acl_revision\":\""<<altiro_acl_revision
                <<"\",\"gemm_kernel\":\"gemm_mm_reshaped_only_rhs_t\",\"reshape_kernel\":\"gemm_reshape_rhs_matrix_t\""
                <<",\"accumulation\":\"f16\",\"control_accumulation\":\"f32\",\"activation_input_rounding\":\"f16_then_f32\",\"output_type\":\"f16\""
                <<",\"block_m0\":4,\"block_n0\":4,\"block_k0\":4,\"block_h0\":1,\"dispatch\":["<<(m+3)/4<<','<<(n+3)/4<<",1]"
                <<",\"activation_transform\":\"f32_to_f16\",\"output_transform\":\"f16_to_f32\",\"boundary_output_type\":\"f32\",\"glue_implementation\":\"altiro_fixed_cast_v1\",\"candidate_gpu_events_per_repeat\":3,\"execution_order\":\"alternating_candidate_first_even\",\"workgroup_selection\":\"driver_selected\""
                <<",\"weight_pack_checked\":true,\"wall_includes_all_transforms\":true,\"unique_gpu_vendor_match\":true"
                <<",\"reference_mode\":\"double_dot_half_rounded_inputs\",\"reference_cells\":"<<references<<",\"full_reference\":"<<(full?"true":"false")
                <<",\"finite\":"<<(finite?"true":"false")<<",\"relative_rmse\":"<<(finite?rmse:-1)<<",\"max_abs_error\":"<<(finite?max_abs:-1)
                <<",\"control_relative_rmse\":"<<(finite?control_rmse:-1)<<",\"control_max_abs_error\":"<<(finite?control_abs:-1)
                <<",\"control_reference_relative_rmse\":"<<(finite?control_reference_rmse:-1)<<",\"source_rounding_max_abs\":"<<rounding
                <<",\"program_build_ms\":"<<build_ms<<",\"setup_ms\":"<<setup_ms<<",\"host_rhs_transpose_ms\":"<<transpose_ms
                <<",\"weight_pack_wall_ms\":"<<pack_ms<<",\"weight_pack_gpu_ns\":";
            if(pack_gpu<0)out<<"null";else out<<pack_gpu;
            out<<",\"resident_bytes\":"<<resident<<",\"scratch_bytes\":"<<(packed.bytes+lhs.bytes+output.bytes)<<",\"control_resident_bytes\":"<<control_resident
                <<",\"host_reference_bytes\":"<<(weights.size()*4+activations.size()*4+reference.size()*8)
                <<",\"candidate_device\":\""<<cl.name<<"\",\"candidate_vendor\":\""<<cl.vendor_name<<"\",\"candidate_driver_version\":\""<<cl.driver
                <<"\",\"candidate_vendor_id\":"<<cl.vendor<<",\"control_device\":\""<<control_name
                <<"\",\"opencl_gpu_count\":1,\"vulkan_gpu_count\":1,\"device_type\":\"GPU\"";
            array(out,"activation_conversion_gpu_ns",input_gpu);array(out,"compute_gpu_ns",compute_gpu);array(out,"output_conversion_gpu_ns",output_gpu);
            array(out,"wall_ms",walls);array(out,"process_cpu_ms",cpus);array(out,"boundary_gpu_ns",gpus);array(out,"readback_check_ms",reads);
            array(out,"control_wall_ms",control_walls);array(out,"control_process_cpu_ms",control_cpus);array(out,"control_boundary_gpu_ns",control_gpus);counters(out,"control_counts",lab.counts);counters(out,"control_shaders",lab.shaders);out<<'}';emit(out.str());
            suite_finite&=finite;suite_numerics&=finite&&rmse<=.02&&control_rmse<=.02&&control_reference_rmse<=.002;
        }
        if(!suite_finite)return "NONFINITE_OR_SHAPE";
        if(!suite_numerics)return "NUMERICAL_MISMATCH";
        emit("{\"lab\":true,\"event\":\"FINISHED\"}");return nullptr;
    }catch(const Error& error){return error.code;}catch(const std::bad_alloc&){return "OUT_OF_MEMORY";}
#endif
}
