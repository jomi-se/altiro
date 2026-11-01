#pragma once
#include <cstdint>

inline uint32_t altiro_small_tile_wm(uint32_t subgroup, bool cooperative, bool forced_small) {
    // Scalar 32x32 tiles on subgroup-16 devices have two logical warps.
    // Each must own 16x32, otherwise warp 1 addresses columns outside the tile.
    // Keep this correction scoped to the forced lab family pending phone tests.
    if (forced_small && !cooperative && subgroup == 16) return 16;
    return subgroup == 8 ? 8 : 32;
}
