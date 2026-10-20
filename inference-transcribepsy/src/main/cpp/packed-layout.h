#pragma once
#include <cstddef>
#include <cstdint>

// Pure layout helpers mirrored by the three small shaders and covered on host.
inline size_t altiro_packed_weight_index(size_t row, size_t kk, size_t k) {
    return ((row / 4) * k + kk) * 4 + row % 4;
}
inline size_t altiro_packed_activation_index(size_t col, size_t kk, size_t k) {
    return ((col / 4) * k + kk) * 4 + col % 4;
}
inline size_t altiro_packed_elements(size_t rows, size_t k) {
    return ((rows + 3) / 4) * k * 4;
}
inline bool altiro_packed_shape_valid(uint32_t m, uint32_t n, uint32_t k) {
    return m > 0 && m <= 2560 && n > 0 && n <= 1000 &&
        k > 0 && k <= 2560 && k % 32 == 0;
}
