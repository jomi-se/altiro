#include "../../inference-whisper/src/main/cpp/audio-window.h"
#include <cassert>

int main() {
    assert(altiro_audio_context(1, 1500, true) == 250);
    assert(altiro_audio_context(4 * 16000, 1500, true) == 250);
    assert(altiro_audio_context(4 * 16000 + 1, 1500, true) == 500);
    assert(altiro_audio_context(11 * 16000, 1500, true) == 750);
    assert(altiro_audio_context(14 * 16000, 1500, true) == 750);
    assert(altiro_audio_context(14 * 16000 + 1, 1500, true) == 1000);
    assert(altiro_audio_context(29 * 16000, 1500, true) == 1500);
    assert(altiro_audio_context(30 * 16000, 1500, true) == 1500);
    assert(altiro_audio_context(300 * 16000, 1500, true) == 1500);
    assert(altiro_audio_context(11 * 16000, 1500, false) == 1500);
    // Every short clip fits, including its complete final sample and padding.
    for (std::size_t samples = 1; samples < 30 * 16000; ++samples) {
        const int context = altiro_audio_context(samples, 1500, true);
        assert(context >= 250 && context <= 1500 && context % 250 == 0);
        assert(context * 320U >= samples);
        assert(context == 1500 || context * 320U >= samples + 16000);
    }
}
