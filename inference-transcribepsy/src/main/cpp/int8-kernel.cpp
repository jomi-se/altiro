#include "encoder-lab.h"
#include "int8-kernel.h"
#include "int8-layout.h"
#include "kernel-backend.h"
#include "packed-layout.h"
#include "run-control.h"
#include "ggml.h"
#include <algorithm>
#include <cmath>
#include <ctime>
#include <cstring>
#include <memory>
#include <sstream>
#include <vector>
#ifdef ALTIRO_VULKAN
#include <vulkan/vulkan.h>
#include "int8-spirv.h"

namespace {
using Clock = std::chrono::steady_clock;
double elapsed(Clock::time_point t) { return std::chrono::duration<double,std::milli>(Clock::now()-t).count(); }
double process_ms() {
    timespec ts{}; clock_gettime(CLOCK_PROCESS_CPUTIME_ID,&ts);
    return ts.tv_sec*1000.0+ts.tv_nsec/1e6;
}
struct Error { const char * code; };
void check(VkResult status) {
    if(status!=VK_SUCCESS) throw Error{status==VK_ERROR_OUT_OF_DEVICE_MEMORY || status==VK_ERROR_OUT_OF_HOST_MEMORY ?
        "OUT_OF_MEMORY" : "INT8_VULKAN_FAILED"};
}
struct Shape { uint32_t m,n,k; };
struct Vulkan {
    VkInstance instance{};
    VkPhysicalDevice physical{};
    VkDevice device{};
    VkQueue queue{};
    VkCommandPool pool{};
    VkCommandBuffer cmd{};
    VkFence fence{};
    VkQueryPool queries{};
    VkDescriptorSetLayout set_layout{};
    VkPipelineLayout layout{};
    VkDescriptorPool descriptors{};
    VkPipeline pack_w{},pack_x{},gemm{};
    VkPhysicalDeviceMemoryProperties memory{};
    VkPhysicalDeviceProperties properties{};
    uint32_t family=0,bits=0,physical_device_count=0,instance_api=VK_API_VERSION_1_1;
    // On timeout/cancellation with GPU work pending, Vulkan resources cannot be
    // destroyed safely. Quarantine them until this isolated worker exits. Never
    // wait indefinitely or submit another operation on the timed-out device.
    bool pending=false;
    explicit Vulkan() {
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName="Altiro integer dot lab";
        uint32_t loader=VK_API_VERSION_1_0;
        const auto version=reinterpret_cast<PFN_vkEnumerateInstanceVersion>(vkGetInstanceProcAddr(nullptr,"vkEnumerateInstanceVersion"));
        if(!version || version(&loader)!=VK_SUCCESS || loader<VK_API_VERSION_1_1) throw Error{"INT8_DOT_UNAVAILABLE"};
        instance_api=std::min(loader,uint32_t(VK_API_VERSION_1_3)); app.apiVersion=instance_api;
        VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ci.pApplicationInfo=&app;
        check(vkCreateInstance(&ci,nullptr,&instance));
    }
    void initialize() {
        uint32_t count=0; check(vkEnumeratePhysicalDevices(instance,&count,nullptr));
        if(!count || count>16) throw Error{"BACKEND_UNAVAILABLE"};
        // GGML does not expose a public physical-device UUID getter. Restrict
        // this comparison to a unique Vulkan physical device rather than
        // silently comparing pipelines on different devices.
        if(count!=1) throw Error{"INT8_DEVICE_AMBIGUOUS"};
        physical_device_count=count;
        std::vector<VkPhysicalDevice> devices(count);
        check(vkEnumeratePhysicalDevices(instance,&count,devices.data()));
        for(auto candidate:devices) {
            VkPhysicalDeviceProperties p; vkGetPhysicalDeviceProperties(candidate,&p);
            if(p.deviceType==VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU || p.deviceType==VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                physical=candidate; properties=p; break;
            }
        }
        if(!physical) throw Error{"BACKEND_UNAVAILABLE"};
        if(properties.apiVersion<VK_API_VERSION_1_1) throw Error{"GRAPH_UNSUPPORTED"};
        if(properties.limits.maxComputeWorkGroupInvocations<64 || properties.limits.maxComputeWorkGroupSize[0]<64 ||
            properties.limits.maxComputeWorkGroupSize[1]<8 || properties.limits.maxPushConstantsSize<sizeof(Shape))
            throw Error{"GRAPH_UNSUPPORTED"};
        uint32_t qc=0; vkGetPhysicalDeviceQueueFamilyProperties(physical,&qc,nullptr);
        if(!qc || qc>256) throw Error{"BACKEND_UNAVAILABLE"};
        std::vector<VkQueueFamilyProperties> qs(qc); vkGetPhysicalDeviceQueueFamilyProperties(physical,&qc,qs.data());
        bool found=false;
        for(uint32_t i=0;i<qc;i++) if(qs[i].queueCount && (qs[i].queueFlags&VK_QUEUE_COMPUTE_BIT)) {
            family=i; bits=qs[i].timestampValidBits; found=true; break;
        }
        if(!found) throw Error{"BACKEND_UNAVAILABLE"};
        float priority=1;
        VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qi.queueFamilyIndex=family; qi.queueCount=1; qi.pQueuePriorities=&priority;
        VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; di.queueCreateInfoCount=1; di.pQueueCreateInfos=&qi;
        uint32_t ec=0;
        check(vkEnumerateDeviceExtensionProperties(physical,nullptr,&ec,nullptr));
        if(ec>512) throw Error{"INT8_DOT_UNAVAILABLE"};
        std::vector<VkExtensionProperties> extensions(ec);
        check(vkEnumerateDeviceExtensionProperties(physical,nullptr,&ec,extensions.data()));
        bool extension=false;
        for(const auto & e:extensions) extension|=std::strcmp(e.extensionName,VK_KHR_SHADER_INTEGER_DOT_PRODUCT_EXTENSION_NAME)==0;
        const bool core=instance_api>=VK_API_VERSION_1_3 && properties.apiVersion>=VK_API_VERSION_1_3;
        if(!core && !extension) throw Error{"INT8_DOT_UNAVAILABLE"};
        VkPhysicalDeviceShaderIntegerDotProductFeatures dot{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_INTEGER_DOT_PRODUCT_FEATURES};
        VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2}; features.pNext=&dot;
        vkGetPhysicalDeviceFeatures2(physical,&features);
        VkPhysicalDeviceShaderIntegerDotProductProperties acceleration{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_INTEGER_DOT_PRODUCT_PROPERTIES};
        VkPhysicalDeviceProperties2 props{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2}; props.pNext=&acceleration;
        vkGetPhysicalDeviceProperties2(physical,&props);
        if(!dot.shaderIntegerDotProduct) throw Error{"INT8_DOT_UNAVAILABLE"};
        if(!acceleration.integerDotProduct4x8BitPackedSignedAccelerated) throw Error{"INT8_DOT_NOT_ACCELERATED"};
        // Packed 32-bit operands use OpSDotKHR without Int8 types. Enabling the
        // actual integer-dot feature is mandatory even when it is in core 1.3.
        const char * extension_name=VK_KHR_SHADER_INTEGER_DOT_PRODUCT_EXTENSION_NAME;
        di.pNext=&dot;
        if(!core) { di.enabledExtensionCount=1; di.ppEnabledExtensionNames=&extension_name; }
        check(vkCreateDevice(physical,&di,nullptr,&device)); vkGetDeviceQueue(device,family,0,&queue);
        vkGetPhysicalDeviceMemoryProperties(physical,&memory);
        VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        pi.queueFamilyIndex=family; pi.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        check(vkCreateCommandPool(device,&pi,nullptr,&pool));
        VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ai.commandPool=pool; ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount=1;
        check(vkAllocateCommandBuffers(device,&ai,&cmd));
        VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; check(vkCreateFence(device,&fi,nullptr,&fence));
        if(bits && bits<=64 && properties.limits.timestampPeriod>0) {
            VkQueryPoolCreateInfo ti{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
            ti.queryType=VK_QUERY_TYPE_TIMESTAMP; ti.queryCount=2;
            check(vkCreateQueryPool(device,&ti,nullptr,&queries));
        }
        VkDescriptorSetLayoutBinding bindings[3]{};
        for(uint32_t i=0;i<3;i++) { bindings[i].binding=i; bindings[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            bindings[i].descriptorCount=1; bindings[i].stageFlags=VK_SHADER_STAGE_COMPUTE_BIT; }
        VkDescriptorSetLayoutCreateInfo si{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        si.bindingCount=3; si.pBindings=bindings;
        check(vkCreateDescriptorSetLayout(device,&si,nullptr,&set_layout));
        VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(Shape)};
        VkPipelineLayoutCreateInfo li{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        li.setLayoutCount=1; li.pSetLayouts=&set_layout; li.pushConstantRangeCount=1; li.pPushConstantRanges=&push;
        check(vkCreatePipelineLayout(device,&li,nullptr,&layout));
        VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,18};
        VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        dp.maxSets=6; dp.poolSizeCount=1; dp.pPoolSizes=&size;
        check(vkCreateDescriptorPool(device,&dp,nullptr,&descriptors));
        pack_w=pipeline(altiro_int8_spv_pack_weights,sizeof(altiro_int8_spv_pack_weights));
        pack_x=pipeline(altiro_int8_spv_quant_activations,sizeof(altiro_int8_spv_quant_activations));
        gemm=pipeline(altiro_int8_spv_dot4,sizeof(altiro_int8_spv_dot4));
    }
    VkPipeline pipeline(const uint32_t * code,size_t bytes) {
        VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; sm.codeSize=bytes; sm.pCode=code;
        VkShaderModule module{}; check(vkCreateShaderModule(device,&sm,nullptr,&module));
        VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        cp.stage={VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        cp.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT; cp.stage.module=module; cp.stage.pName="main"; cp.layout=layout;
        VkPipeline result{}; auto status=vkCreateComputePipelines(device,VK_NULL_HANDLE,1,&cp,nullptr,&result);
        vkDestroyShaderModule(device,module,nullptr); check(status); return result;
    }
    void begin() {
        if(pending) throw Error{"INT8_VULKAN_FAILED"};
        check(vkResetCommandBuffer(cmd,0));
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(cmd,&bi));
        if(queries) { vkCmdResetQueryPool(cmd,queries,0,2);
            vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,queries,0); }
        VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        barrier.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_HOST_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
    }
    void dispatch(VkPipeline p,VkDescriptorSet set,Shape shape,uint32_t x,uint32_t y=1) {
        if(x>properties.limits.maxComputeWorkGroupCount[0] || y>properties.limits.maxComputeWorkGroupCount[1])
            throw Error{"GRAPH_UNSUPPORTED"};
        vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,p);
        vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,1,&set,0,nullptr);
        vkCmdPushConstants(cmd,layout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(shape),&shape);
        vkCmdDispatch(cmd,x,y,1);
    }
    double finish(std::atomic<bool> & cancelled) {
        VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        barrier.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT; barrier.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,
            0,1,&barrier,0,nullptr,0,nullptr);
        if(queries) vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,queries,1);
        check(vkEndCommandBuffer(cmd)); check(vkResetFences(device,1,&fence));
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount=1; si.pCommandBuffers=&cmd;
        check(vkQueueSubmit(queue,1,&si,fence)); pending=true;
        const auto start=Clock::now();
        for(;;) {
            auto status=vkWaitForFences(device,1,&fence,VK_TRUE,100000000);
            if(status==VK_SUCCESS) { pending=false; break; }
            if(status!=VK_TIMEOUT) throw Error{"INT8_VULKAN_FAILED"};
            if(cancelled.load()) throw Error{"NONE"};
            if(elapsed(start)>10000) throw Error{"KERNEL_TIMEOUT"};
        }
        if(cancelled.load()) throw Error{"NONE"};
        if(!queries) return -1;
        uint64_t stamps[2]{};
        check(vkGetQueryPoolResults(device,queries,0,2,sizeof(stamps),stamps,sizeof(uint64_t),VK_QUERY_RESULT_64_BIT));
        return double(altiro_timestamp_delta(stamps[0],stamps[1],bits))*properties.limits.timestampPeriod;
    }
    ~Vulkan() {
        if(pending) return;
        if(device) {
            if(gemm) vkDestroyPipeline(device,gemm,nullptr);
            if(pack_x) vkDestroyPipeline(device,pack_x,nullptr);
            if(pack_w) vkDestroyPipeline(device,pack_w,nullptr);
            if(descriptors) vkDestroyDescriptorPool(device,descriptors,nullptr);
            if(layout) vkDestroyPipelineLayout(device,layout,nullptr);
            if(set_layout) vkDestroyDescriptorSetLayout(device,set_layout,nullptr);
            if(queries) vkDestroyQueryPool(device,queries,nullptr);
            if(fence) vkDestroyFence(device,fence,nullptr);
            if(pool) vkDestroyCommandPool(device,pool,nullptr);
            vkDestroyDevice(device,nullptr);
        }
        if(instance) vkDestroyInstance(instance,nullptr);
    }
};
struct Buffer {
    Vulkan & vk;
    VkBuffer buffer{};
    VkDeviceMemory memory{};
    void * mapped=nullptr;
    size_t bytes=0;
    VkDeviceSize allocated=0;
    VkMemoryPropertyFlags flags=0;
    Buffer(Vulkan & vk,size_t bytes):vk(vk),bytes(bytes) {}
    void allocate() {
        if(bytes>vk.properties.limits.maxStorageBufferRange) throw Error{"GRAPH_UNSUPPORTED"};
        VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bi.size=bytes; bi.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT; bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
        check(vkCreateBuffer(vk.device,&bi,nullptr,&buffer));
        VkMemoryRequirements req; vkGetBufferMemoryRequirements(vk.device,buffer,&req);
        uint32_t type=UINT32_MAX;
        for(uint32_t i=0;i<vk.memory.memoryTypeCount;i++)
            if((req.memoryTypeBits&(1u<<i)) &&
                (vk.memory.memoryTypes[i].propertyFlags&(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))==
                (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) { type=i; break; }
        if(type==UINT32_MAX) throw Error{"INT8_MEMORY_UNAVAILABLE"};
        flags=vk.memory.memoryTypes[type].propertyFlags;
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; ai.allocationSize=req.size; ai.memoryTypeIndex=type;
        allocated=req.size; check(vkAllocateMemory(vk.device,&ai,nullptr,&memory));
        check(vkBindBufferMemory(vk.device,buffer,memory,0));
        check(vkMapMemory(vk.device,memory,0,VK_WHOLE_SIZE,0,&mapped));
        std::memset(mapped,0,bytes);
    }
    ~Buffer() {
        if(vk.pending) return;
        if(mapped) vkUnmapMemory(vk.device,memory);
        if(buffer) vkDestroyBuffer(vk.device,buffer,nullptr);
        if(memory) vkFreeMemory(vk.device,memory,nullptr);
    }
};
struct Tensors {
    Buffer dense_w,packed_w,dense_x,packed_x,y;
    VkDescriptorSet weight_set{},activation_set{},gemm_set{};
    ggml_tensor * control_w{},*control_x{},*control_y{};
    std::vector<float> weights,activations;
    std::vector<uint8_t> original;
    Tensors(Vulkan & vk,Shape s):
        dense_w(vk,((size_t(s.m)*(s.k/32)*34+3)/4)*4),
        packed_w(vk,altiro_int8_block_count(s.m,s.k)*sizeof(AltiroInt8Block)),
        dense_x(vk,size_t(s.n)*s.k*4),
        packed_x(vk,altiro_int8_block_count(s.n,s.k)*sizeof(AltiroInt8Block)),
        y(vk,size_t(s.m)*s.n*4) {}
    uint64_t allocate(Vulkan & vk) {
        uint64_t bytes=0;
        for(auto b:{&dense_w,&packed_w,&dense_x,&packed_x,&y}) { b->allocate(); bytes+=b->allocated; }
        const auto set=[&](Buffer & a,Buffer & b,Buffer & c) {
            VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            ai.descriptorPool=vk.descriptors; ai.descriptorSetCount=1; ai.pSetLayouts=&vk.set_layout;
            VkDescriptorSet ds{}; check(vkAllocateDescriptorSets(vk.device,&ai,&ds));
            VkDescriptorBufferInfo buffers[3]{{a.buffer,0,a.bytes},{b.buffer,0,b.bytes},{c.buffer,0,c.bytes}};
            VkWriteDescriptorSet writes[3]{};
            for(uint32_t i=0;i<3;i++) {
                writes[i]={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}; writes[i].dstSet=ds; writes[i].dstBinding=i;
                writes[i].descriptorCount=1; writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; writes[i].pBufferInfo=&buffers[i];
            }
            vkUpdateDescriptorSets(vk.device,3,writes,0,nullptr); return ds;
        };
        weight_set=set(dense_w,packed_w,y); activation_set=set(dense_x,packed_x,y); gemm_set=set(packed_w,packed_x,y);
        return bytes;
    }
};
struct Control {
    ggml_backend_t backend{};
    ggml_context * ctx{};
    ggml_backend_buffer_t buffer{};
    ggml_cgraph * graph{};
    ~Control() { if(buffer) ggml_backend_buffer_free(buffer); if(ctx) ggml_free(ctx); if(backend) ggml_backend_free(backend); }
};
float datum(size_t i,uint32_t seed) {
    uint32_t v=uint32_t(i)+seed*0x9e3779b9u; v^=v>>16; v*=0x7feb352du; v^=v>>15; v*=0x846ca68bu; v^=v>>16;
    return (int(v&65535)-32768)/131072.0f;
}
size_t sample_index(int p,size_t m,size_t n,bool full) {
    if(full) return p;
    if(p==0) return 0;
    if(p==1) return m*n-1;
    if(p==2) return m-1;
    if(p==3) return (n-1)*m;
    return (size_t(p)*104729+17)%(m*n);
}
std::string device_label(const char * input) {
    std::string output;
    if(!input) return "unavailable";
    for(size_t i=0;i<64 && input[i];i++) {
        const unsigned char c=input[i];
        output+=(c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||
            c==' '||c=='-'||c=='_'||c=='.' ? char(c) : '_';
    }
    return output;
}
void array(std::ostringstream & out,const char * name,const std::vector<double> & values) {
    out<<",\""<<name<<"\":[";
    for(size_t i=0;i<values.size();i++) { if(i) out<<','; if(values[i]<0) out<<"null"; else out<<values[i]; }
    out<<']';
}
template<class T> void map(std::ostringstream & out,const char * name,const std::map<std::string,T> & values) {
    out<<",\""<<name<<"\":{"; size_t i=0;
    for(const auto & v:values) { if(i++>=64) break; if(i>1) out<<','; out<<'"'<<v.first<<"\":"<<v.second; } out<<'}';
}
}
#endif

const char * altiro_int8_hunt(bool gpu,int variant,bool quantized,int encoder_frames,
    std::atomic<bool> & cancelled,const std::function<void(const std::string &)> & emit) {
#ifndef ALTIRO_VULKAN
    return "BACKEND_UNAVAILABLE";
#else
    if(!gpu || variant<80 || variant>82) return "BACKEND_MISMATCH";
    if(!quantized) return "INT8_REQUIRES_Q8";
    if(encoder_frames<1 || encoder_frames>1000) return "GRAPH_UNSUPPORTED";
    try {
        const auto pipeline_start=Clock::now();
        Vulkan vk; vk.initialize();
        const double pipeline_setup_ms=elapsed(pipeline_start);
        const uint32_t width=variant==81?145:variant==82?201:uint32_t(encoder_frames);
        const Shape shapes[]{{2560,width,640},{640,width,2560},{65,65,96},{63,63,64}};
        int sequence=0;
        for(Shape shape:shapes) {
            if(cancelled.load()) return "NONE";
            if(!altiro_packed_shape_valid(shape.m,shape.n,shape.k)) return "GRAPH_UNSUPPORTED";
            const auto setup_start=Clock::now();
            check(vkResetDescriptorPool(vk.device,vk.descriptors,0));
            Control control; control.backend=altiro_kernel_backend(true);
            if(!control.backend || std::string(ggml_backend_name(control.backend)).rfind("Vulkan",0)!=0)
                return "BACKEND_MISMATCH";
            control.ctx=ggml_init({2*1024*1024,nullptr,true});
            if(!control.ctx) return "OUT_OF_MEMORY";
            control.graph=ggml_new_graph_custom(control.ctx,64,false);
            Tensors t(vk,shape);
            const uint64_t resident=t.allocate(vk),scratch=t.packed_w.allocated+t.packed_x.allocated;
            VkMemoryPropertyFlags memory_flags_all=~VkMemoryPropertyFlags(0);
            for(auto b:{&t.dense_w,&t.packed_w,&t.dense_x,&t.packed_x,&t.y}) memory_flags_all&=b->flags;
            const size_t blocks=shape.k/32;
            t.weights.resize(size_t(shape.m)*shape.k);
            for(size_t i=0;i<t.weights.size();i++) t.weights[i]=datum(i,11);
            t.original.resize(ggml_row_size(GGML_TYPE_Q8_0,shape.k)*shape.m);
            if(t.original.size()!=size_t(shape.m)*blocks*34) return "INT8_LAYOUT_MISMATCH";
            ggml_quantize_chunk(GGML_TYPE_Q8_0,t.weights.data(),t.original.data(),0,shape.m,shape.k,nullptr);
            ggml_get_type_traits(GGML_TYPE_Q8_0)->to_float(t.original.data(),t.weights.data(),t.weights.size());
            double source_error=0;
            for(size_t i=0;i<t.weights.size();i++) source_error=std::max(source_error,std::abs(double(t.weights[i])-datum(i,11)));
            std::memcpy(t.dense_w.mapped,t.original.data(),t.original.size());
            t.activations.resize(size_t(shape.n)*shape.k);
            for(size_t i=0;i<t.activations.size();i++) t.activations[i]=ggml_fp16_to_fp32(ggml_fp32_to_fp16(datum(i,31)));
            // Include a real zero block; it must quantize without division by zero.
            std::fill_n(t.activations.begin(),32,0.0f);
            std::memcpy(t.dense_x.mapped,t.activations.data(),t.dense_x.bytes);
            t.control_w=ggml_new_tensor_2d(control.ctx,GGML_TYPE_Q8_0,shape.k,shape.m);
            t.control_x=ggml_new_tensor_2d(control.ctx,GGML_TYPE_F32,shape.k,shape.n);
            t.control_y=ggml_mul_mat(control.ctx,t.control_w,t.control_x);
            ggml_mul_mat_set_prec(t.control_y,GGML_PREC_F32);
            ggml_build_forward_expand(control.graph,t.control_y);
            control.buffer=ggml_backend_alloc_ctx_tensors(control.ctx,control.backend);
            if(!control.buffer) return "OUT_OF_MEMORY";
            const size_t control_resident=ggml_backend_buffer_get_size(control.buffer);
            ggml_backend_tensor_set(t.control_w,t.original.data(),0,t.original.size());
            ggml_backend_tensor_set(t.control_x,t.activations.data(),0,t.dense_x.bytes);
            ggml_backend_synchronize(control.backend);
            auto & lab=*altiro_encoder_lab; lab.variant=variant;
            if(!altiro_before_graph(control.backend,control.graph,1)) return "GRAPH_UNSUPPORTED";
            const auto packing_start=Clock::now();
            vk.begin();
            vk.dispatch(vk.pack_w,t.weight_set,shape,uint32_t((altiro_int8_block_count(shape.m,shape.k)+63)/64));
            const double weight_gpu=vk.finish(cancelled),weight_wall=elapsed(packing_start);
            auto pw=static_cast<const AltiroInt8Block *>(t.packed_w.mapped);
            for(size_t row=0;row<(shape.m+3)/4*4;row++) for(size_t block=0;block<blocks;block++) {
                const auto & packed=pw[altiro_int8_block_index(row,block,blocks)];
                if(row>=shape.m) {
                    if(packed.scale!=0) return "INT8_LAYOUT_MISMATCH";
                    for(uint32_t word:packed.q) if(word) return "INT8_LAYOUT_MISMATCH";
                    continue;
                }
                const auto * source=t.original.data()+(row*blocks+block)*34;
                ggml_fp16_t scale; std::memcpy(&scale,source,2);
                if(packed.scale!=ggml_fp16_to_fp32(scale)) return "INT8_LAYOUT_MISMATCH";
                for(unsigned j=0;j<8;j++) {
                    uint32_t word=0;
                    for(unsigned lane=0;lane<4;lane++) word|=uint32_t(source[2+j*4+lane])<<(lane*8);
                    if(packed.q[j]!=word) return "INT8_LAYOUT_MISMATCH";
                }
            }
            const double setup_ms=elapsed(setup_start);
            const bool full=size_t(shape.m)*shape.n<=8192;
            const int cells=full?int(shape.m*shape.n):64;
            const auto float_ref_start=Clock::now();
            std::vector<double> float_reference(cells),quantized_reference(size_t(shape.m)*shape.n);
            for(int p=0;p<cells;p++) {
                if(cancelled.load()) return "NONE";
                const size_t at=sample_index(p,shape.m,shape.n,full),row=at%shape.m,col=at/shape.m;
                double value=0;
                for(uint32_t kk=0;kk<shape.k;kk++) value+=double(t.weights[row*shape.k+kk])*t.activations[col*shape.k+kk];
                float_reference[p]=value;
            }
            double reference_setup_ms=elapsed(float_ref_start);
            std::vector<AltiroInt8Block> quantized_x(altiro_int8_block_count(shape.n,shape.k));
            std::vector<double> walls,cpus,quant_walls,quant_gpu,compute_gpu,boundary,reads,control_walls,control_gpu;
            bool finite=true; unsigned ties=0;
            double quant_abs=0,quant_rmse=0,float_abs=0,float_rmse=0,control_abs=0,control_rmse=0,control_reference_rmse=0,activation_error=0;
            lab.counts.clear(); lab.shaders.clear(); lab.us.clear();
            for(int repeat=0;repeat<6;repeat++) {
                if(cancelled.load()) return "NONE";
                const auto execute_candidate=[&]() {
                    const auto start=Clock::now(); const double cpu=process_ms();
                    vk.begin();
                    vk.dispatch(vk.pack_x,t.activation_set,shape,uint32_t((quantized_x.size()+63)/64));
                    const double pg=vk.finish(cancelled); quant_gpu.push_back(pg); quant_walls.push_back(elapsed(start));
                    vk.begin(); vk.dispatch(vk.gemm,t.gemm_set,shape,(shape.m+31)/32,(shape.n+31)/32);
                    const double cg=vk.finish(cancelled); compute_gpu.push_back(cg);
                    boundary.push_back(pg<0 || cg<0?-1:pg+cg);
                    walls.push_back(elapsed(start)); cpus.push_back(process_ms()-cpu);
                };
                const auto execute_control=[&]() {
                    lab.measuring=true; lab.boundary_available=false;
                    const auto start=Clock::now();
                    const auto status=ggml_backend_graph_compute(control.backend,control.graph);
                    ggml_backend_synchronize(control.backend);
                    control_walls.push_back(elapsed(start));
                    control_gpu.push_back(lab.boundary_available?double(lab.boundary_gpu_ns):-1);
                    lab.measuring=false;
                    if(status!=GGML_STATUS_SUCCESS) throw Error{"KERNEL_COMPUTE_FAILED"};
                };
                if(repeat%2) { execute_control(); execute_candidate(); }
                else { execute_candidate(); execute_control(); }
                const auto reference_start=Clock::now();
                if(repeat==0) {
                    std::memcpy(quantized_x.data(),t.packed_x.mapped,t.packed_x.bytes);
                    for(size_t col=0;col<(shape.n+3)/4*4;col++) for(size_t block=0;block<blocks;block++) {
                        if(cancelled.load()) return "NONE";
                        const auto & q=quantized_x[altiro_int8_block_index(col,block,blocks)];
                        if(col>=shape.n) {
                            if(q.scale!=0) return "INT8_QUANTIZATION_MISMATCH";
                            for(uint32_t word:q.q) if(word) return "INT8_QUANTIZATION_MISMATCH";
                            continue;
                        }
                        const float * x=t.activations.data()+col*shape.k+block*32;
                        if(!altiro_int8_quantization_valid(x,q,ties)) return "INT8_QUANTIZATION_MISMATCH";
                        for(unsigned j=0;j<32;j++) activation_error=std::max(activation_error,
                            std::abs(double(altiro_int8_signed_byte(q.q[j/4],j%4))*q.scale-x[j]));
                    }
                    // Entire output, independently using exact int64 block dots
                    // and double scale multiplication. This is deliberately
                    // outside measured calls, with bounded cancellation checks.
                    for(size_t col=0;col<shape.n;col++) for(size_t row=0;row<shape.m;row++) {
                        if(row%32==0 && cancelled.load()) return "NONE";
                        double value=0;
                        for(size_t b=0;b<blocks;b++) {
                            const auto & w=pw[altiro_int8_block_index(row,b,blocks)];
                            const auto & x=quantized_x[altiro_int8_block_index(col,b,blocks)];
                            value+=double(altiro_int8_dot32(w,x))*double(w.scale)*double(x.scale);
                        }
                        quantized_reference[col*shape.m+row]=value;
                    }
                    reference_setup_ms+=elapsed(reference_start);
                } else if(std::memcmp(quantized_x.data(),t.packed_x.mapped,t.packed_x.bytes)!=0)
                    return "INT8_QUANTIZATION_MISMATCH";
                const auto read_start=Clock::now();
                auto y=static_cast<const float *>(t.y.mapped);
                std::vector<float> baseline(size_t(shape.m)*shape.n);
                ggml_backend_tensor_get(t.control_y,baseline.data(),0,baseline.size()*sizeof(float));
                double qsq=0,qref=0,fsq=0,fref=0,csq=0,cref=0,control_ref_sq=0;
                for(size_t i=0;i<baseline.size();i++) {
                    finite &= std::isfinite(y[i]) && std::isfinite(baseline[i]);
                    if(!std::isfinite(y[i]) || !std::isfinite(baseline[i])) continue;
                    const double qdelta=double(y[i])-quantized_reference[i],cdelta=double(y[i])-baseline[i];
                    qsq+=qdelta*qdelta; qref+=quantized_reference[i]*quantized_reference[i];
                    csq+=cdelta*cdelta; cref+=double(baseline[i])*baseline[i];
                    quant_abs=std::max(quant_abs,std::abs(qdelta)); control_abs=std::max(control_abs,std::abs(cdelta));
                }
                for(int p=0;p<cells;p++) {
                    const size_t i=sample_index(p,shape.m,shape.n,full);
                    const double ref=float_reference[p],delta=double(y[i])-ref,cdelta=double(baseline[i])-ref;
                    if(!std::isfinite(y[i]) || !std::isfinite(baseline[i])) continue;
                    fsq+=delta*delta; fref+=ref*ref; control_ref_sq+=cdelta*cdelta;
                    float_abs=std::max(float_abs,std::abs(delta));
                }
                quant_rmse=std::max(quant_rmse,std::sqrt(qsq/std::max(qref,1e-30)));
                float_rmse=std::max(float_rmse,std::sqrt(fsq/std::max(fref,1e-30)));
                control_rmse=std::max(control_rmse,std::sqrt(csq/std::max(cref,1e-30)));
                control_reference_rmse=std::max(control_reference_rmse,std::sqrt(control_ref_sq/std::max(fref,1e-30)));
                reads.push_back(elapsed(read_start));
            }
            std::ostringstream out; out.precision(9);
            out<<"{\"lab\":true,\"event\":\"INT8\",\"sequence\":"<<sequence++
                <<",\"m\":"<<shape.m<<",\"n\":"<<shape.n<<",\"k\":"<<shape.k<<",\"streams\":1"
                <<",\"complete_calls_per_repeat\":1,\"encoder_frames\":"<<encoder_frames
                <<",\"source_type\":\"q8_0\",\"storage_type\":\"q8_0\",\"backend\":\"Vulkan\",\"candidate_backend\":\"Vulkan\""
                <<",\"implementation\":\"altiro_q8_block32_sdot4_v1\",\"weight_layout\":\"m4_block32_lane_q8\""
                <<",\"activation_layout\":\"n4_block32_lane_q8\",\"activation_input_rounding\":\"f16_then_f32\""
                <<",\"activation_quantization\":\"symmetric_per32_f32_scale_rna\",\"integer_accumulation\":\"i32_per32\""
                <<",\"f32_accumulation\":true,\"workgroup\":[8,8,1],\"register_tile\":[4,4],\"dispatch\":["
                <<(shape.m+31)/32<<','<<(shape.n+31)/32<<",1],\"packing_workgroup\":[64,1,1]"
                <<",\"resident_bytes\":"<<resident<<",\"scratch_bytes\":"<<scratch<<",\"control_resident_bytes\":"<<control_resident
                <<",\"pipeline_setup_ms\":"<<pipeline_setup_ms<<",\"setup_ms\":"<<setup_ms<<",\"reference_setup_ms\":"<<reference_setup_ms
                <<",\"weight_pack_wall_ms\":"<<weight_wall<<",\"weight_pack_gpu_ns\":";
            if(weight_gpu<0) out<<"null"; else out<<weight_gpu;
            out<<",\"finite\":"<<(finite?"true":"false")<<",\"quantized_relative_rmse\":"<<(finite?quant_rmse:-1)
                <<",\"quantized_max_abs_error\":"<<(finite?quant_abs:-1)<<",\"float_relative_rmse\":"<<(finite?float_rmse:-1)
                <<",\"float_max_abs_error\":"<<(finite?float_abs:-1)<<",\"control_relative_rmse\":"<<(finite?control_rmse:-1)
                <<",\"control_max_abs_error\":"<<(finite?control_abs:-1)<<",\"control_reference_relative_rmse\":"<<(finite?control_reference_rmse:-1)
                <<",\"source_quantization_max_abs\":"<<source_error<<",\"activation_quant_max_abs\":"<<activation_error
                <<",\"activation_rounding_tie_count\":"<<ties<<",\"reference_cells\":"<<cells
                <<",\"quantized_reference_cells\":"<<quantized_reference.size()<<",\"full_quantized_reference\":true"
                <<",\"full_reference\":"<<(full?"true":"false")<<",\"reference_mode\":\"double_dot_q8_weights_half_rounded_x\""
                <<",\"quantized_reference_mode\":\"int64_block_dot_double_scale\""
                <<",\"control_backend\":\"Vulkan\",\"control_weight_type\":\"q8_0\",\"wall_includes_activation_quant\":true,\"weight_pack_checked\":true"
                <<",\"activation_quant_checked\":true,\"candidate_gpu_dispatches_per_repeat\":2"
                <<",\"candidate_shared_memory_bytes\":0,\"specialization_constants\":[]"
                <<",\"shader_integer_dot_product\":true,\"packed_signed_dot_accelerated\":true,\"integer_dot_enabled\":true"
                <<",\"logical_integer_dot_operations\":"<<uint64_t(shape.m)*shape.n*(shape.k/4)
                <<",\"candidate_device\":\""<<device_label(vk.properties.deviceName)<<"\""
                <<",\"candidate_vendor_id\":"<<vk.properties.vendorID<<",\"candidate_device_id\":"<<vk.properties.deviceID
                <<",\"candidate_driver_version\":"<<vk.properties.driverVersion
                <<",\"physical_device_count\":"<<vk.physical_device_count
                <<",\"same_device_verified\":true,\"timestamp_valid_bits\":"<<vk.bits
                <<",\"timestamp_period_ns\":"<<vk.properties.limits.timestampPeriod
                <<",\"candidate_memory_flags_all\":"<<memory_flags_all
                <<",\"candidate_all_memory_device_local\":"<<((memory_flags_all&VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)?"true":"false")
                <<",\"control_device\":\""<<device_label(ggml_backend_dev_description(ggml_backend_get_device(control.backend)))<<"\""
                <<",\"execution_order\":\"alternating_candidate_first_even\""
                <<",\"host_reference_bytes\":"<<((t.weights.size()+t.activations.size())*sizeof(float)+
                    (float_reference.size()+quantized_reference.size())*sizeof(double)+quantized_x.size()*sizeof(AltiroInt8Block)+t.original.size());
            array(out,"wall_ms",walls); array(out,"process_cpu_ms",cpus);
            array(out,"activation_quant_wall_ms",quant_walls); array(out,"activation_quant_gpu_ns",quant_gpu);
            array(out,"compute_gpu_ns",compute_gpu); array(out,"boundary_gpu_ns",boundary);
            array(out,"readback_check_ms",reads); array(out,"control_wall_ms",control_walls); array(out,"control_boundary_gpu_ns",control_gpu);
            map(out,"control_counts",lab.counts); map(out,"control_shaders",lab.shaders);
            out<<'}'; emit(out.str());
            if(!finite) return "NONFINITE_OR_SHAPE";
            // Float error quantifies changed arithmetic; it is never the test
            // for a correct integer-dot implementation or transcription quality.
            if(quant_rmse>0.00001 || control_reference_rmse>0.002) return "NUMERICAL_MISMATCH";
        }
        emit("{\"lab\":true,\"event\":\"FINISHED\"}");
        return nullptr;
    } catch(const Error & e) { return e.code; }
    catch(const std::bad_alloc &) { return "OUT_OF_MEMORY"; }
#endif
}
