#include "encoder-lab.h"
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
#include "packed-spirv.h"

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
        "OUT_OF_MEMORY" : "PACKED_VULKAN_FAILED"};
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
    uint32_t family=0,bits=0,physical_device_count=0;
    // On timeout/cancellation with GPU work pending, Vulkan resources cannot be
    // destroyed safely. Quarantine them until this isolated worker exits. Never
    // wait indefinitely or submit another operation on the timed-out device.
    bool pending=false;
    explicit Vulkan() {
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName="Altiro packed lab"; app.apiVersion=VK_API_VERSION_1_1;
        VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ci.pApplicationInfo=&app;
        check(vkCreateInstance(&ci,nullptr,&instance));
    }
    void initialize() {
        uint32_t count=0; check(vkEnumeratePhysicalDevices(instance,&count,nullptr));
        if(!count || count>16) throw Error{"BACKEND_UNAVAILABLE"};
        // GGML does not expose a public physical-device UUID getter. Restrict
        // this comparison to a unique Vulkan physical device rather than
        // silently comparing pipelines on different devices.
        if(count!=1) throw Error{"PACKED_DEVICE_AMBIGUOUS"};
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
        // Uses half bit unpacking and F32 arithmetic only. No shaderFloat16,
        // storageBuffer16BitAccess, shaderInt8, or guessed integer-dot fast path.
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
        pack_w=pipeline(altiro_spv_pack_weights,sizeof(altiro_spv_pack_weights));
        pack_x=pipeline(altiro_spv_pack_activations,sizeof(altiro_spv_pack_activations));
        gemm=pipeline(altiro_spv_register4,sizeof(altiro_spv_register4));
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
        if(pending) throw Error{"PACKED_VULKAN_FAILED"};
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
            if(status!=VK_TIMEOUT) throw Error{"PACKED_VULKAN_FAILED"};
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
        if(type==UINT32_MAX) throw Error{"PACKED_MEMORY_UNAVAILABLE"};
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
struct Stream {
    Buffer dense_w,packed_w,dense_x,packed_x,y;
    VkDescriptorSet weight_set{},activation_set{},gemm_set{};
    ggml_tensor * control_w{},*control_x{},*control_y{};
    std::vector<float> weights,activations;
    Stream(Vulkan & vk,Shape s):
        dense_w(vk,size_t(s.m)*s.k*2),packed_w(vk,altiro_packed_elements(s.m,s.k)*2),
        dense_x(vk,size_t(s.n)*s.k*4),packed_x(vk,altiro_packed_elements(s.n,s.k)*4),
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
        weight_set=set(dense_w,packed_w,y);
        activation_set=set(dense_x,packed_x,y);
        gemm_set=set(packed_w,packed_x,y); return bytes;
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

const char * altiro_packed_hunt(bool gpu,int variant,bool quantized,int encoder_frames,
    std::atomic<bool> & cancelled,const std::function<void(const std::string &)> & emit) {
#ifndef ALTIRO_VULKAN
    return "BACKEND_UNAVAILABLE";
#else
    if(!gpu || !altiro_packed_variant(variant)) return "BACKEND_MISMATCH";
    if(encoder_frames<1 || encoder_frames>1000) return "GRAPH_UNSUPPORTED";
    try {
        const auto pipeline_start=Clock::now();
        Vulkan vk; vk.initialize();
        const double pipeline_setup_ms=elapsed(pipeline_start);
        const uint32_t width=variant==61?145:variant==62?201:uint32_t(encoder_frames);
        const int streams=variant==63?2:1;
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
            std::vector<std::unique_ptr<Stream>> data;
            uint64_t resident=0,scratch=0;
            VkMemoryPropertyFlags memory_flags_all=~VkMemoryPropertyFlags(0);
            double rounding=0;
            for(int s=0;s<streams;s++) {
                auto t=std::make_unique<Stream>(vk,shape); resident+=t->allocate(vk);
                for(auto b:{&t->dense_w,&t->packed_w,&t->dense_x,&t->packed_x,&t->y}) memory_flags_all &= b->flags;
                scratch+=t->packed_w.allocated+t->packed_x.allocated;
                t->weights.resize(size_t(shape.m)*shape.k);
                for(size_t i=0;i<t->weights.size();i++) t->weights[i]=datum(i,11+s);
                const auto type=quantized?GGML_TYPE_Q8_0:GGML_TYPE_F16;
                std::vector<uint8_t> original(ggml_row_size(type,shape.k)*shape.m);
                ggml_quantize_chunk(type,t->weights.data(),original.data(),0,shape.m,shape.k,nullptr);
                ggml_get_type_traits(type)->to_float(original.data(),t->weights.data(),t->weights.size());
                auto half=static_cast<ggml_fp16_t *>(t->dense_w.mapped);
                for(size_t i=0;i<t->weights.size();i++) {
                    half[i]=ggml_fp32_to_fp16(t->weights[i]); const float rounded=ggml_fp16_to_fp32(half[i]);
                    rounding=std::max(rounding,std::abs(double(rounded)-t->weights[i])); t->weights[i]=rounded;
                }
                t->activations.resize(size_t(shape.n)*shape.k);
                for(size_t i=0;i<t->activations.size();i++)
                    t->activations[i]=ggml_fp16_to_fp32(ggml_fp32_to_fp16(datum(i,31+s)));
                std::memcpy(t->dense_x.mapped,t->activations.data(),t->dense_x.bytes);
                t->control_w=ggml_new_tensor_2d(control.ctx,GGML_TYPE_F16,shape.k,shape.m);
                t->control_x=ggml_new_tensor_2d(control.ctx,GGML_TYPE_F32,shape.k,shape.n);
                t->control_y=ggml_mul_mat(control.ctx,t->control_w,t->control_x);
                ggml_mul_mat_set_prec(t->control_y,GGML_PREC_F32);
                ggml_build_forward_expand(control.graph,t->control_y);
                data.push_back(std::move(t));
            }
            control.buffer=ggml_backend_alloc_ctx_tensors(control.ctx,control.backend);
            if(!control.buffer) return "OUT_OF_MEMORY";
            const size_t control_resident=ggml_backend_buffer_get_size(control.buffer);
            for(const auto & t:data) {
                ggml_backend_tensor_set(t->control_w,t->dense_w.mapped,0,t->dense_w.bytes);
                ggml_backend_tensor_set(t->control_x,t->dense_x.mapped,0,t->dense_x.bytes);
            }
            ggml_backend_synchronize(control.backend);
            auto & lab=*altiro_encoder_lab; lab.variant=variant;
            if(!altiro_before_graph(control.backend,control.graph,1)) return "GRAPH_UNSUPPORTED";
            const auto packing_start=Clock::now();
            vk.begin();
            for(const auto & t:data) vk.dispatch(vk.pack_w,t->weight_set,shape,uint32_t((t->packed_w.bytes/4+63)/64));
            const double weight_gpu=vk.finish(cancelled),weight_wall=elapsed(packing_start);
            // Validate the GPU packing bit-for-bit, including zero padding.
            // This readback is setup work, never hidden in the timed compute.
            for(const auto & t:data) {
                auto dense=static_cast<const uint16_t *>(t->dense_w.mapped);
                auto packed=static_cast<const uint16_t *>(t->packed_w.mapped);
                for(uint32_t row=0;row<(shape.m+3)/4*4;row++) for(uint32_t kk=0;kk<shape.k;kk++)
                    if(packed[altiro_packed_weight_index(row,kk,shape.k)]!=(row<shape.m?dense[size_t(row)*shape.k+kk]:0))
                        return "PACKED_LAYOUT_MISMATCH";
            }
            const double setup_ms=elapsed(setup_start);
            const bool full=size_t(shape.m)*shape.n<=8192;
            const int cells=full?int(shape.m*shape.n):64;
            std::vector<double> reference(size_t(streams)*cells);
            for(int s=0;s<streams;s++) for(int p=0;p<cells;p++) {
                if(cancelled.load()) return "NONE";
                const size_t at=sample_index(p,shape.m,shape.n,full),row=at%shape.m,col=at/shape.m;
                double value=0;
                for(uint32_t kk=0;kk<shape.k;kk++) value+=double(data[s]->weights[row*shape.k+kk])*data[s]->activations[col*shape.k+kk];
                reference[size_t(s)*cells+p]=value;
            }
            std::vector<double> walls,cpus,pack_walls,pack_gpu,compute_gpu,boundary,reads,control_walls,control_gpu;
            bool finite=true; double max_abs=0,rmse=0,control_abs=0,control_rmse=0,control_reference_rmse=0;
            lab.counts.clear(); lab.shaders.clear(); lab.us.clear();
            for(int repeat=0;repeat<6;repeat++) {
                if(cancelled.load()) return "NONE";
                const auto execute_candidate=[&]() {
                    const auto start=Clock::now(); const double cpu=process_ms();
                    vk.begin();
                    for(const auto & t:data) vk.dispatch(vk.pack_x,t->activation_set,shape,uint32_t((t->packed_x.bytes/16+63)/64));
                    const double pg=vk.finish(cancelled); pack_gpu.push_back(pg); pack_walls.push_back(elapsed(start));
                    vk.begin();
                    for(const auto & t:data) vk.dispatch(vk.gemm,t->gemm_set,shape,(shape.m+31)/32,(shape.n+31)/32);
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
                // Alternate order to avoid always giving one route a hotter GPU.
                if(repeat%2) { execute_control(); execute_candidate(); }
                else { execute_candidate(); execute_control(); }
                const auto read_start=Clock::now();
                double sq=0,ref_sq=0,csq=0,crsq=0,control_ref_sq=0;
                for(int s=0;s<streams;s++) {
                    const auto & t=data[s];
                    auto y=static_cast<const float *>(t->y.mapped);
                    std::vector<float> baseline(size_t(shape.m)*shape.n);
                    ggml_backend_tensor_get(t->control_y,baseline.data(),0,baseline.size()*sizeof(float));
                    // Activation layout is checked on every complete call.
                    auto packed=static_cast<const float *>(t->packed_x.mapped);
                    for(uint32_t col=0;col<(shape.n+3)/4*4;col++) for(uint32_t kk=0;kk<shape.k;kk++)
                        if(packed[altiro_packed_activation_index(col,kk,shape.k)]!=(col<shape.n?t->activations[size_t(col)*shape.k+kk]:0))
                            throw Error{"PACKED_LAYOUT_MISMATCH"};
                    for(size_t i=0;i<baseline.size();i++) {
                        finite &= std::isfinite(y[i]) && std::isfinite(baseline[i]);
                        if(!std::isfinite(y[i]) || !std::isfinite(baseline[i])) continue;
                        const double d=double(y[i])-baseline[i];
                        csq+=d*d; crsq+=double(baseline[i])*baseline[i];
                        control_abs=std::max(control_abs,std::abs(d));
                    }
                    for(int p=0;p<cells;p++) {
                        const size_t i=sample_index(p,shape.m,shape.n,full);
                        const double ref=reference[size_t(s)*cells+p],d=double(y[i])-ref,cd=double(baseline[i])-ref;
                        if(!std::isfinite(y[i]) || !std::isfinite(baseline[i])) continue;
                        sq+=d*d; ref_sq+=ref*ref; control_ref_sq+=cd*cd;
                        max_abs=std::max(max_abs,std::abs(d));
                    }
                }
                rmse=std::max(rmse,std::sqrt(sq/std::max(ref_sq,1e-30)));
                control_rmse=std::max(control_rmse,std::sqrt(csq/std::max(crsq,1e-30)));
                control_reference_rmse=std::max(control_reference_rmse,std::sqrt(control_ref_sq/std::max(ref_sq,1e-30)));
                reads.push_back(elapsed(read_start));
            }
            std::ostringstream out; out.precision(9);
            out<<"{\"lab\":true,\"event\":\"PACKED\",\"sequence\":"<<sequence++
                <<",\"m\":"<<shape.m<<",\"n\":"<<shape.n<<",\"k\":"<<shape.k<<",\"streams\":"<<streams
                <<",\"complete_calls_per_repeat\":"<<streams<<",\"encoder_frames\":"<<encoder_frames
                <<",\"source_type\":\""<<(quantized?"q8_0":"f16")<<"\",\"storage_type\":\"f16\",\"backend\":\"Vulkan\""
                <<",\"implementation\":\"altiro_packed_register4_v1\",\"weight_layout\":\"m4_k_mlane\""
                <<",\"activation_layout\":\"n4_k_nlane_f32\",\"activation_input_rounding\":\"f16_then_f32\""
                <<",\"f32_accumulation\":true,\"workgroup\":[8,8,1],\"register_tile\":[4,4],\"dispatch\":["
                <<(shape.m+31)/32<<','<<(shape.n+31)/32<<",1],\"packing_workgroup\":[64,1,1]"
                <<",\"resident_bytes\":"<<resident<<",\"scratch_bytes\":"<<scratch<<",\"control_resident_bytes\":"<<control_resident
                <<",\"pipeline_setup_ms\":"<<pipeline_setup_ms<<",\"setup_ms\":"<<setup_ms
                <<",\"weight_pack_wall_ms\":"<<weight_wall<<",\"weight_pack_gpu_ns\":";
            if(weight_gpu<0) out<<"null"; else out<<weight_gpu;
            out<<",\"finite\":"<<(finite?"true":"false")<<",\"relative_rmse\":"<<(finite?rmse:-1)
                <<",\"max_abs_error\":"<<(finite?max_abs:-1)<<",\"control_relative_rmse\":"<<(finite?control_rmse:-1)
                <<",\"control_max_abs_error\":"<<(finite?control_abs:-1)<<",\"control_reference_relative_rmse\":"<<(finite?control_reference_rmse:-1)
                <<",\"source_rounding_max_abs\":"<<rounding<<",\"reference_cells\":"<<cells*streams
                <<",\"full_reference\":"<<(full?"true":"false")<<",\"reference_mode\":\"double_dot_half_rounded_inputs\""
                <<",\"control_backend\":\"Vulkan\",\"wall_includes_activation_pack\":true,\"weight_pack_checked\":true"
                <<",\"activation_pack_checked\":true,\"candidate_gpu_dispatches_per_repeat\":"<<streams*2
                <<",\"candidate_shared_memory_bytes\":0,\"specialization_constants\":[]"
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
                <<",\"host_reference_bytes\":"<<(size_t(streams)*(shape.m+shape.n)*shape.k*sizeof(float)+reference.size()*sizeof(double));
            array(out,"wall_ms",walls); array(out,"process_cpu_ms",cpus);
            array(out,"activation_pack_wall_ms",pack_walls); array(out,"activation_pack_gpu_ns",pack_gpu);
            array(out,"compute_gpu_ns",compute_gpu); array(out,"boundary_gpu_ns",boundary);
            array(out,"readback_check_ms",reads); array(out,"control_wall_ms",control_walls); array(out,"control_boundary_gpu_ns",control_gpu);
            map(out,"control_counts",lab.counts); map(out,"control_shaders",lab.shaders);
            out<<'}'; emit(out.str());
            if(!finite) return "NONFINITE_OR_SHAPE";
            if(rmse>0.0001 || control_rmse>0.002 || control_reference_rmse>0.002) return "NUMERICAL_MISMATCH";
        }
        emit("{\"lab\":true,\"event\":\"FINISHED\"}");
        return nullptr;
    } catch(const Error & e) { return e.code; }
    catch(const std::bad_alloc &) { return "OUT_OF_MEMORY"; }
#endif
}
