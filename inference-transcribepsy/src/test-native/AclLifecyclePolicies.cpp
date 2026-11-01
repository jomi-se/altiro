// Host-only fake-driver lifecycle regression; never opens a GPU.
#include <dlfcn.h>
#include <cassert>
#include <atomic>
#include <iostream>
int unloaded=0;
int fake_dlclose(void*){++unloaded;return 0;}
#define dlclose fake_dlclose
#include "../main/cpp/acl-kernel.cpp"
#undef dlclose
namespace {
int mode=0,enqueues=0,releases=0,event_releases=0;
std::atomic<bool>* cancel_ptr;
cl_int CL_API_CALL enqueue(cl_command_queue,cl_kernel,cl_uint,const size_t*,const size_t*,const size_t*,cl_uint,const cl_event*,cl_event* e){
    ++enqueues;
    if((mode==3&&enqueues==2)||mode==6)return CL_OUT_OF_RESOURCES;
    *e=reinterpret_cast<cl_event>(uintptr_t(enqueues));
    if((mode==1&&enqueues==1)||(mode==2&&enqueues==2))*cancel_ptr=true;
    return CL_SUCCESS;
}
cl_int CL_API_CALL finish(cl_command_queue){if(mode==4)return CL_OUT_OF_RESOURCES;if(mode==5)*cancel_ptr=true;return CL_SUCCESS;}
cl_int CL_API_CALL profiling(cl_event e,cl_profiling_info key,size_t,void* out,size_t*){*static_cast<cl_ulong*>(out)=uintptr_t(e)*10+(key==CL_PROFILING_COMMAND_END?5:0);return CL_SUCCESS;}
cl_int CL_API_CALL release_event(cl_event){++event_releases;return CL_SUCCESS;}
template<class T>cl_int CL_API_CALL release(T){++releases;return CL_SUCCESS;}
cl_mem CL_API_CALL create_buffer(cl_context,cl_mem_flags,size_t,void*,cl_int* err){*err=CL_SUCCESS;return reinterpret_cast<cl_mem>(1);}
cl_int CL_API_CALL read_buffer(cl_command_queue,cl_mem,cl_bool,size_t,size_t,void*,cl_uint,const cl_event*,cl_event*){return mode==7?CL_OUT_OF_RESOURCES:CL_SUCCESS;}
}
int main(){
    for(bool chain:{false,true})for(mode=0;mode<=7;++mode){
        if(!chain&&(mode==2||mode==3))continue;
        std::atomic<bool> cancelled{false};cancel_ptr=&cancelled;enqueues=releases=event_releases=unloaded=0;
        bool pending=false;
        {
            Cl cl(cancelled);cl.library=reinterpret_cast<void*>(1);cl.context=reinterpret_cast<cl_context>(1);cl.queue=reinterpret_cast<cl_command_queue>(1);
            cl.clEnqueueNDRangeKernel=enqueue;cl.clFinish=finish;cl.clGetEventProfilingInfo=profiling;cl.clReleaseEvent=release_event;
            cl.clReleaseMemObject=release<cl_mem>;cl.clReleaseKernel=release<cl_kernel>;cl.clReleaseProgram=release<cl_program>;
            cl.clReleaseCommandQueue=release<cl_command_queue>;cl.clReleaseContext=release<cl_context>;
            cl.clCreateBuffer=create_buffer;cl.clEnqueueReadBuffer=read_buffer;
            Buffer b(cl,4);Program p(cl);p.value=reinterpret_cast<cl_program>(1);p.kernel=reinterpret_cast<cl_kernel>(1);
            try{
                if(mode==7){int output=0;b.read(&output);}
                else if(chain)cl.run_chain(p.kernel,p.kernel,p.kernel,1,1,1);
                else cl.run(p.kernel,1,1);
            }catch(const Error&){}
            pending=cl.pending;
        }
        const bool quarantine=mode==1||mode==2||mode==3||mode==4||mode==7;
        assert(pending==quarantine);
        if(quarantine){assert(releases==0&&event_releases==0&&unloaded==0);}
        else{assert(releases==5&&unloaded==1);assert(event_releases==(mode==6?0:chain?3:1));}
    }
    std::cout<<"ACL driver lifecycle tests passed\n";
}
