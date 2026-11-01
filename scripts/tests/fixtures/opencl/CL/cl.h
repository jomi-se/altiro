// Test-only OpenCL ABI subset, validated against Khronos OpenCL-Headers v2024.10.24.
// Production always includes pinned upstream headers, never this fixture.
// Declaration/constant excerpts: Copyright (c) 2008-2020 The Khronos Group Inc.
// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cstddef>
#include <cstdint>
#define CL_API_CALL
#define CL_CALLBACK
using cl_int=int32_t;
using cl_uint=uint32_t;
using cl_ulong=uint64_t;
using cl_bool=cl_uint;
using cl_bitfield=cl_ulong;
using cl_device_type=cl_bitfield;
using cl_device_info=cl_uint;
using cl_device_fp_config=cl_bitfield;
using cl_command_queue_properties=cl_bitfield;
using cl_mem_flags=cl_bitfield;
using cl_context_properties=intptr_t;
typedef struct _cl_platform_id * cl_platform_id;
typedef struct _cl_device_id * cl_device_id;
typedef struct _cl_context * cl_context;
typedef struct _cl_command_queue * cl_command_queue;
typedef struct _cl_mem * cl_mem;
typedef struct _cl_program * cl_program;
typedef struct _cl_kernel * cl_kernel;
typedef struct _cl_event * cl_event;
#define CL_BUILD_PROGRAM_FAILURE -11
#define CL_DEVICE_AVAILABLE 0x1027
#define CL_DEVICE_COMPILER_AVAILABLE 0x1028
#define CL_DEVICE_EXTENSIONS 0x1030
#define CL_DEVICE_HALF_FP_CONFIG 0x1033
#define CL_DEVICE_LOCAL_MEM_SIZE 0x1023
#define CL_DEVICE_MAX_WORK_GROUP_SIZE 0x1004
#define CL_DEVICE_NAME 0x102B
#define CL_DEVICE_NOT_FOUND -1
#define CL_DEVICE_TYPE 0x1000
#define CL_DEVICE_TYPE_CPU (1 << 1)
#define CL_DEVICE_TYPE_GPU (1 << 2)
#define CL_DEVICE_VENDOR 0x102C
#define CL_DRIVER_VERSION 0x102D
#define CL_FALSE 0
#define CL_MEM_COPY_HOST_PTR (1 << 5)
#define CL_MEM_READ_WRITE (1 << 0)
#define CL_OUT_OF_RESOURCES -5
#define CL_SUCCESS 0
#define CL_TRUE 1
extern "C" {
cl_int clGetPlatformIDs(cl_uint num_entries, cl_platform_id * platforms, cl_uint * num_platforms);
cl_int clGetDeviceIDs(cl_platform_id platform, cl_device_type device_type, cl_uint num_entries, cl_device_id * devices, cl_uint * num_devices);
cl_int clGetDeviceInfo(cl_device_id device, cl_device_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);
cl_context clCreateContext(const cl_context_properties * properties, cl_uint num_devices, const cl_device_id * devices, void (CL_CALLBACK * pfn_notify)(const char * errinfo, const void * private_info, size_t cb, void * user_data), void * user_data, cl_int * errcode_ret);
cl_command_queue clCreateCommandQueue(cl_context context, cl_device_id device, cl_command_queue_properties properties, cl_int * errcode_ret);
cl_mem clCreateBuffer(cl_context context, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret);
cl_program clCreateProgramWithSource(cl_context context, cl_uint count, const char ** strings, const size_t * lengths, cl_int * errcode_ret);
cl_int clBuildProgram(cl_program program, cl_uint num_devices, const cl_device_id * device_list, const char * options, void (CL_CALLBACK * pfn_notify)(cl_program program, void * user_data), void * user_data);
cl_kernel clCreateKernel(cl_program program, const char * kernel_name, cl_int * errcode_ret);
cl_int clSetKernelArg(cl_kernel kernel, cl_uint arg_index, size_t arg_size, const void * arg_value);
cl_int clEnqueueNDRangeKernel(cl_command_queue command_queue, cl_kernel kernel, cl_uint work_dim, const size_t * global_work_offset, const size_t * global_work_size, const size_t * local_work_size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
cl_int clEnqueueReadBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_read, size_t offset, size_t size, void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
cl_int clFinish(cl_command_queue command_queue);
cl_int clReleaseMemObject(cl_mem memobj);
cl_int clReleaseKernel(cl_kernel kernel);
cl_int clReleaseProgram(cl_program program);
cl_int clReleaseCommandQueue(cl_command_queue command_queue);
cl_int clReleaseContext(cl_context context);
}
